package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** An error produced by the [[Unifier]].
  *
  * @param context
  *   The sourced user-facing message (e.g. `"Type mismatch."`). The source position drives the error highlight.
  * @param expected
  *   The expected semantic value (when the error carries one). Captured in possibly-unforced form so that report-time
  *   forcing through the final flex store can reveal solutions that arrived after the error was raised.
  * @param actual
  *   The actual (inferred) semantic value.
  */
case class UnifyError(
    context: Sourced[String],
    expected: Option[SemValue],
    actual: Option[SemValue]
)
