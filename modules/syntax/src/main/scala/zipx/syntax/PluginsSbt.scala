package zipx.syntax

import zipx.core.*

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.util.SourceFile

/** `project/plugins.sbt` as Scala 3: parse with the compiler, walk untyped trees.
  *
  * Accepts the generated dialect (`addSbtPlugin`, `%`, `.excludeAll(ExclusionRule(...))`, extra parens, `//` trivia).
  * Anything else (`resolvers`, `%%`, unknown calls) is a `zipx:` error naming the tree.
  */
object PluginsSbt:

  def parse(source: String): Either[String, List[Plugin]] =
    given Context = ScalaParse.freshContext()
    // plugins.sbt is a sequence of expressions. Regular Scala 3 compilation units reject that
    // ("Illegal start of toplevel definition"), so wrap as an object body and walk that.
    ScalaParse.untyped(s"object ZipxPluginsSbt {\n$source\n}\n", "plugins.sbt").flatMap(fromTree)

  private def fromTree(tree: Tree)(using Context): Either[String, List[Plugin]] =
    statsOf(tree).flatMap(parseStats)

  private def statsOf(tree: Tree)(using Context): Either[String, List[Tree]] =
    tree match
      case PackageDef(_, stats) => statsOfAll(stats.toList)
      case Thicket(trees)       => statsOfAll(trees.toList)
      case ModuleDef(_, impl)   => Right(impl.body)
      case tmpl: Template       => Right(tmpl.body)
      case other                => Right(List(other))

  private def statsOfAll(trees: List[Tree])(using Context): Either[String, List[Tree]] =
    trees.foldLeft[Either[String, List[Tree]]](Right(Nil)) { (accE, t) =>
      accE.flatMap(acc => statsOf(t).map(acc ++ _))
    }

  private def parseStats(stats: List[Tree])(using Context): Either[String, List[Plugin]] =
    stats.filterNot(isTrivia).foldLeft[Either[String, List[Plugin]]](Right(Nil)) { (accE, stat) =>
      accE.flatMap { acc =>
        parseAddSbtPlugin(stat).map(acc :+ _)
      }
    }

  private def isTrivia(tree: Tree): Boolean =
    tree match
      case EmptyTree | _: Import | _: Export          => true
      case dd: DefDef if dd.name.toString == "<init>" => true
      case _: TypeDef | _: ValDef | _: DefDef         => true
      case _                                          => false

  private def parseAddSbtPlugin(tree: Tree)(using Context): Either[String, Plugin] =
    tree match
      case Apply(fun, List(arg)) if isIdent(fun, "addSbtPlugin") =>
        parsePluginExpr(arg)
      case InfixOp(Ident(name), Ident(op), _) if op.toString == "+=" =>
        Left(s"unexpected '${name.toString}'")
      case Apply(Select(Ident(name), op), _) if op.toString == "+=" =>
        Left(s"unexpected '${name.toString}'")
      case Ident(name) =>
        Left(s"unexpected '${name.toString}'")
      case other =>
        Left(s"unexpected '${showHead(other)}'")

  private def parsePluginExpr(tree: Tree)(using Context): Either[String, Plugin] =
    tree match
      case Parens(inner)                                                    => parsePluginExpr(inner)
      case Typed(expr, _)                                                   => parsePluginExpr(expr)
      case Apply(Select(qual, name), args) if name.toString == "excludeAll" =>
        for
          base <- parsePluginExpr(qual)
          ex   <- parseExcludes(args)
        yield base.copy(excludes = base.excludes ++ ex)
      case InfixOp(_, Ident(name), _) if name.toString == "%" || name.toString.startsWith("%") =>
        parseGav(tree)
      case Apply(Select(_, name), _) if name.toString == "%" || name.toString.startsWith("%") =>
        parseGav(tree)
      case _ =>
        parseGav(tree)

  private def parseGav(tree: Tree)(using Context): Either[String, Plugin] =
    tree match
      case InfixOp(InfixOp(_, Ident(inner), _), Ident(outer), _)
          if (inner.toString != "%" && inner.toString.contains("%")) ||
            (outer.toString != "%" && outer.toString.contains("%")) =>
        val crossed = if inner.toString != "%" then inner.toString else outer.toString
        Left(s"uses $crossed; generated plugins are %")
      case InfixOp(InfixOp(gTree, Ident(pct1), aTree), Ident(pct2), vTree)
          if pct1.toString == "%" && pct2.toString == "%" =>
        for
          g <- stringLit(gTree)
          a <- stringLit(aTree)
          v <- stringLit(vTree)
          p <- mkPlugin(g, a, v, Nil)
        yield p
      case Apply(Select(Apply(Select(gTree, pct1), List(aTree)), pct2), List(vTree))
          if pct1.toString == "%" && pct2.toString == "%" =>
        for
          g <- stringLit(gTree)
          a <- stringLit(aTree)
          v <- stringLit(vTree)
          p <- mkPlugin(g, a, v, Nil)
        yield p
      case InfixOp(_, Ident(name), _) if name.toString.startsWith("%") && name.toString != "%" =>
        Left(s"uses ${name.toString}; generated plugins are %")
      case Apply(Select(_, name), _) if name.toString.startsWith("%") && name.toString != "%" =>
        Left(s"uses ${name.toString}; generated plugins are %")
      case other =>
        Left(s"unexpected '${showHead(other)}'")

  private def parseExcludes(args: List[Tree])(using Context): Either[String, List[ZipxExclude]] =
    args.foldLeft[Either[String, List[ZipxExclude]]](Right(Nil)) { (accE, arg) =>
      accE.flatMap(acc => parseExclude(arg).map(acc :+ _))
    }

  private def parseExclude(tree: Tree)(using Context): Either[String, ZipxExclude] =
    tree match
      case Apply(fun, args) if isIdent(fun, "ExclusionRule") =>
        parseExcludeArgs(args)
      case other =>
        Left(s"expected ExclusionRule args, got '${showHead(other)}'")

  private def parseExcludeArgs(args: List[Tree])(using Context): Either[String, ZipxExclude] =
    args match
      case List(NamedArg(name, orgTree)) if name.toString == "organization" =>
        stringLit(orgTree).flatMap(org => mkExclude(org, None))
      case List(NamedArg(orgName, orgTree), NamedArg(artName, artTree))
          if orgName.toString == "organization" && (artName.toString == "name" || artName.toString == "artifact") =>
        for
          org <- stringLit(orgTree)
          art <- stringLit(artTree)
          ex  <- mkExclude(org, Some(art))
        yield ex
      case List(orgTree) =>
        stringLit(orgTree).flatMap(org => mkExclude(org, None))
      case List(orgTree, artTree) =>
        for
          org <- stringLit(orgTree)
          art <- stringLit(artTree)
          ex  <- mkExclude(org, Some(art))
        yield ex
      case other =>
        Left(s"expected ExclusionRule args, got '${other.map(showHead).mkString(", ")}'")

  private def stringLit(tree: Tree)(using Context): Either[String, String] =
    tree match
      case Literal(c) if c.tag == Constants.StringTag => Right(c.stringValue)
      case Parens(inner)                              => stringLit(inner)
      case other                                      => Left(s"expected string, got '${showHead(other)}'")

  private def isIdent(tree: Tree, expected: String): Boolean =
    tree match
      case Ident(name) => name.toString == expected
      case _           => false

  private def showHead(tree: Tree): String =
    tree match
      case Ident(name)                                        => name.toString
      case Select(_, name)                                    => name.toString
      case Apply(fun, _)                                      => showHead(fun)
      case InfixOp(left, Ident(op), _) if op.toString == "+=" => showHead(left)
      case InfixOp(_, Ident(op), _)                           => op.toString
      case Assign(lhs, _)                                     => showHead(lhs)
      case other                                              => other.toString.take(40)

  private def mkPlugin(g: String, a: String, v: String, ex: List[ZipxExclude]): Either[String, Plugin] =
    for
      gg <- GroupId.make(g)
      aa <- ArtifactId.make(a)
      vv <- DepVersion.make(v)
    yield Plugin(gg, aa, vv, ex)

  private def mkExclude(org: String, art: Option[String]): Either[String, ZipxExclude] =
    for
      g <- GroupId.make(org)
      a <- art match
        case None    => Right(None)
        case Some(s) => ArtifactId.make(s).map(Some(_))
    yield ZipxExclude(g, a)
end PluginsSbt

private[syntax] object ScalaParse:

  def freshContext(): Context =
    val base = new ContextBase
    val ctx  = base.initialCtx.fresh
      .setReporter(new StoreReporter())
      .setSetting(base.settings.Yusejavacp, true)
    given Context = ctx
    base.initialize()
    ctx

  def untyped(source: String, filename: String)(using Context): Either[String, Tree] =
    val src    = SourceFile.virtual(filename, source)
    val parser = new Parsers.Parser(src)
    val tree   = parser.parse()
    if ctx.reporter.hasErrors then
      val msgs = ctx.reporter.allErrors.map(_.msg.message).mkString("; ")
      Left(if msgs.isEmpty then s"zipx: $filename is not valid Scala 3" else msgs)
    else Right(tree)
end ScalaParse
