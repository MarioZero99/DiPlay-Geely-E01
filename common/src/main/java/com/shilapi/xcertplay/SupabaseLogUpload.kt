package com.shilapi.xcertplay

import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot upload of a report the user just generated.
 * Nothing here runs unless [post] is called.
 */
internal object SupabaseLogUpload {
    const val MAX_BYTES = 1024 * 1024

    fun enabled(url: String, key: String): Boolean =
        url.startsWith("https://") && url.contains("supabase.co") && key.isNotBlank()

    /**
     * A report cut into objects the bucket will take.
     *
     * The bucket caps one object at [MAX_BYTES], and a report can be several times that. Cutting the
     * report to fit loses exactly the newest lines — the ones a failure is read from — so the upload
     * sends all of it, in parts, instead.
     *
     * The cut is made on the bytes rather than by walking lines: a part boundary has to leave the text
     * exactly as it was, and every way of reassembling lines gets that wrong somewhere (a trailing
     * newline is the usual place). A boundary goes at the last newline that fits, so a part reads as a
     * log does; a line too long to fit is cut at a character boundary instead, because there is nowhere
     * else left to cut it. Nothing is dropped or added: the parts are the report, byte for byte.
     */
    fun parts(text: String, maxBytes: Int = MAX_BYTES): List<ByteArray> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ArrayList<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            var end = minOf(start + maxBytes, bytes.size)
            if (end < bytes.size) {
                var newline = end
                while (newline > start && bytes[newline - 1] != '\n'.code.toByte()) newline--
                end = if (newline > start) newline else characterBoundary(bytes, start, end)
            }
            out.add(bytes.copyOfRange(start, end))
            start = end
        }
        return out
    }

    /**
     * What one part is called in the bucket, so that a split report reads in order.
     *
     * A report that fits keeps the plain name it always had: one object, as before. A split one names
     * its place in the sequence rather than the time it happened to be sent.
     */
    fun partName(objectName: String, index: Int, count: Int): String {
        if (count <= 1) return objectName
        val dot = objectName.lastIndexOf('.')
        if (dot <= 0) return "$objectName-p${index + 1}of$count"
        return objectName.substring(0, dot) + "-p${index + 1}of$count" + objectName.substring(dot)
    }

    /** The largest index in `start..limit` that is not inside a character, and past `start` itself. */
    private fun characterBoundary(bytes: ByteArray, start: Int, limit: Int): Int {
        var end = limit
        while (end > start && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return if (end > start) end else limit
    }

    fun post(url: String, key: String, objectName: String, body: ByteArray) {
        check(enabled(url, key)) { "Supabase upload is not configured" }
        check(body.size in 1..MAX_BYTES) { "Report is ${body.size} bytes; the cap is $MAX_BYTES" }
        val safeName = objectName.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-'
        }.joinToString("").ifBlank { "diplay.txt" }
        val endpoint = URL(url.trimEnd('/') + "/storage/v1/object/logs/$safeName")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("apikey", key)
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val message = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = when {
                    message.contains("Bucket not found") || message.contains("NoSuchBucket") ->
                        "Supabase 里还没有名为 logs 的存储位置。在 Storage 新建私有 bucket logs，单文件限制 1048576 字节，并允许 anon 写入。"
                    else -> message.ifBlank { "HTTP $code" }
                }
                error(reason.take(400))
            }
        } finally {
            connection.disconnect()
        }
    }
}
