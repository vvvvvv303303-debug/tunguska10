package io.acionyx.tunguska.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SoftwareAesGcmCipherBoxTest {
    @Test
    fun `encrypt decrypt round trip preserves plaintext`() {
        val cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey())
        val plaintext = "tunguska-profile".encodeToByteArray()
        val aad = "profile-v1".encodeToByteArray()

        val envelope = cipherBox.encrypt(plaintext, aad)
        val decrypted = cipherBox.decrypt(envelope, aad)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt fails when aad differs`() {
        val cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey())
        val envelope = cipherBox.encrypt("sealed".encodeToByteArray(), "expected".encodeToByteArray())

        assertFailsWith<GeneralSecurityException> {
            cipherBox.decrypt(envelope, "unexpected".encodeToByteArray())
        }
    }

    @Test
    fun `decrypt fails when ciphertext is tampered`() {
        val cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey())
        val envelope = cipherBox.encrypt("sealed".encodeToByteArray())
        val tamperedCiphertext = envelope.ciphertext().copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        val tamperedEnvelope = envelope.copy(
            ciphertextBase64 = java.util.Base64.getEncoder().encodeToString(tamperedCiphertext),
        )

        assertFailsWith<GeneralSecurityException> {
            cipherBox.decrypt(tamperedEnvelope)
        }
    }

    @Test
    fun `invalid key size is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SoftwareAesGcmCipherBox(ByteArray(8))
        }
    }
}
