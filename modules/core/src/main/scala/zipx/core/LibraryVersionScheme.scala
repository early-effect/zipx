package zipx.core

/** A scheme sbt will stamp on a POM. `early-semver` is zipx's default. The other four are the rest of what sbt accepts.
  * `semver` is not one of them: sbt rejects it as ambiguous.
  *
  * [[binaryBreak]] is the smallest bump that makes a binary break visible to that scheme's eviction check, matching
  * `VersionNumber` in sbt: early-semver breaks on the minor while `0.y` and on the major after; semver-spec breaks on
  * any change while `0.y` and on the major after; pvp breaks on the second component; strict and always treat a patch
  * as enough, because strict is incompatible with every other version and always is compatible with all of them.
  */
enum LibraryVersionScheme:
  case EarlySemVer
  case SemVerSpec
  case Pvp
  case Strict
  case Always

  def token: String = this match
    case LibraryVersionScheme.EarlySemVer => "early-semver"
    case LibraryVersionScheme.SemVerSpec  => "semver-spec"
    case LibraryVersionScheme.Pvp         => "pvp"
    case LibraryVersionScheme.Strict      => "strict"
    case LibraryVersionScheme.Always      => "always"

  def binaryBreak(version: ReleaseVersion): BumpKind = this match
    case LibraryVersionScheme.EarlySemVer =>
      if version.isInitialDevelopment then BumpKind.Minor else BumpKind.Major
    case LibraryVersionScheme.SemVerSpec =>
      if version.isInitialDevelopment then BumpKind.Patch else BumpKind.Major
    case LibraryVersionScheme.Pvp    => BumpKind.Minor
    case LibraryVersionScheme.Strict => BumpKind.Patch
    case LibraryVersionScheme.Always => BumpKind.Patch
end LibraryVersionScheme

object LibraryVersionScheme:
  val Default: LibraryVersionScheme = EarlySemVer

  val tokens: List[String] = LibraryVersionScheme.values.toList.map(_.token)

  def parse(raw: String): Either[LibraryVersionSchemeError, LibraryVersionScheme] =
    raw.trim.toLowerCase match
      case ""             => Left(LibraryVersionSchemeError.Missing)
      case "early-semver" => Right(EarlySemVer)
      case "semver-spec"  => Right(SemVerSpec)
      case "pvp"          => Right(Pvp)
      case "strict"       => Right(Strict)
      case "always"       => Right(Always)
      case "semver"       => Left(LibraryVersionSchemeError.AmbiguousSemver)
      case other          => Left(LibraryVersionSchemeError.Unknown(other))
end LibraryVersionScheme

enum LibraryVersionSchemeError:
  case Missing
  case AmbiguousSemver
  case Unknown(token: String)

  def message: String = this match
    case LibraryVersionSchemeError.Missing =>
      s"versionScheme is empty. zipx sets ${LibraryVersionScheme.Default.token}. Set ThisBuild / versionScheme to ${LibraryVersionScheme.tokens.mkString(", ")}."
    case LibraryVersionSchemeError.AmbiguousSemver =>
      "'semver' is ambiguous. Use early-semver or semver-spec."
    case LibraryVersionSchemeError.Unknown(token) =>
      s"'$token' is not a version scheme. Use ${LibraryVersionScheme.tokens.mkString(", ")}."
end LibraryVersionSchemeError
