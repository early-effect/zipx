// sbt-specular 0.14.1 still pins zio-json 0.10.0 on the metabuild; take 1.1.0.
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"
