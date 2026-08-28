package zipx.syntax

import zipx.core.*

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.*

/** Constructors visible in a catalog source file. Used when the CLI cannot classload the catalog object. */
final case class CatalogConstructors(
    libs: List[Lib],
    plugins: List[Plugin],
    actions: List[Action],
    ships: List[PublishedRow],
    sbt: Option[SbtVersion],
    scala: Option[ScalaVersion],
):
  def coords: List[ZipxCoord] = libs ++ plugins
end CatalogConstructors

object CatalogSource:

  def parse(source: String, filename: String = "ZipxVersions.scala"): Either[String, CatalogConstructors] =
    given Context = ScalaParse.freshContext()
    ScalaParse.untyped(source, filename).map(fromTree)

  private def fromTree(tree: Tree)(using Context): CatalogConstructors =
    val libs    = List.newBuilder[Lib]
    val plugins = List.newBuilder[Plugin]
    val actions = List.newBuilder[Action]
    val ships   = List.newBuilder[PublishedRow]
    var sbt     = Option.empty[SbtVersion]
    var scala   = Option.empty[ScalaVersion]

    def go(t: Tree): Unit =
      t match
        case Apply(Apply(fun, args1), args2) if isCtor(fun, "ShipGroup") =>
          (lits(args1), memberLits(args2)) match
            case (n :: v :: _, members) => mkGroup(n, v, members).foreach(ships += _)
            case _                      => ()
          args1.foreach(go)
          args2.foreach(go)
        case Apply(fun, args) if isCtor(fun, "Ship") =>
          lits(args) match
            case id :: v :: _ => mkShip(id, v).foreach(ships += _)
            case _            => ()
          args.foreach(go)
        case Apply(fun, args) if isCtor(fun, "Lib") =>
          lits(args) match
            case g :: a :: v :: _ => mkLib(g, a, v).foreach(libs += _)
            case _                => ()
          args.foreach(go)
        case Apply(fun, args) if isCtor(fun, "Plugin") =>
          lits(args) match
            case g :: a :: v :: _ => mkPlugin(g, a, v).foreach(plugins += _)
            case _                => ()
          args.foreach(go)
        case Apply(fun, args) if isCtor(fun, "Action") =>
          named(args) match
            case (Some(name), Some(ver), Some(sha)) => Action.make(name, ver, sha).foreach(actions += _)
            case _                                  =>
              lits(args) match
                case name :: ver :: sha :: _ => Action.make(name, ver, sha).foreach(actions += _)
                case _                       => ()
          args.foreach(go)
        case Apply(fun, args) if isCtor(fun, "SbtVersion") =>
          lits(args).headOption.flatMap(SbtVersion.make(_).toOption).foreach(v => sbt = Some(v))
        case Apply(fun, args) if isCtor(fun, "ScalaVersion") =>
          lits(args).headOption.flatMap(ScalaVersion.make(_).toOption).foreach(v => scala = Some(v))
        case Apply(fun, args) =>
          go(fun)
          args.foreach(go)
        case Select(qual, _)      => go(qual)
        case PackageDef(_, stats) => stats.foreach(go)
        case TypeDef(_, rhs)      =>
          rhs match
            case tmpl: Template => tmpl.body.foreach(go)
            case other          => go(other)
        case ModuleDef(_, impl) => impl.body.foreach(go)
        case vd: ValDef         => go(vd.rhs)
        case dd: DefDef         => go(dd.rhs)
        case Block(stats, expr) =>
          stats.foreach(go)
          go(expr)
        case Thicket(trees)   => trees.foreach(go)
        case Parens(inner)    => go(inner)
        case Typed(expr, _)   => go(expr)
        case NamedArg(_, arg) => go(arg)
        case _                => ()
    go(tree)
    CatalogConstructors(libs.result(), plugins.result(), actions.result(), ships.result(), sbt, scala)
  end fromTree

  private def isCtor(tree: Tree, ctor: String): Boolean =
    tree match
      case Ident(name)         => name.toString == ctor
      case Select(New(tpt), _) => isCtor(tpt, ctor)
      case Select(qual, name)  => name.toString == ctor || isCtor(qual, ctor)
      case New(tpt)            => isCtor(tpt, ctor)
      case _                   => false

  private def lits(args: List[Tree]): List[String] =
    args.flatMap {
      case Literal(c) if c.tag == Constants.StringTag              => Some(c.stringValue)
      case NamedArg(_, Literal(c)) if c.tag == Constants.StringTag => Some(c.stringValue)
      case _                                                       => None
    }

  private def named(args: List[Tree]): (Option[String], Option[String], Option[String]) =
    val byName = args.collect {
      case NamedArg(name, Literal(c)) if c.tag == Constants.StringTag => name.toString -> c.stringValue
    }.toMap
    val pos = lits(args)
    (
      byName.get("name").orElse(pos.headOption),
      byName.get("version").orElse(pos.lift(1)),
      byName.get("sha").orElse(pos.lift(2)),
    )
  end named

  private def mkLib(g: String, a: String, v: String): Option[Lib] =
    for
      gg <- GroupId.make(g).toOption
      aa <- ArtifactId.make(a).toOption
      vv <- DepVersion.make(v).toOption
    yield Lib(gg, aa, vv, Cross.Binary, None, Nil)

  private def mkPlugin(g: String, a: String, v: String): Option[Plugin] =
    for
      gg <- GroupId.make(g).toOption
      aa <- ArtifactId.make(a).toOption
      vv <- DepVersion.make(v).toOption
    yield Plugin(gg, aa, vv, Nil)

  private def mkShip(id: String, v: String): Option[Ship] =
    for
      mid <- ModuleId.make(id).toOption
      ver <- DepVersion.make(v).toOption
    yield Ship(mid, ver)

  private def mkGroup(n: String, v: String, members: List[String]): Option[ShipGroup] =
    for
      name <- ShipGroupName.make(n).toOption
      ver  <- DepVersion.make(v).toOption
      ids  <- memberIds(members)
    yield ShipGroup(name, ver, ids)

  private def memberIds(members: List[String]): Option[List[ModuleId]] =
    members.foldLeft(Option(List.empty[ModuleId])) { (acc, m) =>
      for
        ys <- acc
        id <- ModuleId.make(m).toOption
      yield ys :+ id
    }

  /** Varargs of a curried `ShipGroup(...)(...)` may be raw string lits, `Typed`, or `Seq(...)`. */
  private def memberLits(args: List[Tree]): List[String] =
    args match
      case Typed(inner, _) :: Nil                     => memberLits(List(inner))
      case Apply(fun, inner) :: Nil if isSeqLike(fun) => lits(inner)
      case _                                          => lits(args)

  private def isSeqLike(tree: Tree): Boolean =
    tree match
      case Ident(name) =>
        val n = name.toString
        n == "Seq" || n == "List" || n == "s" || n.contains("Seq")
      case Select(_, name) =>
        val n = name.toString
        n == "apply" || n == "Seq" || n.contains("Seq")
      case Apply(fun, _) => isSeqLike(fun)
      case _             => false
end CatalogSource
