package io.acionyx.tunguska.storage

import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.crypto.CipherEnvelope
import io.acionyx.tunguska.domain.CanonicalJson
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ARTIFACT_STORE_SCHEMA: String = "io.acionyx.tunguska.artifact.v1"

class EncryptedArtifactStore(
    path: Path,
    private val cipherBox: CipherBox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = StorageJson.instance,
) {
    val path: Path = path.toAbsolutePath().normalize()

    fun load(): LoadedArtifact? {
        if (!path.existsCompat()) {
            return null
        }
        val envelope = json.decodeFromString<StoredArtifactEnvelope>(path.readUtf8Text())
        require(envelope.schema == ARTIFACT_STORE_SCHEMA) {
            "Unsupported artifact store schema '${envelope.schema}'."
        }
        val plaintext = cipherBox.decrypt(
            envelope = envelope.ciphertext,
            aad = artifactAad(envelope.artifactType),
        ).toString(Charsets.UTF_8)
        val actualHash = CanonicalJson.sha256Hex(plaintext)
        require(actualHash == envelope.payloadHash) {
            "Stored artifact payload hash mismatch for '${envelope.artifactType}'."
        }
        return LoadedArtifact(
            artifactType = envelope.artifactType,
            redacted = envelope.redacted,
            payloadHash = actualHash,
            createdAtEpochMs = envelope.createdAtEpochMs,
            path = path,
            keyReference = envelope.ciphertext.keyReference,
            payloadJson = plaintext,
        )
    }

    fun save(
        artifactType: String,
        payloadJson: String,
        redacted: Boolean,
    ): StoredArtifact {
        require(artifactType.isNotBlank()) {
            "Artifact type must not be blank."
        }
        val createdAtEpochMs = clock()
        val payloadHash = CanonicalJson.sha256Hex(payloadJson)
        val envelope = StoredArtifactEnvelope(
            schema = ARTIFACT_STORE_SCHEMA,
            artifactType = artifactType,
            redacted = redacted,
            payloadHash = payloadHash,
            createdAtEpochMs = createdAtEpochMs,
            ciphertext = cipherBox.encrypt(
                plaintext = payloadJson.toByteArray(Charsets.UTF_8),
                aad = artifactAad(artifactType),
            ),
        )
        atomicWrite(json.encodeToString(envelope))
        return StoredArtifact(
            artifactType = artifactType,
            redacted = redacted,
            payloadHash = payloadHash,
            createdAtEpochMs = createdAtEpochMs,
            path = path,
            keyReference = envelope.ciphertext.keyReference,
        )
    }

    fun delete(): Boolean {
        if (!path.existsCompat()) {
            return false
        }
        path.deleteCompat()
        return true
    }

    private fun atomicWrite(payload: String) {
        val parent = path.parent ?: error("Encrypted artifact path must have a parent directory.")
        parent.createDirectoriesCompat()
        val tempPath = parent.resolve("${path.fileName}.tmp")
        tempPath.writeUtf8Text(payload)
        try {
            Files.move(tempPath, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempPath, path, REPLACE_EXISTING)
        }
    }

    private fun artifactAad(artifactType: String): ByteArray = "$ARTIFACT_STORE_SCHEMA:$artifactType".toByteArray(Charsets.UTF_8)
}

data class StoredArtifact(
    val artifactType: String,
    val redacted: Boolean,
    val payloadHash: String,
    val createdAtEpochMs: Long,
    val path: Path,
    val keyReference: String?,
)

data class LoadedArtifact(
    val artifactType: String,
    val redacted: Boolean,
    val payloadHash: String,
    val createdAtEpochMs: Long,
    val path: Path,
    val keyReference: String?,
    val payloadJson: String,
)

@Serializable
private data class StoredArtifactEnvelope(
    val schema: String,
    val artifactType: String,
    val redacted: Boolean,
    val payloadHash: String,
    val createdAtEpochMs: Long,
    val ciphertext: CipherEnvelope,
)
