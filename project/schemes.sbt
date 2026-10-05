// The metabuild mixes zio-json binary lines, which sbt's eviction check would otherwise reject.
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"
