package me.rerere.rikkahub.ui.components.richtext

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgRenderedCodeBlockTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun svg_code_is_encoded_as_svg_data_uri() {
        val svg = """<svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""

        val dataUri = svgCodeToDataUri(svg)

        assertTrue(dataUri.startsWith("data:image/svg+xml;base64,"))
        assertEquals(
            svg,
            String(Base64.decode(dataUri.substringAfter("base64,")))
        )
    }
}
