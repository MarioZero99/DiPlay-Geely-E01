package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.Iap2IdentificationProfile

/**
 * Picks the identification message set at run time so both profiles can be tried on one build.
 *
 * The property is read on every use, not cached: `adb shell setprop debug.diplay.iap2.profile
 * compliant` followed by a reconnect is the whole test cycle, and there is no package to reinstall
 * when the answer is wrong.
 *
 * Anything unreadable — a non-Android JVM, an empty property, a typo, a value from a newer build —
 * resolves to [Iap2IdentificationProfile.VERIFIED_V041]. A head unit that boots unattended must not
 * land on a profile this car has never accepted because a property was mistyped.
 */
internal object Iap2ProfileSelection {
    const val PROPERTY = "debug.diplay.iap2.profile"

    fun current(): Iap2IdentificationProfile = from(readProperty())

    fun from(value: String?): Iap2IdentificationProfile =
        when (value?.trim()?.lowercase()) {
            "compliant", "protocol-compliant", "protocol_compliant" ->
                Iap2IdentificationProfile.PROTOCOL_COMPLIANT

            else -> Iap2IdentificationProfile.VERIFIED_V041
        }

    /**
     * `android.os.SystemProperties` is hidden API. Read through reflection and treat every failure
     * as "not set": the fallback is the verified profile, so a failed read is never worse than an
     * absent property.
     */
    private fun readProperty(): String? = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        val get = type.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, PROPERTY, "") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
