package io.acionyx.tunguska.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnMvpSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun import_and_connect_from_share_link() {
        val arguments = InstrumentationRegistry.getArguments()
        val shareLink = arguments.getString("profile_share_link_hex")
            ?.let(::decodeHexUtf8)
            ?: arguments.getString("profile_share_link")
            ?: DEFAULT_SHARE_LINK
        val expectedPhase = arguments.getString("expected_phase") ?: "RUNNING"

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTags.IMPORT_DRAFT_FIELD, useUnmergedTree = true)
            .performTextReplacement(shareLink)
        device.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(UiTags.VALIDATE_IMPORT_BUTTON))

        composeRule.onNodeWithTag(UiTags.VALIDATE_IMPORT_BUTTON, useUnmergedTree = true)
            .performClick()

        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(UiTags.CONFIRM_IMPORT_BUTTON, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            captureDiagnostics("post_validate")
            throw error
        }

        composeRule.onNodeWithTag(UiTags.CONFIRM_IMPORT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        waitForAppliedProfile(shareLink)

        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(UiTags.CONNECT_BUTTON))

        tapConnectButton()
        confirmVpnPermissionIfPresent()
        waitForComposeText("Permission: granted", timeoutMillis = 15_000)
        if (hasComposeText("Phase: IDLE")) {
            tapConnectButton()
        }
        waitForRuntimePhase(expectedPhase)
    }

    private fun confirmVpnPermissionIfPresent() {
        val button = device.wait(Until.findObject(By.res("android:id/button1")), 10_000)
            ?: device.wait(Until.findObject(By.textContains("Allow")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("OK")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("Continue")), 2_000)

        button?.click()
    }

    private fun tapConnectButton() {
        composeRule.onNodeWithTag(UiTags.CONNECT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    private fun waitForRuntimePhase(expectedPhase: String) {
        val phaseText = "Phase: $expectedPhase"
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                hasComposeText(phaseText)
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("runtime_phase_timeout")
            fail("Expected runtime phase '$phaseText' was not observed within timeout.")
        }
    }

    private fun waitForComposeText(
        text: String,
        timeoutMillis: Long,
    ) {
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                hasComposeText(text)
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("compose_text_timeout")
            fail("Expected text '$text' was not observed within timeout.")
        }
    }

    private fun hasComposeText(text: String): Boolean = composeRule
        .onAllNodesWithText(
            text = text,
            substring = true,
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun waitForAppliedProfile(shareLink: String) {
        val marker = deriveProfileMarker(shareLink) ?: return
        val found = device.wait(Until.hasObject(By.textContains(marker)), 10_000)
        if (!found) {
            captureDiagnostics("profile_apply_timeout")
            fail("Imported profile marker '$marker' was not observed after confirmation.")
        }
    }

    private fun captureDiagnostics(label: String) {
        val directory = "/sdcard/Download/tunguska-smoke"
        device.executeShellCommand("mkdir -p $directory")
        device.executeShellCommand("uiautomator dump $directory/$label-window.xml")
        device.executeShellCommand("screencap -p $directory/$label-screen.png")
    }

    private fun deriveProfileMarker(shareLink: String): String? = runCatching {
        val uri = URI(shareLink.trim())
        URLDecoder.decode(uri.rawFragment.orEmpty(), Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun decodeHexUtf8(value: String): String {
        require(value.length % 2 == 0) { "Hex-encoded share link must have an even length." }
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val offset = index * 2
            bytes[index] = value.substring(offset, offset + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_SHARE_LINK =
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public+key&sid=abcd1234" +
                "&fp=chrome&flow=xtls-rprx-vision#Alpha%20Import"
    }
}
