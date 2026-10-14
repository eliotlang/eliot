package com.vanillasource.eliot.eliotc.jvm.codec

import com.vanillasource.eliot.eliotc.compiler.cache.codec.{FactCodec, FactKeyCodecs}
import com.vanillasource.eliot.eliotc.compiler.cache.codec.CoreFactCodecs.given
import com.vanillasource.eliot.eliotc.codec.LangFactCodecs.given

/** [[FactCodec]] instances for the JVM backend's own fact types, over the `lang` and core ones. */
object JvmFactCodecs {
  given codec_com_vanillasource_eliot_eliotc_jvm_jargen_GenerateExecutableJar
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.GenerateExecutableJar] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_jvm_jargen_GenerateExecutableJar_Key
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.GenerateExecutableJar.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_jvm_jargen_EntryPointTarget
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.EntryPointTarget] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_jvm_jargen_EntryPointTarget_Key
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.EntryPointTarget.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_jvm_classgen_fact_GeneratedModule
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_jvm_classgen_fact_GeneratedModule_Key
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule.Key] = FactCodec.derived

  /** Hand-written because `ClassFile.bytecode` is a bare `Array[Byte]`, and there is deliberately no
    * `FactCodec[Array[Byte]]`: an array *encodes* fine and compares by **reference**, which is exactly the §3.1 / §4
    * defect (`read(write(v))` never equals `recompute(v)`). Writing the codec here rather than as a general instance
    * keeps that decision at the one place it applies. This makes `GeneratedModule` **encodable**; it does not make it
    * **equality-stable** — that needs `ClassFile` to carry a value-comparable representation, and the conformance
    * harness reports it as a law failure until it does.
    */
  given codec_com_vanillasource_eliot_eliotc_jvm_classgen_fact_ClassFile
      : FactCodec[com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile] =
    new FactCodec[com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile] {
      override def write(
          out: FactCodec.Output,
          value: com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile
      ): Unit = {
        FactCodec[String].write(out, value.fileName)
        FactCodec[Seq[Byte]].write(out, value.bytecode.toSeq)
      }

      override def read(in: FactCodec.Input): com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile =
        com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile(
          FactCodec[String].read(in),
          FactCodec[Seq[Byte]].read(in).toArray
        )
    }

  // Aliases the fact keys use to state their own persistence decision.
  val generatedModuleCodec: FactCodec[com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule]      =
    codec_com_vanillasource_eliot_eliotc_jvm_classgen_fact_GeneratedModule
  val generateExecutableJarCodec: FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.GenerateExecutableJar] =
    codec_com_vanillasource_eliot_eliotc_jvm_jargen_GenerateExecutableJar
  val entryPointTargetCodec: FactCodec[com.vanillasource.eliot.eliotc.jvm.jargen.EntryPointTarget]           =
    codec_com_vanillasource_eliot_eliotc_jvm_jargen_EntryPointTarget

  /** This layer's contribution to the key tag table; see [[FactKeyCodecs]]. */
  val keyCodecs: FactKeyCodecs.Registry = Map(
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_jvm_jargen_GenerateExecutableJar_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_jvm_jargen_EntryPointTarget_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_jvm_classgen_fact_GeneratedModule_Key)
  )
}
