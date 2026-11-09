package com.shilapi.xcertplay.instrument

import org.junit.Assert.assertEquals
import org.junit.Test

class XingruiClusterMediaFormatterTest {

    @Test
    fun formatText_nullOrBlank_returnsSingleSpace() {
        assertEquals(" ", XingruiClusterMediaFormatter.formatText(null))
        assertEquals(" ", XingruiClusterMediaFormatter.formatText(""))
        assertEquals(" ", XingruiClusterMediaFormatter.formatText("   "))
    }

    @Test
    fun formatText_withinLimit_returnsTrimmed() {
        assertEquals("Hello World", XingruiClusterMediaFormatter.formatText("Hello World"))
        assertEquals("Hello World", XingruiClusterMediaFormatter.formatText("  Hello World  "))
    }

    @Test
    fun formatText_exceeds31Chars_truncatedTo31() {
        val longText = "12345678901234567890123456789012345"
        val formatted = XingruiClusterMediaFormatter.formatText(longText)
        assertEquals(31, formatted.length)
        assertEquals("1234567890123456789012345678901", formatted)
    }

    @Test
    fun formatText_chineseCharacters_truncatedTo31() {
        val longChinese = "这是一个非常非常长而且超过了三十一个字符的中文歌曲名称测试超长部分"
        val formatted = XingruiClusterMediaFormatter.formatText(longChinese)
        assertEquals(31, formatted.length)
        assertEquals(longChinese.substring(0, 31), formatted)
    }

    @Test
    fun buildMediaPath_alwaysSkipsTheAlbumLayer() {
        assertEquals("http://carplay", XingruiClusterMediaFormatter.buildMediaPath(null))
        assertEquals("http://carplay", XingruiClusterMediaFormatter.buildMediaPath(""))
        assertEquals("http://carplay", XingruiClusterMediaFormatter.buildMediaPath("   "))
        assertEquals(
            "http://carplay",
            XingruiClusterMediaFormatter.buildMediaPath("file:///sdcard/Music/mediacenter/images/cover_0.jpg"),
        )
        assertEquals(
            "http://carplay",
            XingruiClusterMediaFormatter.buildMediaPath("/sdcard/Music/mediacenter/images/cover_1.jpg"),
        )
    }
}
