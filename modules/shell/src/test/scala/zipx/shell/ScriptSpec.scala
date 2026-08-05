package zipx.shell

import neotype.unwrap
import zio.test.*

object ScriptSpec extends ZIOSpecDefault:

  def spec = suite("Script")(
    suite("Word")(
      test("Lit is verbatim unquoted, escaped inside double quotes") {
        assertTrue(
          Word.lit("--tags").render == "--tags",
          Word.lit("refs/tags/v*").render == "refs/tags/v*",
          Word.lit("$HOME").render == "$HOME",
          Word.lit("$HOME").render(Quoting.InDouble) == "\\$HOME",
          Word.lit("a\"b").render(Quoting.InDouble) == "a\\\"b",
          Word.lit("a\\b").render(Quoting.InDouble) == "a\\\\b",
          Word.lit("`date`").render(Quoting.InDouble) == "\\`date\\`",
        )
      },
      test("Squote wraps in single quotes and never expands") {
        assertTrue(
          Word.squote("v*").render == "'v*'",
          Word.squote("$HOME").render == "'$HOME'",
          Word.squote("").render == "''",
        )
      },
      test("quoted and Dquote wrap in double quotes") {
        assertTrue(
          Word.quoted("hello world").render == "\"hello world\"",
          Word.dquote(Word.lit("a"), Word.lit("b")).render == "\"ab\"",
          Word.quoted("").render == "\"\"",
        )
      },
      test("a nested Dquote escapes its quotes, the form a --jq argument needs") {
        val inner = Word.quoted("inner")
        assertTrue(
          Word.dquote(Word.lit("outer "), inner).render == "\"outer \\\"inner\\\"\""
        )
      },
      test("VarRef renders $name, ${name}, and the modifier forms") {
        assertTrue(
          Word.v("HOME").render == "$HOME",
          Word.vBraced("release").render == "${release}",
          Word.vq("GITHUB_OUTPUT").render == "\"$GITHUB_OUTPUT\"",
          Word.vOrEmpty("MAYBE").render == "${MAYBE:-}",
          Word.vOrElse("TAG", "none").render == "${TAG:-none}",
          Word.vStrip("ref", "refs/tags/").render == "${ref#refs/tags/}",
        )
      },
      test("every ParamMod form renders its shell syntax") {
        def r(mod: ParamMod): String = Word.VarRef(VarName("v"), Some(mod)).render
        assertTrue(
          r(ParamMod.Default(ParamText("d"))) == "${v:-d}",
          r(ParamMod.DefaultIfUnset(ParamText("d"))) == "${v-d}",
          r(ParamMod.Alt(ParamText("d"))) == "${v:+d}",
          r(ParamMod.StripPrefix(ParamText("p"))) == "${v#p}",
          r(ParamMod.StripPrefixLong(ParamText("p"))) == "${v##p}",
          r(ParamMod.StripSuffix(ParamText("s"))) == "${v%s}",
          r(ParamMod.StripSuffixLong(ParamText("s"))) == "${v%%s}",
        )
      },
      test("a variable inside double quotes still expands") {
        assertTrue(
          Word.dquote(Word.v("release"), Word.lit("-ci")).render == "\"$release-ci\"",
          Word.dquote(Word.vBraced("release"), Word.lit("x")).render == "\"${release}x\"",
        )
      },
      test("Cat concatenates without a separator, mixing quote styles") {
        assertTrue(
          Word.cat(Word.squote("literal"), Word.vq("expanded")).render == "'literal'\"$expanded\"",
          Word.cat(Word.lit("a"), Word.lit("b")).render == "ab",
        )
      },
      test("Opaque is verbatim in every context: the GHA expression seam") {
        val expr = Word.opaque("${{ github.sha }}")
        assertTrue(
          expr.render == "${{ github.sha }}",
          expr.render(Quoting.InDouble) == "${{ github.sha }}",
          Word.dquote(expr).render == "\"${{ github.sha }}\"",
        )
      },
      test("Subst renders a command substitution") {
        assertTrue(Word.subst(Exec("git", Word.lit("rev-parse"), Word.lit("HEAD"))).render == "$(git rev-parse HEAD)")
      },
    ),
    suite("Command")(
      test("Exec joins the program and its arguments with spaces") {
        assertTrue(
          Exec("git", Word.lit("describe"), Word.lit("--tags")).inlineRender == "git describe --tags",
          Exec("echo").inlineRender == "echo",
        )
      },
      test("pipelines, and-lists, and or-lists render infix") {
        val a = Exec("git", Word.lit("tag"))
        val b = Exec("sort", Word.lit("-V"))
        assertTrue(
          (a | b).inlineRender == "git tag | sort -V",
          (a && b).inlineRender == "git tag && sort -V",
          (a || b).inlineRender == "git tag || sort -V",
          (a | b | Exec("head")).inlineRender == "git tag | sort -V | head",
        )
      },
      test("redirects render >, >>, and fd forms") {
        val e = Exec("echo", Word.quoted("k=v"))
        assertTrue(
          e.writeTo(Word.vq("GITHUB_OUTPUT")).inlineRender == "echo \"k=v\" > \"$GITHUB_OUTPUT\"",
          e.appendTo(Word.vq("GITHUB_OUTPUT")).inlineRender == "echo \"k=v\" >> \"$GITHUB_OUTPUT\"",
          e.silenced.inlineRender == "echo \"k=v\" >/dev/null 2>&1",
          e.stderrSilenced.inlineRender == "echo \"k=v\" 2>/dev/null",
          Redirect(e, Word.lit("log"), append = false, from = Some(FileDescriptor.Stderr)).inlineRender ==
            "echo \"k=v\" 2> log",
          RedirectFd(e, FileDescriptor.Stderr, FileDescriptor.Stdout).inlineRender == "echo \"k=v\" 2>&1",
        )
      },
      test("Assign renders plain, local, export, and readonly") {
        assertTrue(
          Assign("x", Word.quoted("1")).inlineRender == "x=\"1\"",
          Assign(VarName("x"), Word.lit("1"), Assign.Scope.Local).inlineRender == "local x=1",
          Assign(VarName("x"), Word.lit("1"), Assign.Scope.Export).inlineRender == "export x=1",
          Assign(VarName("x"), Word.lit("1"), Assign.Scope.ReadOnly).inlineRender == "readonly x=1",
        )
      },
      test("SetOpts renders the flag combinations") {
        assertTrue(
          SetOpts().lines(Script.Ctx.root).map(_.unwrap) == List("set -euo pipefail"),
          SetOpts(pipefail = false).lines(Script.Ctx.root).map(_.unwrap) == List("set -eu"),
          SetOpts(errexit = false, nounset = false).lines(Script.Ctx.root).map(_.unwrap) == List("set -o pipefail"),
          SetOpts(nounset = false, pipefail = false).lines(Script.Ctx.root).map(_.unwrap) == List("set -e"),
          SetOpts(errexit = false, nounset = false, pipefail = false).lines(Script.Ctx.root) == Nil,
        )
      },
      test("Exit, Comment, and BlankLine") {
        assertTrue(
          Exit().inlineRender == "exit 0",
          Exit(ExitCode(1)).inlineRender == "exit 1",
          Comment("why this exists").render == "# why this exists",
          BlankLine.lines(Script.Ctx.root).map(_.unwrap) == List(""),
        )
      },
      test("Continued puts a trailing backslash on every line but the last") {
        val cmd = Continued(
          "gh",
          List(
            List(Word.lit("api"), Word.quoted("repos/owner/repo/pulls")),
            List(Word.lit("--jq"), Word.quoted("length")),
            List(Word.lit("--paginate")),
          ),
        )
        val rendered = cmd.render
        assertTrue(
          rendered ==
            """gh api "repos/owner/repo/pulls" \
              |  --jq "length" \
              |  --paginate""".stripMargin,
          rendered.linesIterator.toList.dropRight(1).forall(_.endsWith(" \\")),
          !rendered.linesIterator.toList.last.endsWith("\\"),
        )
      },
      test("continuationIndent applies to every line after the first, on top of the script's depth") {
        val cmd = Continued("curl", List(List(Word.lit("-s")), List(Word.lit("-o"), Word.lit("out"))))
        assertTrue(
          cmd.render == "curl -s \\\n  -o out",
          cmd.copy(continuationIndent = 4).render == "curl -s \\\n    -o out",
          cmd.copy(continuationIndent = 0).render == "curl -s \\\n-o out",
          Script(If(ShTest.varNonEmpty("x"), Block(cmd))).render ==
            """if [ -n "$x" ]; then
              |  curl -s \
              |    -o out
              |fi""".stripMargin,
        )
      },
      test("a Continued with one line, or none, carries no backslash") {
        assertTrue(
          Continued("git", List(List(Word.lit("status")))).render == "git status",
          Continued("git", Nil).render == "git",
        )
      },
      test("a Continued is inline-usable: a continuation is one logical command") {
        val cmd = Continued("gh", List(List(Word.lit("api")), List(Word.lit("--paginate"))))
        assertTrue(
          (cmd | Exec("wc", Word.lit("-l"))).render == "gh api \\\n  --paginate | wc -l",
          cmd.lines(Script.Ctx.root).length == 2,
        )
      },
    ),
    suite("InlineCommand")(
      test("a compound command does not compile where the shell needs one command") {
        for
          piped     <- typeCheck("""Exec("wc", Word.lit("-l")) | If(ShTest.varNonEmpty("x"), Block(Exit()))""")
          condition <- typeCheck("""ShTest.succeeds(ForIn(VarName("x"), Nil, Block(Exit())))""")
          redirect  <- typeCheck("""While(ShTest.varNonEmpty("x"), Block(Exit())).writeTo(Word.lit("log"))""")
          comment   <- typeCheck("""Exec("echo", Word.lit("hi")) | Comment("not a command")""")
          rawBlock  <- typeCheck("""ShTest.succeeds(Raw(Nil))""")
        yield assertTrue(
          piped.isLeft,
          condition.isLeft,
          redirect.isLeft,
          comment.isLeft,
          rawBlock.isLeft,
        )
      },
      test("the inline-usable commands do compile in those positions") {
        for
          exec      <- typeCheck("""Exec("git", Word.lit("tag")) | Exec("wc", Word.lit("-l"))""")
          continued <- typeCheck("""ShTest.succeeds(Continued("gh", List(List(Word.lit("api")))))""")
          assign    <- typeCheck("""Assign("k", Word.lit("v")).writeTo(Word.lit("out"))""")
          rawLine   <- typeCheck("""ShTest.succeeds(RawLine("grep -q pattern file"))""")
        yield assertTrue(exec.isRight, continued.isRight, assign.isRight, rawLine.isRight)
      },
      test("RawLine is the single-line escape hatch, and reports itself as raw") {
        val cmd = RawLine("grep -q '^v' tags")
        assertTrue(
          Script(If(ShTest.succeeds(cmd), Block(Exit()))).render ==
            """if grep -q '^v' tags; then
              |  exit 0
              |fi""".stripMargin,
          cmd.rawFragments == List("grep -q '^v' tags"),
          RawLine.make("two\nlines").isLeft,
          RawLine.make("fine").map(_.inlineRender) == Right("fine"),
        )
      },
      test("a multi-line RawLine literal does not compile") {
        for
          multiline <- typeCheck("""RawLine("two\nlines")""")
          fine      <- typeCheck("""RawLine("one line")""")
        yield assertTrue(multiline.isLeft, fine.isRight)
      },
    ),
    suite("If")(
      test("renders if/then/fi with the body indented two spaces") {
        val cmd = If(ShTest.varNonEmpty("release"), Block(Exec("echo", Word.lit("yes"))))
        assertTrue(
          Script(cmd).render ==
            """if [ -n "$release" ]; then
              |  echo yes
              |fi""".stripMargin
        )
      },
      test("renders elif and else branches") {
        val cmd = If(
          cond = ShTest.varEquals("mode", "a"),
          thenDo = Block(Exec("echo", Word.lit("A"))),
          elifs = List(ShTest.varEquals("mode", "b") -> Block(Exec("echo", Word.lit("B")))),
          elseDo = Some(Block(Exec("echo", Word.lit("other")))),
        )
        assertTrue(
          Script(cmd).render ==
            """if [ "$mode" = "a" ]; then
              |  echo A
              |elif [ "$mode" = "b" ]; then
              |  echo B
              |else
              |  echo other
              |fi""".stripMargin
        )
      },
      test("nested ifs indent cumulatively") {
        val inner = If(ShTest.varNonEmpty("b"), Block(Exec("echo", Word.lit("deep"))))
        val outer = If(ShTest.varNonEmpty("a"), Block(inner))
        assertTrue(
          Script(outer).render ==
            """if [ -n "$a" ]; then
              |  if [ -n "$b" ]; then
              |    echo deep
              |  fi
              |fi""".stripMargin
        )
      },
    ),
    suite("loops and heredocs")(
      test("ForIn renders over its word list") {
        val cmd = ForIn(VarName("f"), List(Word.lit("a"), Word.lit("b")), Block(Exec("echo", Word.vq("f"))))
        assertTrue(
          Script(cmd).render ==
            """for f in a b; do
              |  echo "$f"
              |done""".stripMargin
        )
      },
      test("While renders its condition") {
        val cmd = While(ShTest.varNonEmpty("more"), Block(Exec("shift")))
        assertTrue(
          Script(cmd).render ==
            """while [ -n "$more" ]; do
              |  shift
              |done""".stripMargin
        )
      },
      test("Heredoc quotes the delimiter by default so the body is not expanded") {
        val quoted = Heredoc(Exec("cat"), HeredocTag("EOF"), List(ScriptLine("$literal")))
        val expand = Heredoc(Exec("cat"), HeredocTag("EOF"), List(ScriptLine("$expanded")), quoted = false)
        assertTrue(
          Script(quoted).render == "cat <<'EOF'\n$literal\nEOF",
          Script(expand).render == "cat <<EOF\n$expanded\nEOF",
        )
      },
    ),
    suite("ShTest")(
      test("string and integer comparisons use POSIX single brackets") {
        val l = Word.vq("a")
        val r = Word.quoted("b")
        assertTrue(
          ShTest.StrEq(l, r).render == "[ \"$a\" = \"b\" ]",
          ShTest.StrNe(l, r).render == "[ \"$a\" != \"b\" ]",
          ShTest.IntEq(l, r).render == "[ \"$a\" -eq \"b\" ]",
          ShTest.IntNe(l, r).render == "[ \"$a\" -ne \"b\" ]",
          ShTest.IntGt(l, r).render == "[ \"$a\" -gt \"b\" ]",
          ShTest.IntGe(l, r).render == "[ \"$a\" -ge \"b\" ]",
          ShTest.IntLt(l, r).render == "[ \"$a\" -lt \"b\" ]",
          ShTest.IntLe(l, r).render == "[ \"$a\" -le \"b\" ]",
        )
      },
      test("emptiness and file tests") {
        val p = Word.vq("path")
        assertTrue(
          ShTest.Empty(p).render == "[ -z \"$path\" ]",
          ShTest.NonEmpty(p).render == "[ -n \"$path\" ]",
          ShTest.PathExists(p).render == "[ -e \"$path\" ]",
          ShTest.FileExists(p).render == "[ -f \"$path\" ]",
          ShTest.DirExists(p).render == "[ -d \"$path\" ]",
          ShTest.FileNonEmpty(p).render == "[ -s \"$path\" ]",
          ShTest.Executable(p).render == "[ -x \"$path\" ]",
        )
      },
      test("glob match uses double brackets with an unquoted pattern") {
        assertTrue(
          ShTest.varMatches("ref", "refs/tags/v*").render == "[[ \"$ref\" == refs/tags/v* ]]",
          ShTest.GlobNotMatch(Word.vq("ref"), GlobPattern("v*")).render == "[[ \"$ref\" != v* ]]",
        )
      },
      test("Cmd tests exit status with no brackets") {
        assertTrue(ShTest.succeeds(Exec("git", Word.lit("describe")).silenced).render == "git describe >/dev/null 2>&1")
      },
      test("boolean combinators compose") {
        val a = ShTest.varNonEmpty("a")
        val b = ShTest.varEmpty("b")
        assertTrue(
          (a && b).render == "[ -n \"$a\" ] && [ -z \"$b\" ]",
          (a || b).render == "[ -n \"$a\" ] || [ -z \"$b\" ]",
          (!a).render == "! [ -n \"$a\" ]",
        )
      },
    ),
    suite("Script")(
      test("render joins lines with newlines and honours trailingNewline") {
        val s = Script(Exec("echo", Word.lit("a")), Exec("echo", Word.lit("b")))
        assertTrue(
          s.render == "echo a\necho b",
          s.withTrailingNewline(true).render == "echo a\necho b\n",
        )
      },
      test("strict prepends set -euo pipefail") {
        assertTrue(Script.strict(Exec("echo", Word.lit("hi"))).render == "set -euo pipefail\necho hi")
      },
      test("++ appends and takes the right-hand ending") {
        val a = Script(Exec("echo", Word.lit("a")))
        val b = Script(List(Exec("echo", Word.lit("b"))), trailingNewline = true)
        assertTrue(
          (a ++ b).render == "echo a\necho b\n",
          (b ++ a).render == "echo b\necho a",
          (a ++ Script.empty).render == "echo a",
          Script.empty.render == "",
        )
      },
      test("rawFragments is empty for a fully typed script") {
        assertTrue(Script.strict(Exec("echo", Word.lit("hi"))).rawFragments.isEmpty)
      },
      test("raw accepts safe text and reports the offending line otherwise") {
        val good = Script.raw("echo one\necho two")
        assertTrue(
          good.map(_.render) == Right("echo one\necho two"),
          good.map(_.rawFragments) == Right(List("echo one", "echo two")),
          Script.raw("ok\n\tleading tab").isLeft,
          Script.raw("ok\n\tleading tab").swap.exists(_.contains("line 2")),
          Script.raw("has\rcarriage").isLeft,
        )
      },
      test("raw fragments propagate through wrapping commands so the warning can find them") {
        val raw     = RawLine.make("custom line").toOption.get
        val wrapped = Script(If(ShTest.succeeds(raw), Block(Exec("echo", Word.lit("hi")))))
        assertTrue(wrapped.rawFragments == List("custom line"))
      },
      test("an indented raw line keeps its own text but gains script indentation") {
        val raw = Raw.make("custom").toOption.get
        assertTrue(
          Script(If(ShTest.varNonEmpty("x"), Block(raw))).render ==
            """if [ -n "$x" ]; then
              |  custom
              |fi""".stripMargin
        )
      },
    ),
    suite("sh interpolator")(
      test("literal parts and Word splices concatenate") {
        val tag = Word.vq("TAG")
        assertTrue(
          sh"refs/tags/$tag".render == "refs/tags/\"$TAG\"",
          sh"$tag".render == "\"$TAG\"",
          sh"no splices".render == "no splices",
          sh"".render == "",
          sh"$tag$tag".render == "\"$TAG\"\"$TAG\"",
        )
      },
      test("a literal part is validated, so a multi-line sh literal throws") {
        val failed =
          try
            StringContext("first\nsecond ", "").sh(Word.vq("TAG")); false
          catch case _: IllegalArgumentException => true
        assertTrue(failed)
      },
      test("splices keep their own quoting; the interpolator adds none") {
        assertTrue(
          sh"${Word.squote("v*")}-${Word.v("x")}".render == "'v*'-$x",
          sh"${Word.opaque("${{ github.sha }}")}".render == "${{ github.sha }}",
        )
      },
      test("an sh word is an ordinary Word, usable anywhere one is") {
        val key = sh"sbt-${Word.vq("RUNNER_OS")}"
        assertTrue(
          Exec("echo", key).inlineRender == "echo sbt-\"$RUNNER_OS\"",
          Script(Assign("k", key)).render == "k=sbt-\"$RUNNER_OS\"",
        )
      },
    ),
    suite("extensibility")(
      test("a consumer can implement Command without modifying this module") {
        final case class Case(subject: Word, arms: List[(GlobPattern, Block)]) extends Command:
          def lines(ctx: Script.Ctx): List[ScriptLine] =
            val inner = ctx.nested
            ctx.emit(ShLines.of("case ") ++ subject.lines(Quoting.Unquoted) + " in") :::
              arms.flatMap((p, body) =>
                inner.emit(ShLines.pattern(p) + ")") ::: body.lines(inner.nested) ::: inner.line(";;")
              ) ::: ctx.line("esac")

        val cmd = Case(Word.vq("mode"), List(GlobPattern("a*") -> Block(Exec("echo", Word.lit("A")))))
        assertTrue(
          Script(cmd).render ==
            """case "$mode" in
              |  a*)
              |    echo A
              |  ;;
              |esac""".stripMargin
        )
      }
    ),
  )
end ScriptSpec
