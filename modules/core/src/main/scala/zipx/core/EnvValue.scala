package zipx.core

// Expr is aliased: `EnvValue` has its own `Expr` case, and inside the enum that name wins.
import zipx.workflow.{EnvName, Expr as GhaExpr, RawExpr, SecretName}

import scala.collection.immutable.ListMap

/** A value injected into a job's `env:` block, so a build never hand-writes `${{ secrets.X }}`. Secret *values* never
  * appear in the model, only references.
  */
enum EnvValue:

  /** The one case with unconstrained content: GitHub accepts a multi-line env value (a PEM, a JSON blob) as a block
    * scalar.
    */
  case Plain(value: String)
  case FromSecret(name: SecretName)
  case FromEnv(name: EnvName)

  /** For a value the named cases cannot express. */
  case Typed(expr: GhaExpr)

  /** **Escape hatch.** See [[zipx.workflow.RawExpr]] for what it does and does not guarantee.
    */
  case Expr(expr: RawExpr)

  def render: String = textOrExpr.fold(identity, _.render)

  /** `None` for [[EnvValue.Plain]], whose text can hold more than a [[zipx.workflow.Expr.Lit]] can. */
  def asExpr: Option[GhaExpr] = textOrExpr.toOption

  private def textOrExpr: Either[String, GhaExpr] = this match
    case EnvValue.Plain(value)     => Left(value)
    case EnvValue.FromSecret(name) => Right(GhaExpr.Secret(name))
    case EnvValue.FromEnv(name)    => Right(GhaExpr.Env(name))
    case EnvValue.Typed(expr)      => Right(expr)
    case EnvValue.Expr(expr)       => Right(GhaExpr.Raw(expr))
end EnvValue

object EnvValue:

  // A secret may be named `GITHUB_TOKEN`; an env key may not be `GITHUB_`-prefixed at all.

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

  def renderAll(m: Map[String, EnvValue]): ListMap[String, String] =
    ListMap.from(m.toList.sortBy(_._1).map((k, v) => k -> v.render))

  /** `secret"PGP_PASSPHRASE"`. Parts known at compile time (an `inline val prefix`) are folded and validated; a name
    * built from runtime data is a compile error, so use [[secretMake]] there.
    */
  extension (inline sc: StringContext) inline def secret(inline args: Any*): EnvValue = EnvValue.secret(sc.s(args*))

end EnvValue

object Secret:
  inline def apply(inline name: String): EnvValue = EnvValue.secret(name)
  inline def ref(inline name: String): EnvValue   = EnvValue.secret(name)
