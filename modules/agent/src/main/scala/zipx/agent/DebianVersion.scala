package zipx.agent

/** Debian version order (`epoch:upstream-revision`). The agent apt pins are not `x.y.z`, so the sbt-image numbered
  * candidate cannot move them.
  */
object DebianVersion:

  def compare(left: String, right: String): Int =
    val (le, lu, lr) = parts(left)
    val (re, ru, rr) = parts(right)
    val epoch        = compareDigits(le, re)
    if epoch != 0 then epoch
    else
      val upstream = comparePart(lu, ru)
      if upstream != 0 then upstream else comparePart(lr, rr)

  val ordering: Ordering[String] = (left, right) => compare(left, right)

  private def parts(raw: String): (String, String, String) =
    val colon         = raw.indexOf(':')
    val (epoch, rest) =
      if colon > 0 && raw.substring(0, colon).forall(_.isDigit) then (raw.substring(0, colon), raw.substring(colon + 1))
      else ("0", raw)
    val hyphen = rest.lastIndexOf('-')
    if hyphen < 0 then (epoch, rest, "")
    else (epoch, rest.substring(0, hyphen), rest.substring(hyphen + 1))

  private def comparePart(left: String, right: String): Int =
    compareAt(left, right, 0, 0)

  private def compareAt(left: String, right: String, li: Int, ri: Int): Int =
    val (ln, rn, non) = nonDigits(left, right, li, ri)
    if non != 0 then non
    else
      val (ld, lNext) = digits(left, ln)
      val (rd, rNext) = digits(right, rn)
      val dig         = compareDigits(ld, rd)
      if dig != 0 then dig
      else if lNext >= left.length && rNext >= right.length then 0
      else compareAt(left, right, lNext, rNext)
  end compareAt

  /** Non-digit run. `~` sorts before the end of the part. Letters sort before other characters. */
  private def nonDigits(left: String, right: String, li: Int, ri: Int): (Int, Int, Int) =
    val leftDone  = li >= left.length || left.charAt(li).isDigit
    val rightDone = ri >= right.length || right.charAt(ri).isDigit
    if leftDone && rightDone then (li, ri, 0)
    else
      val lc  = if leftDone then '\u0000' else left.charAt(li)
      val rc  = if rightDone then '\u0000' else right.charAt(ri)
      val cmp = Integer.compare(weight(lc), weight(rc))
      if cmp != 0 then (li, ri, cmp)
      else nonDigits(left, right, li + step(lc), ri + step(rc))
  end nonDigits

  private def digits(raw: String, from: Int): (String, Int) =
    def end(i: Int): Int =
      if i < raw.length && raw.charAt(i).isDigit then end(i + 1) else i
    val stop = end(from)
    val bare = raw.substring(from, stop).dropWhile(_ == '0')
    (if bare.isEmpty then "0" else bare, stop)

  private def compareDigits(left: String, right: String): Int =
    if left.length != right.length then Integer.compare(left.length, right.length)
    else left.compare(right)

  private def weight(c: Char): Int =
    if c == '\u0000' then 0
    else if c == '~' then -1
    else if c.isLetter then c.toInt
    else c.toInt + 256

  private def step(c: Char): Int = if c == '\u0000' then 0 else 1
end DebianVersion
