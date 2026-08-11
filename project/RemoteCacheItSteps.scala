import zipx.core.*
import zipx.shell.Script
import zipx.workflow.Step

/** Pre-pull Docker images used by core's live remote-cache suite (saferis-style).
  *
  * Runs as `extraSteps` on Aggregate `test` so Hub latency and retries sit outside the test JVM.
  * Testcontainers then starts from local images. Ryuk stays enabled (unlike saferis' GHA setting).
  */
object RemoteCacheItSteps:

  /** Retrying `docker pull` for both [[RemoteCacheProof]] images (bazel-remote + sbt fixture). */
  private val pullScript: Script =
    Script
      .raw(
        s"""set -euo pipefail
           |pull_with_retry() {
           |  local image="$$1"
           |  local max=5
           |  local attempt
           |  for attempt in $$(seq 1 "$$max"); do
           |    if docker pull "$$image"; then
           |      return 0
           |    fi
           |    if [ "$$attempt" -eq "$$max" ]; then
           |      echo "Failed to pull $$image after $$max attempts" >&2
           |      return 1
           |    fi
           |    sleep $$((attempt * 10))
           |  done
           |}
           |pull_with_retry '${RemoteCacheProof.image}'
           |pull_with_retry '${RemoteCacheProof.sbtFixtureImage}'
           |""".stripMargin
      )
      .fold(err => sys.error(s"RemoteCacheItSteps.prePull: $err"), identity)

  val prePull: Steps = Steps.built("pre-pull-remote-cache-images")(
    Step.run(pullScript).named("Pre-pull remote-cache IT images")
  )

end RemoteCacheItSteps
