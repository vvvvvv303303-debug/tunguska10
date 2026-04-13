package io.acionyx.tunguska.domain

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CanonicalJson {
    val instance: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }

    fun encodeProfile(profile: ProfileIr): String = instance.encodeToString(profile)

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

