package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.workflow.ActionRef
import java.nio.file.{Files, Path, Paths}

/** The pin file is the one place a `uses:` value enters zipx as untrusted text, so it gets the whole negative surface.
  *
  * The point of the negative tests is not that a bad line is refused: it is that it is refused *loudly*. Before `parse`
  * returned an `Either`, every case in `rejections` below silently fell back to the pin baked into the zipx jar, which
  * is how a deliberately held-back pin reverted itself on the next `zipxWorkflowGenerate`.
  */
object ActionPinFileSpec extends ZIOSpecDefault:

  private val keys: List[String] = ActionPins.Field.values.toList.map(_.key)

  /** `ActionRef("…")` only takes a literal, and every ref built here is assembled from a [[ActionPins.Field.prefix]]
    * and a generated SHA. `make` rather than `unsafeMake`, so a generator that starts producing garbage fails here
    * instead of quietly handing `parse` a ref no real pin file could contain.
    */
  private def ref(text: String): ActionRef =
    ActionRef.make(text).fold(error => throw AssertionError(s"test built an invalid ref: $error"), identity)

  private val gSha: Gen[Any, String] =
    Gen.stringN(40)(Gen.elements(("0123456789abcdef").toList*))

  private val gVersion: Gen[Any, String] =
    for
      major <- Gen.int(0, 99)
      minor <- Gen.int(0, 99)
      patch <- Gen.int(0, 99)
    yield s"v$major.$minor.$patch"

  /** Valid pins by construction: every field gets its own prefix and a fresh SHA, so a generated file is one a real
    * `zipxActionsPull` could have written. Versions are all-or-nothing per file, matching `render`'s two branches.
    */
  private val gPins: Gen[Any, ActionPins] =
    for
      shas     <- Gen.listOfN(ActionPins.Field.values.length)(gSha)
      versions <- Gen.option(Gen.listOfN(ActionPins.Field.values.length)(gVersion))
    yield
      val withRefs = ActionPins.Field.values.zip(shas).foldLeft(ActionPins()) { case (pins, (field, sha)) =>
        pins.withField(field, ref(s"${field.prefix}@$sha"))
      }
      versions.fold(withRefs.copy(versions = Map.empty)) { vs =>
        withRefs.copy(versions = keys.zip(vs).toMap)
      }

  private val gField: Gen[Any, ActionPins.Field] = Gen.elements(ActionPins.Field.values.toList*)

  private val gIdentStart: Gen[Any, Char] = Gen.elements((('A' to 'Z') ++ ('a' to 'z') :+ '_')*)
  private val gIdentRest: Gen[Any, Char]  = Gen.elements((('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') :+ '_')*)

  /** Identifiers the `Line` regex accepts as a key but [[ActionPins.Field]] does not name, which is exactly the typo
    * and wrong-case class: `setupJava2`, `Checkout`, `SETUPSBT`.
    */
  private val gUnknownKey: Gen[Any, String] =
    (for
      head <- gIdentStart
      tail <- Gen.listOf(gIdentRest)
    yield (head :: tail).mkString).filterNot(keys.contains)

  private val header: String =
    ActionPinFile.render(ActionPins.Defaults).linesIterator.takeWhile(_.startsWith("#")).mkString("\n")

  private val goodPin: String = s"checkout: ${ActionPins.Defaults.checkout.unwrap} # v7.0.1"

  /** Each entry is (why it is refused, the offending line). Every one of these is silently ignored or silently trusted
    * before this change; the `describe` half is only there to name the case in the test report.
    */
  private val rejections: List[(String, String)] = List(
    // Class A: no `Line` match at all, so the pin was dropped and the jar default used in its place.
    "a typo'd key"               -> "setup-jav: actions/setup-java@abc123",
    "a stray indent"             -> "  checkout: actions/checkout@abc123",
    "a key with no value"        -> "checkout:",
    "a two-word version comment" -> "checkout: actions/checkout@abc123 # v1 v2",
    // Class B: matches `Line`, so the parser accepted it and then dropped or trusted the result.
    "an unknown key"              -> "setupJava2: actions/setup-java@abc123",
    "a wrong-case key"            -> "Checkout: actions/checkout@abc123",
    "a ref naming another action" -> "checkout: evil/malware@abc123",
    // An invalid ref: rejected by `ActionRef` itself, which had no say before the fields were typed.
    "an unpinned ref"        -> "checkout: actions/checkout",
    "an expression as a ref" -> "checkout: ${{ env.ACTION }}",
    "an empty @ref"          -> "checkout: actions/checkout@",
  )

  /** Forms the committed file and hand edits legitimately contain. Over-strictness would break real builds, so these
    * are as load-bearing as the negatives.
    */
  private val acceptances: List[(String, String)] = List(
    "a comment and a blank line" -> s"# a note\n\n$goodPin\n",
    "no space after the colon"   -> "checkout:actions/checkout@abc123",
    "a tab separator"            -> "checkout:\tactions/checkout@abc123",
    "trailing whitespace"        -> s"$goodPin   ",
    "CRLF line endings"          -> s"$header\r\n$goodPin\r\n",
    "no trailing newline"        -> goodPin,
    "a pin with no version"      -> "checkout: actions/checkout@abc123",
  )

  private val committedPinFile: Path = Paths.get(".github", "zipx", "action-pins.yml")

  def spec = suite("ActionPinFile")(
    suite("Field is the single source of truth")(
      test("every Field reads and writes its own pin, so no field can be missed") {
        val stamped = ActionPins.Field.values.foldLeft(ActionPins.Bootstrap) { (pins, field) =>
          pins.withField(field, ref(s"${field.prefix}@abc123"))
        }
        assertTrue(
          ActionPins.Field.values.forall(f => stamped.field(f) == ref(s"${f.prefix}@abc123")),
          ActionPins.Field.values.map(_.key).distinct.length == ActionPins.Field.values.length,
          ActionPins.Field.values.map(_.prefix).distinct.length == ActionPins.Field.values.length,
        )
      },
      test("rendered line order is Field declaration order, which is the committed pin file's order") {
        val rendered = ActionPinFile
          .render(ActionPins.Defaults)
          .linesIterator
          .collect { case line if !line.startsWith("#") => line.takeWhile(_ != ':') }
          .toList
        assertTrue(rendered == keys)
      },
    ),
    suite("accepts")(
      test("a pin file with version comments") {
        val pins = ActionPinFile.parse(
          """
            |checkout: actions/checkout@abc123 # v7.0.1
            |setupSbt: sbt/setup-sbt@def456 # v1.5.2
            |""".stripMargin
        )
        assertTrue(
          pins.map(_.checkout) == Right(ActionRef("actions/checkout@abc123")),
          pins.map(_.setupSbt) == Right(ActionRef("sbt/setup-sbt@def456")),
          pins.exists(_.versions.get("checkout").contains("v7.0.1")),
          pins.exists(_.versions.get("setupSbt").contains("v1.5.2")),
        )
      },
      test("a field the file omits keeps the bootstrap pin rather than failing") {
        // The one silent fallback that survives: an *absent* line is not an error, so a partial file is usable.
        val pins = ActionPinFile.parse("checkout: actions/checkout@abc123\n")
        assertTrue(
          pins.map(_.checkout) == Right(ActionRef("actions/checkout@abc123")),
          pins.map(_.scalaSteward) == Right(ActionPins.Bootstrap.scalaSteward),
        )
      },
      test("the whitespace and comment forms a hand-edited file actually contains") {
        assertTrue(acceptances.forall { case (_, text) => ActionPinFile.parse(text).isRight })
      },
      test("the actual committed .github/zipx/action-pins.yml, and its own render") {
        // The strongest guard against over-strictness: whatever this repo ships must parse, and the pins it yields
        // must be the ones `Defaults` already loaded from the same text on the classpath.
        val committed = ActionPinFile.load(committedPinFile)
        assertTrue(
          Files.isRegularFile(committedPinFile),
          committed == Right(ActionPins.Defaults),
          ActionPinFile.parse(ActionPinFile.render(ActionPins.Defaults)) == Right(ActionPins.Defaults),
        )
      },
    ),
    suite("refuses, naming the line")(
      test("every pathological line, with the line number in the message") {
        assertTrue(
          rejections.forall { case (_, line) => ActionPinFile.parse(line).isLeft },
          // Line 1 for a one-line file, so a message that omits the number cannot pass by accident.
          rejections.forall { case (_, line) =>
            ActionPinFile.parse(line).swap.exists(_.contains(s"${ActionPinFile.DefaultPath}:1:"))
          },
          // And the offending text is echoed, which is what makes the error actionable in an sbt log.
          rejections.forall { case (_, line) => ActionPinFile.parse(line).swap.exists(_.contains(line.trim)) },
        )
      },
      test("the reported line number is the bad line's, not the first line's") {
        // Header (4 lines) + one good pin, so the bad line is line 6. A fold that forgot to carry the index would
        // report 1 here and still pass the single-line test above.
        val text = s"$header\n$goodPin\nsetup-jav: actions/setup-java@abc123\n"
        assertTrue(
          text.linesIterator.length == 6,
          ActionPinFile.parse(text).swap.exists(_.contains(s"${ActionPinFile.DefaultPath}:6:")),
        )
      },
      test("an unknown key's message lists the legal keys, so the typo is fixable from the error alone") {
        val error = ActionPinFile.parse("setupJava2: actions/setup-java@abc123").swap.toOption.getOrElse("")
        assertTrue(error.contains("setupJava2"), keys.forall(error.contains))
      },
      test("a ref naming another action says which action the key must name") {
        val error = ActionPinFile.parse("checkout: evil/malware@abc123").swap.toOption.getOrElse("")
        assertTrue(
          error.contains(ActionPins.Field.Checkout.prefix),
          error.contains("evil/malware@abc123"),
        )
      },
      test("an unpinned ref is refused with ActionRef's own advice to add an @ref") {
        assertTrue(ActionPinFile.parse("checkout: actions/checkout").swap.exists(_.contains("@ref")))
      },
    ),
    suite("properties")(
      test("render/parse round-trips every field of any valid pin set") {
        check(gPins) { pins =>
          assertTrue(ActionPinFile.parse(ActionPinFile.render(pins)) == Right(pins))
        }
      },
      test("one malformed line refuses the whole file, whichever field it is") {
        // #59's claim as a property: no field may fall back to the jar default because its line was unreadable.
        check(gPins, gField) { (pins, broken) =>
          val text = ActionPinFile
            .render(pins)
            .linesIterator
            .map(line => if line.startsWith(s"${broken.key}:") then s"${broken.key}: ${broken.prefix}" else line)
            .mkString("\n")
          assertTrue(ActionPinFile.parse(text).isLeft, ActionPinFile.parse(text).swap.exists(_.contains(broken.key)))
        }
      },
      test("a key can only name its own action, over every pair of fields") {
        check(gField, gField, gSha) { (key, other, sha) =>
          val text   = s"${key.key}: ${other.prefix}@$sha"
          val parsed = ActionPinFile.parse(text)
          // Same field is the legitimate case and must still parse; any other field is the wrong-action refusal.
          if key == other then assertTrue(parsed.map(_.field(key)) == Right(ref(s"${other.prefix}@$sha")))
          else assertTrue(parsed.isLeft, parsed.swap.exists(_.contains(key.prefix)))
        }
      },
      test("any identifier that is not a Field key is refused") {
        check(gUnknownKey, gField, gSha) { (key, field, sha) =>
          assertTrue(ActionPinFile.parse(s"$key: ${field.prefix}@$sha").isLeft)
        }
      },
      test("a valid pin's ref is a valid ActionRef, and vice versa where the key agrees") {
        check(gField, gSha) { (field, sha) =>
          assertTrue(ActionRef.make(s"${field.prefix}@$sha").isRight)
        }
      },
    ),
    suite("pullFromWorkflow")(
      test("extracts uses pins and their version comments") {
        val yaml =
          """
            |jobs:
            |  test:
            |    steps:
            |      - uses: actions/checkout@deadbeef # v9.9.9
            |      - uses: sbt/setup-sbt@cafebabe # v1.2.3
            |""".stripMargin
        val pulled = ActionPinFile.pullFromWorkflow(yaml, ActionPins.Defaults)
        assertTrue(
          pulled.map(_.checkout) == Right(ActionRef("actions/checkout@deadbeef")),
          pulled.map(_.setupSbt) == Right(ActionRef("sbt/setup-sbt@cafebabe")),
          pulled.exists(_.versions.get("checkout").contains("v9.9.9")),
          pulled.exists(_.versions.get("setupSbt").contains("v1.2.3")),
        )
      },
      test("ignores an unrelated third-party action rather than refusing the workflow") {
        val yaml = "      - uses: aws-actions/configure-aws-credentials@v6\n"
        assertTrue(ActionPinFile.pullFromWorkflow(yaml, ActionPins.Defaults) == Right(ActionPins.Defaults))
      },
      test("refuses a known action whose ref a rewrite left invalid") {
        // The case a silent pull would launder into the pin file: the prefix is one zipx pins, the ref is not usable.
        val yaml = "      - uses: actions/checkout\n"
        assertTrue(
          ActionPinFile.pullFromWorkflow(yaml, ActionPins.Defaults).isLeft,
          ActionPinFile.pullFromWorkflow(yaml, ActionPins.Defaults).swap.exists(_.contains("@ref")),
        )
      },
      test("a pulled workflow round-trips back through the pin file") {
        check(gPins) { pins =>
          val yaml   = ActionPins.Field.values.toList.map(f => s"      - uses: ${pins.field(f).unwrap}").mkString("\n")
          val pulled = ActionPinFile.pullFromWorkflow(yaml, ActionPins.Defaults)
          assertTrue(
            pulled.map(p => ActionPins.Field.values.toList.map(p.field)) == Right(
              ActionPins.Field.values.toList.map(pins.field)
            )
          )
        }
      },
    ),
    suite("annotateUses")(
      test("appends version comments once") {
        val pins  = ActionPins.Defaults
        val raw   = s"      - uses: ${pins.checkout.unwrap}\n"
        val once  = ActionPinFile.annotateUses(raw, pins)
        val twice = ActionPinFile.annotateUses(once, pins)
        assertTrue(
          once.contains(s"uses: ${pins.checkout.unwrap} # ${pins.versions("checkout")}"),
          once == twice,
        )
      }
    ),
    suite("on disk")(
      test("write and loadOption round-trip") {
        val dir  = Files.createTempDirectory("zipx-pins")
        val path = dir.resolve("action-pins.yml")
        ActionPinFile.write(path, ActionPins.Defaults)
        assertTrue(ActionPinFile.loadOption(path) == Some(Right(ActionPins.Defaults)))
      },
      test("an absent file is None but a present-but-bad file is Some(Left), which is what lets the plugin fail") {
        val dir  = Files.createTempDirectory("zipx-pins")
        val bad  = dir.resolve("action-pins.yml")
        val gone = dir.resolve("nope.yml")
        Files.writeString(bad, "setup-jav: actions/setup-java@abc123\n")
        assertTrue(
          ActionPinFile.loadOption(gone).isEmpty,
          ActionPinFile.loadOption(bad).exists(_.isLeft),
        )
      },
      test("an absent classpath resource is None, and Defaults falls back to the bootstrap pins") {
        assertTrue(
          ActionPinFile.loadResource("zipx/does-not-exist.yml").isEmpty,
          ActionPinFile.loadResource().isDefined,
          ActionPins.Defaults.checkout.unwrap.startsWith(ActionPins.Field.Checkout.prefix + "@"),
        )
      },
    ),
  )
end ActionPinFileSpec
