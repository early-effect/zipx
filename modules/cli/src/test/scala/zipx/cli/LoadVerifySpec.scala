package zipx.cli

import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object LoadVerifySpec extends ZIOSpecDefault:

  def spec = suite("LoadVerify")(
    test("failed probe restores the snapshot and reports the error") {
      val dir = Files.createTempDirectory("zipx-load")
      val cat = dir.resolve("ZipxVersions.scala")
      Files.writeString(cat, "original\n", StandardCharsets.UTF_8)
      val result = LoadVerify.applyWrite(
        cat,
        write = () =>
          Files.writeString(cat, "broken\n", StandardCharsets.UTF_8)
          Right(())
        ,
        verify = true,
        run = _ => Left("meta-build failed to load ([E007] Type Mismatch)"),
      )
      val after = Files.readString(cat, StandardCharsets.UTF_8)
      assertTrue(
        result == Right(Some("meta-build failed to load ([E007] Type Mismatch)")),
        after == "original\n",
      )
    },
    test("successful probe keeps the write") {
      val dir = Files.createTempDirectory("zipx-load-ok")
      val cat = dir.resolve("ZipxVersions.scala")
      Files.writeString(cat, "original\n", StandardCharsets.UTF_8)
      val result = LoadVerify.applyWrite(
        cat,
        write = () =>
          Files.writeString(cat, "updated\n", StandardCharsets.UTF_8)
          Right(())
        ,
        verify = true,
        run = _ => Right(()),
      )
      val after = Files.readString(cat, StandardCharsets.UTF_8)
      assertTrue(result == Right(None), after == "updated\n")
    },
  )
end LoadVerifySpec
