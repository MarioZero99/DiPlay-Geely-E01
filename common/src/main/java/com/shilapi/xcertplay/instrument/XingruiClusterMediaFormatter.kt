package com.shilapi.xcertplay.instrument

internal object XingruiClusterMediaFormatter {
    const val MAX_CAN_TEXT_LENGTH = 31
    const val DUMMY_HTTP_MEDIA_PATH = "stream&&&http://none"

    fun formatText(raw: String?): String {
        if (raw.isNullOrBlank()) return " "
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_CAN_TEXT_LENGTH) {
            trimmed.substring(0, MAX_CAN_TEXT_LENGTH)
        } else {
            trimmed
        }
    }

    fun buildMediaPath(sdcardUriOrPath: String?): String {
        if (sdcardUriOrPath.isNullOrBlank()) {
            return DUMMY_HTTP_MEDIA_PATH
        }
        val safeUri = if (sdcardUriOrPath.startsWith("file://")) {
            sdcardUriOrPath
        } else {
            "file://$sdcardUriOrPath"
        }
        return "carplay&&&$safeUri"
    }
}
