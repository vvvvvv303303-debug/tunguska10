package io.acionyx.tunguska.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.acionyx.tunguska.crypto.AES_GCM_TAG_BITS
import io.acionyx.tunguska.crypto.AES_GCM_TRANSFORMATION
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.crypto.CipherEnvelope
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeyStoreCipherBox(
    private val keyAlias: String,
) : CipherBox {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return CipherEnvelope(
            transformation = AES_GCM_TRANSFORMATION,
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
            keyReference = "android-keystore:$keyAlias",
        )
    }

    override fun decrypt(envelope: CipherEnvelope, aad: ByteArray): ByteArray {
        require(envelope.transformation == AES_GCM_TRANSFORMATION) {
            "Unsupported transformation '${envelope.transformation}'."
        }
        val cipher = Cipher.getInstance(envelope.transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(AES_GCM_TAG_BITS, Base64.getDecoder().decode(envelope.ivBase64)),
        )
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertextBase64))
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE: String = "AndroidKeyStore"
    }
}
