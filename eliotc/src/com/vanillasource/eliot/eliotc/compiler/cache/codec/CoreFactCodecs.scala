package com.vanillasource.eliot.eliotc.compiler.cache.codec

/** [[FactCodec]] instances for the core compiler's own types — source positions, the cache's own boundary facts. The
  * layered counterparts are `LangFactCodecs` and `JvmFactCodecs`, each importing the ones below it.
  */
object CoreFactCodecs {
  given codec_com_vanillasource_eliot_eliotc_pos_Position: FactCodec[com.vanillasource.eliot.eliotc.pos.Position] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_pos_PositionRange
      : FactCodec[com.vanillasource.eliot.eliotc.pos.PositionRange] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_compiler_cache_UpToDate
      : FactCodec[com.vanillasource.eliot.eliotc.compiler.cache.UpToDate] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_compiler_cache_UpToDate_Key
      : FactCodec[com.vanillasource.eliot.eliotc.compiler.cache.UpToDate.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_feedback_CompilerError
      : FactCodec[com.vanillasource.eliot.eliotc.feedback.CompilerError] = FactCodec.derived

  // Aliases the fact keys use to state their own persistence decision.
  val upToDateCodec: FactCodec[com.vanillasource.eliot.eliotc.compiler.cache.UpToDate] =
    codec_com_vanillasource_eliot_eliotc_compiler_cache_UpToDate

  /** This layer's contribution to the key tag table; see [[FactKeyCodecs]]. */
  val keyCodecs: FactKeyCodecs.Registry = Map(
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_compiler_cache_UpToDate_Key)
  )
}
