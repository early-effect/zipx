scalaVersion := "3.9.0"
version      := "1.0.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val phantom = Command.command("definitelyNotARealCommand")(identity)
zipxCapabilities += Capability.once(
  name = CapabilityName("bogus"),
  command = zipxTasks.of(phantom),
  gate = Gate.Always,
)
