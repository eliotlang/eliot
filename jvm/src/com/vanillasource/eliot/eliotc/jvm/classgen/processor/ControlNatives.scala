package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.CommonPatterns.valueType
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType.{systemAnyValue, systemFunctionValue, systemUnitValue, types}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{ClassGenerator, JvmIdentifier}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import org.objectweb.asm.{Label, Opcodes}

/** Effects v6's two control-flow primitives on the JVM (`docs/effects.md` §9.6) — the **escape** (a non-local exit)
  * and the **cell** (a value threaded through calls that never mention it). Neither is expressible as a def in a
  * strict pure core, which is the whole reason the carrier existed; they are the only two things left of it.
  *
  * **A frame is passed, never searched for** (effects D20c option 5, `docs/effects.md` §11). The leaf that installs one
  * makes a fresh frame — an escape's is a plain `Object` compared by identity, a cell is a one-slot `Object[]` — and
  * hands it to the thunk it runs, which is the computation it discharges; the environment threading at the
  * `WovenValue` seam passes it on to exactly the operations written inside that computation. So an exit leaves through
  * the frame it was given, and a cell is read and written through the one it was given: a helper's own discharge of
  * the same effect is a different object, invisible to an operation it was not given, and nothing lives in a static
  * field a nested discharge would have to save and restore.
  *
  * Each group is still declared `private` in the module that needs it (`eliot.effect.Throw` and `eliot.effect.Abort`
  * each own an escape; `State`, `Writer` and `Dep` each own a cell) and emitted once per instantiation, but neither
  * separates frames any more: identity does.
  *
  * The exit is a **pre-allocated shared exception** carrying the frame and the value in per-module fields: the machine
  * stack unwinds, and each escape on the way compares the frame with its own and rethrows on a mismatch. Reusing one
  * instance skips the stack-trace fill-in that makes exceptions expensive: an exit carries no diagnostic, only a value.
  *
  * The leaves are written in **continuation-passing** form (`escapeInternal(body, onExit, onValue)`) so that no native
  * here constructs an Eliot `data`: the result `Either`/`Pair` is built by the ordinary Eliot body of the discharger
  * that calls it, and the bytecode only ever applies a `Function`. That is the same shape `foreverInternal` uses, and
  * it keeps the backend's knowledge of the standard library at zero.
  *
  * **Reference representations only.** A key or payload whose representation is a JVM primitive (`{State[Int]}`) is
  * rejected by [[unsupportedInstantiation]] rather than mis-emitted: the cell is an `Object[]` and `Function.apply` is
  * `Object`-typed, so a primitive would need boxing decisions this does not make. The rejection is a loud build error
  * at the definition, never a silent wrong store.
  */
object ControlNatives {

  /** The leaves, keyed by FQN — the module part varies, so each is registered for every module that declares it. */
  val perInstantiation: Map[ValueFQN, (JvmIdentifier, Seq[GroundValue]) => NativeImplementation] =
    Map.from(
      escapeModules.flatMap(module =>
        Seq(
          effectValueFQN(module, "escapeInternal") -> escapeInternal,
          effectValueFQN(module, "exitInternal")   -> exitInternal
        )
      ) ++ cellModules.flatMap(module =>
        Seq(
          effectValueFQN(module, "withCellInternal")  -> withCellInternal,
          effectValueFQN(module, "readCellInternal")  -> readCellInternal,
          effectValueFQN(module, "writeCellInternal") -> writeCellInternal
        )
      )
    )

  /** The modules owning an escape group: the two abortive control effects. */
  private def escapeModules: Seq[String] = Seq("Throw", "Abort")

  /** The modules owning a cell group: the three stateful control effects. */
  private def cellModules: Seq[String] = Seq("State", "Writer", "Dep")

  /** The build error for an instantiation this cannot emit, or [[None]]. Read by [[JvmClassGenerator]] before it emits,
    * so the error lands at the definition rather than as invalid bytecode.
    */
  def unsupportedInstantiation(vfqn: ValueFQN, typeArgs: Seq[GroundValue]): Option[String] =
    typeArgs.find(isPrimitive).map { argument =>
      s"The control-flow primitive '${vfqn.name.name}' cannot be used at '${valueType(argument).name.name}', whose " +
        "machine representation is a primitive. Use a boxed type, or discharge the effect at a reference type."
    }

  private def isPrimitive(value: GroundValue): Boolean =
    types.get(valueType(value)).exists(_.javaClass.isPrimitive)

  /** The per-module fields. Names are prefixed so no Eliot value can collide with them. */
  private val exitSentinelField = JvmIdentifier("eliot$exit")
  private val exitFrameField    = JvmIdentifier("eliot$exitFrame")
  private val exitValueField    = JvmIdentifier("eliot$exitValue")

  private val throwableDescriptor = "Ljava/lang/RuntimeException;"
  private val objectDescriptor    = "Ljava/lang/Object;"
  private val functionApply       = "(Ljava/lang/Object;)Ljava/lang/Object;"
  private val cellClass           = "[Ljava/lang/Object;"

  /** `escapeInternal[K, A, R](body: {Throw[K]} A, onExit: K => {} R, onValue: A => {} R): R` — make a fresh frame, run
    * `body` with it, and hand the result to `onValue`, or whatever left through *this* frame to `onExit`.
    *
    * The frame is an `Object` compared by **identity**, and it reaches exactly the operations the discharger gave it
    * to: the thunk `body` takes it as its argument (effects D20c option 5). An exit carrying any other frame — a
    * caller's, passing through a helper's own discharge — is rethrown, so no frame intercepts an operation it was not
    * given, and two live frames of one instantiation are told apart.
    */
  private def escapeInternal(name: JvmIdentifier, typeArgs: Seq[GroundValue]): NativeImplementation =
    new NativeImplementation {
      override val impure: Boolean = true

      override def generateMethod(classGenerator: ClassGenerator): CompilerIO[Unit] =
        declareExitFields(classGenerator) >>
          classGenerator
            .createMethod[CompilerIO](
              name,
              Seq(systemFunctionValue, systemFunctionValue, systemFunctionValue),
              valueType(typeArgs(2))
            )
            .use { methodGenerator =>
              methodGenerator.runNative[CompilerIO] { methodVisitor =>
                val tryStart = new Label()
                val tryEnd   = new Label()
                val handler  = new Label()
                val rethrow  = new Label()
                val end      = new Label()

                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                methodVisitor.visitInsn(Opcodes.DUP)
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 3)                // this call's frame

                methodVisitor.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException")

                methodVisitor.visitLabel(tryStart)
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)                 // onValue
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)                 // body
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)                 // given its frame
                apply(methodVisitor)                                         // the body's value
                apply(methodVisitor)                                         // onValue(value)
                methodVisitor.visitLabel(tryEnd)
                methodVisitor.visitJumpInsn(Opcodes.GOTO, end)

                methodVisitor.visitLabel(handler)                            // [throwable]
                methodVisitor.visitInsn(Opcodes.DUP)
                methodVisitor.visitFieldInsn(
                  Opcodes.GETSTATIC,
                  classGenerator.internalName,
                  exitSentinelField.value,
                  throwableDescriptor
                )
                methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, rethrow)      // not an Eliot exit at all
                methodVisitor.visitFieldInsn(
                  Opcodes.GETSTATIC,
                  classGenerator.internalName,
                  exitFrameField.value,
                  objectDescriptor
                )
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
                methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, rethrow)      // another frame's exit
                methodVisitor.visitInsn(Opcodes.POP)                         // drop the throwable
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)                 // onExit
                methodVisitor.visitFieldInsn(
                  Opcodes.GETSTATIC,
                  classGenerator.internalName,
                  exitValueField.value,
                  objectDescriptor
                )
                apply(methodVisitor)                                         // onExit(value)
                methodVisitor.visitJumpInsn(Opcodes.GOTO, end)

                methodVisitor.visitLabel(rethrow)
                methodVisitor.visitInsn(Opcodes.ATHROW)

                methodVisitor.visitLabel(end)
              } >> methodGenerator.addCastTo[CompilerIO](valueType(typeArgs(2)))
            }
    }

  /** `exitInternal[K, A](frame: EscapeFrame[K], err: K): A` — leave through `frame`, carrying `err`. Control does not
    * return, so the `ARETURN` `createMethod` appends is dead code that `COMPUTE_FRAMES` rewrites.
    */
  private def exitInternal(name: JvmIdentifier, typeArgs: Seq[GroundValue]): NativeImplementation =
    new NativeImplementation {
      override val impure: Boolean = true

      override def generateMethod(classGenerator: ClassGenerator): CompilerIO[Unit] =
        declareExitFields(classGenerator) >>
          classGenerator
            .createMethod[CompilerIO](name, Seq(systemAnyValue, valueType(typeArgs.head)), valueType(typeArgs(1)))
            .use { methodGenerator =>
              methodGenerator.runNative[CompilerIO] { methodVisitor =>
                val allocated = new Label()

                // The sentinel is allocated on the first exit and reused: an exit carries a value, never a diagnostic,
                // so filling in a stack trace per raise would be pure cost. Unsynchronised, as `readLineInternal`'s
                // reader is, because the language cannot express a thread.
                methodVisitor.visitFieldInsn(
                  Opcodes.GETSTATIC,
                  classGenerator.internalName,
                  exitSentinelField.value,
                  throwableDescriptor
                )
                methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, allocated)
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
                methodVisitor.visitInsn(Opcodes.DUP)
                methodVisitor.visitMethodInsn(
                  Opcodes.INVOKESPECIAL,
                  "java/lang/RuntimeException",
                  "<init>",
                  "()V",
                  false
                )
                methodVisitor.visitFieldInsn(
                  Opcodes.PUTSTATIC,
                  classGenerator.internalName,
                  exitSentinelField.value,
                  throwableDescriptor
                )
                methodVisitor.visitLabel(allocated)

                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                methodVisitor.visitFieldInsn(
                  Opcodes.PUTSTATIC,
                  classGenerator.internalName,
                  exitFrameField.value,
                  objectDescriptor
                )
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
                methodVisitor.visitFieldInsn(
                  Opcodes.PUTSTATIC,
                  classGenerator.internalName,
                  exitValueField.value,
                  objectDescriptor
                )
                methodVisitor.visitFieldInsn(
                  Opcodes.GETSTATIC,
                  classGenerator.internalName,
                  exitSentinelField.value,
                  throwableDescriptor
                )
                methodVisitor.visitInsn(Opcodes.ATHROW)
              }
            }
    }

  /** `withCellInternal[S, A, R](initial: S, body: {State[S]} A, combine: A => S => {} R): R` — make a fresh cell holding
    * `initial`, run `body` with it, and combine the result with the cell's final content. The cell is a one-slot
    * `Object[]` local to this call: the thunk `body` takes it as its argument, so it reaches exactly the operations the
    * discharger gave it to and is gone when the call returns or is exited through.
    */
  private def withCellInternal(name: JvmIdentifier, typeArgs: Seq[GroundValue]): NativeImplementation =
    new NativeImplementation {
      override val impure: Boolean = true

      override def generateMethod(classGenerator: ClassGenerator): CompilerIO[Unit] =
        classGenerator
          .createMethod[CompilerIO](
            name,
            Seq(valueType(typeArgs.head), systemFunctionValue, systemFunctionValue),
            valueType(typeArgs(2))
          )
          .use { methodGenerator =>
            methodGenerator.runNative[CompilerIO] { methodVisitor =>
              methodVisitor.visitInsn(Opcodes.ICONST_1)
              methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
              methodVisitor.visitInsn(Opcodes.DUP)
              methodVisitor.visitInsn(Opcodes.ICONST_0)
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)                   // initial
              methodVisitor.visitInsn(Opcodes.AASTORE)
              methodVisitor.visitVarInsn(Opcodes.ASTORE, 3)                  // this call's cell

              methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)                   // combine
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)                   // body
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)                   // given its cell
              apply(methodVisitor)                                           // the body's value
              apply(methodVisitor)                                           // combine(value)
              // Read *after* the body has run, so the final content is the one the body left.
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
              methodVisitor.visitInsn(Opcodes.ICONST_0)
              methodVisitor.visitInsn(Opcodes.AALOAD)
              apply(methodVisitor)                                           // combine(value)(content)
            } >> methodGenerator.addCastTo[CompilerIO](valueType(typeArgs(2)))
          }
    }

  /** `readCellInternal[S](cell: Cell[S]): S` — the cell's current content. */
  private def readCellInternal(name: JvmIdentifier, typeArgs: Seq[GroundValue]): NativeImplementation =
    new NativeImplementation {
      override val impure: Boolean = true

      override def generateMethod(classGenerator: ClassGenerator): CompilerIO[Unit] =
        classGenerator
          .createMethod[CompilerIO](name, Seq(systemAnyValue), valueType(typeArgs.head))
          .use { methodGenerator =>
            methodGenerator.runNative[CompilerIO] { methodVisitor =>
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
              methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cellClass)
              methodVisitor.visitInsn(Opcodes.ICONST_0)
              methodVisitor.visitInsn(Opcodes.AALOAD)
            } >> methodGenerator.addCastTo[CompilerIO](valueType(typeArgs.head))
          }
    }

  /** `writeCellInternal[S](cell: Cell[S], s: S): Unit` — replace the cell's content. */
  private def writeCellInternal(name: JvmIdentifier, typeArgs: Seq[GroundValue]): NativeImplementation =
    new NativeImplementation {
      override val impure: Boolean = true

      override def generateMethod(classGenerator: ClassGenerator): CompilerIO[Unit] =
        classGenerator
          .createMethod[CompilerIO](name, Seq(systemAnyValue, valueType(typeArgs.head)), systemUnitValue)
          .use { methodGenerator =>
            methodGenerator.runNative[CompilerIO] { methodVisitor =>
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
              methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cellClass)
              methodVisitor.visitInsn(Opcodes.ICONST_0)
              methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
              methodVisitor.visitInsn(Opcodes.AASTORE)
              methodVisitor.visitInsn(Opcodes.ACONST_NULL)
            }
          }
    }

  private def declareExitFields(classGenerator: ClassGenerator): CompilerIO[Unit] =
    classGenerator.createPrivateStaticFieldOnce[CompilerIO](exitSentinelField, throwableDescriptor) >>
      classGenerator.createPrivateStaticFieldOnce[CompilerIO](exitFrameField, objectDescriptor) >>
      classGenerator.createPrivateStaticFieldOnce[CompilerIO](exitValueField, objectDescriptor)

  private def apply(methodVisitor: org.objectweb.asm.MethodVisitor): Unit =
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", functionApply, true)

  private def effectValueFQN(moduleName: String, valueName: String): ValueFQN =
    ValueFQN(ModuleName(ModuleName.effectPackage, moduleName), QualifiedName(valueName, Qualifier.Default))
}
