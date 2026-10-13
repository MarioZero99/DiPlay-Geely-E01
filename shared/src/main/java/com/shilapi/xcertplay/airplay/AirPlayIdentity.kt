package com.shilapi.xcertplay.airplay

import java.security.MessageDigest
import java.util.UUID

/** Long-term Ed25519 identity of the CarPlay accessory, persisted by the caller. */
data class AirPlayIdentity(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val pairingId: String,
) {
    val publicKeyHex: String get() = publicKey.toHexString()

    /**
     * What the accessory calls itself: the first six bytes of the SHA-256 of its public key, forced to
     * a locally-administered unicast address.
     *
     * One value, for everywhere the accessory has to name itself -- the AirPlay `deviceid` in the
     * Bonjour record and in `/info`, the identification's serial number, and the host MAC. It lives on
     * the identity rather than at each call site because a declaration that disagrees with itself is
     * worse than a missing one: the phone is asked to believe a certificate on the strength of an
     * accessory that cannot say who it is.
     */
    val deviceId: String by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest(publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    companion object {
        fun generate(): AirPlayIdentity {
            val keyPair = AirPlayCrypto.ed25519Generate()
            return AirPlayIdentity(keyPair.privateKey, keyPair.publicKey, UUID.randomUUID().toString())
        }
    }
}
