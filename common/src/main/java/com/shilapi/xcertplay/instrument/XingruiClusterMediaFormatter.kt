package com.shilapi.xcertplay.instrument

internal object XingruiClusterMediaFormatter {
    const val MAX_CAN_TEXT_LENGTH = 31
    /** 东软见到 http 开头就跳过封面图层。这台车没有第二块屏，封面图层不会起来。 */
    const val PLAYING_MEDIA_PATH = "http://carplay"
    const val DUMMY_HTTP_MEDIA_PATH = PLAYING_MEDIA_PATH

    fun formatText(raw: String?): String {
        if (raw.isNullOrBlank()) return " "
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_CAN_TEXT_LENGTH) {
            trimmed.substring(0, MAX_CAN_TEXT_LENGTH)
        } else {
            trimmed
        }
    }

    fun buildMediaPath(sdcardUriOrPath: String?): String = PLAYING_MEDIA_PATH
}
