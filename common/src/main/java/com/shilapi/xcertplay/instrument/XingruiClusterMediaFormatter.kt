package com.shilapi.xcertplay.instrument

internal object XingruiClusterMediaFormatter {
    const val MAX_CAN_TEXT_LENGTH = 31
    /** 东软见到 http 开头就跳过封面图层。这台车没有第二块屏，封面图层不会起来。 */
    const val PLAYING_MEDIA_PATH = "http://carplay"
    const val DUMMY_HTTP_MEDIA_PATH = PLAYING_MEDIA_PATH

    private val RE_WHITESPACE = Regex("\\s+")

    fun formatText(raw: String?): String {
        if (raw.isNullOrBlank()) return " "
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_CAN_TEXT_LENGTH) {
            trimmed.substring(0, MAX_CAN_TEXT_LENGTH)
        } else {
            trimmed
        }
    }

    /**
     * 把 CarPlay 送来的任意文本收成仪表能吃的单行：
     * 换行、全角空格折成半角空格，日文波浪号和片假名中点换成字库里有的半角/中点，
     * 再折叠连续空白。仪表只认 ASCII + GBK，换行和 U+3000 在它那边会占一个字宽或直接空白。
     */
    fun cleanClusterText(raw: String?): String {
        if (raw.isNullOrBlank()) return " "
        val cleaned = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('　', ' ')
            .replace('～', '~')
            .replace('〜', '~')
            .replace('／', '/')
            .replace("・", " · ")
            .replace(RE_WHITESPACE, " ")
            .trim()
        return if (cleaned.isEmpty()) " " else cleaned
    }

    private val RE_HANGUL = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]")
    private val RE_KANA = Regex("[\\u3040-\\u30ff]")
    private val RE_HANZI = Regex("[\\u4e00-\\u9fff]")
    private val RE_BRACKETS = Regex("[\\(\\[（【](.*?)[\\)\\]）】]")
    private val RE_LIVE = Regex("(?i)^live\\b")

    /**
     * 仪表字库只有 ASCII + GBK：假名不全，韩文整片空白。
     * 日韩曲目能做的三件事，按优先级：
     * 1. "日文 / 中文" 斜杠双语：取中文。
     * 2. 韩文曲目（完全没有字库）或有中文翻译的日文曲目超长时：只显示中文翻译。
     * 3. 括号里是纯假名/罗马音注音：剥掉，把 31 字让给正文。
     */
    fun optimizeCjkDisplay(raw: String?): String {
        val cleaned = cleanClusterText(raw)
        if (cleaned.isBlank()) return " "

        val hasHangul = RE_HANGUL.containsMatchIn(cleaned)
        val hasKana = RE_KANA.containsMatchIn(cleaned)
        if (!hasHangul && !hasKana) return formatText(cleaned)

        val brackets = RE_BRACKETS.findAll(cleaned).toList()
        val chineseTranslation = brackets.firstOrNull { bracket ->
            val content = bracket.groupValues[1].trim()
            isChineseTranslation(content) && !isVersionMarker(content)
        }?.groupValues?.get(1)?.trim()

        // 1. "日文或韩文 / 中文"
        if ((hasKana || hasHangul) && cleaned.contains("/")) {
            val parts = cleaned.split("/").map { it.trim() }
            if (parts.size == 2 && isChineseTranslation(parts[1])) return formatText(parts[1])
        }

        // 2. 韩文没有字库，整首都没法显示：优先中文翻译，其次括号里的英文释义，都没有就原样。
        if (hasHangul) {
            val displayable = chineseTranslation ?: brackets.firstOrNull { bracket ->
                val content = bracket.groupValues[1].trim()
                isDisplayableGloss(content) && !isVersionMarker(content)
            }?.groupValues?.get(1)?.trim()
            return formatText(displayable ?: cleaned)
        }

        // 3. 剥注音。中文翻译和版本标记（Live/伴奏/feat. …）不是注音，不能动。
        var result = cleaned
        for (bracket in brackets) {
            val content = bracket.groupValues[1].trim()
            val kanaOnly = RE_KANA.containsMatchIn(content) &&
                !RE_HANZI.containsMatchIn(content) &&
                !RE_HANGUL.containsMatchIn(content)
            val romajiOnly = isAsciiWord(content) && !isVersionMarker(content)
            if ((kanaOnly || romajiOnly) && !isVersionMarker(content)) {
                result = result.replace(bracket.value, "").trim()
            }
        }
        result = cleanClusterText(result)
        // 整首曲名都在括号里时剥完会变空：回退原串，不能让第一行只剩一个空格。
        if (result.isBlank()) return formatText(cleaned)

        // 4. 日文超长：中文翻译是完整可显示的那一半，优先于被截断的原歌名。
        if (hasKana && chineseTranslation != null && result.length > MAX_CAN_TEXT_LENGTH) {
            return formatText(chineseTranslation)
        }
        return formatText(result)
    }

    /**
     * 判断文本是否是中文翻译：包含汉字，且绝不包含日文假名与韩文。
     */
    fun isChineseTranslation(text: String): Boolean {
        return RE_HANZI.containsMatchIn(text) &&
            !RE_KANA.containsMatchIn(text) &&
            !RE_HANGUL.containsMatchIn(text)
    }

    /**
     * 去掉歌名末尾的中文翻译括号，给歌词包的后缀匹配用：
     * 上一首是 "日文 (中文)" 时，歌词包往往只带 " - 日文" 这一截。
     */
    fun stripTranslationSuffix(raw: String?): String {
        val cleaned = cleanClusterText(raw)
        if (cleaned.isBlank()) return " "

        // 取最后一个中文括号，而不是最后一个括号："(中文) (Live)" 也要能找到翻译。
        val match = RE_BRACKETS.findAll(cleaned)
            .lastOrNull { isChineseTranslation(it.groupValues[1]) } ?: return cleaned
        // 去掉括号后可能留下两个空格，重新折一遍
        val stripped = cleanClusterText(cleaned.removeRange(match.range))
        return if (stripped.isBlank()) cleaned else stripped
    }

    /** 版本/编制信息，不是翻译：不能被当成中文翻译替换歌名，也不能当罗马音注音剥掉。 */
    private fun isVersionMarker(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // "Live"/"Live in Tokyo" 是一类；用词边界，避免把 Liverpool 也当成 Live 版本。
        if (RE_LIVE.containsMatchIn(trimmed)) return true
        val lower = trimmed.lowercase()
        if (lower.startsWith("feat") ||
            lower.startsWith("instrumental") ||
            lower.startsWith("inst") ||
            lower.startsWith("remix") ||
            lower.startsWith("acoustic") ||
            lower.startsWith("cover") ||
            lower.startsWith("version") ||
            lower.startsWith("ver.") ||
            lower.startsWith("ver ") ||
            lower.startsWith("tv size") ||
            lower.startsWith("tv ver") ||
            lower.startsWith("edit") ||
            lower.endsWith(" version") ||
            lower.endsWith(" edit") ||
            lower.endsWith(" mix") ||
            lower == "remastered" ||
            lower == "deluxe"
        ) {
            return true
        }
        // "中文版"/"日本版" 这类 CJK 版本标记
        if (trimmed.endsWith("版") || trimmed.contains("バージョン")) return true
        return trimmed == "伴奏" || trimmed == "纯音乐" || trimmed == "翻唱" ||
            trimmed == "カラオケ" || trimmed == "インスト"
    }

    /** 纯 ASCII 字母与空白，才是罗马音注音；汉字不是。 */
    private fun isAsciiWord(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasLetter = false
        for (c in text) {
            if (c.code >= 0x80) return false
            if (c.isLetter()) hasLetter = true else if (!c.isWhitespace()) return false
        }
        return hasLetter
    }

    /**
     * 韩文曲目括号里的英文释义：仪表字库只认 ASCII，只要全是可打印 ASCII 且含字母就能显示。
     * 撇号、连字符、数字都常见（Don't Cry / Fate - OST / I Love You 2），不能像罗马音那样严。
     */
    private fun isDisplayableGloss(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasLetter = false
        for (c in text) {
            if (c.code < 0x20 || c.code > 0x7e) return false
            if (c.isLetter()) hasLetter = true
        }
        return hasLetter
    }

    fun buildMediaPath(sdcardUriOrPath: String?): String = PLAYING_MEDIA_PATH
}
