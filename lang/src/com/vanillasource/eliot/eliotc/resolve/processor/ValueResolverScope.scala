package com.vanillasource.eliot.eliotc.resolve.processor

import cats.data.StateT
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.QualifiedName
import com.vanillasource.eliot.eliotc.module.fact.Qualifier.{Ability, Default, Implementation, Slot}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, Role, ValueFQN}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN

case class ValueResolverScope(
    currentModule: ModuleName,
    dictionary: Map[QualifiedName, ValueFQN],
    privateNames: Map[QualifiedName, ValueFQN],
    parameters: Set[String],
    platform: Platform = Platform.Runtime,
    slotsFirst: Boolean = false
)

object ValueResolverScope {
  type ScopedIO[T] = StateT[CompilerIO, ValueResolverScope, T]

  extension [T](io: CompilerIO[T]) {
    def liftToScoped: ScopedIO[T] = StateT.liftF[CompilerIO, ValueResolverScope, T](io)
  }

  def addParameter(name: String): ScopedIO[Unit] =
    StateT.modify[CompilerIO, ValueResolverScope](s => s.copy(parameters = s.parameters + name))

  def isParameter(name: String): ScopedIO[Boolean] =
    StateT.get[CompilerIO, ValueResolverScope].map(_.parameters.contains(name))

  /** Whether the code being resolved is about metas ([[ValueResolverScope.slotsFirst]]). */
  def inMetaCode: ScopedIO[Boolean] = StateT.get[CompilerIO, ValueResolverScope].map(_.slotsFirst)

  /** The value a bare reference `name` names. In code about metas ([[ValueResolverScope.slotsFirst]]) a name in the
    * default namespace is a slot accessor first (`docs/meta.md` §17.1, decision K): `size(list)` in `List`'s brace is
    * the slot, not `List::size`. Anywhere else, and when no accessor has the name, it is [[getValue]]'s.
    */
  def getReferencedValue(name: QualifiedName): ScopedIO[Option[ValueFQN]] =
    StateT
      .get[CompilerIO, ValueResolverScope]
      .flatMap(scope =>
        Option
          .when(scope.slotsFirst && name.qualifier == Default)(QualifiedName(name.name, Slot))
          .flatMap(scope.dictionary.get) match {
          case Some(accessor) => StateT.pure[CompilerIO, ValueResolverScope, Option[ValueFQN]](Some(accessor))
          case None           => getValue(name)
        }
      )

  /** The value `name` names: what the dictionary maps it to, or, for a name holding a `$`, the current module's value of
    * that name. No user can write such a name (`$` is no identifier character), so it is one the compiler generated
    * beside the code naming it, in the same module, and no dictionary carries it (`ModuleValueProcessor`): it would grow
    * every value's fact by names only generated code uses.
    */
  def getValue(name: QualifiedName): ScopedIO[Option[ValueFQN]] =
    StateT
      .get[CompilerIO, ValueResolverScope]
      .map(scope =>
        scope.dictionary.get(name).orElse(Option.when(name.name.contains('$'))(ValueFQN(scope.currentModule, name)))
      )

  def getPrivateName(name: QualifiedName): ScopedIO[Option[ValueFQN]] =
    StateT.get[CompilerIO, ValueResolverScope].map(_.privateNames.get(name))

  def searchAbilities(searchingValueName: String): ScopedIO[Seq[ValueFQN]] =
    StateT
      .get[CompilerIO, ValueResolverScope]
      .map(
        _.dictionary.values
          .collect {
            case vfqn @ ValueFQN(_, QualifiedName(valueName, Ability(_), Role.Runtime))
                if valueName === searchingValueName =>
              vfqn
          }
          .toSeq
      )

  /** Resolve an ability name exactly as any other name resolves: a keyed lookup of the ability's own **marker** — the
    * synthetic `Foo^Foo` value [[com.vanillasource.eliot.eliotc.ast.fact.AbilityBlock]] emits — in the ordinary
    * dictionary. An ability is a value, and this is the one lookup that says so (`docs/effects-syntax-userspace.md` §4
    * stage 2).
    *
    * It replaces a scan of `dictionary.values` matching *any* member carrying the `Ability(name)` qualifier. That scan
    * resolved an ability by a mechanism no other name used, and got two things wrong: an ability resolved whenever any
    * *method* of it was in scope even if the weak prelude tier had dropped the marker itself, and with two same-named
    * abilities in scope the `collectFirst` winner was `Map` iteration order rather than the dictionary's answer.
    * Abilities are always public (`ability` takes no visibility modifier), so there is no `privateNames` fallback to
    * make here.
    */
  def getAbility(name: String): ScopedIO[Option[AbilityFQN]] =
    StateT
      .get[CompilerIO, ValueResolverScope]
      .map(_.dictionary.get(QualifiedName(name, Ability(name))).map(vfqn => AbilityFQN(vfqn.moduleName, name)))

  /** Resolve a **named implementation** — the `h` of a `with h` — exactly as [[getAbility]] resolves an ability name: a
    * keyed lookup of the implementation's own **name marker**
    * ([[com.vanillasource.eliot.eliotc.module.fact.Qualifier.Implementation]]) in the ordinary dictionary, so import
    * scope and shadowing decide it rather than `Map` iteration order.
    *
    * The answer is the *name marker's* [[ValueFQN]], which names the module the implementation lives in; the
    * implementation's real marker — the one a phantom binder carries — is that module's
    * [[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities.markerOfImplementationName]]. The two steps exist
    * because the real marker's qualified name also needs the ability name and the pattern key, which the surface `with
    * h` does not supply. A named implementation is always public, so there is no `privateNames` fallback.
    */
  def getImplementation(name: String): ScopedIO[Option[ValueFQN]] =
    StateT
      .get[CompilerIO, ValueResolverScope]
      .map(_.dictionary.get(QualifiedName(name, Implementation(name))))

  def getCurrentModule: ScopedIO[ModuleName] =
    StateT.get[CompilerIO, ValueResolverScope].map(_.currentModule)

  def getPlatform: ScopedIO[Platform] =
    StateT.get[CompilerIO, ValueResolverScope].map(_.platform)

  def withLocalScope[T](computation: ScopedIO[T]): ScopedIO[T] =
    for {
      originalScope <- StateT.get[CompilerIO, ValueResolverScope]
      result        <- computation
      _             <- StateT.set[CompilerIO, ValueResolverScope](originalScope)
    } yield result
}
