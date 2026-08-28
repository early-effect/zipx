package zipx.core

/** Sticky PR comment for suggested Ship edits. Tests assert the body; they never POST. */
object ModverComment:

  val Marker: String = "<!-- zipx-modver -->"

  def body(report: ModverReport, suggestion: Option[String] = None): String =
    val table =
      if report.rows.isEmpty then "No Ship / ShipGroup bumps required on this PR."
      else
        val header = "| Identity | From | Suggested | Written | Status |"
        val sep    = "|---|---|---|---|---|"
        val rows   = report.rows.map { r =>
          s"| `${r.identity}` | `${r.from}` | `${r.suggested}` | `${r.written}` | ${r.status} |"
        }
        (header +: sep +: rows).mkString("\n")
    val fence = suggestion.fold("") { ctor =>
      s"""
         |
         |```suggestion
         |$ctor
         |```
         |""".stripMargin
    }
    s"""$Marker
       |
       |## zipx module versions
       |
       |$table
       |$fence
       |""".stripMargin
  end body
end ModverComment
