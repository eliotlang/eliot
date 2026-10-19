package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{ClassGenerator, JvmIdentifier}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType.{
  systemAnyValue,
  systemCollectionType,
  systemFunctionValue,
  systemLangType
}
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.NativeImplementation.GenericNativeSignature
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.{Label, MethodVisitor}

/** The JVM leaf natives behind `eliot.file` — the pure `Path` algebra and the impure `FileSystem` operations, all
  * backed by `java.nio.file`.
  *
  * The guiding constraint: a native never constructs an Eliot `data` value in bytecode. So the `Path` inspectors return
  * a nullable and the Eliot wrappers build the `Option` (via [[isNull]]); the impure operations catch their exception
  * and return a two-slot `Object[]` result holder (error message at index 0, value at index 1 — see [[NativeType]]'s
  * `IoResult` mapping) which the Eliot instance reflects into `Throw[IoError]`. The one call *into* Eliot code is the
  * fold step, applied exactly as `List.foldLeftInternal` applies its combine.
  */
object FileNatives {

  private def pathFqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "file"), "Path"), QualifiedName(name, Qualifier.Default))
  private def fileFqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "file"), "File"), QualifiedName(name, Qualifier.Default))

  private val pathType: ValueFQN     = NativeType.systemFileType("Path", "Path")
  private val ioResultType: ValueFQN = NativeType.systemFileType("File", "IoResult")
  private val stringType: ValueFQN   = systemLangType("String")
  private val boolType: ValueFQN     = systemLangType("Bool")
  private val listType: ValueFQN     = systemCollectionType("List")

  // Internal JVM class names used across the natives.
  private val Files       = "java/nio/file/Files"
  private val JPath       = "java/nio/file/Path"
  private val OpenOption  = "java/nio/file/OpenOption"
  private val StdOpenOpt  = "java/nio/file/StandardOpenOption"
  private val LinkOption  = "java/nio/file/LinkOption"
  private val VisitOption = "java/nio/file/FileVisitOption"
  private val FileAttr    = "java/nio/file/attribute/FileAttribute"
  private val JString     = "java/lang/String"
  private val JBoolean    = "java/lang/Boolean"
  private val JObject     = "java/lang/Object"
  private val JBigInteger = "java/math/BigInteger"
  private val JCharacter  = "java/lang/Character"
  private val JList       = "java/util/List"
  private val JArrayList  = "java/util/ArrayList"
  private val JIterator   = "java/util/Iterator"
  private val JStream     = "java/util/stream/Stream"
  private val JBufReader  = "java/io/BufferedReader"
  private val JFunction   = "java/util/function/Function"
  private val ObjectArray = "[Ljava/lang/Object;"

  /** Every generic file/path native, with the erased signature both its definition and its call sites resolve through
    * (the `List.foldLeftInternal` mechanism).
    */
  val genericNativeSignatures: Map[ValueFQN, GenericNativeSignature] = Map(
    pathFqn("isNull")                 -> GenericNativeSignature(Seq(systemAnyValue), boolType),
    fileFqn("isNull")                 -> GenericNativeSignature(Seq(systemAnyValue), boolType),
    fileFqn("resultValue")            -> GenericNativeSignature(Seq(ioResultType), systemAnyValue),
    // A `List`-headed generic return (element `E` erased) rather than the bare `Any` of `resultValue`: the
    // directory/line operations read a `List`-shaped result back at its own head.
    fileFqn("resultAsList")           -> GenericNativeSignature(Seq(ioResultType), listType),
    fileFqn("foldLinesInternal")      ->
      GenericNativeSignature(Seq(systemAnyValue, systemFunctionValue, pathType), ioResultType),
    fileFqn("foldCodePointsInternal") ->
      GenericNativeSignature(Seq(systemAnyValue, systemFunctionValue, pathType), ioResultType)
  )

  /** The leaf natives keyed by their `Default`-qualified FQN, folded into [[NativeImplementation.implementations]]. */
  val implementations: Seq[(ValueFQN, NativeImplementation)] = Seq(
    // --- eliot.file.Path (pure) ---
    entry(pathFqn("pathInternal"), Seq(stringType), pathType)(pathOf),
    entry(pathFqn("slashInternal"), Seq(pathType, stringType), pathType)(resolve),
    entry(pathFqn("parentInternal"), Seq(pathType), pathType)(getParent),
    entry(pathFqn("fileNameInternal"), Seq(pathType), stringType)(getFileName),
    entry(pathFqn("extensionInternal"), Seq(pathType), stringType)(getExtension),
    entry(pathFqn("isAbsoluteInternal"), Seq(pathType), boolType)(isAbsolute),
    genericEntry(pathFqn("isNull"))(isNull),
    // --- eliot.file.File (impure I/O + pure holder helpers) ---
    entry(fileFqn("readFileInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(readFile)),
    entry(fileFqn("readLinesInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(readLines)),
    entry(fileFqn("writeFileInternal"), Seq(stringType, pathType), ioResultType, impure = true)(wrap(2)(writeFile)),
    entry(fileFqn("appendFileInternal"), Seq(stringType, pathType), ioResultType, impure = true)(wrap(2)(appendFile)),
    entry(fileFqn("existsInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(exists)),
    entry(fileFqn("isDirectoryInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(isDirectory)),
    entry(fileFqn("listDirectoryInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(listDirectory)),
    entry(fileFqn("walkInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(walk)),
    entry(fileFqn("createDirectoriesInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(createDirs)),
    entry(fileFqn("deleteInternal"), Seq(pathType), ioResultType, impure = true)(wrap(1)(delete)),
    genericEntry(fileFqn("foldLinesInternal"), impure = true)(wrap(3)(foldLines)),
    genericEntry(fileFqn("foldCodePointsInternal"), impure = true)(wrap(3)(foldCodePoints)),
    genericEntry(fileFqn("resultValue"))(resultValue),
    genericEntry(fileFqn("resultAsList"))(resultAsList),
    entry(fileFqn("resultErrorMessage"), Seq(ioResultType), stringType)(resultErrorMessage),
    genericEntry(fileFqn("isNull"))(isNull)
  )

  /** The runtime ability-impl natives, folded into [[NativeImplementation.abilityImplementations]]: `Eq[Path]::equals`
    * and `Show[Path]::show`, realised as `java.nio.file.Path.equals`/`.toString` — the `Eq[String]`/`Show[String]`
    * model.
    */
  val abilityImplementations: Seq[(String, String, String, JvmIdentifier => NativeImplementation)] = Seq(
    ("Eq", "equals", "Path", pathEquals),
    ("Show", "show", "Path", pathShow)
  )

  // ------------------------------------------------------------------------------------------------------------------
  // Registration helpers
  // ------------------------------------------------------------------------------------------------------------------

  /** A fixed-signature native emitted under its FQN's simple name. */
  private def entry(fqn: ValueFQN, params: Seq[ValueFQN], ret: ValueFQN, impure: Boolean = false)(
      body: MethodVisitor => Unit
  ): (ValueFQN, NativeImplementation) = {
    val isImpure = impure
    fqn -> new NativeImplementation {
      override val impure: Boolean                                      = isImpure
      override def generateMethod(cg: ClassGenerator): CompilerIO[Unit] =
        cg.createMethod[CompilerIO](JvmIdentifier(fqn.name.name), params, ret).use(_.runNative(body))
    }
  }

  /** A generic (erased) native, taking its signature from [[genericNativeSignatures]]. */
  private def genericEntry(fqn: ValueFQN, impure: Boolean = false)(
      body: MethodVisitor => Unit
  ): (ValueFQN, NativeImplementation) = {
    val isImpure = impure
    val sig      = genericNativeSignatures(fqn)
    fqn -> new NativeImplementation {
      override val impure: Boolean                                      = isImpure
      override def generateMethod(cg: ClassGenerator): CompilerIO[Unit] =
        cg.createMethod[CompilerIO](JvmIdentifier(fqn.name.name), sig.parameterTypes, sig.returnType)
          .use(_.runNative(body))
    }
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Holder helpers (pure)
  // ------------------------------------------------------------------------------------------------------------------

  private def resultValue(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitTypeInsn(CHECKCAST, ObjectArray)
    mv.visitInsn(ICONST_1)
    mv.visitInsn(AALOAD)
  }

  private def resultAsList(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitTypeInsn(CHECKCAST, ObjectArray)
    mv.visitInsn(ICONST_1)
    mv.visitInsn(AALOAD)
    mv.visitTypeInsn(CHECKCAST, JList)
  }

  private def resultErrorMessage(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitTypeInsn(CHECKCAST, ObjectArray)
    mv.visitInsn(ICONST_0)
    mv.visitInsn(AALOAD)
    mv.visitTypeInsn(CHECKCAST, JString)
  }

  private def isNull(mv: MethodVisitor): Unit = {
    val isNullL = new Label()
    val endL    = new Label()
    mv.visitVarInsn(ALOAD, 0)
    mv.visitJumpInsn(IFNULL, isNullL)
    mv.visitFieldInsn(GETSTATIC, JBoolean, "FALSE", "Ljava/lang/Boolean;")
    mv.visitJumpInsn(GOTO, endL)
    mv.visitLabel(isNullL)
    mv.visitFieldInsn(GETSTATIC, JBoolean, "TRUE", "Ljava/lang/Boolean;")
    mv.visitLabel(endL)
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Path algebra (pure)
  // ------------------------------------------------------------------------------------------------------------------

  private def pathOf(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, JString)
    mv.visitMethodInsn(INVOKESTATIC, JPath, "of", s"(L$JString;[L$JString;)L$JPath;", true)
  }

  private def resolve(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitVarInsn(ALOAD, 1)
    mv.visitMethodInsn(INVOKEINTERFACE, JPath, "resolve", s"(L$JString;)L$JPath;", true)
  }

  private def getParent(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKEINTERFACE, JPath, "getParent", s"()L$JPath;", true)
  }

  private def getFileName(mv: MethodVisitor): Unit = {
    val nullL = new Label()
    val endL  = new Label()
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKEINTERFACE, JPath, "getFileName", s"()L$JPath;", true)
    mv.visitInsn(DUP)
    mv.visitJumpInsn(IFNULL, nullL)
    mv.visitMethodInsn(INVOKEVIRTUAL, JObject, "toString", s"()L$JString;", false)
    mv.visitJumpInsn(GOTO, endL)
    mv.visitLabel(nullL)
    mv.visitInsn(POP)
    mv.visitInsn(ACONST_NULL)
    mv.visitLabel(endL)
  }

  private def getExtension(mv: MethodVisitor): Unit = {
    val nullL = new Label()
    val endL  = new Label()
    // Store the file name Path in a local so every jump to `nullL` leaves an empty stack (a `DUP`/`IFNULL` would reach
    // `nullL` with a Path still on the stack, but the numeric guards below reach it empty — an inconsistent stackmap).
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKEINTERFACE, JPath, "getFileName", s"()L$JPath;", true)
    mv.visitVarInsn(ASTORE, 3)         // fileName Path (or null)
    mv.visitVarInsn(ALOAD, 3)
    mv.visitJumpInsn(IFNULL, nullL)
    mv.visitVarInsn(ALOAD, 3)
    mv.visitMethodInsn(INVOKEVIRTUAL, JObject, "toString", s"()L$JString;", false)
    mv.visitVarInsn(ASTORE, 1)         // name
    mv.visitVarInsn(ALOAD, 1)
    mv.visitIntInsn(BIPUSH, 46)        // '.'
    mv.visitMethodInsn(INVOKEVIRTUAL, JString, "lastIndexOf", "(I)I", false)
    mv.visitVarInsn(ISTORE, 2)         // idx
    mv.visitVarInsn(ILOAD, 2)
    mv.visitJumpInsn(IFLE, nullL)      // idx <= 0  -> no extension / dotfile
    mv.visitVarInsn(ILOAD, 2)
    mv.visitVarInsn(ALOAD, 1)
    mv.visitMethodInsn(INVOKEVIRTUAL, JString, "length", "()I", false)
    mv.visitInsn(ICONST_1)
    mv.visitInsn(ISUB)
    mv.visitJumpInsn(IF_ICMPGE, nullL) // idx >= len-1 -> trailing dot
    mv.visitVarInsn(ALOAD, 1)
    mv.visitVarInsn(ILOAD, 2)
    mv.visitInsn(ICONST_1)
    mv.visitInsn(IADD)
    mv.visitMethodInsn(INVOKEVIRTUAL, JString, "substring", s"(I)L$JString;", false)
    mv.visitJumpInsn(GOTO, endL)
    mv.visitLabel(nullL)
    mv.visitInsn(ACONST_NULL)
    mv.visitLabel(endL)
  }

  private def isAbsolute(mv: MethodVisitor): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKEINTERFACE, JPath, "isAbsolute", "()Z", true)
    mv.visitMethodInsn(INVOKESTATIC, JBoolean, "valueOf", "(Z)Ljava/lang/Boolean;", false)
  }

  // ------------------------------------------------------------------------------------------------------------------
  // FileSystem success bodies — leave exactly one Object (the value) on the stack; may use locals from `free` upward
  // ------------------------------------------------------------------------------------------------------------------

  private def readFile(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKESTATIC, Files, "readString", s"(L$JPath;)L$JString;", false)
  }

  private def readLines(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKESTATIC, Files, "readAllLines", s"(L$JPath;)L$JList;", false)
  }

  private def writeFile(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 1)               // path
    mv.visitVarInsn(ALOAD, 0)               // content
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, OpenOption) // no options: create/truncate
    mv.visitMethodInsn(
      INVOKESTATIC,
      Files,
      "writeString",
      s"(L$JPath;Ljava/lang/CharSequence;[L$OpenOption;)L$JPath;",
      false
    )
    mv.visitInsn(POP)
    mv.visitInsn(ACONST_NULL)
  }

  private def appendFile(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 1) // path
    mv.visitVarInsn(ALOAD, 0) // content
    mv.visitInsn(ICONST_2)
    mv.visitTypeInsn(ANEWARRAY, OpenOption)
    mv.visitInsn(DUP)
    mv.visitInsn(ICONST_0)
    mv.visitFieldInsn(GETSTATIC, StdOpenOpt, "CREATE", s"L$StdOpenOpt;")
    mv.visitInsn(AASTORE)
    mv.visitInsn(DUP)
    mv.visitInsn(ICONST_1)
    mv.visitFieldInsn(GETSTATIC, StdOpenOpt, "APPEND", s"L$StdOpenOpt;")
    mv.visitInsn(AASTORE)
    mv.visitMethodInsn(
      INVOKESTATIC,
      Files,
      "writeString",
      s"(L$JPath;Ljava/lang/CharSequence;[L$OpenOption;)L$JPath;",
      false
    )
    mv.visitInsn(POP)
    mv.visitInsn(ACONST_NULL)
  }

  private def exists(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, LinkOption)
    mv.visitMethodInsn(INVOKESTATIC, Files, "exists", s"(L$JPath;[L$LinkOption;)Z", false)
    mv.visitMethodInsn(INVOKESTATIC, JBoolean, "valueOf", "(Z)Ljava/lang/Boolean;", false)
  }

  private def isDirectory(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, LinkOption)
    mv.visitMethodInsn(INVOKESTATIC, Files, "isDirectory", s"(L$JPath;[L$LinkOption;)Z", false)
    mv.visitMethodInsn(INVOKESTATIC, JBoolean, "valueOf", "(Z)Ljava/lang/Boolean;", false)
  }

  private def listDirectory(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKESTATIC, Files, "list", s"(L$JPath;)L$JStream;", false)
    mv.visitMethodInsn(INVOKEINTERFACE, JStream, "toList", s"()L$JList;", true)
  }

  private def walk(mv: MethodVisitor, free: Int): Unit = {
    val list = free
    val iter = free + 1
    val cur  = free + 2
    val loop = new Label()
    val skip = new Label()
    val done = new Label()
    mv.visitTypeInsn(NEW, JArrayList)
    mv.visitInsn(DUP)
    mv.visitMethodInsn(INVOKESPECIAL, JArrayList, "<init>", "()V", false)
    mv.visitVarInsn(ASTORE, list)
    mv.visitVarInsn(ALOAD, 0)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, VisitOption)
    mv.visitMethodInsn(INVOKESTATIC, Files, "walk", s"(L$JPath;[L$VisitOption;)L$JStream;", false)
    mv.visitMethodInsn(INVOKEINTERFACE, JStream, "iterator", s"()L$JIterator;", true)
    mv.visitVarInsn(ASTORE, iter)
    mv.visitLabel(loop)
    mv.visitVarInsn(ALOAD, iter)
    mv.visitMethodInsn(INVOKEINTERFACE, JIterator, "hasNext", "()Z", true)
    mv.visitJumpInsn(IFEQ, done)
    mv.visitVarInsn(ALOAD, iter)
    mv.visitMethodInsn(INVOKEINTERFACE, JIterator, "next", s"()L$JObject;", true)
    mv.visitTypeInsn(CHECKCAST, JPath)
    mv.visitVarInsn(ASTORE, cur)
    mv.visitVarInsn(ALOAD, cur)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, LinkOption)
    mv.visitMethodInsn(INVOKESTATIC, Files, "isRegularFile", s"(L$JPath;[L$LinkOption;)Z", false)
    mv.visitJumpInsn(IFEQ, skip)
    mv.visitVarInsn(ALOAD, list)
    mv.visitVarInsn(ALOAD, cur)
    mv.visitMethodInsn(INVOKEINTERFACE, JList, "add", s"(L$JObject;)Z", true)
    mv.visitInsn(POP)
    mv.visitLabel(skip)
    mv.visitJumpInsn(GOTO, loop)
    mv.visitLabel(done)
    mv.visitVarInsn(ALOAD, list)
  }

  private def createDirs(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitInsn(ICONST_0)
    mv.visitTypeInsn(ANEWARRAY, FileAttr)
    mv.visitMethodInsn(INVOKESTATIC, Files, "createDirectories", s"(L$JPath;[L$FileAttr;)L$JPath;", false)
    mv.visitInsn(POP)
    mv.visitInsn(ACONST_NULL)
  }

  private def delete(mv: MethodVisitor, free: Int): Unit = {
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKESTATIC, Files, "delete", s"(L$JPath;)V", false)
    mv.visitInsn(ACONST_NULL)
  }

  private def foldLines(mv: MethodVisitor, free: Int): Unit = {
    val acc    = free
    val reader = free + 1
    val line   = free + 2
    val loop   = new Label()
    val done   = new Label()
    mv.visitVarInsn(ALOAD, 0) // initial
    mv.visitVarInsn(ASTORE, acc)
    mv.visitVarInsn(ALOAD, 2) // path
    mv.visitMethodInsn(INVOKESTATIC, Files, "newBufferedReader", s"(L$JPath;)L$JBufReader;", false)
    mv.visitVarInsn(ASTORE, reader)
    mv.visitLabel(loop)
    mv.visitVarInsn(ALOAD, reader)
    mv.visitMethodInsn(INVOKEVIRTUAL, JBufReader, "readLine", s"()L$JString;", false)
    mv.visitInsn(DUP)
    mv.visitVarInsn(ASTORE, line)
    mv.visitJumpInsn(IFNULL, done)
    mv.visitVarInsn(ALOAD, 1) // step
    mv.visitVarInsn(ALOAD, acc)
    mv.visitMethodInsn(INVOKEINTERFACE, JFunction, "apply", s"(L$JObject;)L$JObject;", true)
    mv.visitTypeInsn(CHECKCAST, JFunction)
    mv.visitVarInsn(ALOAD, line)
    mv.visitMethodInsn(INVOKEINTERFACE, JFunction, "apply", s"(L$JObject;)L$JObject;", true)
    mv.visitVarInsn(ASTORE, acc)
    mv.visitJumpInsn(GOTO, loop)
    mv.visitLabel(done)
    mv.visitVarInsn(ALOAD, reader)
    mv.visitMethodInsn(INVOKEVIRTUAL, JBufReader, "close", "()V", false)
    mv.visitVarInsn(ALOAD, acc)
  }

  private def foldCodePoints(mv: MethodVisitor, free: Int): Unit = {
    val acc     = free
    val content = free + 1
    val i       = free + 2
    val cp      = free + 3
    val loop    = new Label()
    val done    = new Label()
    mv.visitVarInsn(ALOAD, 0) // initial
    mv.visitVarInsn(ASTORE, acc)
    mv.visitVarInsn(ALOAD, 2) // path
    mv.visitMethodInsn(INVOKESTATIC, Files, "readString", s"(L$JPath;)L$JString;", false)
    mv.visitVarInsn(ASTORE, content)
    mv.visitInsn(ICONST_0)
    mv.visitVarInsn(ISTORE, i)
    mv.visitLabel(loop)
    mv.visitVarInsn(ILOAD, i)
    mv.visitVarInsn(ALOAD, content)
    mv.visitMethodInsn(INVOKEVIRTUAL, JString, "length", "()I", false)
    mv.visitJumpInsn(IF_ICMPGE, done)
    mv.visitVarInsn(ALOAD, content)
    mv.visitVarInsn(ILOAD, i)
    mv.visitMethodInsn(INVOKEVIRTUAL, JString, "codePointAt", "(I)I", false)
    mv.visitVarInsn(ISTORE, cp)
    mv.visitVarInsn(ALOAD, 1) // step
    mv.visitVarInsn(ALOAD, acc)
    mv.visitMethodInsn(INVOKEINTERFACE, JFunction, "apply", s"(L$JObject;)L$JObject;", true)
    mv.visitTypeInsn(CHECKCAST, JFunction)
    mv.visitVarInsn(ILOAD, cp)
    mv.visitInsn(I2L)
    mv.visitMethodInsn(INVOKESTATIC, JBigInteger, "valueOf", s"(J)L$JBigInteger;", false)
    mv.visitMethodInsn(INVOKEINTERFACE, JFunction, "apply", s"(L$JObject;)L$JObject;", true)
    mv.visitVarInsn(ASTORE, acc)
    mv.visitVarInsn(ILOAD, i)
    mv.visitVarInsn(ILOAD, cp)
    mv.visitMethodInsn(INVOKESTATIC, JCharacter, "charCount", "(I)I", false)
    mv.visitInsn(IADD)
    mv.visitVarInsn(ISTORE, i)
    mv.visitJumpInsn(GOTO, loop)
    mv.visitLabel(done)
    mv.visitVarInsn(ALOAD, acc)
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Ability-impl natives
  // ------------------------------------------------------------------------------------------------------------------

  private def pathEquals(methodName: JvmIdentifier): NativeImplementation = new NativeImplementation {
    override def generateMethod(cg: ClassGenerator): CompilerIO[Unit] =
      cg.createMethod[CompilerIO](methodName, Seq(pathType, pathType), boolType).use { mg =>
        mg.runNative { mv =>
          mv.visitVarInsn(ALOAD, 0)
          mv.visitVarInsn(ALOAD, 1)
          mv.visitMethodInsn(INVOKEVIRTUAL, JObject, "equals", s"(L$JObject;)Z", false)
          mv.visitMethodInsn(INVOKESTATIC, JBoolean, "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }
      }
  }

  private def pathShow(methodName: JvmIdentifier): NativeImplementation = new NativeImplementation {
    override def generateMethod(cg: ClassGenerator): CompilerIO[Unit] =
      cg.createMethod[CompilerIO](methodName, Seq(pathType), stringType).use { mg =>
        mg.runNative { mv =>
          mv.visitVarInsn(ALOAD, 0)
          mv.visitMethodInsn(INVOKEVIRTUAL, JObject, "toString", s"()L$JString;", false)
        }
      }
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Result-holder wrapping
  // ------------------------------------------------------------------------------------------------------------------

  /** Wrap a success body (which leaves exactly one `Object` — the value — on the stack) in a try/catch that returns the
    * two-slot `Object[]` holder: `result[1] = value` on success, `result[0] = String.valueOf(exception)` on failure
    * (`String.valueOf` never returns null, so a null exception message still reads as a failure). The success body may
    * use local slots from `paramCount + 2` upward (`paramCount` = holder, `paramCount + 1` = caught exception).
    */
  private def wrap(paramCount: Int)(success: (MethodVisitor, Int) => Unit): MethodVisitor => Unit = { mv =>
    val result   = paramCount
    val ex       = paramCount + 1
    val free     = paramCount + 2
    val tryStart = new Label()
    val tryEnd   = new Label()
    val handler  = new Label()
    val end      = new Label()
    mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Exception")
    mv.visitInsn(ICONST_2)
    mv.visitTypeInsn(ANEWARRAY, JObject)
    mv.visitVarInsn(ASTORE, result)
    mv.visitLabel(tryStart)
    mv.visitVarInsn(ALOAD, result)
    mv.visitInsn(ICONST_1)
    success(mv, free)
    mv.visitInsn(AASTORE)
    mv.visitLabel(tryEnd)
    mv.visitJumpInsn(GOTO, end)
    mv.visitLabel(handler)
    mv.visitVarInsn(ASTORE, ex)
    mv.visitVarInsn(ALOAD, result)
    mv.visitInsn(ICONST_0)
    mv.visitVarInsn(ALOAD, ex)
    mv.visitMethodInsn(INVOKESTATIC, JString, "valueOf", s"(L$JObject;)L$JString;", false)
    mv.visitInsn(AASTORE)
    mv.visitLabel(end)
    mv.visitVarInsn(ALOAD, result)
  }
}
