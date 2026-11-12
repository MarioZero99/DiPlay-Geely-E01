package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    /** Shown in place of a line that carried a credential. Tells the reader evidence existed. */
    const val WITHHELD = "[withheld: credential or device name]"

    /**
     * The trailing separator is what makes a keyword a value rather than a subject: "pair record" is
     * the name of a thing the log has to be able to talk about — one line says whether the stored
     * record was reused or a new one was made, which is the only evidence separating a stale host from
     * a phone that forgot it — while "pair_record=…" is the record itself and never leaves the device.
     */
    private val secret = Regex("(?i)(pass(word|phrase)?|token\\s*[=:]|private.?key|certificate|pair.?record\\s*[=:]|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    /** The leading boundary is what keeps `filename:` and `username:` out of it. */
    private val namedDevice = Regex("(?i)\\b(phone|device|peer|host)?name\\s*[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    private fun colonCount(line: String): Int {
        var count = 0
        for (c in line) if (c == ':' && ++count >= 7) return 7
        return count
    }

    private fun hasHexRun(line: String, minimum: Int): Boolean {
        var run = 0
        for (c in line) {
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') {
                if (++run >= minimum) return true
            } else {
                run = 0
            }
        }
        return false
    }

    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return WITHHELD
        val colons = colonCount(line)
        var result = line
        // mac 需要 5 个冒号；ipv6 需要 "::" 或 7 个冒号；两者都是匹配的必要条件，跳过不可能命中。
        if (colons >= 5) result = result.replace(mac, "[address]")
        if (hasHexRun(line, 8)) result = result.replace(identifier, "[identifier]")
        if (line.contains('.')) result = result.replace(address, "[ip]")
        if (colons >= 7 || line.contains("::")) result = result.replace(ipv6, "[ip]")
        return result.take(700)
    }
}
