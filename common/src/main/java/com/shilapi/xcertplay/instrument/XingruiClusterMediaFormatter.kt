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
            .replace("・", " · ")
            .replace(RE_WHITESPACE, " ")
            .trim()
        return if (cleaned.isEmpty()) " " else cleaned
    }

    fun buildMediaPath(sdcardUriOrPath: String?): String = PLAYING_MEDIA_PATH
}
