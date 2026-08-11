package zipx.workflow

import neotype.unwrap
import zipx.shell.ShText

/** A GitHub Actions expression: the typed replacement for a hand-written `${{ … }}` string.
  *
  * {{{
  * Expr.secret("PGP_PASSPHRASE").render   // ${{ secrets.PGP_PASSPHRASE }}
  * Expr.stepOutput("check", "run").render // ${{ steps.check.outputs.run }}
  *
  * (!Expr.cancelled && Expr.github("event_name") !== Expr.quoted("workflow_dispatch")).unwrapped
  * // !cancelled() && github.event_name != 'workflow_dispatch'
  * }}}
  *
  * [[Expr.Lit]], [[Expr.Quoted]] and [[Expr.Raw]] emit their text bare where every other case wraps in `${{ … }}`,
  * which is what lets [[Expr.Concat]] build `sbt-${{ runner.os }}-key` without an interpolator.
  *
  * **Grouping is explicit:** [[&&]] and [[||]] join their operands bare and [[Expr.Group]] is the only case that emits
  * a paren. `JobCondition` instead parenthesizes every clause, because it composes user-supplied conditions of unknown
  * shape, where an `Expr` is assembled here and the rendered bytes are the author's to control.
  */
enum Expr:

  /** `${{ secrets.NAME }}`. */
  case Secret(name: SecretName)

  /** `${{ env.NAME }}`. */
  case Env(name: EnvName)

  /** `${{ vars.NAME }}`: a configuration variable, not a secret. */
  case Var(name: EnvName)

  /** `${{ github.<path> }}`, as in `github.sha` or `github.event.pull_request.base.sha`. */
  case Github(path: ContextPath)

  /** `${{ runner.<path> }}`, as in `runner.os`. */
  case Runner(path: ContextPath)

  /** `${{ steps.<id>.outputs.<name> }}`: an output of an earlier step in the same job. */
  case StepOutput(stepId: StepId, name: OutputName)

  /** `${{ needs.<id>.outputs.<name> }}`: an output of an upstream job. */
  case JobOutput(jobId: JobId, name: OutputName)

  /** `${{ needs.<id>.result }}`: an upstream job's conclusion (`success`, `failure`, `skipped`, `cancelled`). */
  case JobResult(jobId: JobId)

  /** `${{ matrix.<axis> }}`. */
  case Matrix(axis: MatrixAxis)

  /** Literal text, emitted with no `${{ }}` wrapper. The non-expression part of a [[Concat]].
    *
    * A [[zipx.shell.ShText]] rather than a `String` because a `Concat` holding one becomes a shell word through
    * [[asWord]], and a newline there would collapse the generated `run:` script to a single quoted YAML line.
    */
  case Lit(text: ShText)

  /** A single-quoted literal *inside* an expression: the `'refs/tags/v'` of `startsWith(github.ref, 'refs/tags/v')`.
    * Emitted bare, quotes included, since it is only ever an operand of a [[Call]] or [[Compare]].
    */
  case Quoted(text: ExprLiteral)

  /** A call to one of GitHub's expression functions: `contains(a, b)`, `fromJson(x)`, `cancelled()`.
    *
    * Arguments render [[unwrapped]], because an argument is already inside an expression context; a nested `${{ }}`
    * there would make the whole thing a template string that evaluates to neither operand.
    */
  case Call(function: FunctionName, args: List[Expr])

  /** `a == b` / `a != b`. Operands render [[unwrapped]], as [[Call]]'s arguments do. */
  case Compare(lhs: Expr, op: CompareOp, rhs: Expr)

  /** `a && b` / `a || b`, joined bare. Wrap an operand in [[Group]] where precedence needs it. */
  case Join(lhs: Expr, op: JoinOp, rhs: Expr)

  /** `!inner`, with no parens of its own; `!(…)` is `Not(Group(…))`. */
  case Not(inner: Expr)

  /** `(inner)`: the only case that emits a paren. */
  case Group(inner: Expr)

  /** Concatenation, so a cache key or tag is assembled from parts instead of interpolated. */
  case Concat(parts: List[Expr])

  /** **Escape hatch.** A raw expression, emitted verbatim. See [[RawExpr]] for what is and is not guaranteed.
    */
  case Raw(expression: RawExpr)

  /** Render to the string that lands in the YAML. */
  def render: String = this match
    case Lit(text)       => text.unwrap
    case Quoted(text)    => s"'${text.unwrap}'"
    case Raw(expression) => expression.unwrap
    case Concat(parts)   => parts.map(_.render).mkString
    case other           => s"$${{ ${other.unwrapped} }}"

  /** The text *inside* the `${{ }}`, for a position that is already an expression context: an `if:`, or an operand of
    * an operator or [[Expr.Call]].
    *
    * Bare composes where wrapped does not: two conditions ANDed bare are one expression, where two wrapped ones
    * concatenate into a template string that evaluates to neither.
    */
  def unwrapped: String = this match
    case Secret(name)         => s"secrets.${name.unwrap}"
    case Env(name)            => s"env.${name.unwrap}"
    case Var(name)            => s"vars.${name.unwrap}"
    case Github(path)         => s"github.${path.unwrap}"
    case Runner(path)         => s"runner.${path.unwrap}"
    case StepOutput(id, name) => s"steps.${id.unwrap}.outputs.${name.unwrap}"
    case JobOutput(id, name)  => s"needs.$id.outputs.${name.unwrap}"
    case JobResult(id)        => s"needs.$id.result"
    case Matrix(axis)         => s"matrix.${axis.unwrap}"
    case Lit(text)            => text.unwrap
    case Quoted(text)         => s"'${text.unwrap}'"
    case Call(fn, args)       => s"${fn.unwrap}(${args.map(_.unwrapped).mkString(", ")})"
    case Compare(l, op, r)    => s"${l.unwrapped} ${op.symbol} ${r.unwrapped}"
    case Join(l, op, r)       => s"${l.unwrapped} ${op.symbol} ${r.unwrapped}"
    case Not(inner)           => s"!${inner.unwrapped}"
    case Group(inner)         => s"(${inner.unwrapped})"
    case Concat(parts)        => parts.map(_.unwrapped).mkString
    case Raw(expression)      => expression.unwrap

  /** `this == other`. Named `===` because `==` is `Any`'s and cannot be an expression. */
  infix def ===(other: Expr): Expr = Compare(this, CompareOp.Eq, other)

  /** `this != other`. */
  infix def !==(other: Expr): Expr = Compare(this, CompareOp.Ne, other)

  /** `this && other`, joined bare; wrap in [[Expr.group]] where precedence needs a paren. */
  infix def &&(other: Expr): Expr = Join(this, JoinOp.And, other)

  /** `this || other`, joined bare. */
  infix def ||(other: Expr): Expr = Join(this, JoinOp.Or, other)

  /** `!this`, with no parens; write `!Expr.group(…)` for `!(…)`. */
  def unary_! : Expr = Not(this)

  /** Concatenate with `other`, flattening so nested [[Expr.Concat]]s do not nest. */
  infix def ++(other: Expr): Expr = (this, other) match
    case (Concat(a), Concat(b)) => Concat(a ++ b)
    case (Concat(a), b)         => Concat(a :+ b)
    case (a, Concat(b))         => Concat(a :: b)
    case (a, b)                 => Concat(List(a, b))

  /** [[render]] as a [[zipx.shell.ShText]], which every case satisfies by construction: each holds either an already
    * validated newtype or an already validated `Expr`, and the punctuation this adds (`${{ }}`, `'`, `,`, operators) is
    * printable. `unsafeMake` states that once, here, instead of every consumer re-validating text it built from
    * validated parts.
    */
  def renderShText: ShText = ShText.unsafeMake(render)

  /** This expression as a shell [[zipx.shell.Word]], for embedding in a `run:` script.
    *
    * [[zipx.shell.Word.Opaque]] specifically, since an expression's `$` must survive into the YAML unescaped. The
    * return type is the precise `Opaque` rather than `Word` so it can nest inside a double-quoted word, which
    * `"repos/${{ github.repository }}/commits"` needs.
    */
  def asWord: zipx.shell.Word.Opaque = zipx.shell.Word.Opaque(renderShText)

end Expr

/** The comparison operators [[Expr.Compare]] renders. Equality only; nothing here needs GitHub's numeric comparisons.
  */
enum CompareOp(val symbol: String):
  case Eq extends CompareOp("==")
  case Ne extends CompareOp("!=")

/** The boolean connectives [[Expr.Join]] renders. */
enum JoinOp(val symbol: String):
  case And extends JoinOp("&&")
  case Or  extends JoinOp("||")

object Expr:

  // Literal constructors are `inline` so a bad name fails at compile time; the `*Make` siblings take runtime input.

  /** `${{ secrets.NAME }}`, name checked at compile time. */
  inline def secret(inline name: String): Expr = Secret(SecretName(name))

  def secretMake(name: String): Either[String, Expr] = SecretName.make(name).map(Secret(_))

  /** `${{ env.NAME }}`. */
  inline def env(inline name: String): Expr = Env(EnvName(name))

  def envMake(name: String): Either[String, Expr] = EnvName.make(name).map(Env(_))

  /** `${{ vars.NAME }}`. */
  inline def vars(inline name: String): Expr = Var(EnvName(name))

  def varsMake(name: String): Either[String, Expr] = EnvName.make(name).map(Var(_))

  /** `${{ github.<path> }}`. */
  inline def github(inline path: String): Expr = Github(ContextPath(path))

  def githubMake(path: String): Either[String, Expr] = ContextPath.make(path).map(Github(_))

  /** `${{ runner.<path> }}`. */
  inline def runner(inline path: String): Expr = Runner(ContextPath(path))

  /** `${{ steps.<id>.outputs.<name> }}`. */
  inline def stepOutput(inline stepId: String, inline name: String): Expr =
    StepOutput(StepId(stepId), OutputName(name))

  def stepOutputMake(stepId: String, name: String): Either[String, Expr] =
    for
      id <- StepId.make(stepId)
      n  <- OutputName.make(name)
    yield StepOutput(id, n)

  /** `${{ needs.<id>.outputs.<name> }}`. */
  inline def jobOutput(inline jobId: String, inline name: String): Expr =
    JobOutput(JobId(jobId), OutputName(name))

  def jobOutputMake(jobId: String, name: String): Either[String, Expr] =
    for
      id <- JobId.make(jobId)
      n  <- OutputName.make(name)
    yield JobOutput(id, n)

  /** `${{ needs.<id>.result }}`. */
  inline def jobResult(inline jobId: String): Expr = JobResult(JobId(jobId))

  def jobResultMake(jobId: String): Either[String, Expr] = JobId.make(jobId).map(JobResult(_))

  /** `${{ matrix.<axis> }}`. */
  inline def matrix(inline axis: String): Expr = Matrix(MatrixAxis(axis))

  def matrixMake(axis: String): Either[String, Expr] = MatrixAxis.make(axis).map(Matrix(_))

  /** Literal text with no `${{ }}` wrapper, for the fixed parts of a [[Concat]]. */
  inline def lit(inline text: String): Expr = Lit(ShText(text))

  def litMake(text: String): Either[String, Expr] = ShText.make(text).map(Lit(_))

  /** `'text'`: a single-quoted literal inside an expression, checked at compile time. */
  inline def quoted(inline text: String): Expr = Quoted(ExprLiteral(text))

  def quotedMake(text: String): Either[String, Expr] = ExprLiteral.make(text).map(Quoted(_))

  /** `fn(args…)`, function name checked at compile time against GitHub's list. See [[FunctionName]]. */
  inline def call(inline function: String, args: Expr*): Expr = Call(FunctionName(function), args.toList)

  def callMake(function: String, args: Expr*): Either[String, Expr] =
    FunctionName.make(function).map(Call(_, args.toList))

  /** `contains(haystack, needle)`: substring for strings, membership for arrays. */
  def contains(haystack: Expr, needle: Expr): Expr = call("contains", haystack, needle)

  /** `startsWith(value, prefix)`. */
  def startsWith(value: Expr, prefix: Expr): Expr = call("startsWith", value, prefix)

  /** `fromJson(value)`: parse a JSON string into an array or object, so `contains` can search it. */
  def fromJson(value: Expr): Expr = call("fromJson", value)

  /** `cancelled()`. Negated, it keeps a job runnable after a *skipped* upstream, which `success()` (the implicit
    * default) does not.
    */
  val cancelled: Expr = Call(FunctionName("cancelled"), Nil)

  /** `(inner)`. Explicit, because no operator adds parens of its own. */
  def group(inner: Expr): Expr = Group(inner)

  /** Concatenate parts, flattening nested [[Concat]]s. */
  def concat(parts: Expr*): Expr = parts.toList match
    case single :: Nil => single
    case many          => Concat(many.flatMap { case Concat(ps) => ps; case e => List(e) })

  /** **Escape hatch.** A raw expression, checked at compile time. See [[Expr.Raw]].
    */
  inline def raw(inline expression: String): Expr = Raw(RawExpr(expression))

  def rawMake(expression: String): Either[String, Expr] = RawExpr.make(expression).map(Raw(_))

  /** The `GITHUB_TOKEN` injected into every workflow run: `${{ github.token }}`, equivalent to `secrets.GITHUB_TOKEN`.
    */
  val githubToken: Expr = Github(ContextPath("token"))

end Expr
