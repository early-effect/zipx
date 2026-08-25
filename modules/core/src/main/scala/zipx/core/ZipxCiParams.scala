package zipx.core

/** Generated `project/zipx-ci.env`: JDK and runner labels the version-updates companion reads at runtime so Action and
  * JDK bumps do not rewrite `.github/workflows/`.
  *
  * `ZIPX_CLI_VERSION` is optional and only a **release** (not dynver `-ci`, not `SNAPSHOT`). Baking the in-dev version
  * here makes `zipxWorkflowCheck` fail on the next SHA. Dogfood sets it at runtime via `zipxVersionUpdatesPreSteps`.
  */
object ZipxCiParams:

  val RelPath: String = "project/zipx-ci.env"

  def isReleaseCli(version: String): Boolean =
    version.nonEmpty &&
      !version.endsWith("-SNAPSHOT") &&
      !version.endsWith("-ci") &&
      !version.contains('+')

  def render(javaVersion: String, runnerOs: String, cliVersion: String = ""): String =
    val cli =
      if !isReleaseCli(cliVersion) then ""
      else s"ZIPX_CLI_VERSION=$cliVersion\n"
    s"""${ZipxCatalog.GeneratedHeader}
       |ZIPX_JAVA_VERSION=$javaVersion
       |ZIPX_RUNNER_OS=$runnerOs
       |$cli""".stripMargin
end ZipxCiParams
