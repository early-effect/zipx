package zipx.core

import neotype.Subtype

/** A single sbt command name the build defines (built-in, plugin `commands +=`, or `addCommandAlias`).
  *
  * Narrower than sbt's own `Command.validID` (which allows operator names): identifier-shaped only so generate-time
  * checks stay a set membership test. Operator commands go through [[SbtStep.Built]].
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
