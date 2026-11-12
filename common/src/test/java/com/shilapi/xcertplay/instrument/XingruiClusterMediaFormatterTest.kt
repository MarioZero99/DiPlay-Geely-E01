package com.shilapi.xcertplay.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun cleanClusterText_normalizesWhitespaceAndCjkPunctuation() {
        // 全角空格折叠与转半角
        assertEquals("A B", XingruiClusterMediaFormatter.cleanClusterText("A　B"))
        // 换行符替换为空格
        assertEquals("Line1 Line2", XingruiClusterMediaFormatter.cleanClusterText("Line1\nLine2"))
        assertEquals("Line1 Line2", XingruiClusterMediaFormatter.cleanClusterText("Line1\r\nLine2"))
        // 日文波浪号与全角符号规整化
        assertEquals("夏~コクリコ坂から", XingruiClusterMediaFormatter.cleanClusterText("夏～コクリコ坂から"))
        assertEquals("A · B", XingruiClusterMediaFormatter.cleanClusterText("A・B"))
        // 多余空白折叠与首尾 trim
        assertEquals("Hello World", XingruiClusterMediaFormatter.cleanClusterText("  Hello   World  "))
    }

    @Test
    fun optimizeCjkDisplay_stripsFuriganaRomajiAnnotations() {
        // 剥离纯假名/罗马音注音括号，释放仪表字符空间
        assertEquals("仓木麻衣", XingruiClusterMediaFormatter.optimizeCjkDisplay("仓木麻衣 (くらき まい)"))
        assertEquals("手嶌葵", XingruiClusterMediaFormatter.optimizeCjkDisplay("手嶌葵 (てしま あおい)"))
        assertEquals("Buono!", XingruiClusterMediaFormatter.optimizeCjkDisplay("Buono! (ボーノ)"))
    }

    @Test
    fun optimizeCjkDisplay_extractsChineseTranslationWhenCjkPresent() {
        // 带中文翻译的日文歌曲：优先提取中文，确保在31字内且含中文
        val jpLong = "さよならの夏~コクリコ坂から~ (道别之夏 ~虞美人盛开的山坡~)"
        val optJp = XingruiClusterMediaFormatter.optimizeCjkDisplay(jpLong)
        assertTrue(optJp.length <= XingruiClusterMediaFormatter.MAX_CAN_TEXT_LENGTH)
        assertEquals("道别之夏 ~虞美人盛开的山坡~", optJp)

        // 韩文歌曲带中文翻译：直接提取中文翻译避免大片字空白
        val krWithZh = "사랑해요 (我爱你)"
        assertEquals("我爱你", XingruiClusterMediaFormatter.optimizeCjkDisplay(krWithZh))

        // 斜杠分隔双语歌词：提取中文翻译
        val slashBilingual = "さよなら / 再见"
        assertEquals("再见", XingruiClusterMediaFormatter.optimizeCjkDisplay(slashBilingual))
    }

    @Test
    fun optimizeCjkDisplay_preservesStandardVersionsWithoutFalseDrop() {
        // 中文歌曲带 (Live)、(伴奏)、(feat. xxx) 等，不得误删
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.optimizeCjkDisplay("七里香 (Live)"))
        assertEquals("稻香 (伴奏)", XingruiClusterMediaFormatter.optimizeCjkDisplay("稻香 (伴奏)"))
        assertEquals("怎么了 (feat. 周兴哲)", XingruiClusterMediaFormatter.optimizeCjkDisplay("怎么了 (feat. 周兴哲)"))
        // 日文曲目的版本标记也不能被当成中文翻译，把歌名换成「伴奏」
        assertEquals("夜に駆ける (Live)", XingruiClusterMediaFormatter.optimizeCjkDisplay("夜に駆ける (Live)"))
    }

    @Test
    fun stripTranslationSuffix_removesOnlyChineseTranslationBracket() {
        assertEquals("さよならの夏", XingruiClusterMediaFormatter.stripTranslationSuffix("さよならの夏 (道别之夏)"))
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.stripTranslationSuffix("七里香 (Live)"))
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
