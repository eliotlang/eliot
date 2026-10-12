package com.vanillasource.eliot.eliotc.ability.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.{AbilityImplementationCheck, ModuleAbilityOverlapCheck}
import com.vanillasource.eliot.eliotc.ability.util.AbilityMatcher
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  ModuleName,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  ValueFQN
}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

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
      abilityMethods <- collectAbilityMethods(abilityFQN, key.platform)
      implMethods    <- candidateModules.toSeq.flatTraverse(collectImplMethods(_, abilityFQN, typeArguments, key.platform))
      _              <- implMethods match {
                          case Nil =>
                            // No implementation matches structurally — nothing to signature-check, and not an error
                            // *here*: `AbilityImplementationProcessor` registers the `NoImplementation` outcome, and
                            // it is the demander that decides whether that is a compile error (a demanded capability,
                            // reported at its use site) or a normal decline (a checker-inserted probe like `Coerce`).
                            ().pure[CompilerIO]
                          case _   =>
                            checkCompleteness(abilityFQN, abilityMethods, implMethods) >>
                              checkNoExtras(abilityMethods, implMethods) >>
                              checkSignatures(abilityFQN, abilityMethods, implMethods, key.platform)
                        }
      _              <- registerFactIfClear(AbilityImplementationCheck(abilityFQN, typeArguments, key.platform))
    } yield ()
  }

  private case class ResolvedMethod(
      vfqn: ValueFQN,
      name: Sourced[com.vanillasource.eliot.eliotc.resolve.fact.QualifiedName],
      methodSig: Sourced[OperatorResolvedExpression],
      hasRuntime: Boolean
  )

  private def collectAbilityMethods(abilityFQN: AbilityFQN, platform: Platform): CompilerIO[Seq[ResolvedMethod]] =
    getFactOrAbort(ModuleAbilities.Key(abilityFQN.moduleName, platform)).flatMap { abilities =>
      abilities.declaredMethodsOf(abilityFQN.abilityName).traverse(vfqn => toResolvedMethod(vfqn, platform))
    }

  private def collectImplMethods(
      moduleName: ModuleName,
      abilityFQN: AbilityFQN,
      typeArguments: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Seq[ResolvedMethod]] =
    getFactOrAbort(ModuleAbilities.Key(moduleName, platform)).flatMap { impls =>
      impls
        .anonymousImplementationMethodsOf(abilityFQN.abilityName)
        .traverse(vfqn => toResolvedMethod(vfqn, platform))
        .flatMap(_.traverseFilter { method =>
          method.name.value.qualifier match {
            case ResolveQualifier.AbilityImplementation(resolvedFQN, _, _) if resolvedFQN == abilityFQN =>
              for {
                markerSig <- loadMarkerSignature(method.vfqn, abilityFQN.abilityName, platform)
                matched   <- AbilityMatcher.matchImpl(markerSig, typeArguments)
              } yield matched.map(_ => method)
            case _                                                                                   =>
              None.pure[CompilerIO]
          }
        })
    }

  private def toResolvedMethod(vfqn: ValueFQN, platform: Platform): CompilerIO[ResolvedMethod] =
    getFactOrAbort(OperatorResolvedValue.Key(vfqn, platform)).map(resolved =>
      ResolvedMethod(
        vfqn,
        resolved.name,
        resolved.signature,
        resolved.runtime.isDefined
      )
    )

  /** A missing method is reported at the implementation's **marker** — the synthetic method named after the ability,
    * positioned at the `implement` head — so the error points at the block that is incomplete. `implMethods` arrives in
    * name-table order, which is a `Map`'s and so hash-dependent; picking its head would report at whichever method's
    * name hashes first, and did until the qualifier grew a component. The declaring ability method is the fallback
    * when no marker is among the methods.
    */
  private def checkCompleteness(
      abilityFQN: AbilityFQN,
      abilityMethods: Seq[ResolvedMethod],
      implMethods: Seq[ResolvedMethod]
  ): CompilerIO[Unit] = {
    val implMethodNames = implMethods.map(_.vfqn.name.name).toSet
    val abstractMethods = abilityMethods.filter(!_.hasRuntime)
    val marker          = implMethods.find(_.vfqn.name.name == abilityFQN.abilityName)
    abstractMethods
      .filterNot(m => implMethodNames.contains(m.vfqn.name.name))
      .traverse_ { m =>
        marker match {
          case Some(implMarker) =>
            compilerError(implMarker.name.as(s"Ability implementation is missing method '${m.vfqn.name.name}'."))
          case None             =>
            compilerError(m.name.map(qn => s"Ability implementation is missing method '${qn.name}'."))
        }
      }
  }

  private def checkNoExtras(
      abilityMethods: Seq[ResolvedMethod],
      implMethods: Seq[ResolvedMethod]
  ): CompilerIO[Unit] = {
    val abilityMethodNames = abilityMethods.map(_.vfqn.name.name).toSet
    implMethods
      .filterNot(m => abilityMethodNames.contains(m.vfqn.name.name))
      .traverse_(m =>
        compilerError(
          m.name.map(qn => s"Method not defined in ability.")
        )
      )
  }

  private def checkSignatures(
      abilityFQN: AbilityFQN,
      abilityMethods: Seq[ResolvedMethod],
      implMethods: Seq[ResolvedMethod],
      platform: Platform
  ): CompilerIO[Unit] = {
    val implByName = implMethods.map(m => m.vfqn.name.name -> m).toMap
    abilityMethods
      // Skip the synthetic marker method (local name == ability name): it is a pattern vessel, not a real ability
      // method. Its return-type slot now carries the implementation guard (ability-guards §2.3), which is not part of
      // the method contract, so comparing the impl marker's signature against the ability marker's would spuriously
      // unify the guard against the ability marker's own return. The marker's pattern is already validated at this
      // same use site by `matchImpl` in `collectImplMethods`.
      .filterNot(_.vfqn.name.name == abilityFQN.abilityName)
      .flatMap(abstractMethod => implByName.get(abstractMethod.vfqn.name.name).map(abstractMethod -> _))
      .traverse_ { case (abstractMethod, implMethod) =>
        for {
          implMarkerSig <- loadMarkerSignature(implMethod.vfqn, abilityFQN.abilityName, platform)
          parameters    <- implementationParameterCount(implMethod, platform)
          compatible    <- AbilityMatcher.signaturesCompatible(
                             abstractMethod.methodSig,
                             withoutLeadingParameters(implMethod.methodSig, parameters),
                             implMarkerSig
                           )
          _             <- if (!compatible)
                             compilerError(
                               implMethod.name.map(_ => s"Signature of implementation does not match the ability definition.")
                             )
                           else ().pure[CompilerIO]
        } yield ()
      }
  }

  /** How many parameters the implementation `method` belongs to takes ([[ImplementationParameters]]): each clause
    * receives them ahead of the ability member's own, so they are not part of the contract compared here. Zero when
    * the implementation declares none, which is when it has no parameters declaration at all.
    */
  private def implementationParameterCount(method: ResolvedMethod, platform: Platform): CompilerIO[Int] = {
    val declaration = ImplementationParameters.of(method.vfqn)
    getFactOrAbort(UnifiedModuleNames.Key(method.vfqn.moduleName, platform)).flatMap { names =>
      if (names.names.contains(declaration.name))
        getFactOrAbort(OperatorResolvedValue.Key(declaration, platform)).map(declared =>
          SignatureView.of(declared.signature).parameters.size
        )
      else 0.pure[CompilerIO]
    }
  }

  private def withoutLeadingParameters(
      signature: Sourced[OperatorResolvedExpression],
      count: Int
  ): Sourced[OperatorResolvedExpression] =
    if (count === 0) signature
    else {
      val view = SignatureView.of(signature)
      signature.as(view.copy(parameters = view.parameters.drop(count)).toExpression)
    }

  private def loadMarkerSignature(
      methodVfqn: ValueFQN,
      abilityName: String,
      platform: Platform
  ): CompilerIO[Sourced[OperatorResolvedExpression]] = {
    val markerVfqn = ValueFQN(
      methodVfqn.moduleName,
      QualifiedName(abilityName, methodVfqn.name.qualifier)
    )
    getFactOrAbort(OperatorResolvedValue.Key(markerVfqn, platform)).map(r => r.signature)
  }

  private def collectModuleNames(v: GroundValue): Seq[ModuleName] =
    v match {
      case GroundValue.Structure(typeName, args, _) =>
        args.flatMap(collectModuleNames) :+ typeName.moduleName
      case _                                        => Seq.empty
    }
}
