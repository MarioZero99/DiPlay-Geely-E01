package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import android.os.Build
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One ChaCha20-Poly1305 instance for a single thread.
 *
 * Playback reuses this across packets and calls [process] again for each one.
 * The allocating [AirPlayCrypto.chachaSeal] and [AirPlayCrypto.chachaOpen] stay
 * for pairing and other paths that run rarely.
 *
 * Two implementations of the same RFC 8439 transform, tried in this order:
 *
 * 1. [JceAead] — a `javax.crypto.Cipher`. On API 28+ that is the platform's own provider. On the
 *    E01's Android 5.1 the platform throws `NoSuchAlgorithmException`, so the bundled
 *    `conscrypt-android` provider answers instead, and the library doing the work is BoringSSL's
 *    `EVP_AEAD_chacha20_poly1305`: hand-written NEON assembly on arm64 and armv7, the same code
 *    the platform's own TLS uses.
 * 2. BouncyCastle — Java arithmetic. Never removed, so playback does not depend on the bundled
 *    library loading at all: a device without it decodes the same frames, only slower.
 *
 * [path] says which one this instance resolved to; the streams print it on their first frame.
 */
internal class AirPlayAead(
    private val forEncryption: Boolean,
    /** Called once, if the JCE path is retired for this instance; the streams log it. */
    private val onFallback: ((GeneralSecurityException) -> Unit)? = null,
) {
    private val jce = JceAead.create(forEncryption)
    private val bouncyCastle = ChaCha20Poly1305()

    @Volatile
    private var jceFailed = false

    /** The implementation in use right now, for the first-frame log. */
    val path: String
        get() = if (jce != null && !jceFailed) jce.label else "bouncycastle"

    /**
     * Encrypts or decrypts into [output]. Returns the number of bytes written at
     * [outputOffset]. [input] is read from [inputOffset] for [inputLength] bytes,
     * so a receive buffer can be used in place.
     */
    fun process(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        output: ByteArray,
        outputOffset: Int = 0,
    ): Int {
        val jce = jce
        if (jce != null && !jceFailed) {
            try {
                return jce.process(
                    key, nonce, input, inputOffset, inputLength, aad, output, outputOffset,
                )
            } catch (tag: AEADBadTagException) {
                // A tag that does not verify is the answer, not a reason to try another
                // implementation: the other path rejects the same packets.
                throw tag
            } catch (error: GeneralSecurityException) {
                // Retire this provider only when it cannot do the cipher at all. A frame that
                // does not fit — short buffer, wrong block size — is a data problem: the
                // fallback would reject it the same way, and giving up the fast path over one
                // malformed packet would slow every frame after it.
                if (!error.isProviderUnavailable()) throw error
                jceFailed = true
                onFallback?.invoke(error)
            }
        }
        bouncyCastle.init(forEncryption, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val written = bouncyCastle.processBytes(input, inputOffset, inputLength, output, outputOffset)
        val finished = bouncyCastle.doFinal(output, outputOffset + written)
        return written + finished
    }

    private fun GeneralSecurityException.isProviderUnavailable(): Boolean =
        this is NoSuchAlgorithmException ||
            this is NoSuchPaddingException ||
            this is InvalidKeyException ||
            this is InvalidAlgorithmParameterException

    private companion object {
        const val MAC_BITS = 128
    }
}

/**
 * ChaCha20-Poly1305 as a JCE `Cipher`, writing into the caller's array.
 *
 * The platform provider is asked first so an API 28+ build uses the BoringSSL already in the
 * system image and loads nothing extra. Where the platform has no ChaCha20 (API 19-27) the bundled
 * provider answers: `conscrypt-android`, whose native library is packaged for both shipped ABIs.
 *
 * Conscrypt is only ever used through [Cipher.getInstance]; it is not inserted into `Security`, so
 * the app's TLS stack and provider order stay exactly as the platform set them.
 */
internal class JceAead private constructor(
    private val forEncryption: Boolean,
    private val cipher: Cipher,
    val label: String,
) {
    private var cachedKey: ByteArray? = null
    private var cachedKeySpec: SecretKeySpec? = null

    fun process(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        output: ByteArray,
        outputOffset: Int,
    ): Int {
        val mode = if (forEncryption) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        // `IvParameterSpec` copies the nonce it is given, so reusing one instance would replay the
        // first frame's nonce for every frame after it. Twelve bytes allocated per frame is the one
        // part of this path JCE does not let us skip.
        cipher.init(mode, keySpec(key), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(input, inputOffset, inputLength, output, outputOffset)
    }

    /**
     * The `SecretKeySpec` for [key], rebuilt only when a different array arrives.
     *
     * `SecretKeySpec` copies the key, so building one per frame copies 32 bytes for every packet.
     * The cache is keyed on array identity: the playback streams hold their key in a `val` that is
     * never rewritten, which is what makes reuse safe here. A caller that does rewrite a key array
     * in place has to pass a fresh array instead.
     */
    private fun keySpec(key: ByteArray): SecretKeySpec {
        if (key === cachedKey) cachedKeySpec?.let { return it }
        return SecretKeySpec(key, KEY_ALGORITHM).also {
            cachedKey = key
            cachedKeySpec = it
        }
    }

    companion object {
        /**
         * The same cipher under both names it is registered with: Android's providers use the
         * `algorithm/mode/padding` spelling, while the OpenJDK provider the host tests run on
         * registers it as one hyphenated name. Trying both costs one failed `getInstance` per
         * stream and lets the unit tests exercise this class instead of only BouncyCastle.
         */
        private val TRANSFORMATIONS = arrayOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")
        private const val KEY_ALGORITHM = "ChaCha20"

        fun create(forEncryption: Boolean): JceAead? {
            cipher(null)?.let { return JceAead(forEncryption, it, "jce:platform") }
            cipher(bundledProvider)?.let { return JceAead(forEncryption, it, "jce:conscrypt") }
            return null
        }

        private fun cipher(provider: Provider?): Cipher? {
            for (transformation in TRANSFORMATIONS) {
                try {
                    return if (provider == null) Cipher.getInstance(transformation)
                    else Cipher.getInstance(transformation, provider)
                } catch (_: GeneralSecurityException) {
                    // The next name, or the next provider.
                }
            }
            return null
        }

        /**
         * The bundled `conscrypt-android` provider. Not consulted on API 28+, where the platform
         * provider already is that implementation and ours would only cost the native library.
         */
        private val bundledProvider: Provider? by lazy {
            if (Build.VERSION.SDK_INT >= 28) return@lazy null
            try {
                org.conscrypt.Conscrypt.newProvider()
            } catch (_: Throwable) {
                // No library for this ABI, or an image that will not load it. Playback does not
                // depend on this: BouncyCastle is still compiled in and opens the same frames.
                null
            }
        }
    }
}

/** BouncyCastle-backed primitives for the CarPlay pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private val random = SecureRandom()

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        nonce64Into(counter, nonce)
        return nonce
    }

    /** Writes [nonce64] into an existing 12-byte buffer. The first four bytes are cleared. */
    fun nonce64Into(counter: Long, nonce: ByteArray) {
        require(nonce.size >= NONCE_SIZE)
        nonce[0] = 0
        nonce[1] = 0
        nonce[2] = 0
        nonce[3] = 0
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
    }

    /** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.asciiBytes()
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}
