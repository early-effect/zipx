// Declaring a command name that is not registered in this build must fail at generate time.
scalaVersion := "3.8.4"
version      := "1.0.0-ci"

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val phantom = Command.command("definitelyNotARealCommand")(identity)
zipxCapabilities += Capability.once(
  name = CapabilityName("bogus"),
  command = zipxTasks.of(phantom),
  gate = Gate.Always,
)
