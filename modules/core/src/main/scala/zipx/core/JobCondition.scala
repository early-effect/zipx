package zipx.core

import neotype.unwrap
import zipx.workflow.{ContextPath, EnvName, EventName, Expr, ExprLiteral, RawExpr}

/** A typed job `if:` predicate: an optional extra filter, where [[Gate]] is the timeline axis. The planner ANDs both
  * [[Capability.condition]] and [[Target.condition]] with its gate and affected clauses.
  *
  * Every case holds a value validated at construction, so [[expr]] and [[render]] are total. A literal goes through an
  * `inline` constructor and is checked while the consumer's build compiles; runtime data goes through the `*Make`
  * sibling and comes back as an `Either`.
  */
enum JobCondition:
  case RepositoryIs(repo: ExprLiteral)
  case VarNonEmpty(name: EnvName)
  case RefIs(ref: ExprLiteral)
  case RefStartsWith(prefix: ExprLiteral)
  case EventIs(name: EventName)
  case HasPrLabel(label: ExprLiteral)

  /** `first` plus `rest` rather than one list, so `All(Nil)` is unconstructible rather than rejected at render time. */
  case All(first: JobCondition, rest: List[JobCondition])

  /** Non-empty by the same construction as [[JobCondition.All]]. */
  case Any(first: JobCondition, rest: List[JobCondition])
  case Not(inner: JobCondition)

  /** **Escape hatch.** See [[zipx.workflow.RawExpr]] for what it does and does not guarantee.
    */
  case Raw(expression: RawExpr)

  /** `(this) && (other)`. */
  infix def &&(other: JobCondition): JobCondition = JobCondition.and(this, other)

  /** `(this) || (other)`. */
  infix def ||(other: JobCondition): JobCondition = JobCondition.or(this, other)

  /** `!(this)`. */
  def unary_! : JobCondition = JobCondition.not(this)

  /** This condition as a structural [[zipx.workflow.Expr]] rather than one opaque [[zipx.workflow.Expr.Raw]], so that
    * operator jointing has a single definition, in `Expr`.
    *
    * Every clause of an [[JobCondition.All]] or [[JobCondition.Any]] is wrapped in [[zipx.workflow.Expr.Group]]: a
    * `JobCondition` composes conditions of unknown shape, where `Expr`'s own `&&` joins operands bare and leaves
    * grouping to the author.
    */
  def expr: Expr = this match
    case RepositoryIs(repo)    => Expr.Github(JobCondition.RepositoryPath) === Expr.Quoted(repo)
    case VarNonEmpty(name)     => Expr.Var(name) !== JobCondition.EmptyLiteral
    case RefIs(ref)            => Expr.Github(JobCondition.RefPath) === Expr.Quoted(ref)
    case RefStartsWith(prefix) => Expr.startsWith(Expr.Github(JobCondition.RefPath), Expr.Quoted(prefix))
    case EventIs(name)         =>
      Expr.Github(JobCondition.EventNamePath) === Expr.Quoted(JobCondition.asLiteral(name))
    case HasPrLabel(label) => Expr.contains(Expr.Github(JobCondition.LabelsPath), Expr.Quoted(label))
    case All(first, rest)  => JobCondition.joined(first, rest, _ && _)
    case Any(first, rest)  => JobCondition.joined(first, rest, _ || _)
    case Not(inner)        => !Expr.group(inner.expr)
    case Raw(expression)   => Expr.Raw(expression)

  /** [[zipx.workflow.Expr.unwrapped]], not `render`: an `if:` is already an expression context, and two wrapped
    * conditions concatenate into a template string that evaluates to neither operand.
    */
  def render: String = expr.unwrapped
end JobCondition

object JobCondition:

  // The `inline` constructors trim, which neotype folds at compile time along with the rest of the validator. Trimming
  // is not laxness: `ExprLiteral` rejects whitespace outright, so without it a copied-in value with a trailing space
  // would be a compile error naming a character the author cannot see.

  /** `github.repository == 'owner/repo'`. */
  inline def repositoryIs(inline repo: String): JobCondition = RepositoryIs(ExprLiteral(repo.trim))

  def repositoryIsMake(repo: String): Either[String, JobCondition] =
    ExprLiteral.make(repo.trim).map(RepositoryIs(_))

  /** `vars.NAME != ''`. */
  inline def varNonEmpty(inline name: String): JobCondition = VarNonEmpty(EnvName(name))

  def varNonEmptyMake(name: String): Either[String, JobCondition] = EnvName.make(name).map(VarNonEmpty(_))

  /** `github.ref == 'refs/…'`. */
  inline def refIs(inline ref: String): JobCondition = RefIs(ExprLiteral(ref.trim))

  def refIsMake(ref: String): Either[String, JobCondition] = ExprLiteral.make(ref.trim).map(RefIs(_))

  /** `startsWith(github.ref, 'prefix')`. */
  inline def refStartsWith(inline prefix: String): JobCondition = RefStartsWith(ExprLiteral(prefix.trim))

  def refStartsWithMake(prefix: String): Either[String, JobCondition] =
    ExprLiteral.make(prefix.trim).map(RefStartsWith(_))

  /** `github.event_name == 'name'`. */
  inline def eventIs(inline name: String): JobCondition = EventIs(EventName(name))

  def eventIsMake(name: String): Either[String, JobCondition] = EventName.make(name).map(EventIs(_))

  /** Manual **Actions → Run workflow**, which needs `zipxWorkflowDispatch := true` to be reachable. */
  def onWorkflowDispatch: JobCondition = eventIs("workflow_dispatch")

  /** The same refs as [[Gate.OnReleaseTag]]. */
  def onReleaseTag: JobCondition = refStartsWith("refs/tags/v")

  /** `contains(github.event.pull_request.labels.*.name, 'label')`. */
  inline def hasPrLabel(inline label: String): JobCondition = HasPrLabel(ExprLiteral(label.trim))

  def hasPrLabelMake(label: String): Either[String, JobCondition] = ExprLiteral.make(label.trim).map(HasPrLabel(_))

  /** The signature is what rejects an empty conjunction: there is no `and()` to call. */
  def and(first: JobCondition, rest: JobCondition*): JobCondition = All(first, rest.toList)

  def or(first: JobCondition, rest: JobCondition*): JobCondition = Any(first, rest.toList)

  /** [[and]] over a list of unknown length. `None` for an empty list, since "no clauses" is not a condition, and a
    * caller assembling clauses conditionally wants the `Option[JobCondition]` that [[Capability.condition]] holds.
    */
  def allOf(clauses: List[JobCondition]): Option[JobCondition] = clauses match
    case Nil          => None
    case head :: tail => Some(All(head, tail))

  /** See [[allOf]]. */
  def anyOf(clauses: List[JobCondition]): Option[JobCondition] = clauses match
    case Nil          => None
    case head :: tail => Some(Any(head, tail))

  def not(inner: JobCondition): JobCondition = Not(inner)

  /** **Escape hatch.** A raw GHA expression, trimmed and checked at compile time.
    */
  inline def raw(inline expression: String): JobCondition = Raw(RawExpr(expression.trim))

  def rawMake(expression: String): Either[String, JobCondition] = RawExpr.make(expression.trim).map(Raw(_))

  def renderOpt(c: Option[JobCondition]): Option[String] = c.map(_.render)

  private def joined(
      first: JobCondition,
      rest: List[JobCondition],
      op: (Expr, Expr) => Expr,
  ): Expr = (first :: rest).map(c => Expr.group(c.expr)).reduceLeft(op)

  /** `unsafeMake` because [[EventName]]'s character set (letters, digits, `_`) is a subset of [[ExprLiteral]]'s, so the
    * alternative would be an `Either` with an unreachable `Left`.
    */
  private def asLiteral(name: EventName): ExprLiteral = ExprLiteral.unsafeMake(name.unwrap)

  /** A [[zipx.workflow.Expr.Lit]] rather than a [[zipx.workflow.Expr.Quoted]], because `Quoted` wraps an
    * [[ExprLiteral]], which is non-empty by definition.
    */
  private val EmptyLiteral: Expr = Expr.lit("''")

  private val RepositoryPath: ContextPath = ContextPath("repository")
  private val RefPath: ContextPath        = ContextPath("ref")
  private val EventNamePath: ContextPath  = ContextPath("event_name")
  private val LabelsPath: ContextPath     = ContextPath("event.pull_request.labels.*.name")

end JobCondition
