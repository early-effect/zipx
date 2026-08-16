package zipx.core

/** Generated `project/zipx-ci.env`: JDK and runner labels the version-updates companion reads at runtime so Action and
  * JDK bumps do not rewrite `.github/workflows/`.
  */
object ZipxCiParams:

  val RelPath: String = "project/zipx-ci.env"

  def render(javaVersion: String, runnerOs: String): String =
    s"""${ZipxCatalog.GeneratedHeader}
       |ZIPX_JAVA_VERSION=$javaVersion
       |ZIPX_RUNNER_OS=$runnerOs
       |""".stripMargin
end ZipxCiParams
