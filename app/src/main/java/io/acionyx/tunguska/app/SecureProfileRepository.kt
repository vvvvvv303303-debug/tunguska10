package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.storage.EncryptedProfileStore
import io.acionyx.tunguska.storage.StoredProfile

private const val PROFILE_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.profile.master"
private const val PROFILE_STORE_RELATIVE_PATH: String = "profiles/default-profile.json.enc"

class SecureProfileRepository(
    context: Context,
    cipherBox: CipherBox = AndroidKeyStoreCipherBox(PROFILE_MASTER_KEY_ALIAS),
) {
    private val store = EncryptedProfileStore(
        path = context.filesDir.toPath().resolve(PROFILE_STORE_RELATIVE_PATH),
        cipherBox = cipherBox,
    )

    val storagePath: String = store.path.toString()
    val keyReference: String = "android-keystore:$PROFILE_MASTER_KEY_ALIAS"

    fun loadOrSeed(defaultProfile: ProfileIr): ProfileLoadResult {
        val storedProfile = store.load()
        return if (storedProfile != null) {
            ProfileLoadResult(
                storedProfile = storedProfile,
                seeded = false,
                status = "Loaded encrypted profile from app-private storage.",
            )
        } else {
            ProfileLoadResult(
                storedProfile = store.save(defaultProfile),
                seeded = true,
                status = "Seeded the bootstrap profile into encrypted app-private storage.",
            )
        }
    }

    fun reload(): StoredProfile {
        return checkNotNull(store.load()) {
            "No encrypted profile is stored at $storagePath."
        }
    }

    fun reseal(profile: ProfileIr): StoredProfile = store.save(profile)
}

data class ProfileLoadResult(
    val storedProfile: StoredProfile,
    val seeded: Boolean,
    val status: String,
)
