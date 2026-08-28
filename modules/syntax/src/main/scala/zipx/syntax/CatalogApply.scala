package zipx.syntax

import zipx.core.*

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.*

/** Rewrite catalog constructors at tree spans. No `String.replace` of the whole file. */
object CatalogApply:

  def applyBumps(source: String, bumps: List[DepBump]): Either[String, String] =
    if bumps.isEmpty then Right(source)
    else
      given Context = ScalaParse.freshContext()
      ScalaParse.untyped(source, "ZipxVersions.scala").map { tree =>
        val edits = bumps.flatMap { bump =>
          findCtor(tree, bump.ctor, List(bump.group, bump.artifact, bump.from)).map { lit =>
            spanOf(lit) -> quote(bump.to)
          }
        }
        applyEdits(source, edits)
      }

  def applyActionBumps(source: String, bumps: List[ActionBump]): Either[String, String] =
    bumps.foldLeft[Either[String, String]](Right(source)) { (accE, bump) =>
      accE.flatMap { src =>
        given Context = ScalaParse.freshContext()
        ScalaParse.untyped(src, "ZipxVersions.scala").flatMap { tree =>
          val from = ZipxCatalog.actionConstructor(bump.action)
          bump.action.bumped(bump.toVersion, bump.toSha).flatMap { next =>
            val to = ZipxCatalog.actionConstructor(next)
            findCtor(tree, "Action", List(bump.action.name, bump.action.version, bump.action.sha: String))
              .map(_ => src.replace(from, to))
              .toRight(
                s"zipx: catalog has no Action constructor for '${bump.action.name}' ${bump.action.version}. " +
                  s"Paste $from into project/ZipxVersions.scala, then sbt \"zipxActionUpdate yes\"."
              )
          }
        }
      }
    }

  def applyPinBumps(source: String, bumps: List[PinBump]): Either[String, String] =
    ZipxCatalog.applyPinBumps(source, bumps)

  /** Rewrite the version literal of `Ship(` / `ShipGroup(` by tree span. Missing constructor is Left. */
  def applyShipBumps(source: String, bumps: List[ShipBump]): Either[String, String] =
    bumps.foldLeft[Either[String, String]](Right(source)) { (accE, bump) =>
      accE.flatMap(src => applyOneShipBump(src, bump))
    }

  private def applyOneShipBump(src: String, bump: ShipBump): Either[String, String] =
    if bump.to.endsWith("-ci") then Left(s"zipx: zipxModverBump must not write a -ci suffix (got '${bump.to}')")
    else
      given Context = ScalaParse.freshContext()
      ScalaParse.untyped(src, "ZipxVersions.scala").flatMap { tree =>
        findShipVersionLit(tree, bump.identity)
          .map { lit =>
            val (start, end) = spanOf(lit)
            applyEdits(src, List((start, end) -> quote(bump.to)))
          }
          .toRight(s"zipx: catalog has no Ship / ShipGroup constructor for '${bump.identity}' ${bump.from}")
      }

  private def findShipVersionLit(tree: Tree, identity: String)(using Context): Option[Tree] =
    var found: Option[Tree] = None
    def go(t: Tree): Unit   =
      if found.isDefined then ()
      else
        t match
          case Apply(Apply(fun, args1), args2)
              if isCtor(fun, "ShipGroup") && lits(args1).headOption.contains(identity) =>
            found = versionLit(args1)
            args2.foreach(go)
          case Apply(fun, applyArgs) if isCtor(fun, "Ship") && lits(applyArgs).headOption.contains(identity) =>
            found = versionLit(applyArgs)
          case Apply(fun, applyArgs) =>
            go(fun)
            applyArgs.foreach(go)
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
    found
  end findShipVersionLit

  private def versionLit(args: List[Tree]): Option[Tree] =
    val stringArgs = args.collect {
      case lit @ Literal(c) if c.tag == Constants.StringTag              => lit
      case NamedArg(_, lit @ Literal(c)) if c.tag == Constants.StringTag => lit
    }
    stringArgs.lift(1)

  private def findCtor(tree: Tree, ctor: String, args: List[String])(using Context): Option[Tree] =
    var found: Option[Tree] = None
    def go(t: Tree): Unit   =
      if found.isDefined then ()
      else
        t match
          case Apply(fun, applyArgs) if isCtor(fun, ctor) && lits(applyArgs) == args =>
            found = applyArgs.lift(args.length - 1)
          case Apply(fun, applyArgs) =>
            go(fun)
            applyArgs.foreach(go)
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
    found
  end findCtor

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

  private def spanOf(tree: Tree): (Int, Int) =
    val s = tree.span
    (s.start, s.end)

  private def quote(value: String): String = "\"" + value + "\""

  private def applyEdits(source: String, edits: List[((Int, Int), String)]): String =
    val ordered = edits.sortBy(-_._1._1)
    ordered.foldLeft(source) { case (src, ((start, end), repl)) =>
      if start < 0 || end > src.length || start > end then src
      else src.substring(0, start) + repl + src.substring(end)
    }
end CatalogApply
