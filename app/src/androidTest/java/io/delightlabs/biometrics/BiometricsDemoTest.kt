package io.delightlabs.biometrics

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class BiometricsDemoTest {
    @get:Rule
    val compose = createAndroidComposeRule<ExampleActivity>()

    @Test
    fun recreatingHostKeepsSelectedModeWithoutStartingAuthentication() {
        node(R.string.status_ready).assertIsDisplayed()
        node(R.string.mode_credential_only).performScrollTo().performClick()

        compose.activityRule.scenario.recreate()

        node(R.string.status_ready).assertIsDisplayed()
        node(R.string.mode_credential_only).assertIsSelected()
    }

    @Test
    fun disabledBiometricsShowsAppOwnedFallback() {
        node(R.string.enable_biometrics).performScrollTo().performClick()
        node(R.string.authenticate).performScrollTo().performClick()

        node(R.string.status_app_pin_required).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun availabilityButtonShowsUsableMethodsAndConfirmationToast() {
        ToastMessages().use { toasts ->
            node(R.string.check_availability).performScrollTo().performClick()
            lateinit var report: DemoAvailabilityReport
            compose.runOnIdle {
                val viewModel = ViewModelProvider(compose.activity)[BiometricsDemoViewModel::class.java]
                report = checkNotNull(viewModel.state.value.availabilityReport)
            }
            compose.onNodeWithText(compose.activity.availabilityReportText(report))
                .performScrollTo().assertIsDisplayed()
            val feedback = compose.activity.availabilityFeedbackText(report)
            compose.waitUntil(5_000) { toasts.count(feedback) > 0 }
            assertEquals(1, toasts.count(feedback))
            assertEquals(0, toasts.count(compose.activity.getString(R.string.authentication_success_toast)))
        }
    }

    @Test
    fun authenticateButtonShowsSuccessToastOnceWithoutReplayingAfterRecreation() {
        val pin = requireCredentialTestPin()
        ToastMessages().use { toasts ->
            val modeLabel = if (Build.VERSION.SDK_INT == 29) {
                R.string.mode_biometric_or_credential
            } else {
                R.string.mode_credential_only
            }
            node(modeLabel).performScrollTo().performClick()
            node(R.string.authenticate).performScrollTo().performClick()
            awaitAndCompleteCredentialPrompt(pin)
            val message = compose.activity.getString(R.string.authentication_success_toast)
            compose.waitUntil(10_000) { toasts.count(message) > 0 }
            val successLabel = if (Build.VERSION.SDK_INT == 29) {
                R.string.status_success_unknown
            } else {
                R.string.status_success_credential
            }
            node(successLabel).performScrollTo().assertIsDisplayed()
            assertEquals(1, toasts.count(message))

            compose.activityRule.scenario.recreate()

            node(successLabel).performScrollTo().assertIsDisplayed()
            observeNoRepeatedToast(toasts, message)
        }
    }

    private fun node(label: Int) = compose.onNodeWithText(compose.activity.getString(label))

    private fun requireCredentialTestPin(): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runCredentialPromptTests") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)
        return args.getString("credentialPromptTestPin").orEmpty().also { pin ->
            assertTrue(pin.length in 4..16 && pin.all { it in '0'..'9' })
        }
    }

    private fun awaitAndCompleteCredentialPrompt(pin: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val promptTitle = compose.activity.getString(R.string.prompt_title)
        compose.waitUntil(10_000) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(promptTitle)?.isNotEmpty() == true
        }
        listOf("input text $pin", "input keyevent KEYCODE_ENTER").forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun observeNoRepeatedToast(toasts: ToastMessages, message: String) {
        val deadline = SystemClock.uptimeMillis() + 800
        do {
            assertEquals("Recreation must not replay a consumed success Toast", 1, toasts.count(message))
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private class ToastMessages : AutoCloseable {
        private val messages = ConcurrentLinkedQueue<String>()
        private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

        init {
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                    messages.addAll(event.text.map(CharSequence::toString))
                }
            }
        }

        fun count(message: String): Int = messages.count { it == message }

        override fun close() {
            automation.setOnAccessibilityEventListener(null)
        }
    }
}
