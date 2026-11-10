package io.delightlabs.biometrics

import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.delightlabs.biometricslib.AuthenticationFailureReason
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.BiometricsConfig
import io.delightlabs.biometricslib.BiometricsManager
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Run only on a dedicated Android 10 test device with a synthetic PIN. */
@RunWith(AndroidJUnit4::class)
class Android10PromptCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun cancellingSecondPromptBeforeDisplayAllowsImmediateThirdRequest() {
        val pin = requireTestDevice()
        val results = PromptResults()
        val secondTitleSeen = AtomicBoolean()
        try {
            ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> startSequence(activity, results) }
                awaitPrompt(FIRST_TITLE)
                monitorSecondPrompt(secondTitleSeen)
                enterTestCredential(pin)

                assertEquals(CREDENTIAL_SUCCESS, results.first.poll(10, TimeUnit.SECONDS))
                val timing = awaitRetryInsideRestartWindow(results)
                observeNoSecondPrompt(secondTitleSeen, timing.finishedAt + OBSERVATION_WINDOW_MILLIS)
                awaitPrompt(THIRD_TITLE, secondTitleSeen, results.third)
                enterTestCredential(pin)
                assertEquals(CREDENTIAL_SUCCESS, results.third.poll(10, TimeUnit.SECONDS))
                assertNull("The cancelled request must not deliver an outcome", results.second.poll())
                assertSecondNeverAppeared(secondTitleSeen)

                Log.i(
                    TAG,
                    "model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}, androidxWasDelaying=${timing.androidxWasDelaying}, " +
                        "secondCancelledAfterMs=${timing.cancelledAfterMs}, thirdRequestedAfterMs=${timing.thirdAfterMs}, " +
                        "secondTitleSeen=false, first=Success(UNKNOWN), third=Success(UNKNOWN)"
                )
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    private fun monitorSecondPrompt(secondTitleSeen: AtomicBoolean) {
        automation.setOnAccessibilityEventListener { event ->
            if (event.text.any { SECOND_TITLE in it } ||
                event.contentDescription?.contains(SECOND_TITLE) == true
            ) {
                secondTitleSeen.set(true)
            }
        }
    }

    private fun awaitRetryInsideRestartWindow(results: PromptResults): RetryTiming {
        assertTrue("The second coroutine must observe cancellation", results.cancelled.await(5, TimeUnit.SECONDS))
        val timing = checkNotNull(results.timing.poll(5, TimeUnit.SECONDS)) {
            "The third request must be issued after cancellation"
        }
        assertTrue("AndroidX must be delaying restarts after the first real prompt", timing.androidxWasDelaying)
        assertTrue("The second request must be pending before cancellation", timing.secondWasActive)
        assertEquals(
            AuthenticationOutcome.Failure(AuthenticationFailureReason.IN_PROGRESS),
            results.duplicate.poll()
        )
        assertTrue(
            "The second cancellation must occur in the restart window",
            timing.cancelledAfterMs in 0L until RESTART_WINDOW_MILLIS
        )
        assertTrue(
            "The third request must be issued in the same restart window",
            timing.thirdAfterMs in 0L until RESTART_WINDOW_MILLIS
        )
        return timing
    }

    private fun requireTestDevice(): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runAndroid10PromptCancellationTest") == "true")
        assertEquals("Android 10 is required", Build.VERSION_CODES.Q, Build.VERSION.SDK_INT)
        if (args.getString("allowEmulatorModel") != "true") {
            assertEquals("Pixel 4 is required", "Pixel 4", Build.MODEL)
        }

        val context = instrumentation.targetContext
        val delayModels = context.resources.getIdentifier("delay_showing_prompt_models", "array", context.packageName)
        assertTrue("The merged AndroidX restart-delay model list must exist", delayModels != 0)
        assertTrue("The current model must trigger the AndroidX delay", Build.MODEL in context.resources.getStringArray(delayModels))
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Configure a synthetic PIN on the test device", keyguard?.isDeviceSecure == true)
        val pin = args.getString("credentialPromptTestPin").orEmpty()
        assertTrue("Supply a synthetic PIN as credentialPromptTestPin", pin.length in 4..16 && pin.all { it in '0'..'9' })
        return pin
    }

    private fun startSequence(activity: ExampleActivity, results: PromptResults) {
        val first = createManager(activity, FIRST_TITLE)
        val second = createManager(activity, SECOND_TITLE)
        val third = createManager(activity, THIRD_TITLE)
        first.startAuthentication(MIXED_MODE) { outcome ->
            val firstFinishedAt = SystemClock.uptimeMillis()
            results.first.offer(outcome)
            if (outcome == CREDENTIAL_SUCCESS) {
                cancelSecondAndRequestThird(activity, second, third, firstFinishedAt, results)
            }
        }
    }

    private fun cancelSecondAndRequestThird(
        activity: ExampleActivity,
        second: BiometricsManager,
        third: BiometricsManager,
        firstFinishedAt: Long,
        results: PromptResults
    ) {
        val androidxWasDelaying = isAndroidXDelayingPrompt(activity)
        val secondJob = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                results.second.offer(second.awaitAuthentication(MIXED_MODE))
            } catch (error: CancellationException) {
                results.cancelled.countDown()
                throw error
            }
        }
        val secondWasActive = secondJob.isActive
        third.startAuthentication(MIXED_MODE) { results.duplicate.offer(it) }
        val cancelledAfterMs = SystemClock.uptimeMillis() - firstFinishedAt
        secondJob.cancel()
        // The cancellation handler posts cleanup to the main queue. This message follows it.
        Handler(Looper.getMainLooper()).post {
            val thirdAfterMs = SystemClock.uptimeMillis() - firstFinishedAt
            results.timing.offer(RetryTiming(firstFinishedAt, androidxWasDelaying, secondWasActive, cancelledAfterMs, thirdAfterMs))
            third.startAuthentication(MIXED_MODE) { results.third.offer(it) }
        }
    }

    private fun isAndroidXDelayingPrompt(activity: ExampleActivity): Boolean {
        // This is an opt-in integration check for the branch in pinned AndroidX Biometric 1.1.0.
        val viewModelClass = Class.forName("androidx.biometric.BiometricViewModel").asSubclass(ViewModel::class.java)
        val viewModel = ViewModelProvider(activity)[viewModelClass]
        val isDelayingPrompt = viewModelClass.getDeclaredMethod("isDelayingPrompt").apply { isAccessible = true }
        return isDelayingPrompt.invoke(viewModel) == true
    }

    private fun awaitPrompt(
        title: String,
        secondTitleSeen: AtomicBoolean? = null,
        results: LinkedBlockingQueue<AuthenticationOutcome>? = null
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            secondTitleSeen?.let(::assertSecondNeverAppeared)
            assertNull("The request for $title must not finish before its prompt is visible", results?.peek())
            if (isTitleVisible(title)) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("The system prompt for $title must become visible", isTitleVisible(title))
    }

    private fun observeNoSecondPrompt(secondTitleSeen: AtomicBoolean, deadline: Long) {
        do {
            assertSecondNeverAppeared(secondTitleSeen)
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun assertSecondNeverAppeared(secondTitleSeen: AtomicBoolean) {
        assertFalse("The cancelled second prompt appeared", secondTitleSeen.get() || isTitleVisible(SECOND_TITLE))
    }

    private fun isTitleVisible(title: String): Boolean =
        automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.isNotEmpty() == true

    private fun createManager(activity: ExampleActivity, title: String): BiometricsManager =
        BiometricsManager.create(activity, BiometricsConfig(title = title))

    private fun enterTestCredential(pin: String) {
        runShellCommand("input text $pin")
        runShellCommand("input keyevent KEYCODE_ENTER")
    }

    private fun runShellCommand(command: String) {
        val output = automation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private class PromptResults {
        val first = LinkedBlockingQueue<AuthenticationOutcome>()
        val second = LinkedBlockingQueue<AuthenticationOutcome>()
        val duplicate = LinkedBlockingQueue<AuthenticationOutcome>()
        val third = LinkedBlockingQueue<AuthenticationOutcome>()
        val timing = LinkedBlockingQueue<RetryTiming>()
        val cancelled = CountDownLatch(1)
    }

    private data class RetryTiming(
        val finishedAt: Long,
        val androidxWasDelaying: Boolean,
        val secondWasActive: Boolean,
        val cancelledAfterMs: Long,
        val thirdAfterMs: Long
    )

    private companion object {
        const val TAG = "Android10PromptTest"
        const val FIRST_TITLE = "Android 10 first authentication"
        const val SECOND_TITLE = "Android 10 cancelled authentication"
        const val THIRD_TITLE = "Android 10 next authentication"
        const val RESTART_WINDOW_MILLIS = 600L
        const val OBSERVATION_WINDOW_MILLIS = 1_200L
        val MIXED_MODE = AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL
        val CREDENTIAL_SUCCESS = AuthenticationOutcome.Success(VerifiedAuthenticationMethod.UNKNOWN)
    }
}
