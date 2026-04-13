package io.acionyx.tunguska.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

const val AES_GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
const val AES_GCM_IV_BYTES: Int = 12
const val AES_GCM_TAG_BITS: Int = 128

interface CipherBox {
    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): CipherEnvelope

    @Throws(GeneralSecurityException::class)
    fun decrypt(envelope: CipherEnvelope, aad: ByteArray = ByteArray(0)): ByteArray
}

@Serializable
data class CipherEnvelope(
    val transformation: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val keyReference: String? = null,
) {
    fun iv(): ByteArray = Base64.getDecoder().decode(ivBase64)

    fun ciphertext(): ByteArray = Base64.getDecoder().decode(ciphertextBase64)
}

class SoftwareAesGcmCipherBox(
    keyBytes: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) : CipherBox {
    private val keySpec: SecretKeySpec

    init {
        require(keyBytes.size in VALID_AES_KEY_SIZES_BYTES) {
            "AES key must be 128, 192, or 256 bits."
        }
        keySpec = SecretKeySpec(keyBytes.copyOf(), "AES")
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): CipherEnvelope {
        val iv = ByteArray(AES_GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return CipherEnvelope(
            transformation = AES_GCM_TRANSFORMATION,
            ivBase64 = iv.encodeBase64(),
            ciphertextBase64 = ciphertext.encodeBase64(),
            keyReference = SOFTWARE_KEY_REFERENCE,
        )
    }

    override fun decrypt(envelope: CipherEnvelope, aad: ByteArray): ByteArray {
        require(envelope.transformation == AES_GCM_TRANSFORMATION) {
            "Unsupported transformation '${envelope.transformation}'."
        }
        val cipher = Cipher.getInstance(envelope.transformation)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(AES_GCM_TAG_BITS, envelope.iv()))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(envelope.ciphertext())
    }

    companion object {
        private const val SOFTWARE_KEY_REFERENCE: String = "software:aes-gcm"
        private val VALID_AES_KEY_SIZES_BYTES = setOf(16, 24, 32)

        fun generateKey(
            secureRandom: SecureRandom = SecureRandom(),
            sizeBytes: Int = 32,
        ): ByteArray {
            require(sizeBytes in VALID_AES_KEY_SIZES_BYTES) {
                "AES key must be 128, 192, or 256 bits."
            }
            return ByteArray(sizeBytes).also(secureRandom::nextBytes)
        }
    }
}

private fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)
