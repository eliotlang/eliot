package com.vanillasource.eliot.eliotc.jvm.plugin

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule
import com.vanillasource.eliot.eliotc.jvm.jargen.{GenerateExecutableJar, JvmProgramGenerator}
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey
import com.vanillasource.eliot.eliotc.progress.{ProgressActivity, ProgressDescriber}

/** How the jvm backend's facts read to a user (`docs/progress-indication.md` §3.5): `generating` a module's classes and
  * `packaging` the jar.
  */
object JvmProgressDescriber extends ProgressDescriber {

  override def describe(key: CompilerFactKey[?]): Option[ProgressActivity] =
    Some(key).collect {
      case GeneratedModule.Key(moduleName, _) => ProgressActivity("generating", moduleName.show)
      case GenerateExecutableJar.Key(main)    => ProgressActivity("packaging", JvmProgramGenerator.jarFileName(main))
    }
}
