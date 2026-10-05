package zipx.core

import neotype.Subtype

/** Narrower than sbt's `Command.validID` so generate-time checks stay a set membership test; operator commands go
  * through [[SbtStep.Built]].
  */
type SbtCommandName = SbtCommandName.Type
object SbtCommandName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an sbt command name must be non-empty"
    else if input.contains(";") then "an sbt command name must be one word; use SbtCommand.session for compounds"
    else if input.matches("[A-Za-z][A-Za-z0-9_-]*") then true
    else
      "invalid sbt command name: must start with a letter and contain only letters, digits, - or _ " +
        "(operator commands use SbtCommand.unsafeBuilt)"
end SbtCommandName
