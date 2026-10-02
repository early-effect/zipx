package zipx.core

/** What `zipxModverBump` was asked to rewrite. The grammar lives here, not in the sbt task. */
enum ShipBumpRequest:
  /** Every row whose catalog number is already on the release registry. */
  case Released(kind: ReleaseBump)

  /** One named row, released or not. Naming a row may skip a number. */
  case One(identity: String, kind: ReleaseBump)

  def bumpKind: ReleaseBump = this match
    case Released(kind) => kind
    case One(_, kind)   => kind
end ShipBumpRequest

object ShipBumpRequest:

  /** `identities` are ship ids and group names. A single token that is one of them is that row, even when the token is
    * also a kind word (`patch`, `minor`, `major`).
    */
  def parse(tokens: List[String], identities: Set[String]): Either[ShipBumpError, ShipBumpRequest] =
    def kindOf(token: String): Either[ShipBumpError, ReleaseBump] =
      ReleaseBump.fromToken(token).toRight(ShipBumpError.UnknownKind(token))
    tokens match
      case Nil                                        => Right(Released(ReleaseBump.Patch))
      case token :: Nil if identities.contains(token) => Right(One(token, ReleaseBump.Patch))
      case token :: Nil                               =>
        ReleaseBump
          .fromToken(token)
          .map(Released(_))
          .toRight(ShipBumpError.UnknownIdentity(token, identities.toList))
      case id :: kind :: Nil =>
        if identities.contains(id) then kindOf(kind).map(One(id, _))
        else Left(ShipBumpError.UnknownIdentity(id, identities.toList))
      case more => Left(ShipBumpError.Extra(more))
    end match
  end parse
end ShipBumpRequest

enum ShipBumpError:
  case UnknownIdentity(name: String, known: List[String])
  case UnknownKind(token: String)
  case Extra(tokens: List[String])

  def message: String = this match
    case UnknownIdentity(name, known) =>
      val list = if known.isEmpty then "the catalog has no ships" else known.sorted.mkString(", ")
      s"'$name' is not a ship; this catalog has $list"
    case UnknownKind(token) => s"'$token' is not a bump kind (patch, minor, or major)"
    case Extra(tokens)      =>
      s"zipxModverBump takes a ship, a kind, or a ship and a kind; got '${tokens.mkString(" ")}'"
end ShipBumpError
