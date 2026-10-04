package zipx.core

import zio.test.*
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.*

object ShellWorkflowSpec extends ZIOSpecDefault:

  private val proof = ShellWorkflow(
    path = ".github/workflows/publish-proof.yml",
    name = "publish-proof",
    steps = _ =>
      List(
        Step
          .run(Script(Exec("scala-cli", Word.lit("run"), Word.lit("--server=false"), Word.lit("lab/publish"))))
          .named("Prove publish")
          .build
      ),
  )

  def spec = suite("ShellWorkflow")(
    test("a dispatch workflow gets the sbt toolchain and then the build's steps") {
      val yaml = ShellWorkflow.render(proof, PlanConfig())
      assertTrue(
        yaml.exists(_.contains("workflow_dispatch")),
        yaml.exists(_.contains("zipx sbt setup")),
        yaml.exists(_.contains("scala-cli run --server=false lab/publish")),
        yaml.exists(!_.contains("sbt \"")),
      )
    },
    test("ci.yml is not a shell workflow path") {
      val yaml = ShellWorkflow.render(proof.copy(path = ".github/workflows/ci.yml"), PlanConfig())
      assertTrue(yaml.isLeft)
    },
  )
end ShellWorkflowSpec
