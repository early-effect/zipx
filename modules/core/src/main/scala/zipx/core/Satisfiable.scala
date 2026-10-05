package zipx.core

import neotype.unwrap

/** Whether a job's `if:` can ever be true, over the decidable subset of [[JobCondition]]. The gate, capability
  * condition and target condition are written in different places, so nobody sees their conjunction.
  *
  * Deliberately not a SAT solver: it reasons only about single-valued contexts (`github.ref`, `github.event_name`,
  * `github.repository`) in a conjunction. A wrong rejection is worse than a missed one, so anything else
  * ([[JobCondition.Any]], [[JobCondition.Raw]], `vars.*`, PR labels) counts as satisfiable.
  */
private[core] object Satisfiable:

  /** `source` lets the error name both places to look. */
  final case class Clause(source: String, condition: JobCondition)

  /** The earliest pair of conjuncts that cannot both hold, quoting each side's rendered GHA expression. Later pairs on
    * the same context would restate the same bug.
    */
  def findContradiction(clauses: List[Clause]): Option[String] =
    val atoms = clauses.flatMap(c => conjunctsOf(c.condition).flatMap(atomOf(c.source, _)))
    firstConflict(atoms).map { case (a, b) =>
      s"${a.source} requires `${a.rendered}` and ${b.source} requires `${b.rendered}`, which cannot both hold: " +
        s"${explain(a, b)}. The two are ANDed, so this job's `if:` is never true and it would silently never run."
    }

  /** `positive` false means the clause excludes the claim, a much weaker fact: exactly one value satisfies an equality,
    * but every other value satisfies its negation.
    */
  private final case class Atom(source: String, rendered: String, claim: Claim, positive: Boolean)

  private enum Claim:
    /** `github.<context> == '<value>'`, for a context that holds exactly one value per run. */
    case Eq(context: String, value: String)

    /** `startsWith(github.ref, '<prefix>')`. */
    case RefPrefix(prefix: String)

  /** `!(a || b)` is a conjunction by De Morgan; `!(a && b)` is a disjunction, so it stops here and contributes nothing.
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

  /** Quadratic, over at most a handful of clauses. */
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
