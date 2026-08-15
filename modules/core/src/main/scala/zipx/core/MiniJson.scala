package zipx.core

/** Tiny JSON extractors for OSV responses and the pin-inventory dump. Not a general parser. */
private[core] object MiniJson:

  def extractArray(json: String, key: String): Option[Either[String, String]] =
    val needle = s"\"$key\""
    val idx    = json.indexOf(needle)
    if idx < 0 then None
    else
      var i = idx + needle.length
      while i < json.length && json.charAt(i).isWhitespace do i += 1
      if i < json.length && json.charAt(i) == ':' then
        i += 1
        while i < json.length && json.charAt(i).isWhitespace do i += 1
      if i >= json.length then Some(Left(s"json: missing value for $key"))
      else if json.charAt(i) == '[' then Some(balanced(json, i, '[', ']'))
      else if json.startsWith("null", i) then None
      else Some(Left(s"json: $key is not an array"))
    end if
  end extractArray

  def balanced(json: String, start: Int, open: Char, close: Char): Either[String, String] =
    var depth = 0
    var i     = start
    var inStr = false
    var esc   = false
    while i < json.length do
      val c = json.charAt(i)
      if inStr then
        if esc then esc = false
        else if c == '\\' then esc = true
        else if c == '"' then inStr = false
      else if c == '"' then inStr = true
      else if c == open then depth += 1
      else if c == close then
        depth -= 1
        if depth == 0 then return Right(json.substring(start, i + 1))
      i += 1
    end while
    Left("json: unterminated JSON array")
  end balanced

  def objects(arrayJson: String): List[String] =
    val inner = arrayJson.substring(1, arrayJson.length - 1).trim
    if inner.isEmpty then Nil
    else
      val buf   = List.newBuilder[String]
      var i     = 0
      var depth = 0
      var start = -1
      var inStr = false
      var esc   = false
      while i < inner.length do
        val c = inner.charAt(i)
        if inStr then
          if esc then esc = false
          else if c == '\\' then esc = true
          else if c == '"' then inStr = false
        else if c == '"' then inStr = true
        else if c == '{' then
          if depth == 0 then start = i
          depth += 1
        else if c == '}' then
          depth -= 1
          if depth == 0 && start >= 0 then
            buf += inner.substring(start, i + 1)
            start = -1
        end if
        i += 1
      end while
      buf.result()
    end if
  end objects

  def stringField(obj: String, key: String): Option[String] =
    val needle = s"\"$key\""
    val idx    = obj.indexOf(needle)
    if idx < 0 then None
    else
      val colon = obj.indexOf(':', idx + needle.length)
      if colon < 0 then None
      else
        var i = colon + 1
        while i < obj.length && obj.charAt(i).isWhitespace do i += 1
        if i >= obj.length || obj.charAt(i) != '"' then None
        else
          val from = i + 1
          var j    = from
          var esc  = false
          while j < obj.length do
            val c = obj.charAt(j)
            if esc then esc = false
            else if c == '\\' then esc = true
            else if c == '"' then return Some(unescape(obj.substring(from, j)))
            j += 1
          None
        end if
      end if
    end if
  end stringField

  private def unescape(s: String): String =
    val out = StringBuilder(s.length)
    var i   = 0
    while i < s.length do
      if s.charAt(i) == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case '"'  => out.append('"'); i += 2
          case '\\' => out.append('\\'); i += 2
          case 'n'  => out.append('\n'); i += 2
          case 'r'  => out.append('\r'); i += 2
          case 't'  => out.append('\t'); i += 2
          case c    => out.append(c); i += 2
      else
        out.append(s.charAt(i))
        i += 1
    end while
    out.toString
  end unescape
end MiniJson
