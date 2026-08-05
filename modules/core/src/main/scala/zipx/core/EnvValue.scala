package zipx.core

// Expr is aliased: `EnvValue` has its own `Expr` case, and inside the enum that name wins.
import zipx.workflow.{EnvName, Expr as GhaExpr, RawExpr, SecretName}

import scala.collection.immutable.ListMap

/** A value injected into a job's `env:` block, so a build never hand-writes `${{ secrets.X }}`. Secret *values* never
  * appear in the model, only references: zipx owns the rendering, the build owns which names.
  *
  * Every case holds a name validated at construction, so [[render]] and [[asExpr]] are total. A literal name goes
  * through an `inline` constructor and is checked while the consumer's build compiles; runtime data goes through the
  * `*Make` sibling and comes back as an `Either`.
  */
enum EnvValue:
  case Plain(value: String)
  case FromSecret(name: SecretName)
  case FromEnv(name: EnvName)

  /** Any [[zipx.workflow.Expr]], for a value the named cases cannot express. */
  case Typed(expr: GhaExpr)

  /** **Escape hatch.** See [[zipx.workflow.RawExpr]] for what it does and does not guarantee.
    */
  case Expr(expr: RawExpr)

  def render: String = asExpr.render

  /** Named `asExpr` rather than `expr` because the [[EnvValue.Expr]] case already has a field of that name. */
  def asExpr: GhaExpr = this match
    case EnvValue.Plain(value)     => GhaExpr.Lit(value)
    case EnvValue.FromSecret(name) => GhaExpr.Secret(name)
    case EnvValue.FromEnv(name)    => GhaExpr.Env(name)
    case EnvValue.Typed(expr)      => expr
    case EnvValue.Expr(expr)       => GhaExpr.Raw(expr)
end EnvValue

object EnvValue:

  // `SecretName` and `EnvName` are separate newtypes because the rules genuinely differ: a secret may be named
  // `GITHUB_TOKEN`, an env key may not be `GITHUB_`-prefixed at all.

  inline def secret(inline name: String): EnvValue = FromSecret(SecretName(name))

  def secretMake(name: String): Either[String, EnvValue] = SecretName.make(name).map(FromSecret(_))

  inline def env(inline name: String): EnvValue = FromEnv(EnvName(name))

  def envMake(name: String): Either[String, EnvValue] = EnvName.make(name).map(FromEnv(_))

  def plain(value: String): EnvValue = Plain(value)

  def typed(expr: GhaExpr): EnvValue = Typed(expr)

  val githubToken: EnvValue = Typed(GhaExpr.githubToken)

  /** **Escape hatch.** Prefer [[typed]]; use this only for an expression the [[zipx.workflow.Expr]] AST cannot build.
    */
  inline def expr(inline raw: String): EnvValue = Expr(RawExpr(raw))

  def exprMake(raw: String): Either[String, EnvValue] = RawExpr.make(raw).map(Expr(_))

  /** Keys sorted, so a `Map` still renders deterministically. */
  def renderAll(m: Map[String, EnvValue]): ListMap[String, String] =
    ListMap.from(m.toList.sortBy(_._1).map((k, v) => k -> v.render))

  /** `secret"PGP_PASSPHRASE"`. `inline` all the way down, so an interpolation of *compile-time-known* parts is still
    * checked: `secret"${prefix}_TOKEN"` for an `inline val prefix` is folded and validated. A name assembled from
    * runtime data is a compile error naming the input rather than a silent runtime check; use [[secretMake]] there.
    */
  extension (inline sc: StringContext) inline def secret(inline args: Any*): EnvValue = EnvValue.secret(sc.s(args*))

end EnvValue

object Secret:
  inline def apply(inline name: String): EnvValue = EnvValue.secret(name)
  inline def ref(inline name: String): EnvValue   = EnvValue.secret(name)
