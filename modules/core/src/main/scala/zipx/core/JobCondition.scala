package zipx.core

import neotype.unwrap
import zipx.workflow.{ContextPath, EnvName, EventName, Expr, ExprLiteral, RawExpr}

/** Typed GitHub Actions job `if:` predicate. Prefer smart constructors over assembling cases by hand.
  *
  * [[Gate]] is the timeline axis (`Always` / `OnReleaseTag`). [[JobCondition]] is an optional extra filter ANDed into
  * the job `if` (fork repo, PR label, branch, repo var, …). Both [[Capability.condition]] and [[Target.condition]] use
  * this AST; the planner renders and ANDs them with gate / affected clauses.
  *
  * Compose with [[&&]] / [[||]] (or [[JobCondition.and]] / [[JobCondition.or]]). Infix `&&` / `||` follow Boolean
  * precedence (`&&` tighter than `||`, both left-associative). [[JobCondition.Raw]] is the escape hatch for expressions
  * the variants cannot express.
  *
  * **Nothing here can fail.** Every case holds a value that was validated when it was made, so [[expr]] and [[render]]
  * are total functions with no error to report. A literal goes through an `inline` constructor and is checked while the
  * build compiles; a name that arrives as runtime data goes through the `*Make` sibling and comes back as an `Either`.
  * That is the whole error story, and it is over before a `JobCondition` exists.
  */
enum JobCondition:
  case RepositoryIs(repo: ExprLiteral)
  case VarNonEmpty(name: EnvName)
  case RefIs(ref: ExprLiteral)
  case RefStartsWith(prefix: ExprLiteral)
  case EventIs(name: EventName)
  case HasPrLabel(label: ExprLiteral)

  /** Conjunction. Split into `first` and `rest` rather than one list because an empty conjunction has no meaning: this
    * is how `All(Nil)` stops being constructible instead of being rejected at render time.
    */
  case All(first: JobCondition, rest: List[JobCondition])

  /** Disjunction. Non-empty by the same construction as [[All]]. */
  case Any(first: JobCondition, rest: List[JobCondition])
  case Not(inner: JobCondition)

  /** **Escape hatch.** A raw expression. See [[zipx.workflow.RawExpr]] for what it does and does not guarantee.
    */
  case Raw(expression: RawExpr)

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

  /** This condition as a [[zipx.workflow.Expr]], case by case rather than as one opaque [[zipx.workflow.Expr.Raw]].
    *
    * Structural, which is what makes this total and [[render]] a one-liner: the operator jointing has one definition,
    * in `Expr`, instead of one here and one there. Note the parens. Every clause of an [[All]] or [[Any]] is wrapped in
    * [[zipx.workflow.Expr.Group]], because a `JobCondition` composes conditions of unknown shape, where `Expr`'s own
    * `&&` joins operands bare and leaves grouping to the author.
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

  /** Render to the string that lands in a job's `if:` field.
    *
    * [[zipx.workflow.Expr.unwrapped]], not `render`: an `if:` is already an expression context, and two wrapped
    * conditions concatenate into a template string that evaluates to neither operand.
    */
  def render: String = expr.unwrapped
end JobCondition

object JobCondition:

  // Literal constructors are `inline`, so a bad value fails while the consumer's build compiles; the `*Make` siblings
  // take runtime data and report the same rule as a `Left`. The rules themselves live in zipx-workflow as newtypes, so
  // there is one definition of "valid GHA identifier" and "valid quoted literal" across the layers.
  //
  // The literal constructors trim, which neotype folds at compile time along with the rest of the validator. Trimming
  // is not laxness: `ExprLiteral` rejects whitespace outright, so without it a copied-in value with a trailing space
  // would be a compile error naming a character the author cannot see.

  /** `github.repository == 'owner/repo'`. */
  inline def repositoryIs(inline repo: String): JobCondition = RepositoryIs(ExprLiteral(repo.trim))

  /** [[repositoryIs]] for a slug that arrives as runtime data (a setting, a pack parameter). */
  def repositoryIsMake(repo: String): Either[String, JobCondition] =
    ExprLiteral.make(repo.trim).map(RepositoryIs(_))

  /** `vars.NAME != ''`. */
  inline def varNonEmpty(inline name: String): JobCondition = VarNonEmpty(EnvName(name))

  /** [[varNonEmpty]] for a name that arrives as runtime data. */
  def varNonEmptyMake(name: String): Either[String, JobCondition] = EnvName.make(name).map(VarNonEmpty(_))

  /** `github.ref == 'refs/…'`. */
  inline def refIs(inline ref: String): JobCondition = RefIs(ExprLiteral(ref.trim))

  /** [[refIs]] for a ref that arrives as runtime data. */
  def refIsMake(ref: String): Either[String, JobCondition] = ExprLiteral.make(ref.trim).map(RefIs(_))

  /** `startsWith(github.ref, 'prefix')`. */
  inline def refStartsWith(inline prefix: String): JobCondition = RefStartsWith(ExprLiteral(prefix.trim))

  /** [[refStartsWith]] for a prefix that arrives as runtime data. */
  def refStartsWithMake(prefix: String): Either[String, JobCondition] =
    ExprLiteral.make(prefix.trim).map(RefStartsWith(_))

  /** `github.event_name == 'name'` (e.g. `pull_request`, `workflow_dispatch`). */
  inline def eventIs(inline name: String): JobCondition = EventIs(EventName(name))

  /** [[eventIs]] for an event name that arrives as runtime data. */
  def eventIsMake(name: String): Either[String, JobCondition] = EventName.make(name).map(EventIs(_))

  /** Manual **Actions → Run workflow** (requires `zipxWorkflowDispatch := true`). */
  def onWorkflowDispatch: JobCondition = eventIs("workflow_dispatch")

  /** Release tag refs (`refs/tags/v…`), same shape as [[Gate.OnReleaseTag]]. */
  def onReleaseTag: JobCondition = refStartsWith("refs/tags/v")

  /** PR has a label with this exact name. */
  inline def hasPrLabel(inline label: String): JobCondition = HasPrLabel(ExprLiteral(label.trim))

  /** [[hasPrLabel]] for a label that arrives as runtime data (`PlanConfig.verifyCleanLabel`, a setting). */
  def hasPrLabelMake(label: String): Either[String, JobCondition] = ExprLiteral.make(label.trim).map(HasPrLabel(_))

  /** Conjunction. The signature is what rejects an empty one: there is no `and()` to call. */
  def and(first: JobCondition, rest: JobCondition*): JobCondition = All(first, rest.toList)

  /** Disjunction. Non-empty by signature, like [[and]]. */
  def or(first: JobCondition, rest: JobCondition*): JobCondition = Any(first, rest.toList)

  /** [[and]] over a list whose length is not known statically. `None` for an empty list, which is the honest answer:
    * "no clauses" is not a condition, and a caller assembling clauses conditionally wants an `Option[JobCondition]`
    * anyway, since that is what [[Capability.condition]] holds.
    */
  def allOf(clauses: List[JobCondition]): Option[JobCondition] = clauses match
    case Nil          => None
    case head :: tail => Some(All(head, tail))

  /** [[or]] over a list whose length is not known statically. See [[allOf]]. */
  def anyOf(clauses: List[JobCondition]): Option[JobCondition] = clauses match
    case Nil          => None
    case head :: tail => Some(Any(head, tail))

  def not(inner: JobCondition): JobCondition = Not(inner)

  /** **Escape hatch.** A raw GHA expression, trimmed and checked at compile time.
    */
  inline def raw(inline expression: String): JobCondition = Raw(RawExpr(expression.trim))

  /** [[raw]] for an expression that arrives as runtime data. */
  def rawMake(expression: String): Either[String, JobCondition] = RawExpr.make(expression.trim).map(Raw(_))

  /** Render an optional condition for planner `if:` assembly. */
  def renderOpt(c: Option[JobCondition]): Option[String] = c.map(_.render)

  private def joined(
      first: JobCondition,
      rest: List[JobCondition],
      op: (Expr, Expr) => Expr,
  ): Expr = (first :: rest).map(c => Expr.group(c.expr)).reduceLeft(op)

  /** An event name as a quoted literal. `unsafeMake` because [[EventName]]'s character set (letters, digits, `_`) is a
    * subset of [[ExprLiteral]]'s, so the conversion cannot fail; the alternative would be an `Either` with an
    * unreachable `Left`.
    */
  private def asLiteral(name: EventName): ExprLiteral = ExprLiteral.unsafeMake(name.unwrap)

  /** `''`: the empty string [[VarNonEmpty]] compares against. A [[zipx.workflow.Expr.Lit]] rather than a
    * [[zipx.workflow.Expr.Quoted]] because `Quoted` wraps an [[ExprLiteral]], which is non-empty by definition.
    */
  private val EmptyLiteral: Expr = Expr.Lit("''")

  private val RepositoryPath: ContextPath = ContextPath("repository")
  private val RefPath: ContextPath        = ContextPath("ref")
  private val EventNamePath: ContextPath  = ContextPath("event_name")
  private val LabelsPath: ContextPath     = ContextPath("event.pull_request.labels.*.name")

end JobCondition
