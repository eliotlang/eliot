package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.HandedBinders
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** **What a named implementation captures** where it is bound (`docs/effects.md` D36 point 2), on the new effect path.
  *
  * A clause's entry its member spells too, under the implementation's pattern, is the **call's**: a handle parameter of
  * the instance method, which every call of the operation hands over. Any other entry is **captured**: the handle in
  * scope where a `with` installs the implementation, kept in a field of its handle `data`. A double is the usual case:
  *
  * {{{
  * implement recordingConsole: Console { def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";") }
  *   ⟶ data RecordingConsole$Handle[Capture$0](recordingConsole$capture$0: Capture$0)
  *     printLine(self$handle: RecordingConsole$Handle[Capture$0], s: String): Unit =
  *        (Impl$handle -> tell(?Writer[String]{Impl$handle …}, s ++ ";"))(recordingConsole$capture$0(self$handle))
  *   greeting("Bob") with recordingConsole
  *     ⟶ ($with1 -> greeting(?Console{$with1 …}, "Bob"))(RecordingConsole$Handle(?Writer[String]{…in scope here…}))
  * }}}
  *
  * Which entries a clause captures needs its member's declaration, which is in another file, so the core desugar gives
  * the handle a field for every entry any clause writes (in the order of the `$captures` declaration) and this decides,
  * reading the same declarations both where a clause is written ([[clause]]) and where a `with` installs it
  * ([[captured]]). A field nothing captures is filled with `unit`.
  */
object Captures {

  /** An effect entry as declared: its ability and its type arguments. */
  type Entry = (AbilityFQN, Seq[OperatorResolvedExpression])

  private def spelled(entry: Entry): (AbilityFQN, Seq[String]) = (entry._1, entry._2.map(_.render))

  /** The entries the member a clause `clause` implements declares beyond its own effect, spelled in the
    * implementation's names: the member's ability-level binders are the ability's parameters, which the
    * implementation's `pattern` gives — its handle type first, which no entry names.
    */
  def memberEntries(
      ability: AbilityFQN,
      clause: String,
      pattern: Seq[OperatorResolvedExpression],
      universe: RowChecker.Universe
  ): Option[Seq[Entry]] =
    for {
      member <- universe.lookup(ValueFQN(ability.moduleName, QualifiedName(clause, Qualifier.Ability(ability.abilityName))))
      marker <- universe.lookup(
                  ValueFQN(ability.moduleName, QualifiedName(ability.abilityName, Qualifier.Ability(ability.abilityName)))
                )
    } yield {
      val arity        = SignatureView.of(marker.signature).binders.size
      val substitution = SignatureView.of(member.signature).binders.take(arity).map(_.name.value).zip(pattern).toMap
      val entries      = BindingWriter.entryArguments(member)
      BindingWriter.handleParameters(member).drop(1).map { (index, entryAbility) =>
        entryAbility -> entries.getOrElse(index, Seq.empty).map(substituted(_, substitution))
      }
    }

  /** The entries a named implementation's handle has a field for, in field order, each with whether some clause
    * captures it — what a `with` installing the implementation `marker` fills: a captured one with the handle in scope
    * there, any other with `unit`. [[None]] while a declaration it reads is missing.
    */
  def captured(marker: OperatorResolvedValue, universe: RowChecker.Universe): Option[Seq[(Entry, Boolean)]] =
    marker.name.value.qualifier match {
      case ResolveQualifier.AbilityImplementation(ability, _, Some(_)) =>
        for {
          fields  <- universe.lookup(ValueFQN(marker.vfqn.moduleName, QualifiedName(Handles.captures, marker.vfqn.name.qualifier)))
          names   <- universe.clausesOf(marker.vfqn)
          clauses <- names.filter(name => name.name.name.startsWith(Handles.clause(""))).traverse(universe.lookup)
          pattern  = OperatorResolvedExpression.ValueReference(marker.name.as(WellKnownTypes.typeFQN)) +:
                       SignatureView.of(marker.signature).parameters.map(_.value)
          written <- clauses.traverse { clause =>
                       memberEntries(ability, clause.vfqn.name.name.stripPrefix(Handles.clause("")), pattern, universe)
                         .map(declared => entriesOf(clause).filterNot(e => declared.exists(spelled(_) == spelled(e))))
                     }
        } yield {
          val capturedByAny = written.flatten.map(spelled).toSet
          entriesOf(fields).map(entry => entry -> capturedByAny.contains(spelled(entry)))
        }
      case _                                                       => Some(Seq.empty)
    }

  /** A clause of an implementation's instance shaped as its member is (`docs/effects.md` D36 point 2): after its own
    * handle, the handles of the entries its member declares, in the member's order — the clause's own where it spells
    * the entry, an unused one where it does not — and its own parameters; each entry it captures bound from its handle's
    * field around its body. [[None]] for any other value, or while a declaration it reads is missing.
    */
  def clause(orv: OperatorResolvedValue, universe: RowChecker.Universe): Option[OperatorResolvedValue] =
    (orv.name.value.qualifier, orv.runtime) match {
      case (ResolveQualifier.AbilityImplementation(ability, _, None), Some(body))
          if RowChecker.peelBinders(body.value)._1.headOption.contains(Handles.self) =>
        for {
          instance <- universe.lookup(ValueFQN(orv.vfqn.moduleName, QualifiedName(ability.abilityName, orv.vfqn.name.qualifier)))
          pattern   = SignatureView.of(instance.signature).parameters.map(_.value)
          declared <- memberEntries(ability, orv.vfqn.name.name, pattern, universe)
          member   <- universe.lookup(ValueFQN(ability.moduleName, QualifiedName(orv.vfqn.name.name, Qualifier.Ability(ability.abilityName))))
          marker   <- universe.lookup(ValueFQN(ability.moduleName, QualifiedName(ability.abilityName, Qualifier.Ability(ability.abilityName))))
          fields   <- universe.lookup(ValueFQN(orv.vfqn.moduleName, QualifiedName(Handles.captures, orv.vfqn.name.qualifier)))
        } yield shaped(
          orv,
          body,
          declared,
          entriesOf(fields),
          member,
          SignatureView.of(marker.signature).binders.size,
          SignatureView.of(instance.signature).binders.size
        )
      case _ => None
    }

  private def shaped(
      orv: OperatorResolvedValue,
      body: Sourced[OperatorResolvedExpression],
      declared: Seq[Entry],
      fields: Seq[Entry],
      member: OperatorResolvedValue,
      abilityArity: Int,
      instanceArity: Int
  ): OperatorResolvedValue = {
    val view            = SignatureView.of(orv.signature)
    val marks           = BindingWriter.phantoms(orv).toMap
    val effects         = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    val entries         = BindingWriter.entryArguments(orv)
    val binderIndex     = view.binders.map(_.name.value).zipWithIndex.toMap
    // The clause's handles: its parameters after its own handle that are typed by one of its effect bindings.
    val handles         = view.parameters.zipWithIndex.drop(1).flatMap {
      case (Sourced(_, _, ParameterReference(name)), position) =>
        binderIndex.get(name.value).filter(index => marks.get(index).exists(effects.contains)).map { index =>
          position -> (marks(index), entries.getOrElse(index, Seq.empty))
        }
      case _                                                    => None
    }
    val (names, inner)  = RowChecker.peelBinders(body.value)
    val count           = view.parameters.size
    val parameterNames  = names.take(count)
    val innerBody       = names.drop(count).foldRight(inner)((name, acc) => FunctionLiteral(body.as(name), None, body.as(acc)))
    // The member's entries in its order, each taken from the clause where it spells it, else an unused handle.
    val (ordered, taken) = declared.zipWithIndex.foldLeft((Seq.empty[Either[Int, Int]], Set.empty[Int])) {
      case ((acc, used), (entry, k)) =>
        handles.find((position, own) => !used(position) && spelled(own) == spelled(entry)) match {
          case Some((position, _)) => (acc :+ Left(position), used + position)
          case None                => (acc :+ Right(k), used)
        }
    }
    val capturedHandles = handles.filterNot((position, _) => taken(position))
    val own             = view.parameters.indices.drop(1).filterNot(handles.map(_._1).contains)
    val unusedBinder    = (k: Int) => s"Unused$$$k"
    val unusedName      = (k: Int) => Handles.parameter(unusedBinder(k))
    val newParameters   =
      view.parameters.head +: (ordered.map {
        case Left(position) => view.parameters(position)
        case Right(k)       => orv.signature.as(ParameterReference(orv.signature.as(unusedBinder(k))))
      } ++ own.map(view.parameters))
    val newNames        =
      parameterNames.head +: (ordered.map {
        case Left(position) => parameterNames(position)
        case Right(k)       => unusedName(k)
      } ++ own.map(parameterNames))
    val implementation  = spine(view.parameters.head.value)._1 match {
      case ValueReference(name, _) => Handles.implementationOf(name.value.name.name)
      case other                   => throw IllegalStateException(s"A clause's own handle is not a handle type: ${other.render}")
    }
    val bound           = capturedHandles.foldRight(innerBody) { case ((position, entry), acc) =>
      val field = fields.indexWhere(spelled(_) == spelled(entry))
      FunctionApplication(
        body.as(FunctionLiteral(body.as(parameterNames(position)), None, body.as(acc))),
        body.as(
          FunctionApplication(
            body.as(
              ValueReference(
                body.as(
                  ValueFQN(orv.vfqn.moduleName, QualifiedName(Handles.captureField(implementation, field), Qualifier.Default))
                )
              )
            ),
            body.as(ParameterReference(body.as(Handles.self)))
          )
        )
      )
    }
    val newBody         = newNames.foldRight(bound)((name, acc) => FunctionLiteral(body.as(name), None, body.as(acc)))
    // The binders in the order dispatch hands a clause its type arguments (`PostDrainQuoter`'s `forwarded`): its
    // instance's, then its member's method-level binders mirrored — at a binder for an entry the member declares, the
    // clause's handle for it (or an unused one), and at any other, the clause's own binder of the same rank — then a
    // block's handed binders, then the captured entries' binders, which the fields' types solve.
    val binderAt        = view.parameters.zipWithIndex.collect {
      case (Sourced(_, _, ParameterReference(name)), position) if binderIndex.contains(name.value) =>
        position -> binderIndex(name.value)
    }.toMap
    val handleBinders   = handles.map((position, _) => binderAt(position)).toSet
    val handed          = HandedBinders.of(orv.signature).toSet
    val unused          = (k: Int) =>
      SignatureView.Binder(orv.signature.as(unusedBinder(k)), Some(orv.signature.as(ValueReference(orv.signature.as(WellKnownTypes.typeFQN)))))
    val memberHandles   = BindingWriter.handleParameters(member).drop(1).map(_._1).zipWithIndex.toMap
    val memberHanded    = HandedBinders.of(member.signature).toSet
    val clauseOwn       = view.binders.indices.drop(instanceArity).filterNot(i => handleBinders(i) || handed(i))
    val (mirrored, rest) =
      SignatureView.of(member.signature).binders.indices.drop(abilityArity).filterNot(memberHanded).foldLeft(
        (Seq.empty[SignatureView.Binder], clauseOwn)
      ) { case ((acc, own), index) =>
        memberHandles.get(index) match {
          case Some(k) =>
            val binder = ordered(k) match {
              case Left(position) => view.binders(binderAt(position))
              case Right(k)       => unused(k)
            }
            (acc :+ binder, own)
          case None    => own.headOption.fold((acc, own))(next => (acc :+ view.binders(next), own.tail))
        }
      }
    val newBinders      =
      view.binders.take(instanceArity) ++ mirrored ++ rest.map(view.binders) ++
        view.binders.indices.filter(handed).map(view.binders) ++
        capturedHandles.map((position, _) => view.binders(binderAt(position)))
    // A binder marked for an effect stands for the entry of the same rank among that effect's entries
    // (`BindingWriter.entryArguments`), so the row follows the binders' new order.
    val entryOf         = BindingWriter.phantoms(orv).groupBy(_._2).toSeq.flatMap { (ability, binders) =>
      val written = orv.effectRow.returnEffects.filter(_.abilityFQN == ability)
      if (written.size != binders.size) Seq.empty else binders.map(_._1).zip(written)
    }.toMap
    val reordered       = newBinders.flatMap(binder => entryOf.get(binderIndex.getOrElse(binder.name.value, -1)))
    val newRow          = reordered ++ orv.effectRow.returnEffects.filterNot(entry => reordered.exists(_ eq entry))
    orv.copy(
      signature = orv.signature.as(view.copy(binders = newBinders, parameters = newParameters).toExpression),
      runtime = Some(body.as(newBody)),
      effectRow = orv.effectRow.copy(returnEffects = newRow)
    )
  }

  private def entriesOf(orv: OperatorResolvedValue): Seq[Entry] =
    orv.effectRow.returnEffects.map(entry => entry.abilityFQN -> entry.typeArgs)

  private def substituted(
      expression: OperatorResolvedExpression,
      substitution: Map[String, OperatorResolvedExpression]
  ): OperatorResolvedExpression =
    substitution.foldLeft(expression) { case (acc, (name, value)) => substitute(acc, name, value) }
}
