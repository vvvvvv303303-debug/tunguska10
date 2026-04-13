package io.acionyx.tunguska.storage

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class EncryptedArtifactStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save then load artifact round trip preserves metadata and payload`() {
        val store = buildStore(tempDir.resolve("exports/diagnostic-bundle.json.enc"))
        val payloadJson = """{"kind":"diagnostic","status":"healthy"}"""

        val saved = store.save(
            artifactType = "diagnostic_bundle",
            payloadJson = payloadJson,
            redacted = true,
        )
        val loaded = store.load()

        requireNotNull(loaded)
        assertEquals(saved.artifactType, loaded.artifactType)
        assertEquals(saved.payloadHash, loaded.payloadHash)
        assertEquals(saved.redacted, loaded.redacted)
        assertEquals(saved.path, loaded.path)
        assertEquals(payloadJson, loaded.payloadJson)
    }

    @Test
    fun `load returns null when no artifact is stored`() {
        val store = buildStore(tempDir.resolve("exports/diagnostic-bundle.json.enc"))

        assertNull(store.load())
    }

    @Test
    fun `tampered payload hash is rejected`() {
        val path = tempDir.resolve("exports/diagnostic-bundle.json.enc")
        val store = buildStore(path)
        store.save(
            artifactType = "diagnostic_bundle",
            payloadJson = """{"kind":"diagnostic"}""",
            redacted = true,
        )
        val mutated = Files.readString(path).replace("\"payloadHash\":\"", "\"payloadHash\":\"tampered-")
        Files.writeString(path, mutated)

        assertFailsWith<IllegalArgumentException> {
            store.load()
        }
    }

    @Test
    fun `artifact type is authenticated through aad`() {
        val path = tempDir.resolve("exports/diagnostic-bundle.json.enc")
        val store = buildStore(path)
        store.save(
            artifactType = "diagnostic_bundle",
            payloadJson = """{"kind":"diagnostic"}""",
            redacted = true,
        )
        val mutated = Files.readString(path).replace("diagnostic_bundle", "profile_backup")
        Files.writeString(path, mutated)

        assertFailsWith<Exception> {
            store.load()
        }
    }

    @Test
    fun `stored artifact records redaction flag`() {
        val store = buildStore(tempDir.resolve("exports/profile-backup.json.enc"))

        val saved = store.save(
            artifactType = "profile_backup",
            payloadJson = """{"kind":"backup"}""",
            redacted = false,
        )

        assertFalse(saved.redacted)
        assertTrue(Files.exists(saved.path))
    }

    private fun buildStore(path: Path): EncryptedArtifactStore = EncryptedArtifactStore(
        path = path,
        cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        clock = { 1234L },
    )
}
