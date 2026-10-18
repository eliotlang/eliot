package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}

import java.nio.file.Path
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Reads back the calls and integer conversions a compiled jar actually contains, for the suites that assert on
  * *emitted code* rather than on a program's output. Only these two kinds of instruction are collected: they are
  * exactly what a representation conversion consists of, so an unrelated instruction between two of them cannot make an
  * assertion pass or fail spuriously.
  */
trait EmittedInstructions { self: FullIntegrationTest =>

  /** The `count` instructions the compiled jar emits immediately after its (first) call to `owner.name`, rendered as
    * the same opcode/target tokens `javap -c` shows. A program that does not call it at all answers with a sentinel
    * rather than an empty window, so "the call vanished" can never pass as "the call emitted no conversion".
    */
  protected def instructionsFollowing(call: String, count: Int): IO[Seq[String]] = IO.blocking {
    val emitted = classesOf(jarPath).flatMap(instructionsOf(_, None))
    emitted.indexOf(s"INVOKESTATIC $call") match {
      case -1    => Seq(s"$call is never called")
      case index => emitted.slice(index + 1, index + 1 + count)
    }
  }

  /** Every collected instruction of the method `name` of the compiled jar, in emission order. Where
    * [[instructionsFollowing]] asks what happens at one edge, this asks what a whole method costs — which is what an
    * assertion about a *removed* conversion needs, since a conversion the peephole moved elsewhere would still be
    * absent from any single window.
    */
  protected def instructionsOfMethod(name: String): IO[Seq[String]] = IO.blocking {
    classesOf(jarPath).flatMap(instructionsOf(_, Some(name)))
  }

  /** The names of every class the compiled jar contains, as internal names (`eliot/effect/Console`). */
  protected def classNames: IO[Seq[String]] = IO.blocking {
    classesOf(jarPath).map(new ClassReader(_).getClassName)
  }

  /** The JVM descriptor of every method of the compiled jar named `name`, or mangled from it (`name$…`), leaving out
    * the methods its lambdas lower to — what a definition's instances take and return.
    */
  protected def descriptorsOf(name: String): IO[Seq[String]] = IO.blocking {
    classesOf(jarPath).flatMap { classBytes =>
      val collected = Seq.newBuilder[String]
      new ClassReader(classBytes).accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              methodName: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String]
          ): MethodVisitor = {
            if ((methodName == name || methodName.startsWith(name + "$")) && !methodName.contains("$lambda$"))
              collected += descriptor
            null
          }
        },
        ClassReader.SKIP_CODE
      )
      collected.result()
    }
  }

  private def classesOf(jar: Path): Seq[Array[Byte]] =
    Using.resource(new JarFile(jar.toFile)) { jarFile =>
      jarFile
        .entries()
        .asScala
        .toSeq
        .filter(_.getName.endsWith(".class"))
        .sortBy(_.getName)
        .map(entry => jarFile.getInputStream(entry).readAllBytes())
    }

  private def instructionsOf(classBytes: Array[Byte], onlyMethod: Option[String] = None): Seq[String] = {
    val collected = Seq.newBuilder[String]
    new ClassReader(classBytes).accept(
      new ClassVisitor(Opcodes.ASM9) {
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          if (onlyMethod.exists(_ != name)) null
          else
            new MethodVisitor(Opcodes.ASM9) {
              override def visitMethodInsn(
                  opcode: Int,
                  owner: String,
                  methodName: String,
                  methodDescriptor: String,
                  isInterface: Boolean
              ): Unit = collected += s"${opcodeName(opcode)} $owner.$methodName"

              override def visitInsn(opcode: Int): Unit = opcode match {
                case Opcodes.L2I => collected += "L2I"
                case Opcodes.I2B => collected += "I2B"
                case Opcodes.I2S => collected += "I2S"
                case _           => ()
              }
            }
      },
      ClassReader.SKIP_FRAMES
    )
    collected.result()
  }

  private def opcodeName(opcode: Int): String = opcode match {
    case Opcodes.INVOKESTATIC    => "INVOKESTATIC"
    case Opcodes.INVOKEVIRTUAL   => "INVOKEVIRTUAL"
    case Opcodes.INVOKEINTERFACE => "INVOKEINTERFACE"
    case Opcodes.INVOKESPECIAL   => "INVOKESPECIAL"
    case _                       => "INVOKEDYNAMIC"
  }
}
