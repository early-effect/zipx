package zipx.shell

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
extension (sc: StringContext)

  /** Concatenate literal parts and [[Word]] splices into one [[Word]].
    *
    * Literal parts go through [[ShText]], which throws if a part carries a newline or a control character. They are
    * source literals, so such a failure is deterministic, the same contract as `Script.Ctx.line`.
    */
  def sh(args: Word*): Word =
    val parts   = sc.parts.iterator
    val splices = args.iterator
    val out     = List.newBuilder[Word]
    while parts.hasNext do
      val part = parts.next()
      if part.nonEmpty then out += Word.Lit(ShText.makeOrThrow(part))
      if splices.hasNext then out += splices.next()
    Word.Cat(out.result())
end extension
