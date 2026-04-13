package io.acionyx.tunguska.storage

import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.crypto.CipherEnvelope
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.ProfileIr
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PROFILE_STORE_SCHEMA: String = "io.acionyx.tunguska.profile.v1"
private val PROFILE_STORE_AAD: ByteArray = PROFILE_STORE_SCHEMA.toByteArray(Charsets.UTF_8)

class EncryptedProfileStore(
    path: Path,
    private val cipherBox: CipherBox,
    private val json: Json = StorageJson.instance,
) {
    val path: Path = path.toAbsolutePath().normalize()

    fun load(): StoredProfile? {
        if (!path.existsCompat()) {
            return null
        }
        val envelope = json.decodeFromString<StoredProfileEnvelope>(path.readUtf8Text())
        require(envelope.schema == PROFILE_STORE_SCHEMA) {
            "Unsupported profile store schema '${envelope.schema}'."
        }
        val plaintext = cipherBox.decrypt(envelope.ciphertext, PROFILE_STORE_AAD).toString(Charsets.UTF_8)
        val profile = CanonicalJson.instance.decodeFromString<ProfileIr>(plaintext)
        val actualHash = profile.canonicalHash()
        require(actualHash == envelope.profileHash) {
            "Stored profile hash mismatch for '${profile.id}'."
        }
        val issues = profile.validate()
        require(issues.isEmpty()) {
            "Stored profile failed validation: ${issues.joinToString { "${it.field}: ${it.message}" }}"
        }
        return StoredProfile(
            profile = profile,
            profileHash = actualHash,
            encryptedAtEpochMs = envelope.encryptedAtEpochMs,
            path = path,
            keyReference = envelope.ciphertext.keyReference,
        )
    }

    fun save(profile: ProfileIr): StoredProfile {
        val issues = profile.validate()
        require(issues.isEmpty()) {
            "Profile validation failed: ${issues.joinToString { "${it.field}: ${it.message}" }}"
        }
        val canonicalJson = CanonicalJson.encodeProfile(profile)
        val encryptedAtEpochMs = System.currentTimeMillis()
        val storedEnvelope = StoredProfileEnvelope(
            schema = PROFILE_STORE_SCHEMA,
            profileId = profile.id,
            profileHash = profile.canonicalHash(),
            encryptedAtEpochMs = encryptedAtEpochMs,
            ciphertext = cipherBox.encrypt(canonicalJson.toByteArray(Charsets.UTF_8), PROFILE_STORE_AAD),
        )
        atomicWrite(json.encodeToString(storedEnvelope))
        return StoredProfile(
            profile = profile,
            profileHash = storedEnvelope.profileHash,
            encryptedAtEpochMs = encryptedAtEpochMs,
            path = path,
            keyReference = storedEnvelope.ciphertext.keyReference,
        )
    }

    fun delete() {
        path.deleteIfExistsCompat()
    }

    private fun atomicWrite(payload: String) {
        val parent = path.parent ?: error("Encrypted profile path must have a parent directory.")
        parent.createDirectoriesCompat()
        val tempPath = parent.resolve("${path.fileName}.tmp")
        tempPath.writeUtf8Text(payload)
        try {
            Files.move(tempPath, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempPath, path, REPLACE_EXISTING)
        }
    }
}

data class StoredProfile(
    val profile: ProfileIr,
    val profileHash: String,
    val encryptedAtEpochMs: Long,
    val path: Path,
    val keyReference: String?,
)

@Serializable
private data class StoredProfileEnvelope(
    val schema: String,
    val profileId: String,
    val profileHash: String,
    val encryptedAtEpochMs: Long,
    val ciphertext: CipherEnvelope,
)

internal object StorageJson {
    val instance: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
}
