import zipx.core.*
import zipx.shell.*
import zipx.workflow.Step

/** Pre-pull Docker images used by core's live remote-cache suite (saferis-style).
  *
  * Runs as `extraSteps` on Aggregate `test` so Hub latency and retries sit outside the test JVM.
  * Testcontainers then starts from local images. Ryuk stays enabled (unlike saferis' GHA setting).
  */
object RemoteCacheItSteps:

  private def imageWord(image: String): Word =
    Word.squoteMake(image).fold(err => sys.error(s"RemoteCacheItSteps.prePull: $err"), identity)

  private val images: List[Word] = List(
    imageWord(RemoteCacheProof.image),
    imageWord(RemoteCacheProof.sbtFixtureImage),
  )

  private val attempts: List[Word] =
    List(Word.lit("1"), Word.lit("2"), Word.lit("3"), Word.lit("4"), Word.lit("5"))

  /** Backoff before the next attempt: 10/20/30/40s after attempts 1..4 (literal sleeps; no arithmetic). */
  private val backoff: Command = If(
    ShTest.IntEq(Word.vq("attempt"), Word.lit("1")),
    Block(Exec("sleep", Word.lit("10"))),
    elifs = List(
      ShTest.IntEq(Word.vq("attempt"), Word.lit("2")) -> Block(Exec("sleep", Word.lit("20"))),
      ShTest.IntEq(Word.vq("attempt"), Word.lit("3")) -> Block(Exec("sleep", Word.lit("30"))),
      ShTest.IntEq(Word.vq("attempt"), Word.lit("4")) -> Block(Exec("sleep", Word.lit("40"))),
    ),
  )

  /** Retrying `docker pull` for both [[RemoteCacheProof]] images (bazel-remote + sbt fixture). */
  private val pullScript: Script = Script.strict(
    ForIn(
      VarName("image"),
      images,
      Block(
        Assign("pulled", Word.lit("0")),
        ForIn(
          VarName("attempt"),
          attempts,
          Block(
            If(
              ShTest.IntEq(Word.vq("pulled"), Word.lit("0")),
              Block(
                If(
                  ShTest.succeeds(Exec("docker", Word.lit("pull"), Word.vq("image"))),
                  Block(Assign("pulled", Word.lit("1"))),
                  elseDo = Some(
                    Block(
                      If(
                        ShTest.IntEq(Word.vq("attempt"), Word.lit("5")),
                        Block(
                          RedirectFd(
                            Exec(
                              "echo",
                              Word.dquote(
                                Word.lit("Failed to pull "),
                                Word.v("image"),
                                Word.lit(" after 5 attempts"),
                              ),
                            ),
                            FileDescriptor.Stdout,
                            FileDescriptor.Stderr,
                          ),
                          Exit(ExitCode.Failure),
                        ),
                        elseDo = Some(Block(backoff)),
                      )
                    )
                  ),
                )
              ),
            )
          ),
        ),
      ),
    )
  )

  val prePull: Steps = Steps.built("pre-pull-remote-cache-images")(
    Step.run(pullScript).named("Pre-pull remote-cache IT images")
  )

end RemoteCacheItSteps
