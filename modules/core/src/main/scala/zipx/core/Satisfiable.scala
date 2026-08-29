package zipx.core

import neotype.unwrap

/** Whether a job's `if:` can ever be true, over the subset of [[JobCondition]] where that question is decidable.
  *
  * The planner ANDs a [[Gate]] with [[Capability.condition]] and with [[Target.condition]], and each of those is
  * written in a different place: a pack supplies the gate, a build supplies the condition, and `project/Deploy.scala`
  * supplies the target. Nobody looking at one of them sees the conjunction, which is how `examples/monorepo` shipped a
  * `deploy-prod` job carrying `startsWith(github.ref, 'refs/tags/v') && github.ref == 'refs/heads/main'`: a job that
  * looked deliberate, passed every check, and could not run.
  *
  * **This is not a SAT solver, and deliberately not.** It reasons about single-valued contexts (`github.ref`,
  * `github.event_name`, `github.repository`) inside a *conjunction*, and says nothing about anything else. An unsound
  * rejection is far worse than a missed one: a missed contradiction is the status quo, a wrong rejection is a build
  * that cannot generate its own CI and no way for the author to argue. So every case it does not understand
  * ([[JobCondition.Any]], [[JobCondition.Raw]], `vars.*`, PR labels) is treated as satisfiable and passed over in
  * silence.
  */
private[core] object Satisfiable:

  /** One clause of the conjunction, with where it came from, so an error can name the two places to look. */
  final case class Clause(source: String, condition: JobCondition)

  /** Finds a pair of conjuncts that cannot both hold. `None` when nothing decidable is wrong.
    *
    * The message quotes the GHA expression each side renders to, since that is the text the author will see in the
    * generated file. Earliest pair in clause order, not every pair: generate fail-fasts on the first, and extra pairs
    * on the same context are the same bug restated.
    */
  def findContradiction(clauses: List[Clause]): Option[String] =
    val atoms = clauses.flatMap(c => conjunctsOf(c.condition).flatMap(atomOf(c.source, _)))
    firstConflict(atoms).map { case (a, b) =>
      s"${a.source} requires `${a.rendered}` and ${b.source} requires `${b.rendered}`, which cannot both hold: " +
        s"${explain(a, b)}. The two are ANDed, so this job's `if:` is never true and it would silently never run."
    }

  /** A clause reduced to a claim about one single-valued context, or nothing.
    *
    * `positive` false means the clause *excludes* the claim, which is a much weaker fact: exactly one value satisfies
    * an equality, but every other value satisfies its negation.
    */
  private final case class Atom(source: String, rendered: String, claim: Claim, positive: Boolean)

  private enum Claim:
    /** `github.<context> == '<value>'`, for a context that holds exactly one value per run. */
    case Eq(context: String, value: String)

    /** `startsWith(github.ref, '<prefix>')`. */
    case RefPrefix(prefix: String)

  /** Flattens the conjunctive structure. [[JobCondition.All]] is a conjunction by definition, and `!(a || b)` is one by
    * De Morgan; `!(a && b)` is a *disjunction*, so it stops here and contributes nothing rather than being read as one.
    */
  private def conjunctsOf(condition: JobCondition): List[JobCondition] = condition match
    case JobCondition.All(first, rest)                   => (first :: rest).flatMap(conjunctsOf)
    case JobCondition.Not(JobCondition.Not(inner))       => conjunctsOf(inner)
    case JobCondition.Not(JobCondition.Any(first, rest)) =>
      (first :: rest).map(JobCondition.Not(_)).flatMap(conjunctsOf)
    case other => List(other)

  private def atomOf(source: String, condition: JobCondition): Option[Atom] =
    def atom(claim: Claim, positive: Boolean, rendered: JobCondition): Option[Atom] =
      Some(Atom(source, rendered.render, claim, positive))

    condition match
      case c @ JobCondition.RefIs(ref)            => atom(Claim.Eq("ref", ref.unwrap), positive = true, c)
      case c @ JobCondition.RefStartsWith(prefix) => atom(Claim.RefPrefix(prefix.unwrap), positive = true, c)
      case c @ JobCondition.EventIs(name)         => atom(Claim.Eq("event_name", name.unwrap), positive = true, c)
      case c @ JobCondition.RepositoryIs(repo)    => atom(Claim.Eq("repository", repo.unwrap), positive = true, c)

      // The negated forms, reported as the whole `!(…)` so the message quotes what the file will contain.
      case c @ JobCondition.Not(inner) =>
        atomOf(source, inner).flatMap {
          case Atom(_, _, claim, true) => atom(claim, positive = false, c)
          // `!!x` is already flattened away, so this is a nested shape carrying no usable claim.
          case _ => None
        }

      case _ => None
    end match
  end atomOf

  /** The first pair that cannot hold together, in clause order, so the message names the earliest-written pair rather
    * than an arbitrary one. Quadratic, over at most a handful of clauses.
    */
  private def firstConflict(atoms: List[Atom]): Option[(Atom, Atom)] =
    atoms.tails.toList
      .collect { case head :: tail => head -> tail }
      .flatMap { case (a, rest) => rest.filter(b => conflicts(a, b)).map(a -> _) }
      .headOption

  /** Both-negative pairs are never a conflict here: two exclusions always leave a third value, and no context zipx
    * reasons about is modelled as a closed set.
    */
  private def conflicts(a: Atom, b: Atom): Boolean = (a.positive, b.positive) match
    case (true, true)   => bothRequired(a.claim, b.claim)
    case (true, false)  => excludesEverything(a.claim, b.claim)
    case (false, true)  => excludesEverything(b.claim, a.claim)
    case (false, false) => false

  private def bothRequired(a: Claim, b: Claim): Boolean = (a, b) match
    // One value per run, so two different required values is a contradiction; a different context is not comparable.
    case (Claim.Eq(ctxA, valueA), Claim.Eq(ctxB, valueB)) => ctxA == ctxB && valueA != valueB

    case (Claim.Eq("ref", value), Claim.RefPrefix(prefix)) => !value.startsWith(prefix)
    case (Claim.RefPrefix(prefix), Claim.Eq("ref", value)) => !value.startsWith(prefix)

    // Not merely different: `refs/tags/` and `refs/tags/v` are compatible (one contains the other's refs), while
    // `refs/tags/v` and `refs/heads/` share no ref at all. Prefix-comparability is exactly that distinction.
    case (Claim.RefPrefix(p), Claim.RefPrefix(q)) => !(p.startsWith(q) || q.startsWith(p))

    case _ => false

  /** Whether `excluded` rules out every value `required` allows. */
  private def excludesEverything(required: Claim, excluded: Claim): Boolean = (required, excluded) match
    case (Claim.Eq(ctxA, valueA), Claim.Eq(ctxB, valueB))  => ctxA == ctxB && valueA == valueB
    case (Claim.Eq("ref", value), Claim.RefPrefix(prefix)) => value.startsWith(prefix)

    // Every ref starting with `p` also starts with `q` when `p` extends `q`, so excluding `q` excludes all of them.
    case (Claim.RefPrefix(p), Claim.RefPrefix(q)) => p.startsWith(q)

    // The reverse direction is not decidable: excluding one exact ref leaves every other ref under the prefix.
    case _ => false

  private def explain(a: Atom, b: Atom): String =
    if a.positive && b.positive then
      (a.claim, b.claim) match
        case (Claim.Eq(ctx, _), Claim.Eq(_, _)) => s"`github.$ctx` holds one value per run"
        case (Claim.Eq("ref", _), Claim.RefPrefix(_)) | (Claim.RefPrefix(_), Claim.Eq("ref", _)) =>
          "that ref does not start with that prefix"
        case (Claim.RefPrefix(p), Claim.RefPrefix(q)) => s"no ref starts with both '$p' and '$q'"
        case _                                        => "the two cannot hold together"
    else "one negates the other"

end Satisfiable
