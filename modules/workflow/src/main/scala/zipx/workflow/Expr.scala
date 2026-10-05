package zipx.workflow

import neotype.unwrap
import zipx.shell.ShText

/** [[Expr.Lit]], [[Expr.Quoted]] and [[Expr.Raw]] render bare where every other case wraps in `\${{ … }}`, which lets
  * [[Expr.Concat]] build `sbt-\${{ runner.os }}-key` without an interpolator.
  *
  * An operand that binds looser than its position renders in parens, so the rendered expression means what the tree
  * says.
  */
enum Expr:

  case Secret(name: SecretName)

  case Env(name: EnvName)

  case Var(name: EnvName)

  case Github(path: ContextPath)

  case Runner(path: ContextPath)

  case StepOutput(stepId: StepId, name: OutputName)

  case JobOutput(jobId: JobId, name: OutputName)

  case JobResult(jobId: JobId)

  case Matrix(axis: MatrixAxis)

  case Input(name: InputName)

  case Member(of: Expr, key: PropertyName)

  case Index(of: Expr, key: Expr)

  /** A [[zipx.shell.ShText]] because a [[Concat]] holding one becomes a shell word through [[asWord]], and a newline
    * there would collapse the generated `run:` script to a single quoted YAML line.
    */
  case Lit(text: ShText)

  case Quoted(text: ExprLiteral)

  /** Arguments render [[unwrapped]]: a nested `\${{ }}` would turn the call into a template string. */
  case Call(function: FunctionName, args: List[Expr])

  case Compare(lhs: Expr, op: CompareOp, rhs: Expr)

  case Join(lhs: Expr, op: JoinOp, rhs: Expr)

  case Not(inner: Expr)

  case Group(inner: Expr)

  case Concat(parts: List[Expr])

  /** **Escape hatch.** See [[RawExpr]] for what is and is not guaranteed.
    */
  case Raw(expression: RawExpr)

  def render: String = this match
    case Lit(text)       => text.unwrap
    case Quoted(text)    => s"'${text.unwrap}'"
    case Raw(expression) => expression.unwrap
    case Concat(parts)   => parts.map(_.render).mkString
    case other           => s"$${{ ${other.unwrapped} }}"

  /** The text inside `\${{ }}`, for a position that is already an expression context (an `if:`, an operand). Bare
    * composes where wrapped does not: two wrapped conditions concatenate into a template string that evaluates to
    * neither.
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
    case Input(name)          => s"inputs.$name"
    case Member(of, key)      => s"${of.operand(Binding.Atom)}.$key"
    case Index(of, key)       => s"${of.operand(Binding.Atom)}[${key.unwrapped}]"
    case Lit(text)            => text.unwrap
    case Quoted(text)         => s"'${text.unwrap}'"
    case Call(fn, args)       => s"${fn.unwrap}(${args.map(_.unwrapped).mkString(", ")})"
    case Compare(l, op, r)    => s"${l.operand(Binding.Equality)} ${op.symbol} ${r.operand(Binding.Negation)}"
    case Join(l, op, r)       => s"${l.operand(op.binding)} ${op.symbol} ${r.operand(op.binding)}"
    case Not(inner)           => s"!${inner.operand(Binding.Negation)}"
    case Group(inner)         => s"(${inner.unwrapped})"
    case Concat(parts)        => parts.map(_.unwrapped).mkString
    case Raw(expression)      => expression.unwrap

  /** A raw expression's shape is unknown, so it binds loosest. */
  private def binding: Binding = this match
    case Join(_, op, _)   => op.binding
    case Compare(_, _, _) => Binding.Equality
    case Not(_)           => Binding.Negation
    case Raw(_)           => Binding.Or
    case _                => Binding.Atom

  private def operand(position: Binding): String =
    if binding.ordinal < position.ordinal then s"($unwrapped)" else unwrapped

  /** Not `==`, which is `Any`'s and cannot be an expression. */
  infix def ===(other: Expr): Expr = Compare(this, CompareOp.Eq, other)

  infix def !==(other: Expr): Expr = Compare(this, CompareOp.Ne, other)

  infix def &&(other: Expr): Expr = Join(this, JoinOp.And, other)

  infix def ||(other: Expr): Expr = Join(this, JoinOp.Or, other)

  def unary_! : Expr = Not(this)

  def member(key: PropertyName): Expr = Member(this, key)

  def at(key: Expr): Expr = Index(this, key)

  infix def ++(other: Expr): Expr = (this, other) match
    case (Concat(a), Concat(b)) => Concat(a ++ b)
    case (Concat(a), b)         => Concat(a :+ b)
    case (a, Concat(b))         => Concat(a :: b)
    case (a, b)                 => Concat(List(a, b))

  /** Total: every case holds validated text and adds only printable punctuation, so `unsafeMake` states that once here
    * instead of every consumer revalidating text it built from validated parts.
    */
  def renderShText: ShText = ShText.unsafeMake(render)

  /** `Opaque` so the expression's `$` survives into the YAML unescaped, and typed as `Opaque` rather than `Word` so it
    * can nest inside a double-quoted word.
    */
  def asWord: zipx.shell.Word.Opaque = zipx.shell.Word.Opaque(renderShText)

end Expr

/** Equality only; nothing here needs GitHub's numeric comparisons. */
enum CompareOp(val symbol: String):
  case Eq extends CompareOp("==")
  case Ne extends CompareOp("!=")

enum JoinOp(val symbol: String, val binding: Binding):
  case And extends JoinOp("&&", Binding.And)
  case Or  extends JoinOp("||", Binding.Or)

/** GitHub's expression precedence, loosest first. */
enum Binding:
  case Or, And, Equality, Negation, Atom

object Expr:

  // Literal constructors are `inline` so a bad name fails at compile time; the `*Make` siblings take runtime input.

  inline def secret(inline name: String): Expr = Secret(SecretName(name))

  def secretMake(name: String): Either[String, Expr] = SecretName.make(name).map(Secret(_))

  inline def env(inline name: String): Expr = Env(EnvName(name))

  def envMake(name: String): Either[String, Expr] = EnvName.make(name).map(Env(_))

  inline def vars(inline name: String): Expr = Var(EnvName(name))

  def varsMake(name: String): Either[String, Expr] = EnvName.make(name).map(Var(_))

  inline def github(inline path: String): Expr = Github(ContextPath(path))

  def githubMake(path: String): Either[String, Expr] = ContextPath.make(path).map(Github(_))

  inline def runner(inline path: String): Expr = Runner(ContextPath(path))

  inline def stepOutput(inline stepId: String, inline name: String): Expr =
    StepOutput(StepId(stepId), OutputName(name))

  def stepOutputMake(stepId: String, name: String): Either[String, Expr] =
    for
      id <- StepId.make(stepId)
      n  <- OutputName.make(name)
    yield StepOutput(id, n)

  inline def jobOutput(inline jobId: String, inline name: String): Expr =
    JobOutput(JobId(jobId), OutputName(name))

  def jobOutputMake(jobId: String, name: String): Either[String, Expr] =
    for
      id <- JobId.make(jobId)
      n  <- OutputName.make(name)
    yield JobOutput(id, n)

  inline def jobResult(inline jobId: String): Expr = JobResult(JobId(jobId))

  def jobResultMake(jobId: String): Either[String, Expr] = JobId.make(jobId).map(JobResult(_))

  inline def matrix(inline axis: String): Expr = Matrix(MatrixAxis(axis))

  def matrixMake(axis: String): Either[String, Expr] = MatrixAxis.make(axis).map(Matrix(_))

  inline def input(inline name: String): Expr = Input(InputName(name))

  inline def lit(inline text: String): Expr = Lit(ShText(text))

  def litMake(text: String): Either[String, Expr] = ShText.make(text).map(Lit(_))

  /** `'text'`, a string literal inside an expression. */
  inline def quoted(inline text: String): Expr = Quoted(ExprLiteral(text))

  def quotedMake(text: String): Either[String, Expr] = ExprLiteral.make(text).map(Quoted(_))

  inline def call(inline function: String, args: Expr*): Expr = Call(FunctionName(function), args.toList)

  def callMake(function: String, args: Expr*): Either[String, Expr] =
    FunctionName.make(function).map(Call(_, args.toList))

  def contains(haystack: Expr, needle: Expr): Expr = call("contains", haystack, needle)

  def startsWith(value: Expr, prefix: Expr): Expr = call("startsWith", value, prefix)

  def fromJson(value: Expr): Expr = call("fromJson", value)

  /** Negated, it keeps a job runnable after a *skipped* upstream, which the implicit `success()` does not. */
  val cancelled: Expr = Call(FunctionName("cancelled"), Nil)

  def group(inner: Expr): Expr = Group(inner)

  def concat(parts: Expr*): Expr = parts.toList match
    case single :: Nil => single
    case many          => Concat(many.flatMap { case Concat(ps) => ps; case e => List(e) })

  /** **Escape hatch.** See [[RawExpr]].
    */
  inline def raw(inline expression: String): Expr = Raw(RawExpr(expression))

  def rawMake(expression: String): Either[String, Expr] = RawExpr.make(expression).map(Raw(_))

  /** Equivalent to `secrets.GITHUB_TOKEN`. */
  val githubToken: Expr = Github(ContextPath("token"))

end Expr
