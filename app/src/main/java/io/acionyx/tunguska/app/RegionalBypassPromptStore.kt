package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.domain.ProfileIr

private const val PREFS_NAME: String = "regional_bypass_prompt"
private const val DECISION_PREFIX: String = "decision:"

class RegionalBypassPromptStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldPrompt(
        profile: ProfileIr,
        seeded: Boolean,
    ): Boolean {
        if (seeded) return false
        if (!profile.routing.regionalBypass.isEmpty()) return false
        return !hasDecision(profile.id)
    }

    fun markDecisionRecorded(profileId: String) {
        preferences.edit()
            .putBoolean("$DECISION_PREFIX$profileId", true)
            .apply()
    }

    fun hasDecision(profileId: String): Boolean = preferences.getBoolean("$DECISION_PREFIX$profileId", false)
}
