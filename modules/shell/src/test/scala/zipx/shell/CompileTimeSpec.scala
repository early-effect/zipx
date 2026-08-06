package zipx.shell

import zio.test.*

object CompileTimeSpec extends ZIOSpecDefault:

  def spec = suite("compile-time validation")(
    test("a valid literal compiles") {
      for
        varName <- typeCheck("""VarName("GITHUB_OUTPUT")""")
        text    <- typeCheck("""ShText("echo hi")""")
        line    <- typeCheck("""ScriptLine("  indented")""")
        glob    <- typeCheck("""GlobPattern("refs/tags/v*")""")
        program <- typeCheck("""ProgramName("git")""")
        tag     <- typeCheck("""HeredocTag("EOF")""")
        code    <- typeCheck("""ExitCode(0)""")
        fd      <- typeCheck("""FileDescriptor(2)""")
      yield assertTrue(
        varName.isRight,
        text.isRight,
        line.isRight,
        glob.isRight,
        program.isRight,
        tag.isRight,
        code.isRight,
        fd.isRight,
      )
    },
    test("an invalid VarName literal does not compile") {
      for
        dashed <- typeCheck("""VarName("has-dash")""")
        digit  <- typeCheck("""VarName("1LEADING")""")
        empty  <- typeCheck("""VarName("")""")
        spaced <- typeCheck("""VarName("has space")""")
      yield assertTrue(dashed.isLeft, digit.isLeft, empty.isLeft, spaced.isLeft)
    },
    test("an invalid ScriptLine literal does not compile") {
      for
        newline    <- typeCheck("""ScriptLine("two\nlines")""")
        leadingTab <- typeCheck("""ScriptLine("\tindented")""")
        cr         <- typeCheck("""ScriptLine("has\rcr")""")
      yield assertTrue(newline.isLeft, leadingTab.isLeft, cr.isLeft)
    },
    test("an invalid ShText literal does not compile") {
      for
        newline <- typeCheck("""ShText("two\nlines")""")
        cr      <- typeCheck("""ShText("has\rcr")""")
      yield assertTrue(newline.isLeft, cr.isLeft)
    },
    test("quoting rules are enforced on literals") {
      for
        squote <- typeCheck("""SquoteText("it's")""")
        brace  <- typeCheck("""ParamText("closes}early")""")
      yield assertTrue(squote.isLeft, brace.isLeft)
    },
    test("an invalid GlobPattern literal does not compile") {
      for
        spaced <- typeCheck("""GlobPattern("has space")""")
        quoted <- typeCheck("""GlobPattern("has'quote")""")
        empty  <- typeCheck("""GlobPattern("")""")
      yield assertTrue(spaced.isLeft, quoted.isLeft, empty.isLeft)
    },
    test("an invalid ProgramName literal does not compile") {
      for
        spaced <- typeCheck("""ProgramName("two words")""")
        subst  <- typeCheck("""ProgramName("sub$(cmd)")""")
        piped  <- typeCheck("""ProgramName("a|b")""")
      yield assertTrue(spaced.isLeft, subst.isLeft, piped.isLeft)
    },
    test("out-of-range Int literals do not compile") {
      for
        negative <- typeCheck("""ExitCode(-1)""")
        tooBig   <- typeCheck("""ExitCode(256)""")
        badFd    <- typeCheck("""FileDescriptor(10)""")
      yield assertTrue(negative.isLeft, tooBig.isLeft, badFd.isLeft)
    },
    test("smart constructors forward literals into the compile-time check") {
      for
        badVar    <- typeCheck("""Word.v("has-dash")""")
        badLit    <- typeCheck("""Word.lit("two\nlines")""")
        badSquote <- typeCheck("""Word.squote("it's")""")
        badExec   <- typeCheck("""Exec("two words")""")
        badGlob   <- typeCheck("""ShTest.varMatches("ref", "has space")""")
        goodVar   <- typeCheck("""Word.v("GITHUB_OUTPUT")""")
        goodExec  <- typeCheck("""Exec("git", Word.lit("status"))""")
      yield assertTrue(
        badVar.isLeft,
        badLit.isLeft,
        badSquote.isLeft,
        badExec.isLeft,
        badGlob.isLeft,
        goodVar.isRight,
        goodExec.isRight,
      )
    },
    test("the compile error carries the validator's message, not a generic type error") {
      for dashed <- typeCheck("""VarName("has-dash")""")
      yield assertTrue(dashed.swap.exists(_.contains("invalid shell variable name")))
    },
    test("sh\"…\" rejects a String splice, which is the reason it takes Word*") {
      for
        stringSplice <- typeCheck("""val s = "user input"; sh"echo $s"""")
        intSplice    <- typeCheck("""val n = 3; sh"echo $n"""")
        wordSplice   <- typeCheck("""sh"echo ${Word.vq("TAG")}"""")
        litWrapped   <- typeCheck("""val s = "user input"; sh"echo ${Word.lit("safe")}"""")
      yield assertTrue(
        stringSplice.isLeft,
        intSplice.isLeft,
        wordSplice.isRight,
        litWrapped.isRight,
      )
    },
    test("sh\"…\" validates its literal parts while the interpolation compiles") {
      // Spelled as the desugared call rather than as `sh"…"`, because interpolator parts arrive *raw*: a `\n` written
      // in `sh"a\nb"` stays the two-character escape, exactly as it does in `s"…"`. A part is genuinely two lines only
      // when the literal spans lines or, as here, when the `StringContext` is built by hand. That is the last thing
      // left to reject, since every splice is already a `Word`.
      for
        multiLine <- typeCheck("""StringContext("first\nsecond ", "").sh(Word.vq("TAG"))""")
        oneLine   <- typeCheck("""StringContext("first second ", "").sh(Word.vq("TAG"))""")
      yield assertTrue(
        multiLine.swap.exists(_.contains("must not contain a newline")),
        oneLine.isRight,
      )
    },
  )
end CompileTimeSpec
