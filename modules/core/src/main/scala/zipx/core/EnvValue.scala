package zipx.core

// Expr is aliased: `EnvValue` has its own `Expr` case, and inside the enum that name wins.
import zipx.workflow.{EnvName, Expr as GhaExpr, RawExpr, SecretName}

import scala.collection.immutable.ListMap

/** A value injected into a GitHub Actions job's `env:` block.
  *
  * Typed so consumers stop hand-writing `${{ secrets.X }}` strings. zipx owns *rendering* to GHA expressions; the build
  * owns *which* secrets/names. Secret *values* never appear in the model, only references.
  *
  *   - [[EnvValue.Plain]]: a literal string (region, tier, URI, …).
  *   - [[EnvValue.FromSecret]]: `${{ secrets.NAME }}` (GitHub Actions secret reference).
  *   - [[EnvValue.FromEnv]]: `${{ env.NAME }}` (reference another job/env key; rare, but useful for chaining).
  *   - [[EnvValue.Typed]]: any [[zipx.workflow.Expr]], for a value the three named cases cannot express.
  *   - [[EnvValue.Expr]]: escape hatch for a raw GHA expression.
  *
  * Prefer the smart constructors [[EnvValue.secret]], [[EnvValue.env]], [[EnvValue.plain]], [[EnvValue.typed]] (and the
  * `secret"NAME"` interpolator) over assembling cases by hand. [[EnvValue.expr]] is the last resort, and is what
  * `zipxWorkflowGenerate`'s raw-usage warning reports.
  *
  * **Nothing here can fail.** Each case holds a name that was validated when it was made, so [[render]] and [[asExpr]]
  * are total. A literal name is checked while the consumer's build compiles (`secret"PGP_PASSPHRASE"` is an `inline`
  * constructor, not a runtime call); a name that arrives as runtime data goes through a `*Make` sibling and comes back
  * as an `Either`.
  */
enum EnvValue:
  case Plain(value: String)
  case FromSecret(name: SecretName)
  case FromEnv(name: EnvName)

  /** A typed expression: `${{ github.token }}`, a `contains(…)` call, a [[zipx.workflow.Expr.Concat]] of parts. */
  case Typed(expr: GhaExpr)

  /** **Escape hatch.** A raw GHA expression. See [[zipx.workflow.RawExpr]] for what it does and does not guarantee.
    */
  case Expr(expr: RawExpr)

  /** Render to the string that lands in the workflow YAML `env:` block. */
  def render: String = asExpr.render

  /** This value as a [[zipx.workflow.Expr]].
    *
    * [[render]] goes through here, so `${{ secrets.X }}` has one definition in the codebase rather than one per layer.
    *
    * Named `asExpr` rather than `expr` because the [[EnvValue.Expr]] case already has a field of that name.
    */
  def asExpr: GhaExpr = this match
    case EnvValue.Plain(value)     => GhaExpr.Lit(value)
    case EnvValue.FromSecret(name) => GhaExpr.Secret(name)
    case EnvValue.FromEnv(name)    => GhaExpr.Env(name)
    case EnvValue.Typed(expr)      => expr
    case EnvValue.Expr(expr)       => GhaExpr.Raw(expr)
end EnvValue

object EnvValue:

  // The `inline` / `*Make` pairing the rest of the DSL uses: a literal name is checked during compilation, a runtime
  // name comes back as an `Either`. The rules live in zipx-workflow as newtypes ([[zipx.workflow.SecretName]] /
  // [[zipx.workflow.EnvName]]), and they genuinely differ: a secret may be `GITHUB_TOKEN`, an env key may not be
  // `GITHUB_`-prefixed at all.

  /** A GitHub Actions secret reference: renders as `${{ secrets.<name> }}`. Name checked at compile time. */
  inline def secret(inline name: String): EnvValue = FromSecret(SecretName(name))

  /** [[secret]] for a name that arrives as runtime data (a setting, a pack parameter). */
  def secretMake(name: String): Either[String, EnvValue] = SecretName.make(name).map(FromSecret(_))

  /** A reference to another env key: renders as `${{ env.<name> }}`. Name checked at compile time. */
  inline def env(inline name: String): EnvValue = FromEnv(EnvName(name))

  /** [[env]] for a name that arrives as runtime data. */
  def envMake(name: String): Either[String, EnvValue] = EnvName.make(name).map(FromEnv(_))

  /** A literal (non-secret) value. */
  def plain(value: String): EnvValue = Plain(value)

  /** Any [[zipx.workflow.Expr]] as an env value: the typed alternative to [[expr]]. */
  def typed(expr: GhaExpr): EnvValue = Typed(expr)

  /** The workflow's injected `GITHUB_TOKEN`, as `${{ github.token }}`. */
  val githubToken: EnvValue = Typed(GhaExpr.githubToken)

  /** **Escape hatch.** A raw expression, rendered verbatim, checked at compile time. Prefer [[typed]]; use this only
    * for an expression the [[zipx.workflow.Expr]] AST cannot build.
    */
  inline def expr(inline raw: String): EnvValue = Expr(RawExpr(raw))

  /** [[expr]] for an expression that arrives as runtime data. */
  def exprMake(raw: String): Either[String, EnvValue] = RawExpr.make(raw).map(Expr(_))

  /** Render a map to deterministic `ListMap` of strings (keys sorted) for job `env:` blocks. */
  def renderAll(m: Map[String, EnvValue]): ListMap[String, String] =
    ListMap.from(m.toList.sortBy(_._1).map((k, v) => k -> v.render))

  /** `secret"PGP_PASSPHRASE"` → [[FromSecret]], name checked at compile time.
    *
    * `inline` all the way down, which is what lets an interpolation of *compile-time-known* parts still be checked:
    * `secret"${prefix}_TOKEN"` for an `inline val prefix` is folded and validated. A name assembled from runtime data
    * cannot be, and is a compile error naming the input rather than a silent runtime check; use [[secretMake]] there.
    */
  extension (inline sc: StringContext) inline def secret(inline args: Any*): EnvValue = EnvValue.secret(sc.s(args*))

end EnvValue

/** Convenience aliases for secret references: `Secret("PGP_PASSPHRASE")` / `Secret.ref("…")`. */
object Secret:
  inline def apply(inline name: String): EnvValue = EnvValue.secret(name)
  inline def ref(inline name: String): EnvValue   = EnvValue.secret(name)
