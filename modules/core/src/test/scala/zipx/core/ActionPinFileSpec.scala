package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.workflow.ActionRef
import java.nio.file.{Files, Path}

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

  /** Valid pins by construction: every field gets its own prefix and a fresh SHA. Versions are all-or-nothing per file,
    * matching `render`'s two branches. The YAML is jar/generate output, not an editable source.
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

  /** The same, plus extra pins, which have their own `extra.<key>` version labels and their own render block. Keys are
    * drawn from a fixed set rather than generated, so the property tests below say something about *sorting* the keys
    * (which `render` must do, a `Map` having no order) rather than about which characters a key may contain.
    */
  private val extraKeyPool: List[String] = List("configure-aws-credentials", "org-action", "zz-last", "aa-first")

  private val gPinsWithExtra: Gen[Any, ActionPins] =
    for
      base    <- gPins
      include <- Gen.listOfN(extraKeyPool.length)(Gen.boolean)
      shas    <- Gen.listOfN(extraKeyPool.length)(gSha)
      labels  <- Gen.option(Gen.listOfN(extraKeyPool.length)(gVersion))
    yield extraKeyPool.zip(shas).zipWithIndex.foldLeft(base) { case (pins, ((key, sha), idx)) =>
      if include(idx) then pins.withExtra(key, ref(s"acme/$key@$sha"), labels.map(_(idx))) else pins
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
          pins.map(_.downloadArtifact) == Right(ActionPins.Bootstrap.downloadArtifact),
        )
      },
      test("the whitespace and comment forms a hand-edited file actually contains") {
        assertTrue(acceptances.forall { case (_, text) => ActionPinFile.parse(text).isRight })
      },
      test("render of Defaults round-trips through parse") {
        assertTrue(
          ActionPinFile.parse(ActionPinFile.render(ActionPins.Defaults)) == Right(ActionPins.Defaults)
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
        // Comments plus one good pin, then a typo. A fold that forgot to carry the index would report 1 here and
        // still pass the single-line test above. Line count is derived so a Header edit cannot stale the number.
        val bad  = "setup-jav: actions/setup-java@abc123"
        val text = s"$header\n$goodPin\n$bad\n"
        val n    = text.linesIterator.toList.indexWhere(_ == bad) + 1
        assertTrue(
          n > 1,
          ActionPinFile.parse(text).swap.exists(_.contains(s"${ActionPinFile.DefaultPath}:$n:")),
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
    suite("extra pins")(
      test("round-trip an extra pin, its label, and the sorted render order") {
        check(gPinsWithExtra) { pins =>
          val rendered  = ActionPinFile.render(pins)
          val extraKeys = rendered.linesIterator
            .dropWhile(_ != s"${ActionPins.ExtraPrefix}:")
            .drop(1)
            .map(_.trim.takeWhile(_ != ':'))
            .toList
          assertTrue(
            ActionPinFile.parse(rendered) == Right(pins),
            extraKeys == pins.extra.keys.toList.sorted,
          )
        }
      },
      test("an extra pin is refused when unpinned, the one check a keyless pin can get") {
        val text = s"${ActionPins.ExtraPrefix}:\n  aws: aws-actions/configure-aws-credentials\n"
        assertTrue(
          ActionPinFile.parse(text).isLeft,
          ActionPinFile.parse(text).swap.exists(_.contains("@ref")),
          ActionPinFile.parse(text).swap.exists(_.contains(s"${ActionPinFile.DefaultPath}:2:")),
        )
      },
      test("an extra key may name any action, since there is no prefix to check it against") {
        // The documented weakening: `aws: totally/unrelated@sha` is legal where `checkout: totally/unrelated@sha`
        // is not. Worth asserting so the asymmetry is deliberate rather than an oversight.
        val text = s"${ActionPins.ExtraPrefix}:\n  aws: totally/unrelated@abc123\n"
        assertTrue(
          ActionPinFile.parse(text).map(_.extraRef("aws")) == Right(Some(ActionRef("totally/unrelated@abc123")))
        )
      },
      test("indentation only means something inside an open extra: block") {
        val stray = "  aws: acme/thing@abc123\n"
        assertTrue(
          ActionPinFile.parse(stray).isLeft,
          ActionPinFile.parse(stray).swap.exists(_.contains(ActionPins.ExtraPrefix)),
        )
      },
      test("a top-level pin after the block closes it, so a mid-file extra: does not swallow the rest") {
        val text = s"${ActionPins.ExtraPrefix}:\n  aws: acme/thing@abc123\ncheckout: actions/checkout@dead\n"
        val pins = ActionPinFile.parse(text)
        assertTrue(
          pins.map(_.extraRef("aws")) == Right(Some(ActionRef("acme/thing@abc123"))),
          pins.map(_.checkout) == Right(ActionRef("actions/checkout@dead")),
        )
      },
      test("an unknown top-level key's message names the extra: block as the escape hatch") {
        val error = ActionPinFile.parse("configureAws: acme/thing@abc123").swap.toOption.getOrElse("")
        assertTrue(error.contains(s"${ActionPins.ExtraPrefix}:"))
      },
      test("withExtra replaces a label, and dropping the label drops the stamp rather than keeping a stale one") {
        val pins    = ActionPins().withExtra("aws", ActionRef("acme/thing@abc123"), Some("v6.0.0"))
        val relabel = pins.withExtra("aws", ActionRef("acme/thing@dead"), Some("v7.0.0"))
        val nolabel = pins.withExtra("aws", ActionRef("acme/thing@dead"))
        assertTrue(
          pins.extraVersion("aws") == Some("v6.0.0"),
          relabel.extraVersion("aws") == Some("v7.0.0"),
          nolabel.extraVersion("aws").isEmpty,
        )
      },
      test("annotateUses stamps an extra pin's version onto its uses: line") {
        val pins = ActionPins().withExtra("aws", ActionRef("acme/thing@abc123"), Some("v6.1.2"))
        val once = ActionPinFile.annotateUses("      - uses: acme/thing@abc123\n", pins)
        assertTrue(
          once.contains("uses: acme/thing@abc123 # v6.1.2"),
          ActionPinFile.annotateUses(once, pins) == once,
        )
      },
      test("pullFromWorkflow bumps an extra pin whose key already exists, and invents no new one") {
        val base = ActionPins.Defaults.withExtra("aws", ActionRef("acme/thing@abc123"), Some("v6.0.0"))
        val yaml =
          """      - uses: acme/thing@cafebabe # v6.1.0
            |      - uses: nobody/knows@deadbeef # v1.0.0
            |""".stripMargin
        val pulled = ActionPinFile.pullFromWorkflow(yaml, base)
        assertTrue(
          pulled.map(_.extraRef("aws")) == Right(Some(ActionRef("acme/thing@cafebabe"))),
          pulled.exists(_.extraVersion("aws").contains("v6.1.0")),
          // No key exists for it, so guessing one would be worse than leaving it to whoever wrote the step.
          pulled.exists(_.extra.keySet == Set("aws")),
        )
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
