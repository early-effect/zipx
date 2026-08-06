package zipx.shell

import scala.quoted.*

/** The `sh"…"` interpolator: literal text with typed [[Word]] splices.
  *
  * `args: Word*` means a bare `String` splice does not compile, and there is no implicit `String => Word` conversion.
  * Wrap a string with [[Word.lit]] (compile-time checked) or [[Word.litMake]] (`Either`, for runtime input).
  *
  * {{{
  * val tag = Word.vq("TAG")
  * sh"refs/tags/$tag"         // Cat(Lit("refs/tags/"), Dquote(VarRef(TAG)))
  * sh"refs/tags/${userInput}" // does not compile when userInput is a String
  * }}}
  *
  * This builds a *word*, not a command: it concatenates, it does not parse shell syntax. Use [[Exec]] and the
  * [[Command]] cases for structure.
  */
extension (inline sc: StringContext)

  /** Concatenate literal parts and [[Word]] splices into one [[Word]].
    *
    * A macro, so the literal parts go through [[ShText]] while the interpolation is being compiled: a part carrying a
    * newline or a control character is a compile error naming the part, not a runtime failure. Every splice is already
    * a [[Word]], so nothing is left to check when this runs.
    *
    * Parts arrive raw, as they do in every interpolator: `sh"a\nb"` holds the two-character escape and is one line, so
    * the newline case needs a `sh"""…"""` that actually spans lines.
    */
  inline def sh(inline args: Word*): Word =
    ${ shMacro('sc, 'args) }

end extension

/** Interleave the parts and splices, validating each part as [[ShText]] here rather than in generated code.
  *
  * Callable at expansion time because `ShText` is compiled in an earlier run than any call site: a macro cannot be used
  * in the compilation run that defines it, which is why nothing in `zipx-shell`'s own sources writes `sh"…"`.
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
  // `Word.lit` and not `Word.Lit(ShText.unsafeMake(…))`: the part is a constant, so the ordinary checked constructor
  // validates it a second time as this expansion inlines, and the generated tree names no unsafe entry point.
  val words: Seq[Expr[Word]] =
    parts
      .map[Option[Expr[Word]]](part => Option.when(part.nonEmpty)('{ Word.lit(${ Expr(part) }) }))
      .zipAll(splices.map(Some(_)), None, None)
      .flatMap((part, splice) => part.toList ++ splice.toList)
  '{ Word.Cat(${ Expr.ofList(words.toList) }) }
end shMacro
