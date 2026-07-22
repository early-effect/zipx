package zipx.core

import zio.test.*
import java.nio.file.Files

object ActionPinFileSpec extends ZIOSpecDefault:

  def spec = suite("ActionPinFile")(
    test("parse pin file with version comments") {
      val pins = ActionPinFile.parse(
        """
          |checkout: actions/checkout@abc123 # v7.0.1
          |setupSbt: sbt/setup-sbt@def456 # v1.5.2
          |""".stripMargin
      )
      assertTrue(
        pins.checkout == "actions/checkout@abc123",
        pins.setupSbt == "sbt/setup-sbt@def456",
        pins.versions.get("checkout").contains("v7.0.1"),
        pins.versions.get("setupSbt").contains("v1.5.2"),
      )
    },
    test("round-trip render/parse preserves refs and versions") {
      val original = ActionPins.Defaults
      val parsed   = ActionPinFile.parse(ActionPinFile.render(original))
      assertTrue(
        parsed.checkout == original.checkout,
        parsed.setupSbt == original.setupSbt,
        parsed.versions.get("checkout") == original.versions.get("checkout"),
        parsed.versions.get("setupSbt") == original.versions.get("setupSbt"),
      )
    },
    test("pullFromWorkflow extracts uses pins and comments") {
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
        pulled.checkout == "actions/checkout@deadbeef",
        pulled.setupSbt == "sbt/setup-sbt@cafebabe",
        pulled.versions.get("checkout").contains("v9.9.9"),
        pulled.versions.get("setupSbt").contains("v1.2.3"),
      )
    },
    test("annotateUses appends version comments once") {
      val pins  = ActionPins.Defaults
      val raw   = s"      - uses: ${pins.checkout}\n"
      val once  = ActionPinFile.annotateUses(raw, pins)
      val twice = ActionPinFile.annotateUses(once, pins)
      assertTrue(
        once.contains(s"uses: ${pins.checkout} # ${pins.versions("checkout")}"),
        once == twice,
      )
    },
    test("write and loadOption round-trip on disk") {
      val dir  = Files.createTempDirectory("zipx-pins")
      val path = dir.resolve("action-pins.yml")
      ActionPinFile.write(path, ActionPins.Defaults)
      val loaded = ActionPinFile.loadOption(path)
      assertTrue(loaded.exists(_.checkout == ActionPins.Defaults.checkout))
    },
  )
end ActionPinFileSpec
