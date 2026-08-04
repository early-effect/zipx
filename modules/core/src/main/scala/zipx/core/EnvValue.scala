package zipx.core

import neotype.unwrap

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
  *   - [[EnvValue.Expr]]: escape hatch for a raw GHA expression the other variants can't express.
  *
  * Prefer the smart constructors [[EnvValue.secret]], [[EnvValue.env]], [[EnvValue.plain]], [[EnvValue.expr]] (and the
  * `secret"NAME"` interpolator) over assembling cases by hand; constructors validate names.
  */
enum EnvValue:
  case Plain(value: String)
  case FromSecret(name: String)
  case FromEnv(name: String)
  case Expr(expr: String)

  /** Render to the string that lands in the workflow YAML `env:` block. */
  def render: String = asExpr.render

  /** This value as a [[zipx.workflow.Expr]].
    *
    * [[render]] goes through here, so `${{ secrets.X }}` has one definition in the codebase rather than one per layer.
    *
    * Names are re-validated on the way through rather than trusted: the constructors below check, but `EnvValue` is a
    * public `enum`, so `EnvValue.FromSecret("bad name")` is constructible by a caller who skips them.
    *
    * Named `asExpr` rather than `expr` because the [[EnvValue.Expr]] case already has a field of that name.
    */
  def asExpr: GhaExpr = this match
    case EnvValue.Plain(value)     => GhaExpr.Lit(value)
    case EnvValue.FromSecret(name) => GhaExpr.Secret(SecretName.makeOrThrow(name))
    case EnvValue.FromEnv(name)    => GhaExpr.Env(EnvName.makeOrThrow(name))
    case EnvValue.Expr(expr)       => GhaExpr.Raw(RawExpr.makeOrThrow(expr))
end EnvValue

object EnvValue:

  /** Validate a GitHub Actions identifier used as a secret or env name.
    *
    * Delegates to [[zipx.workflow.SecretName]] / [[zipx.workflow.EnvName]], which hold the rule; this keeps the
    * throwing signature callers depend on. `kind` selects the rule as well as labelling the error, since the two
    * differ: a secret may be `GITHUB_TOKEN`, an env name may not be `GITHUB_`-prefixed at all.
    */
  def requireName(kind: String, name: String): String =
    val checked = if kind == "secret" then SecretName.make(name).map(_.unwrap) else EnvName.make(name).map(_.unwrap)
    checked match
      case Right(value) => value
      case Left(error)  => throw IllegalArgumentException(error)

  /** A GitHub Actions secret reference: renders as `${{ secrets.<name> }}`. */
  def secret(name: String): EnvValue = FromSecret(requireName("secret", name))

  /** A reference to another env key: renders as `${{ env.<name> }}`. */
  def env(name: String): EnvValue = FromEnv(requireName("env", name))

  /** A literal (non-secret) value. */
  def plain(value: String): EnvValue = Plain(value)

  /** Escape hatch: a raw expression string, rendered verbatim. Use sparingly. */
  def expr(raw: String): EnvValue = Expr(raw)

  /** Render a map to deterministic `ListMap` of strings (keys sorted) for job `env:` blocks. */
  def renderAll(m: Map[String, EnvValue]): ListMap[String, String] =
    ListMap.from(m.toList.sortBy(_._1).map((k, v) => k -> v.render))

  /** `secret"PGP_PASSPHRASE"` → [[FromSecret]]. Rejects interpolated forms that fail [[requireName]]. */
  extension (sc: StringContext) def secret(args: Any*): EnvValue = EnvValue.secret(sc.s(args*))

end EnvValue

/** Convenience aliases for secret references: `Secret("PGP_PASSPHRASE")` / `Secret.ref("…")`. */
object Secret:
  def apply(name: String): EnvValue = EnvValue.secret(name)
  def ref(name: String): EnvValue   = EnvValue.secret(name)
