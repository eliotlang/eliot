package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.source.scan.PathScan
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.Path

class JvmClassGeneratorProcessorTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val file           = URI.create("Test.els")
  private val testModuleName = ModuleName(Seq.empty, "Test")
  private val mainVfqn       = ValueFQN(testModuleName, QualifiedName("main", Qualifier.Default))
  private val moduleKey      = GeneratedModule.Key(testModuleName, mainVfqn)

  private val processors = SequentialCompilerProcessors(
    LangProcessors(systemModules =
      Seq(
        ModuleName.systemFunctionModuleName,
        ModuleName(ModuleName.defaultSystemPackage, "Unit"),
        ModuleName(ModuleName.defaultSystemPackage, "Implementation"),
        ModuleName(ModuleName.defaultSystemPackage, "PatternMatch"),
        ModuleName(ModuleName.defaultSystemPackage, "TypeMatch")
      )
    ) :+ JvmClassGenerator()
  )

  private def runGenerator(
      source: String,
      trigger: CompilerFactKey[? <: CompilerFact]
  ): IO[(Seq[CompilerError], Map[CompilerFactKey[?], CompilerFact])] =
    for {
      generator <- IncrementalFactGenerator.create(processors, None)
      _         <- generator.registerFact(SourceContent(file, Sourced(file, PositionRange.zero, source)))
      // Register each source under both pools: the value mono reads its signature twin (compiler pool) mandatorily since
      // signature-unification C1/C2, mirroring a real build where the compiler pool borrows the whole runtime track.
      _         <- generator.registerFact(PathScan(Path.of("Test.els"), Seq(file)))
      _         <- generator.registerFact(PathScan(Path.of("Test.els"), Seq(file), Platform.Compiler))
      _         <- Seq(
                     ModuleName(ModuleName.defaultSystemPackage, "Function") -> "type Function[A, B]",
                     ModuleName(ModuleName.compilerPackage, "Type")          -> "type Type",
                     ModuleName(ModuleName.defaultSystemPackage, "Unit")     -> "type Unit",
                     // A synthesized `implement`/`data` marker's default `true` guard resolves to `eliot.lang.Bool::true`
                     // (ability-guards §2.3), so Bool must be loadable — as it always is in a real layer.
                     ModuleName(ModuleName.defaultSystemPackage, "Bool")     -> "type Bool\ndef true: Bool\ndef false: Bool",
                     ModuleName(ModuleName.defaultSystemPackage, "Implementation") -> "type Inferred\ntype Handed = Type",
                     ModuleName(ModuleName.defaultSystemPackage, "PatternMatch") -> "ability PatternMatch[T] {\ntype Cases[R]\ndef handleCases[R](value: T, cases: Cases[R]): R\n}",
                     ModuleName(ModuleName.defaultSystemPackage, "TypeMatch")    -> "ability TypeMatch[T] {\ntype Fields[R]\ndef typeMatch[R](value: Type, matched: Fields[R], notMatched: Function[Unit, R]): R\n}"
                   ).traverse { (moduleName, content) =>
                     val path    = moduleName.toPath
                     val impFile = URI.create(path.toString)
                     generator.registerFact(PathScan(path, Seq(impFile))) >>
                       generator.registerFact(PathScan(path, Seq(impFile), Platform.Compiler)) >>
                       generator.registerFact(SourceContent(impFile, Sourced(impFile, PositionRange.zero, content)))
                   }
      _         <- generator.getFact(trigger)
      facts     <- generator.currentFacts()
      errors    <- generator.currentErrors()
    } yield (errors, facts)

  "jvm class generator" should "resolve ability call directly via monomorphization" in {
    runGenerator(
      """ability Show[A] { def show(x: A): A }
        |data MyStr(token: Function[Unit, Unit])
        |implement Show[MyStr] { def show(x: MyStr): MyStr = x }
        |def someValue: MyStr = MyStr(u -> u)
        |def main: MyStr = f(someValue)
        |def f[A ~ Show[A]](x: A): A = show(x)""".stripMargin,
      moduleKey
    ).map { case (_, facts) =>
      val generatedModule = facts(moduleKey).asInstanceOf[GeneratedModule]
      val testClassBytes  = generatedModule.classFiles.find(_.fileName == "Test.class").get.bytecode
      val classReader     = new ClassReader(testClassBytes)
      var fCallsShowImpl  = false
      classReader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String]
          ): MethodVisitor =
            // `f[A ~ Show[A]]` at `MyStr` is mangled `f$MyStr`. Matched by shape rather than spelled out, so the name
            // stays about the instantiation and not about the encoding.
            if (name.startsWith("f$") && name.endsWith("MyStr"))
              new MethodVisitor(Opcodes.ASM9) {
                override def visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean
                ): Unit =
                  // The impl method now carries an impl-disambiguator suffix (`show$Show$impl$0`) so that two
                  // implementations of one ability whose methods share an erased descriptor cannot collide.
                  if (opcode == Opcodes.INVOKESTATIC && name.startsWith("show"))
                    fCallsShowImpl = true
              }
            else null
        },
        0
      )
      fCallsShowImpl
    }.asserting(_ shouldBe true)
  }

  it should "generate monomorphically mangled method name for generic function" in {
    runGenerator(
      """ability Show[A] { def show(x: A): A }
        |data MyStr(token: Function[Unit, Unit])
        |implement Show[MyStr] { def show(x: MyStr): MyStr = x }
        |def someValue: MyStr = MyStr(u -> u)
        |def main: MyStr = f(someValue)
        |def f[A ~ Show[A]](x: A): A = x""".stripMargin,
      moduleKey
    ).map { case (_, facts) =>
      val generatedModule = facts(moduleKey).asInstanceOf[GeneratedModule]
      val testClassBytes  = generatedModule.classFiles.find(_.fileName == "Test.class").get.bytecode
      val classReader     = new ClassReader(testClassBytes)
      var fDescriptor     = Option.empty[String]
      classReader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String]
          ): MethodVisitor = {
            // `f[A ~ Show[A]]` at `MyStr` is mangled `f$MyStr`. Matched by shape rather than spelled out, so the name
            // stays about the instantiation and not about the encoding.
            if (name.startsWith("f$") && name.endsWith("MyStr")) fDescriptor = Some(descriptor)
            null
          }
        },
        0
      )
      fDescriptor
    }.asserting(_ shouldBe Some("(LTest$MyStr;)LTest$MyStr;"))
  }

  it should "generate monomorphic methods for generic function" in {
    runGenerator(
      """data MyStr(token: Function[Unit, Unit])
        |def someValue: MyStr = MyStr(u -> u)
        |def id[A](x: A): A = x
        |def main: MyStr = id(someValue)""".stripMargin,
      moduleKey
    ).map { case (errors, facts) =>
      val generatedModule    = facts.get(moduleKey).map(_.asInstanceOf[GeneratedModule]).getOrElse(
        fail(s"GeneratedModule not found. Errors: ${errors.map(e => s"${e.contentSource}: ${e.message}").mkString("; ")}")
      )
      val testClassBytes     = generatedModule.classFiles.find(_.fileName == "Test.class").get.bytecode
      val classReader        = new ClassReader(testClassBytes)
      var idMethods          = Map.empty[String, String]
      classReader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String]
          ): MethodVisitor = {
            if (name.startsWith("id")) idMethods = idMethods + (name -> descriptor)
            null
          }
        },
        0
      )
      idMethods
    }.asserting(_ shouldBe Map(
      "id$MyStr" -> "(LTest$MyStr;)LTest$MyStr;"
    ))
  }

  it should "generate separate monomorphic methods for different type args" in {
    runGenerator(
      """data MyStr(strField: Function[Unit, Unit])
        |data MyInt(intField: Function[Unit, Unit])
        |def someStr: MyStr = MyStr(u -> u)
        |def someInt: MyInt = MyInt(u -> u)
        |def id[A](x: A): A = x
        |def main: MyStr = id(id(someStr))""".stripMargin,
      moduleKey
    ).map { case (errors, facts) =>
      val generatedModule    = facts.get(moduleKey).map(_.asInstanceOf[GeneratedModule]).getOrElse(
        fail(s"GeneratedModule not found. Errors: ${errors.map(e => s"${e.contentSource}: ${e.message}").mkString("; ")}")
      )
      val testClassBytes     = generatedModule.classFiles.find(_.fileName == "Test.class").get.bytecode
      val classReader        = new ClassReader(testClassBytes)
      var idMethods          = Set.empty[String]
      classReader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String]
          ): MethodVisitor = {
            if (name.startsWith("id")) idMethods = idMethods + name
            null
          }
        },
        0
      )
      idMethods
    }.asserting(_ shouldBe Set("id$MyStr"))
  }
}
