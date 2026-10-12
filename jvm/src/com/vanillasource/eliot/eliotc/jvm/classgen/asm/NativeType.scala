package com.vanillasource.eliot.eliotc.jvm.classgen.asm

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.ModuleName
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.defaultSystemPackage
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import org.objectweb.asm.Type

trait NativeType {
  def javaClass: Class[?]
}

object NativeType {
  def systemLangType(typeName: String): ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, typeName), QualifiedName(typeName, Qualifier.Default))

  /** A type in the `eliot.collection` package (e.g. `List`), whose runtime representation is a native JVM collection
    * rather than an Eliot `data` type — so Eliot values interoperate with the JDK/Java libraries directly.
    */
  def systemCollectionType(typeName: String): ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "collection"), typeName), QualifiedName(typeName, Qualifier.Default))

  /** A type in the `eliot.file` package, represented by a native JVM type rather than an Eliot `data` type. `moduleName`
    * and `typeName` differ for the private `File.IoResult` holder (module `File`, type `IoResult`); they coincide for
    * `Path.Path`.
    */
  def systemFileType(moduleName: String, typeName: String): ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "file"), moduleName), QualifiedName(typeName, Qualifier.Default))

  /** A control effect's private frame type in the `eliot.effect` package — `Throw.ThrowFrame`, `State.StateCell` — which the
    * platform's control primitives make and the effect's implementation takes as its parameter (`docs/effects.md` §11,
    * D20c option 5). Every one is an opaque `Object`: an escape frame is compared by identity, and a cell is a one-slot
    * `Object[]` only the primitives look inside.
    */
  def effectFrameType(moduleName: String, typeName: String): ValueFQN =
    ValueFQN(ModuleName(ModuleName.effectPackage, moduleName), QualifiedName(typeName, Qualifier.Default))

  /** A type in the `eliot.system` package, represented by a native JVM type rather than an Eliot `data` type — the
    * `Process.ProcessOutcome` holder.
    */
  def systemSystemType(moduleName: String, typeName: String): ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "system"), moduleName), QualifiedName(typeName, Qualifier.Default))

  // JVM representation types live in the jvm-layer `eliot.lang.Int` module (see jvm `Int.els`, alongside the opaque
  // `Int` body that selects among them), so unlike `systemLangType` the module name is fixed (`Int`) and only the
  // value name varies. `Qualifier.Default` matches the qualifier that type FQNs carry by the time they reach this map
  // (`CommonPatterns.stripDataTypeSuffix`).
  def jvmRepresentationType(typeName: String): ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Int"), QualifiedName(typeName, Qualifier.Default))

  val systemFunctionValue: ValueFQN = systemLangType("Function")
  val systemAnyValue: ValueFQN      = systemLangType("Any")
  val systemUnitValue: ValueFQN     = systemLangType("Unit")
  // `Type` lives in `eliot.compiler` (the importable compiler package), not the `eliot.lang` prelude — but the `lang`
  // source root is on both the compiler and runtime paths, so the type-constructor interface is generated for codegen
  // too. The module name therefore differs from `systemLangType`'s `eliot.lang` default.
  val systemTypeValue: ValueFQN     =
    ValueFQN(ModuleName(ModuleName.compilerPackage, "Type"), QualifiedName("Type", Qualifier.Default))

  val types: Map[ValueFQN, NativeType] = Map.from(
    Seq(
      (systemLangType("String"), eliot_lang_String),
      (systemLangType("Bool"), eliot_lang_Bool),
      (systemLangType("Function"), eliot_lang_Function),
      (systemLangType("Unit"), eliot_lang_Unit),
      (systemLangType("Any"), eliot_lang_Any),
      (systemLangType("BigInteger"), eliot_lang_BigInteger),
      // `eliot.collection.List` is represented as a native `java.util.List` (not an Eliot `data` type), so lists pass to
      // and from Java library methods directly. It is immutable by contract: the `empty`/`append` natives build fresh
      // lists and the Eliot API exposes no mutators.
      (systemCollectionType("List"), eliot_collection_List),
      // `eliot.file.Path` is a native `java.nio.file.Path`; the private `eliot.file.File.IoResult` holder is a two-slot
      // `Object[]` (error message at 0, value at 1) so a leaf file native never constructs an Eliot `data` value.
      (systemFileType("Path", "Path"), eliot_file_Path),
      (systemFileType("File", "IoResult"), eliot_file_IoResult),
      // The private `eliot.system.Process.ProcessOutcome` holder is a four-slot `Object[]` (error message, exit code,
      // captured stdout, captured stderr), for the same reason `IoResult` is a two-slot one.
      (systemSystemType("Process", "ProcessOutcome"), eliot_system_ProcessOutcome),
      // The control effects' frames (D20c option 5): opaque to everything but the primitives that make them.
      (effectFrameType("Throw", "ThrowFrame"), eliot_lang_Any),
      (effectFrameType("Abort", "AbortFrame"), eliot_lang_Any),
      (effectFrameType("State", "StateCell"), eliot_lang_Any),
      (effectFrameType("Writer", "WriterCell"), eliot_lang_Any),
      (effectFrameType("Dep", "DepCell"), eliot_lang_Any),
      // The fixed set of JVM representation types `Int[MIN, MAX]` can lower to (see jvm `Int.els`). These five entries
      // are the entire backend "type knowledge" of integer widths; all width *policy* (which range picks which) lives
      // in Eliot's opaque `Int` body (Phase 2) and the unfold pass (Phase 3).
      (jvmRepresentationType("JvmByte"), eliot_lang_JvmByte),
      (jvmRepresentationType("JvmShort"), eliot_lang_JvmShort),
      (jvmRepresentationType("JvmInt"), eliot_lang_JvmInt),
      (jvmRepresentationType("JvmLong"), eliot_lang_JvmLong),
      (jvmRepresentationType("JvmBigInteger"), eliot_lang_JvmBigInteger),
      // A bare `Int` type (a method parameter/return descriptor, or any ⊤ boundary) maps to `java.math.BigInteger` — the
      // widest layout, the sound ⊤ representation. `Int` is no longer lowered to a `Jvm*` type: an `Int` *body node*'s
      // narrow width is decoded from its refinement-channel meta by `IntRepresentation` at codegen, but the descriptor of
      // a boundary (where no range is tracked) is always the bignum (`docs/generic-refinement-merges.md` Step 6).
      (jvmRepresentationType("Int"), eliot_lang_JvmBigInteger)
    )
  )

  def javaSignatureName(vfqn: ValueFQN): String =
    types.get(vfqn).map(_.javaClass.descriptorString()).getOrElse(convertToJavaName(vfqn))

  def javaInternalName(vfqn: ValueFQN): String =
    types
      .get(vfqn)
      .map(cl => Type.getInternalName(cl.javaClass))
      .getOrElse(convertToNestedClassName(vfqn))

  private def convertToJavaName(vfqn: ValueFQN): String =
    // All data classes are nested classes inside the class denoted by the "module"!
    "L" + convertToNestedClassName(vfqn) + ";"

  def convertToMainClassName(moduleName: ModuleName): String =
    moduleName.packages.appended(moduleName.name).map(JvmIdentifier.encode(_).value).mkString("/")

  def convertToNestedClassName(vfqn: ValueFQN): String = {
    val prefix = vfqn.name.qualifier match {
      case Qualifier.Type => "type$"
      case _              => ""
    }
    convertToMainClassName(vfqn.moduleName) + "$" + prefix + JvmIdentifier.encode(vfqn.name.name).value
  }

  def convertToSignatureString(parameterTypes: Seq[ValueFQN], resultType: ValueFQN): String =
    s"(${parameterTypes.map(javaSignatureName).mkString})${javaSignatureName(resultType)}"

  def convertToCtorSignatureString(parameterTypes: Seq[ValueFQN]): String =
    s"(${parameterTypes.map(javaSignatureName).mkString})V"

  def convertToApplySignatureString(parameterTypes: Seq[ValueFQN], resultType: ValueFQN): String =
    s"(${parameterTypes.map(javaSignatureName).map(t => if (t === "V") "Ljava/lang/Void;" else t).mkString})${
        if (resultType === systemUnitValue) "Ljava/lang/Void;" else javaSignatureName(resultType)
      }"

  private def eliot_lang_String: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.String]
  }

  // The opaque `Bool` (no `data`, no chosen representation in the base) is carried at runtime as a boxed
  // `java.lang.Boolean` — matching the compile-time `VConst(Direct(Boolean, …))` domain. `true`/`false`/`fold`/
  // `&&`/`||`/`!` are emitted inline by `ExpressionCodeGenerator` (see `Intrinsics`) over this representation.
  private def eliot_lang_Bool: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Boolean]
  }

  // We compile Unit to Void not "void", because there's just too many random exceptions
  // that we can't (don't want to) handle. We assume that previous optimizations will mostly get
  // rid of these anyway.
  private def eliot_lang_Unit: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Void]
  }

  // TODO: This is not a "real" eliot type, just there to map to Object
  private def eliot_lang_Any: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Object]
  }

  // An unbounded `BigInteger` is represented as a real `java.math.BigInteger` (not a stopgap `Long`), so a materialised
  // compile-time constant whose bounds exceed `Long` (the general width-dispatch case) keeps its full precision.
  private def eliot_lang_BigInteger: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.math.BigInteger]
  }

  private def eliot_lang_JvmByte: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Byte]
  }

  private def eliot_lang_JvmShort: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Short]
  }

  private def eliot_lang_JvmInt: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Integer]
  }

  private def eliot_lang_JvmLong: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.lang.Long]
  }

  private def eliot_lang_JvmBigInteger: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.math.BigInteger]
  }

  private def eliot_lang_Function: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.util.function.Function[?, ?]]
  }

  private def eliot_collection_List: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.util.List[?]]
  }

  private def eliot_file_Path: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[java.nio.file.Path]
  }

  // The `IoResult` holder is a two-slot `Object[]`: slot 0 the error message (or null), slot 1 the value (or null).
  private def eliot_file_IoResult: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[Array[Object]]
  }

  // The `ProcessOutcome` holder is a four-slot `Object[]`: slot 0 the error message (or null), slot 1 the exit code as a
  // `BigInteger`, slots 2 and 3 the captured standard output and error.
  private def eliot_system_ProcessOutcome: NativeType = new NativeType {
    override def javaClass: Class[?] = classOf[Array[Object]]
  }
}
