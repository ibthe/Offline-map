package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxXmlTest {
    @Test
    fun escapesMarkupAndPreservesValidUnicode() {
        assertEquals(
            "Rock &amp; &lt;trail&gt; &quot;雪&quot; 🏔",
            GpxXml.escapeText("Rock & <trail> \"雪\" 🏔")
        )
    }

    @Test
    fun removesXmlForbiddenControlsAndBmpNoncharacters() {
        val input = "a\u0000b\u0008c\u000Bd\uFFFEe\uFFFFf"
        assertEquals("abcdef", GpxXml.escapeText(input))
    }

    @Test
    fun removesUnpairedSurrogatesButKeepsValidPairs() {
        val input = "a\uD800b\uDC00c\uD83C\uDFD4d"
        assertEquals("abc🏔d", GpxXml.escapeText(input))
    }

    @Test
    fun preservesXmlWhitespace() {
        assertEquals("a\tb\nc\rd", GpxXml.escapeText("a\tb\nc\rd"))
    }
}
