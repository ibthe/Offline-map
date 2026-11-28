package app.georgiamap

/** XML 1.0-safe text escaping for GPX element content. */
object GpxXml {
    fun escapeText(value: String): String {
        val clean = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    val codePoint = Character.toCodePoint(ch, value[i + 1])
                    if (codePoint in 0x10000..0x10FFFF) {
                        clean.append(ch).append(value[i + 1])
                    }
                    i += 2
                }
                ch.isSurrogate() -> i++
                ch == '\t' || ch == '\n' || ch == '\r' ||
                    ch.code in 0x20..0xD7FF || ch.code in 0xE000..0xFFFD -> {
                    clean.append(ch)
                    i++
                }
                else -> i++
            }
        }
        return clean.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
