package com.vanillasource.eliot.eliotc.jvm.classgen.asm

import cats.effect.Sync
import cats.syntax.all.*
import cats.effect.kernel.Resource
import ClassGenerator.{createClassGenerator, createInterfaceGenerator}
import NativeType.{convertToCtorSignatureString, convertToMainClassName, convertToSignatureString, javaSignatureName}
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import org.objectweb.asm.{ClassWriter, Opcodes}

class ClassGenerator(val moduleName: ModuleName, val internalName: String, private val classWriter: ClassWriter) {
  /** The names [[createPrivateStaticFieldOnce]] has already declared on this class. */
  private val declaredFields: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty


  /** Generate the byte-code for the currently created class.
    */
  def generate[F[_]: Sync](): F[ClassFile] =
    Sync[F].delay(classWriter.visitEnd()) >> Sync[F].pure(ClassFile(internalName + ".class", classWriter.toByteArray))

  /** Create inner interface class with the given non-qualified name.
    */
  def createInnerInterfaceGenerator[F[_]: Sync](innerName: JvmIdentifier): F[ClassGenerator] =
    val innerInternalName = internalName + "$" + innerName.value
    for {
      interfaceGenerator <-
        createInterfaceGenerator[F](
          ModuleName(moduleName.packages, moduleName.name + "$" + innerName.value),
          innerInternalName
        )
      _                  <- Sync[F].delay(
                              interfaceGenerator.classWriter.visitInnerClass(
                                innerInternalName,
                                internalName,
                                innerName.value,
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
                              )
                            )
      _                  <- Sync[F].delay(
                              classWriter.visitInnerClass(
                                innerInternalName,
                                internalName,
                                innerName.value,
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
                              )
                            )
    } yield interfaceGenerator

  /** Add an abstract method declaration to this class (for use in interfaces).
    */
  def createAbstractMethod[F[_]: Sync](
      name: JvmIdentifier,
      parameterTypes: Seq[ValueFQN],
      resultType: ValueFQN
  ): F[Unit] = Sync[F].delay {
    classWriter
      .visitMethod(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
        name.value,
        convertToSignatureString(parameterTypes, resultType),
        null,
        null
      )
      .visitEnd()
  }

  /** Create inner class with the given non-qualified name.
    * @param innerName
    *   The plain non-qualified and non-embedded name of the inner class.
    */
  def createInnerClassGenerator[F[_]: Sync](
      innerName: JvmIdentifier,
      interfaces: Seq[String] = Seq.empty
  ): F[ClassGenerator] =
    val innerInternalName = internalName + "$" + innerName.value
    for {
      classGenerator <-
        createClassGenerator[F](
          ModuleName(moduleName.packages, moduleName.name + "$" + innerName.value),
          innerInternalName,
          interfaces
        )
      _              <- Sync[F].delay(
                          classGenerator.classWriter.visitInnerClass(
                            innerInternalName,
                            internalName,
                            innerName.value,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                          )
                        )
      _              <- Sync[F].delay(
                          classWriter.visitInnerClass(
                            innerInternalName,
                            internalName,
                            innerName.value,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                          )
                        )
    } yield classGenerator

  /** Create a public static final field (for singleton INSTANCE fields).
    */
  def createStaticFinalField[F[_]: Sync](name: JvmIdentifier, fieldType: ValueFQN): F[Unit] = Sync[F].delay {
    classWriter
      .visitField(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
        name.value,
        javaSignatureName(fieldType),
        null,
        null
      )
      .visitEnd()
  }

  /** Create a private, mutable static field carrying a **raw JVM descriptor**.
    *
    * Every other field helper here names its type by [[ValueFQN]], because every other field holds an Eliot value. This
    * one is for a native's own private state — the shared `java.io.BufferedReader` over standard input — which has no
    * Eliot type to name it by and is never visible to Eliot code. Mutable (not `final`) so the native can initialise it
    * on first use.
    */
  def createPrivateStaticField[F[_]: Sync](name: JvmIdentifier, descriptor: String): F[Unit] = Sync[F].delay {
    classWriter
      .visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name.value, descriptor, null, null)
      .visitEnd()
  }

  /** As [[createPrivateStaticField]], but a no-op when this class already has a field of that name.
    *
    * The one need is a field **shared between natives**: effects v6's control-flow leaves
    * ([[com.vanillasource.eliot.eliotc.jvm.classgen.processor.ControlNatives]]) come in groups that read and write one
    * cell or one exit slot, each emitted independently and any subset of which may be reached, so no single one of
    * them can own the declaration. Declaring it twice would emit a duplicate field and produce an invalid class.
    */
  def createPrivateStaticFieldOnce[F[_]: Sync](name: JvmIdentifier, descriptor: String): F[Unit] = Sync[F].delay {
    if (declaredFields.add(name.value))
      classWriter
        .visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name.value, descriptor, null, null)
        .visitEnd()
  }

  /** Create a public, mutable static field carrying a **raw JVM descriptor**.
    *
    * As [[createPrivateStaticField]], but readable from another class: the one use is the entry point's argument stash,
    * which the `eliot.system.Environment` natives read from their own class (see `SystemNatives`).
    */
  def createPublicStaticField[F[_]: Sync](name: String, descriptor: String): F[Unit] = Sync[F].delay {
    classWriter
      .visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null)
      .visitEnd()
  }

  /** Create a static initializer block (&lt;clinit&gt;). Ends with RETURN automatically.
    */
  def createStaticInit[F[_]: Sync](): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_STATIC,
        "<clinit>",
        "()V",
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        methodGenerator.methodVisitor.visitInsn(Opcodes.RETURN)
        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

  /** Create a public instance method (non-static). Ends with ARETURN automatically.
    */
  def createPublicInstanceMethod[F[_]: Sync](
      name: JvmIdentifier,
      parameterTypes: Seq[ValueFQN],
      resultType: ValueFQN
  ): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_PUBLIC,
        name.value,
        convertToSignatureString(parameterTypes, resultType),
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        methodGenerator.methodVisitor.visitInsn(Opcodes.ARETURN)
        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

  /** Create the given field with the given type.
    * @param fieldType
    *   The type of the field.
    */
  def createField[F[_]: Sync](name: JvmIdentifier, fieldType: ValueFQN): F[Unit] = Sync[F].delay {
    classWriter
      .visitField(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        name.value,
        javaSignatureName(fieldType),
        null,
        null
      )
      .visitEnd()
  }

  /** Create the given method with the given signature.
    */
  def createMethod[F[_]: Sync](
      name: JvmIdentifier,
      parameterTypes: Seq[ValueFQN],
      resultType: ValueFQN
  ): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
        name.value,
        convertToSignatureString(parameterTypes, resultType),
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        // TODO: add more types (primitives)
        // Note: we assume every method will return something (there's no void)
        methodGenerator.methodVisitor.visitInsn(Opcodes.ARETURN)

        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

  def createCtor[F[_]: Sync](
      parameterTypes: Seq[ValueFQN]
  ): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_PUBLIC,
        "<init>",
        convertToCtorSignatureString(parameterTypes),
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        methodGenerator.methodVisitor.visitInsn(Opcodes.RETURN)
        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

  def createMainMethod[F[_]: Sync](): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
        "main",
        "([Ljava/lang/String;)V",
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        methodGenerator.methodVisitor.visitInsn(Opcodes.RETURN)
        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

  def createApplyMethod[F[_]: Sync](
      parameterTypes: Seq[ValueFQN],
      resultType: ValueFQN
  ): Resource[F, MethodGenerator] =
    Resource.make(Sync[F].delay {
      val methodVisitor = classWriter.visitMethod(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        "apply",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        null,
        null
      )

      methodVisitor.visitCode()

      MethodGenerator(internalName, methodVisitor)
    })(methodGenerator =>
      Sync[F].delay {
        // TODO: add more types (primitives)
        methodGenerator.methodVisitor.visitInsn(Opcodes.ARETURN)

        methodGenerator.methodVisitor.visitMaxs(0, 0)
        methodGenerator.methodVisitor.visitEnd()
      }
    )

}

object ClassGenerator {

  /** A `ClassWriter` whose frame computation does not need the classes being generated to be *loadable*.
    *
    * `COMPUTE_FRAMES` asks `getCommonSuperClass` for the join of two stack types wherever control flow merges, and
    * ASM answers by loading both classes with reflection. That works for `java.*` and for anything already on the
    * classpath, and cannot work for a class this compiler is in the middle of emitting — a generated lambda or data
    * class — which surfaces as `TypeNotPresentException` from deep inside `visitMaxs`.
    *
    * Merges are reachable because a code parameter listing no effects is a thunk (`fold`'s `whenTrue uses *: A` is
    * `Unit => A`), so a branch yields a lambda *instance*, and two arms of one `fold` are two different generated
    * classes.
    *
    * The fallback is deliberately narrow. Two generated lambdas join at `java/util/function/Function`, which every one
    * of them implements ([[com.vanillasource.eliot.eliotc.jvm.classgen.processor.LambdaGenerator]]) and which is
    * precise enough for the `INVOKEINTERFACE apply` that follows. Anything else joins at `Object`, which is sound
    * because this backend casts wherever a reference is used at a narrower type.
    */
  private class GeneratingClassWriter extends ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
    override protected def getCommonSuperClass(type1: String, type2: String): String =
      try super.getCommonSuperClass(type1, type2)
      catch {
        case _: TypeNotPresentException | _: ClassNotFoundException | _: NoClassDefFoundError =>
          if (isGeneratedFunction(type1) && isGeneratedFunction(type2)) "java/util/function/Function"
          else "java/lang/Object"
      }

    private def isGeneratedFunction(internalName: String): Boolean =
      internalName.matches(".*\\$lambda\\$\\d+")
  }

  /** Generates an empty class for the given module. Each module has exactly one class generated for it.
    */
  def createClassGenerator[F[_]: Sync](name: ModuleName, interfaces: Seq[String] = Seq.empty): F[ClassGenerator] =
    val className = convertToMainClassName(name)
    createClassGenerator(name, className, interfaces)

  private def createClassGenerator[F[_]: Sync](
      name: ModuleName,
      className: String,
      interfaces: Seq[String]
  ): F[ClassGenerator] =
    Sync[F].delay {
      val classWriter = GeneratingClassWriter()

      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC,
        className,
        null,
        "java/lang/Object",
        if (interfaces.isEmpty) null else interfaces.toArray
      )

      new ClassGenerator(name, className, classWriter)
    }

  /** Generates a JVM interface for the given name.
    */
  def createInterfaceGenerator[F[_]: Sync](name: ModuleName): F[ClassGenerator] =
    val className = convertToMainClassName(name)
    createInterfaceGenerator(name, className)

  private def createInterfaceGenerator[F[_]: Sync](name: ModuleName, className: String): F[ClassGenerator] =
    Sync[F].delay {
      val classWriter = GeneratingClassWriter()

      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
        className,
        null,
        "java/lang/Object",
        null
      )

      new ClassGenerator(name, className, classWriter)
    }
}
