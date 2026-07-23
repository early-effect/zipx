package zipx.core

/** Typed GitHub Actions job `if:` predicate. Prefer smart constructors over assembling cases by hand.
  *
  * [[Gate]] is the timeline axis (`Always` / `OnReleaseTag`). [[JobCondition]] is an optional extra filter ANDed into
  * the job `if` (fork repo, PR label, branch, repo var, …). Both [[Capability.condition]] and [[Target.condition]] use
  * this AST; the planner renders and ANDs them with gate / affected clauses.
  *
  * Compose with [[&&]] / [[||]] (or [[JobCondition.and]] / [[JobCondition.or]]). [[Raw]] is the escape hatch for
  * expressions the variants cannot express.
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

  /** Conjunction with `other` (renders as `(this) && (other)`). */
  infix def &&(other: JobCondition): JobCondition = JobCondition.and(this, other)

  /** Disjunction with `other` (renders as `(this) || (other)`). */
  infix def ||(other: JobCondition): JobCondition = JobCondition.or(this, other)

  /** Negation (renders as `!(this)`). */
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
      s"github.event_name == '${JobCondition.requireIdent("event", name)}'"
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
  def eventIs(name: String): JobCondition = EventIs(requireIdent("event", name))

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

  private val IdentPattern = raw"[A-Za-z_][A-Za-z0-9_]*".r

  /** owner/repo, refs, labels: printable ASCII without quotes, `$`, or whitespace. */
  private val LiteralPattern = raw"""[A-Za-z0-9_./@+:-][A-Za-z0-9_./@+:-]*""".r

  private val MaxLiteralLen = 256

  private def requireIdent(kind: String, name: String): String =
    if name.isEmpty then throw IllegalArgumentException(s"$kind name must be non-empty")
    if IdentPattern.matches(name) then name
    else
      throw IllegalArgumentException(
        s"invalid $kind name '$name': must match ${IdentPattern.regex}"
      )

  private def requireLiteral(kind: String, value: String): String =
    val trimmed = value.trim
    if trimmed.isEmpty then throw IllegalArgumentException(s"$kind must be non-empty")
    if trimmed.length > MaxLiteralLen then throw IllegalArgumentException(s"$kind exceeds $MaxLiteralLen characters")
    if trimmed.contains('\'') || trimmed.contains('"') || trimmed.contains('$') || trimmed.exists(_.isWhitespace) then
      throw IllegalArgumentException(
        s"invalid $kind '$trimmed': must not contain quotes, $$ , or whitespace"
      )
    if LiteralPattern.matches(trimmed) then trimmed
    else
      throw IllegalArgumentException(
        s"invalid $kind '$trimmed': allowed characters are letters, digits, _ . / @ + : -"
      )
  end requireLiteral

  private def requireRaw(expression: String): String =
    val trimmed = expression.trim
    if trimmed.isEmpty then throw IllegalArgumentException("raw JobCondition expression must be non-empty")
    if trimmed.length > MaxLiteralLen * 4 then
      throw IllegalArgumentException(s"raw JobCondition expression exceeds ${MaxLiteralLen * 4} characters")
    trimmed

  private def requireNonEmpty(op: String, clauses: List[JobCondition]): List[JobCondition] =
    if clauses.isEmpty then throw IllegalArgumentException(s"JobCondition.$op requires at least one clause")
    clauses

end JobCondition
