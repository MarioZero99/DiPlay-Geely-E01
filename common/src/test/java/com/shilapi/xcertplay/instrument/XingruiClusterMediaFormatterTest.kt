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

        // 韩文曲目没有字库，括号里的英文释义是唯一能显示的内容，不能被当罗马音剥掉
        assertEquals("I Love You", XingruiClusterMediaFormatter.optimizeCjkDisplay("사랑해 (I Love You)"))

        // 斜杠分隔双语歌词：提取中文翻译
        val slashBilingual = "さよなら / 再见"
        assertEquals("再见", XingruiClusterMediaFormatter.optimizeCjkDisplay(slashBilingual))
        // 韩文 + 斜杠双语同样要取中文，否则只剩一片空白
        assertEquals("我爱你", XingruiClusterMediaFormatter.optimizeCjkDisplay("사랑해 / 我爱你"))
    }

    @Test
    fun optimizeCjkDisplay_preservesStandardVersionsWithoutFalseDrop() {
        // 中文歌曲带 (Live)、(伴奏)、(feat. xxx) 等，不得误删
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.optimizeCjkDisplay("七里香 (Live)"))
        assertEquals("稻香 (伴奏)", XingruiClusterMediaFormatter.optimizeCjkDisplay("稻香 (伴奏)"))
        assertEquals("怎么了 (feat. 周兴哲)", XingruiClusterMediaFormatter.optimizeCjkDisplay("怎么了 (feat. 周兴哲)"))
        // 日文曲目的版本标记也不能被当成中文翻译，把歌名换成「伴奏」
        assertEquals("夜に駆ける (Live)", XingruiClusterMediaFormatter.optimizeCjkDisplay("夜に駆ける (Live)"))
        // 假名写的版本标记同样不能当注音剥掉
        assertEquals("夜に駆ける (カラオケ)", XingruiClusterMediaFormatter.optimizeCjkDisplay("夜に駆ける (カラオケ)"))
        // 超长日文名里的「(中文版)」是版本标记，不能当成翻译顶替歌名
        val versioned = "さよならの夏~コクリコ坂から~ いつかまた会おうと誓った日 (中文版)"
        assertTrue(versioned.length > XingruiClusterMediaFormatter.MAX_CAN_TEXT_LENGTH)
        assertTrue(XingruiClusterMediaFormatter.optimizeCjkDisplay(versioned) != "中文版")
    }

    @Test
    fun stripTranslationSuffix_removesOnlyChineseTranslationBracket() {
        assertEquals("さよならの夏", XingruiClusterMediaFormatter.stripTranslationSuffix("さよならの夏 (道别之夏)"))
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.stripTranslationSuffix("七里香 (Live)"))
        // 翻译括号后面还跟着版本标记时，也要找到那个中文括号
        assertEquals(
            "さよならの夏 (Live)",
            XingruiClusterMediaFormatter.stripTranslationSuffix("さよならの夏 (道别之夏) (Live)"),
        )
    }

    @Test
    fun stripTranslationSuffix_removesTranslationWhenBaseHasNoKanaOrHangul() {
        // 英文歌/纯汉字日文歌的歌词包后缀同样不带括号翻译
        assertEquals("See You Again", XingruiClusterMediaFormatter.stripTranslationSuffix("See You Again (当我们再相见)"))
        assertEquals("愛唄", XingruiClusterMediaFormatter.stripTranslationSuffix("愛唄 (爱之歌)"))
    }

    @Test
    fun optimizeCjkDisplay_extractsEnglishGlossWithPunctuationAndDigits() {
        // 韩文字库缺失，括号里的英文释义是唯一能显示的内容；撇号、连字符、数字不能让它失效
        assertEquals("Don't Cry", XingruiClusterMediaFormatter.optimizeCjkDisplay("노래 (Don't Cry)"))
        assertEquals("It's Okay", XingruiClusterMediaFormatter.optimizeCjkDisplay("노래 (It's Okay)"))
        assertEquals("Fate - OST", XingruiClusterMediaFormatter.optimizeCjkDisplay("인연 (Fate - OST)"))
        assertEquals("I Love You 2", XingruiClusterMediaFormatter.optimizeCjkDisplay("사랑해 (I Love You 2)"))
    }

    @Test
    fun optimizeCjkDisplay_keepsCompoundLiveVersions() {
        assertEquals("夜に駆ける (Live in Tokyo)", XingruiClusterMediaFormatter.optimizeCjkDisplay("夜に駆ける (Live in Tokyo)"))
        // 韩文分支不能把版本标记当成歌名显示
        assertEquals("노래 (Live in Tokyo)", XingruiClusterMediaFormatter.optimizeCjkDisplay("노래 (Live in Tokyo)"))
        // 但不能宽到把 Liverpool 也当 Live 版本，它仍按罗马音注音剥掉
        assertEquals("夜に駆ける", XingruiClusterMediaFormatter.optimizeCjkDisplay("夜に駆ける (Liverpool)"))
    }

    @Test
    fun optimizeCjkDisplay_normalizesFullWidthSlash() {
        assertEquals("さよなら/再见", XingruiClusterMediaFormatter.cleanClusterText("さよなら／再见"))
        assertEquals("再见", XingruiClusterMediaFormatter.optimizeCjkDisplay("さよなら／再见"))
    }

    @Test
    fun optimizeCjkDisplay_keepsWholeTitleInBrackets() {
        // 整首曲名都在括号里时剥完会变空，必须回退原串
        assertEquals("(ボーノ)", XingruiClusterMediaFormatter.optimizeCjkDisplay("(ボーノ)"))
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
