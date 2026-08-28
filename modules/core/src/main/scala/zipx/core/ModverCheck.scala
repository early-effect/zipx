package zipx.core

import zipx.shell.*
import zipx.workflow.Step

/** PR `modver-check` / `modver-suggest` extras: base SHA env and the fetch step both jobs need. */
object ModverCheck:

  val BaseShaEnv: String = "ZIPX_MODVER_BASE_SHA"

  val fetchBaseSha: Steps =
    Steps.of("fetch-modver-base")(
      Step
        .run(
          Script(
            Exec(
              "git",
              Word.lit("fetch"),
              Word.lit("--no-tags"),
              Word.lit("origin"),
              Word.vq("ZIPX_MODVER_BASE_SHA"),
            )
          )
        )
        .named("Fetch PR base SHA")
        .build
    )
end ModverCheck
