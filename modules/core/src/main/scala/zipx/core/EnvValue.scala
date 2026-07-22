package zipx.core

import scala.collection.immutable.ListMap

/** A value injected into a GitHub Actions job's `env:` block.
  *
  * Typed so consumers stop hand-writing `${{ secrets.X }}` strings. zipx owns *rendering* to GHA expressions; the build
  * owns *which* secrets/names. Secret *values* never appear in the model — only references.
  *
  *   - [[EnvValue.Plain]] — a literal string (region, tier, URI, …).
  *   - [[EnvValue.FromSecret]] — `${{ secrets.NAME }}` (GitHub Actions secret reference).
  *   - [[EnvValue.FromEnv]] — `${{ env.NAME }}` (reference another job/env key; rare, but useful for chaining).
  *   - [[EnvValue.Expr]] — escape hatch for a raw GHA expression the other variants can't express.
  *
  * Prefer the smart constructors [[EnvValue.secret]], [[EnvValue.env]], [[EnvValue.plain]], [[EnvValue.expr]] (and the
  * `secret"NAME"` interpolator) over assembling cases by hand — constructors validate names.
  */
enum EnvValue:
  case Plain(value: String)
  case FromSecret(name: String)
  case FromEnv(name: String)
  case Expr(expr: String)

  /** Render to the string that lands in the workflow YAML `env:` block. */
  def render: String = this match
    case EnvValue.Plain(value)     => value
    case EnvValue.FromSecret(name) => s"$${{ secrets.$name }}"
    case EnvValue.FromEnv(name)    => s"$${{ env.$name }}"
    case EnvValue.Expr(expr)       => expr
end EnvValue

object EnvValue:

  /** GitHub Actions secret / env names: start with a letter or underscore, then alphanumerics and underscores. Rejects
    * empty, spaces, `${{`, hyphens-at-start, and other characters that would produce broken or surprising YAML.
    */
  private val NamePattern = raw"[A-Za-z_][A-Za-z0-9_]*".r

  /** Validate a GitHub Actions identifier used as a secret or env name. */
  def requireName(kind: String, name: String): String =
    if name.isEmpty then throw IllegalArgumentException(s"$kind name must be non-empty")
    if NamePattern.matches(name) then name
    else
      throw IllegalArgumentException(
        s"invalid $kind name '$name': must match ${NamePattern.regex} (letters, digits, underscore; no spaces or expressions)"
      )

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

/** Convenience aliases for secret references — `Secret("PGP_PASSPHRASE")` / `Secret.ref("…")`. */
object Secret:
  def apply(name: String): EnvValue = EnvValue.secret(name)
  def ref(name: String): EnvValue   = EnvValue.secret(name)
