package zipx.core

import neotype.Subtype
import zipx.shell.Patterns

/** The text of what a generated job types at the sbt shell: the `+api/publish` of `sbt '+api/publish'`.
  *
  * Not sbt syntax. zipx does not parse or understand sbt commands, and modelling them would foreclose aliases, cross
  * `+`, config axes and compound `a; b` for no gain. What this type guarantees is the one property the generated file
  * depends on: the command reaches sbt as **one** argument, on one line, and cannot corrupt the YAML that carries it.
  *
  * So the rules are only the ones that would break something. A newline or a control character would end the `run:`
  * scalar or force YAML to quote-escape the whole script. A single quote is *allowed*, because `'…'` cannot escape one
  * but [[SbtCommand.render]] splits the word into `'a'\''b'` segments, which is the concatenation
  * [[zipx.shell.SquoteText]]'s own docs point at. Rejecting it, as an earlier design did, would have made a legitimate
  * command unrepresentable while catching nothing.
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
