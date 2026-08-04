package zipx.core

import neotype.unwrap
import zipx.workflow.{EnvName, EventName, Expr, ExprLiteral, RawExpr}

/** Typed GitHub Actions job `if:` predicate. Prefer smart constructors over assembling cases by hand.
  *
  * [[Gate]] is the timeline axis (`Always` / `OnReleaseTag`). [[JobCondition]] is an optional extra filter ANDed into
  * the job `if` (fork repo, PR label, branch, repo var, …). Both [[Capability.condition]] and [[Target.condition]] use
  * this AST; the planner renders and ANDs them with gate / affected clauses.
  *
  * Compose with [[&&]] / [[||]] (or [[JobCondition.and]] / [[JobCondition.or]]). Infix `&&` / `||` follow Boolean
  * precedence (`&&` tighter than `||`, both left-associative). [[JobCondition.Raw]] is the escape hatch for expressions
  * the variants cannot express.
  */
enum JobCondition:
  case RepositoryIs(repo: String)
  case VarNonEmpty(name: String)
  case RefIs(ref: String)
  case RefStartsWith(prefix: String)
  case EventIs(name: String)
  case HasPrLabel(label: String)
  case All(clauses: List[JobCondition])
  case Any(clauses: List[JobCondition])
  case Not(inner: JobCondition)
  case Raw(expression: String)

  /** Conjunction with `other` (renders as `(this) && (other)`).
    *
    * Infix precedence matches Boolean ops: `&&` binds tighter than `||`, both left-associative (`a || b && c` ≡
    * `a || (b && c)`; `a && b && c` ≡ `(a && b) && c`).
    */
  infix def &&(other: JobCondition): JobCondition = JobCondition.and(this, other)

  /** Disjunction with `other` (renders as `(this) || (other)`).
    *
    * Lower precedence than [[&&]] (same as Boolean `||` vs `&&`). Parenthesize when you mean `(a || b) && c`.
    */
  infix def ||(other: JobCondition): JobCondition = JobCondition.or(this, other)

  /** Negation (renders as `!(this)`). Prefix `!` binds tighter than [[&&]] / [[||]]. */
  def unary_! : JobCondition = JobCondition.not(this)

  /** Render to the string that lands in a job's `if:` field. */
  def render: String = this match
    case JobCondition.RepositoryIs(repo) =>
      s"github.repository == '${JobCondition.requireLiteral("repository", repo)}'"
    case JobCondition.VarNonEmpty(name) =>
      s"vars.${JobCondition.requireIdent("var", name)} != ''"
    case JobCondition.RefIs(ref) =>
      s"github.ref == '${JobCondition.requireLiteral("ref", ref)}'"
    case JobCondition.RefStartsWith(prefix) =>
      s"startsWith(github.ref, '${JobCondition.requireLiteral("ref prefix", prefix)}')"
    case JobCondition.EventIs(name) =>
      s"github.event_name == '${JobCondition.requireEvent(name)}'"
    case JobCondition.HasPrLabel(label) =>
      s"contains(github.event.pull_request.labels.*.name, '${JobCondition.requireLiteral("label", label)}')"
    case JobCondition.All(clauses) =>
      JobCondition.requireNonEmpty("All", clauses).map(c => s"(${c.render})").mkString(" && ")
    case JobCondition.Any(clauses) =>
      JobCondition.requireNonEmpty("Any", clauses).map(c => s"(${c.render})").mkString(" || ")
    case JobCondition.Not(inner) =>
      s"!(${inner.render})"
    case JobCondition.Raw(expression) =>
      JobCondition.requireRaw(expression)

  /** This condition as a [[zipx.workflow.Expr]], so a validated `if:` can be dropped into a step field.
    *
    * [[zipx.workflow.Expr.Raw]] because [[render]] already produces a jointly-validated expression: every literal in it
    * went through [[ExprLiteral]] and every name through [[EnvName]] or [[EventName]]. Re-deriving the structure as
    * typed `Expr` cases would duplicate the operator jointing that [[render]] owns. Together with `Expr.asWord`, this
    * is the whole cross-layer coupling.
    */
  def expr: Expr = Expr.Raw(RawExpr.makeOrThrow(render))
end JobCondition

object JobCondition:

  /** `github.repository == 'owner/repo'`. */
  def repositoryIs(repo: String): JobCondition = RepositoryIs(requireLiteral("repository", repo))

  /** `vars.NAME != ''`. */
  def varNonEmpty(name: String): JobCondition = VarNonEmpty(requireIdent("var", name))

  /** `github.ref == 'refs/…'`. */
  def refIs(ref: String): JobCondition = RefIs(requireLiteral("ref", ref))

  /** `startsWith(github.ref, 'prefix')`. */
  def refStartsWith(prefix: String): JobCondition = RefStartsWith(requireLiteral("ref prefix", prefix))

  /** `github.event_name == 'name'` (e.g. `pull_request`, `workflow_dispatch`). */
  def eventIs(name: String): JobCondition = EventIs(requireEvent(name))

  /** Manual **Actions → Run workflow** (requires `zipxWorkflowDispatch := true`). */
  def onWorkflowDispatch: JobCondition = eventIs("workflow_dispatch")

  /** Release tag refs (`refs/tags/v…`), same shape as [[Gate.OnReleaseTag]]. */
  def onReleaseTag: JobCondition = refStartsWith("refs/tags/v")

  /** PR has a label with this exact name. */
  def hasPrLabel(label: String): JobCondition = HasPrLabel(requireLiteral("label", label))

  /** Conjunction; rejects an empty list. */
  def and(clauses: JobCondition*): JobCondition = All(requireNonEmpty("and", clauses.toList))

  /** Disjunction; rejects an empty list. */
  def or(clauses: JobCondition*): JobCondition = Any(requireNonEmpty("or", clauses.toList))

  def not(inner: JobCondition): JobCondition = Not(inner)

  /** Escape hatch: raw GHA expression, trimmed; must be non-empty. */
  def raw(expression: String): JobCondition = Raw(requireRaw(expression))

  /** Render an optional condition for planner `if:` assembly. */
  def renderOpt(c: Option[JobCondition]): Option[String] = c.map(_.render)

  // The rules these helpers used to spell out inline now live in zipx-workflow as newtypes, so there is one definition
  // of "valid GHA identifier" and "valid quoted literal" across the layers. The helpers stay private and keep throwing
  // `IllegalArgumentException`, which is this file's public contract, and prefix the newtype's message with `kind` so
  // the error still says which field was wrong.

  private def orThrow(kind: String, result: Either[String, String]): String =
    result match
      case Right(value) => value
      case Left(error)  => throw IllegalArgumentException(s"$kind: $error")

  /** A `vars.` name. Delegates to [[EnvName]]: a repository variable and an `env:` key share GitHub's name rule. */
  private def requireIdent(kind: String, name: String): String =
    orThrow(kind, EnvName.make(name).map(_.unwrap))

  private def requireEvent(name: String): String =
    orThrow("event", EventName.make(name).map(_.unwrap))

  /** owner/repo, refs, labels: trimmed, then [[ExprLiteral]]'s rule (no quotes, `$` or whitespace). */
  private def requireLiteral(kind: String, value: String): String =
    orThrow(kind, ExprLiteral.make(value.trim).map(_.unwrap))

  private def requireRaw(expression: String): String =
    orThrow("raw JobCondition expression", RawExpr.make(expression.trim).map(_.unwrap))

  private def requireNonEmpty(op: String, clauses: List[JobCondition]): List[JobCondition] =
    if clauses.isEmpty then throw IllegalArgumentException(s"JobCondition.$op requires at least one clause")
    clauses

end JobCondition
