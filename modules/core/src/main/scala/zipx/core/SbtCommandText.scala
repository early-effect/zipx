package zipx.core

import neotype.Subtype
import zipx.shell.Patterns

/** The `+api/publish` of `sbt '+api/publish'`. Not parsed as sbt syntax: it only guarantees one argument on one line
  * that cannot corrupt the YAML. A single quote is allowed because [[SbtCommand.render]] splits it into `'a'\''b'`.
  */
type SbtCommandText = SbtCommandText.Type
object SbtCommandText extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an sbt command must be non-empty"
    else if input.contains("\n") then
      "an sbt command must not contain a newline: it is one argument to sbt, and a newline would end the generated " +
        "`run:` line"
    else if input.contains("\r") then "an sbt command must not contain a carriage return"
    else if !input.matches(Patterns.NoControlChars) then
      "an sbt command must not contain control characters: YAML would quote-escape the whole script"
    else true
end SbtCommandText
