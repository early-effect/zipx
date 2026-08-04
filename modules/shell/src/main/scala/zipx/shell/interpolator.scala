package zipx.shell

/** The `sh"…"` interpolator: literal text with typed [[Word]] splices.
  *
  * The splice type is the point. `args: Word*` means a bare `String` splice does not compile, so the one thing this
  * module exists to prevent cannot come back in through an interpolator:
  *
  * {{{
  * val tag = Word.vq("TAG")
  * sh"refs/tags/$tag"        // Word: Cat(Lit("refs/tags/"), Dquote(VarRef(TAG)))
  * sh"refs/tags/${userInput}" // does not compile when userInput is a String
  * }}}
  *
  * There is deliberately no implicit `String => Word` conversion: it would reopen the hole. Wrap a string explicitly
  * with [[Word.lit]] (compile-time checked) or [[Word.litMake]] (`Either`, for runtime input).
  *
  * This builds a *word*, not a command: it concatenates, it does not parse shell syntax. Use [[Exec]] and the
  * [[Command]] cases for structure, so `|`, `&&`, redirects and conditionals stay typed rather than becoming text.
  */
extension (sc: StringContext)

  /** Concatenate literal parts and [[Word]] splices into one [[Word]].
    *
    * Literal parts go through [[ShText]], which throws on the spot if a part carries a newline or a control character.
    * The parts are source literals, so such a failure is deterministic and lands at generate time with the validator's
    * message, the same contract as `Script.Ctx.line`. Nothing here can fail on the splices: they are already [[Word]]s.
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
