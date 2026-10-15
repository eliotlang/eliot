package com.vanillasource.eliot.eliotc.ability.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.{AbilityImplementationCheck, ModuleAbilityOverlapCheck}
import com.vanillasource.eliot.eliotc.ability.util.{AbilityMatcher, ImplementationConformance}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ModuleName}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityFQN, Qualifier as ResolveQualifier}

/** Checks the **anonymous** implementations of an ability that match the ground type arguments the two-site search
  * resolves it at ([[ImplementationConformance]]). A named implementation is never searched, so it is checked where a
  * binding dispatches to it instead ([[NamedImplementationCheckProcessor]]).
  */
class AbilityImplementationCheckProcessor
    extends SingleKeyTypeProcessor[AbilityImplementationCheck.Key] {

  override protected def generateFact(key: AbilityImplementationCheck.Key): CompilerIO[Unit] = {
    val abilityFQN                        = key.abilityFQN
    val typeArguments                     = key.typeArguments
    val candidateModules: Set[ModuleName] =
      Set(abilityFQN.moduleName) ++ typeArguments.flatMap(collectModuleNames)

    for {
      // Trigger the per-module overlap check eagerly for every module that could contain an impl of
      // this ability. The fact is cached per (module, abilityName), so the actual overlap scan runs
      // at most once per pair across the whole compilation regardless of how many call sites hit it.
      // `getFact` (not `getFactOrAbort`) — if no overlap processor is registered in the current
      // pipeline (some unit tests exclude it), skip the check silently rather than abort.
      _              <- candidateModules.toSeq.traverse_(m =>
                          getFactIfProduced(ModuleAbilityOverlapCheck.Key(m, abilityFQN.abilityName, key.platform)).void
                        )
      abilityMethods <- ImplementationConformance.abilityMethods(abilityFQN, key.platform)
      implMethods    <- candidateModules.toSeq.flatTraverse(collectImplMethods(_, abilityFQN, typeArguments, key.platform))
      _              <- implMethods match {
                          case Nil =>
                            // No implementation matches structurally — nothing to signature-check, and not an error
                            // *here*: `AbilityImplementationProcessor` registers the `NoImplementation` outcome, and
                            // it is the demander that decides whether that is a compile error (a demanded capability,
                            // reported at its use site) or a normal decline (a checker-inserted probe like `Coerce`).
                            ().pure[CompilerIO]
                          case _   =>
                            ImplementationConformance.check(abilityFQN, abilityMethods, implMethods, key.platform)
                        }
      _              <- registerFactIfClear(AbilityImplementationCheck(abilityFQN, typeArguments, key.platform))
    } yield ()
  }

  private def collectImplMethods(
      moduleName: ModuleName,
      abilityFQN: AbilityFQN,
      typeArguments: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Seq[ImplementationConformance.Method]] =
    getFactOrAbort(ModuleAbilities.Key(moduleName, platform)).flatMap { impls =>
      impls
        .anonymousImplementationMethodsOf(abilityFQN.abilityName)
        .traverse(ImplementationConformance.method(_, platform))
        .flatMap(_.traverseFilter { method =>
          method.name.value.qualifier match {
            case ResolveQualifier.AbilityImplementation(resolvedFQN, _, _) if resolvedFQN == abilityFQN =>
              for {
                markerSig <- ImplementationConformance.markerSignature(method.vfqn, abilityFQN.abilityName, platform)
                matched   <- AbilityMatcher.matchImpl(markerSig, typeArguments)
              } yield matched.map(_ => method)
            case _                                                                                   =>
              None.pure[CompilerIO]
          }
        })
    }

  private def collectModuleNames(v: GroundValue): Seq[ModuleName] =
    v match {
      case GroundValue.Structure(typeName, args, _) =>
        args.flatMap(collectModuleNames) :+ typeName.moduleName
      case _                                        => Seq.empty
    }
}
