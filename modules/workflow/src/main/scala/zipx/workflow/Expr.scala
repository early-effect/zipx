package zipx.workflow

import neotype.unwrap

/** A GitHub Actions expression: the typed replacement for a hand-written `${{ … }}` string.
  *
  * Covers the contexts zipx actually renders into, each one a validated name rather than spliced text:
  *
  * {{{
  * Expr.secret("PGP_PASSPHRASE").render      // ${{ secrets.PGP_PASSPHRASE }}
  * Expr.stepOutput("check", "run").render    // ${{ steps.check.outputs.run }}
  * Expr.github("event.pull_request.base.sha")
  * }}}
  *
  * Closed rather than open, unlike `zipx.shell.Command`: GitHub fixes the context list, so a new case would be a new
  * GitHub feature, and [[Expr.Raw]] covers anything not yet modelled. The two seams to other layers are
  * [[Expr.asWord]], which drops an expression into a shell script, and `JobCondition.expr` in `zipx-core`, which lifts
  * a validated condition into a step field.
  *
  * Note the asymmetry in [[render]]: [[Expr.Lit]] and [[Expr.Raw]] emit their text bare, every other case wraps in
  * `${{ … }}`. That is what makes [[Expr.Concat]] able to build `sbt-${{ runner.os }}-key` without an interpolator.
  */
enum Expr:

  /** `${{ secrets.NAME }}`. */
  case Secret(name: SecretName)

  /** `${{ env.NAME }}`. */
  case Env(name: EnvName)

  /** `${{ vars.NAME }}`: a repository or organization configuration variable, not a secret. */
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

  /** Literal text, emitted with no `${{ }}` wrapper. The non-expression part of a [[Concat]]. */
  case Lit(text: String)

  /** Concatenation, so a cache key or tag is assembled from parts instead of interpolated. */
  case Concat(parts: List[Expr])

  /** **Escape hatch.** A raw expression, emitted verbatim. See [[RawExpr]] for what is and is not guaranteed.
    */
  case Raw(expression: RawExpr)

  /** Render to the string that lands in the YAML. */
  def render: String = this match
    case Secret(name)         => s"$${{ secrets.${name.unwrap} }}"
    case Env(name)            => s"$${{ env.${name.unwrap} }}"
    case Var(name)            => s"$${{ vars.${name.unwrap} }}"
    case Github(path)         => s"$${{ github.${path.unwrap} }}"
    case Runner(path)         => s"$${{ runner.${path.unwrap} }}"
    case StepOutput(id, name) => s"$${{ steps.${id.unwrap}.outputs.${name.unwrap} }}"
    case JobOutput(id, name)  => s"$${{ needs.${id.unwrap}.outputs.${name.unwrap} }}"
    case JobResult(id)        => s"$${{ needs.${id.unwrap}.result }}"
    case Matrix(axis)         => s"$${{ matrix.${axis.unwrap} }}"
    case Lit(text)            => text
    case Concat(parts)        => parts.map(_.render).mkString
    case Raw(expression)      => expression.unwrap

  /** Concatenate with `other`, flattening so nested [[Concat]]s do not nest. */
  infix def ++(other: Expr): Expr = (this, other) match
    case (Concat(a), Concat(b)) => Concat(a ++ b)
    case (Concat(a), b)         => Concat(a :+ b)
    case (a, Concat(b))         => Concat(a :: b)
    case (a, b)                 => Concat(List(a, b))

  /** This expression as a shell [[zipx.shell.Word]], for embedding in a `run:` script.
    *
    * [[zipx.shell.Word.Opaque]] specifically: an expression's `$` must survive into the YAML unescaped, so it is the
    * one word kind the shell renderer never touches. This is the whole coupling between the expression AST and the
    * shell AST, in one direction only.
    */
  def asWord: zipx.shell.Word = zipx.shell.Word.Opaque(zipx.shell.ShText.makeOrThrow(render))

end Expr

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

  /** `${{ matrix.<axis> }}`. */
  inline def matrix(inline axis: String): Expr = Matrix(MatrixAxis(axis))

  def matrixMake(axis: String): Either[String, Expr] = MatrixAxis.make(axis).map(Matrix(_))

  /** Literal text with no `${{ }}` wrapper, for the fixed parts of a [[Concat]]. */
  def lit(text: String): Expr = Lit(text)

  /** Concatenate parts, flattening nested [[Concat]]s. */
  def concat(parts: Expr*): Expr = parts.toList match
    case single :: Nil => single
    case many          => Concat(many.flatMap { case Concat(ps) => ps; case e => List(e) })

  /** **Escape hatch.** A raw expression, checked at compile time. See [[Expr.Raw]].
    */
  inline def raw(inline expression: String): Expr = Raw(RawExpr(expression))

  def rawMake(expression: String): Either[String, Expr] = RawExpr.make(expression).map(Raw(_))

  /** The `GITHUB_TOKEN` injected into every workflow run: `${{ github.token }}`.
    *
    * `github.token` rather than `secrets.GITHUB_TOKEN`; the two are equivalent and this is the shorter documented form.
    */
  val githubToken: Expr = Github(ContextPath("token"))

end Expr
