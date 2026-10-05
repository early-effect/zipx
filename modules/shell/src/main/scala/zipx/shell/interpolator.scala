package zipx.shell

import scala.quoted.*

/** Builds a *word*, not a command: it concatenates and does not parse shell syntax. A `String` splice does not compile;
  * wrap it with [[Word.lit]] or [[Word.litMake]].
  */
extension (inline sc: StringContext)

  /** Literal parts are checked as [[ShText]] at compile time. Parts arrive raw: `sh"a\nb"` holds the two-character
    * escape and is one line, so only a `sh"""…"""` that actually spans lines hits the newline check.
    */
  inline def sh(inline args: Word*): Word =
    ${ shMacro('sc, 'args) }

end extension

/** A macro cannot be used in the compilation run that defines it, which is why nothing in `zipx-shell`'s own sources
  * writes `sh"…"`.
  */
private def shMacro(sc: Expr[StringContext], args: Expr[Seq[Word]])(using Quotes): Expr[Word] =
  import quotes.reflect.*
  val parts: Seq[String] = sc match
    case '{ StringContext(${ Varargs(exprs) }*) } =>
      exprs.map(e => e.value.getOrElse(report.errorAndAbort("sh\"…\" requires literal text parts", e)))
    case _ => report.errorAndAbort("sh\"…\" requires a literal interpolation", sc)
  val splices: Seq[Expr[Word]] = args match
    case Varargs(exprs) => exprs
    case _              => report.errorAndAbort("sh\"…\" requires literal splices", args)
  parts.foreach { part =>
    ShText.make(part).left.foreach(error => report.errorAndAbort(s"""invalid sh"…" text "$part": $error""", sc))
  }
  // `Word.lit`, not `Word.Lit(ShText.unsafeMake(…))`: the part is a constant, so the checked constructor revalidates
  // it as this expansion inlines and the generated tree names no unsafe entry point.
  val words: Seq[Expr[Word]] =
    parts
      .map[Option[Expr[Word]]](part => Option.when(part.nonEmpty)('{ Word.lit(${ Expr(part) }) }))
      .zipAll(splices.map(Some(_)), None, None)
      .flatMap((part, splice) => part.toList ++ splice.toList)
  '{ Word.Cat(${ Expr.ofList(words.toList) }) }
end shMacro
