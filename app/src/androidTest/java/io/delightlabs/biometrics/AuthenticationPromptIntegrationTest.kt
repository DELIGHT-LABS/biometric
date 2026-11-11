package io.delightlabs.biometrics

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.delightlabs.biometricslib.authentication.BiometricsConfig
import io.delightlabs.biometricslib.authentication.BiometricsManager
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode
import io.delightlabs.biometricslib.authentication.request.AuthenticationRequestId
import io.delightlabs.biometricslib.authentication.request.AuthenticationResult
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome
import io.delightlabs.biometricslib.authentication.result.VerifiedAuthenticationMethod
import io.delightlabs.biometricslib.availability.AuthenticationAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Run on a test emulator with a configured device credential. */
@RunWith(AndroidJUnit4::class)
class AuthenticationPromptIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun requireConfiguredTestDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runCredentialPromptTests") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        assumeTrue(keyguard?.isDeviceSecure == true)
    }

    @Test
    fun recreatedHostReceivesSuccessFromExistingPrompt() {
        val pin = InstrumentationRegistry.getArguments().getString("credentialPromptTestPin").orEmpty()
        assumeTrue(pin.length in 4..16 && pin.all { it in '0'..'9' })
        val originalResults = LinkedBlockingQueue<AuthenticationOutcome>()
        val restoredResults = LinkedBlockingQueue<AuthenticationOutcome>()

        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = createManager(activity)
                assertEquals(
                    AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
                    manager.checkAuthenticationAvailability(AuthenticationMode.DEVICE_CREDENTIAL_ONLY)
                )
                manager.startAuthentication(AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                    originalResults.offer(it)
                }
            }
            awaitPromptVisibility(visible = true)

            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(createManager(activity).reconnectAuthentication { restoredResults.offer(it) })
            }

            awaitPromptVisibility(visible = true)
            enterTestCredential(pin)

            assertEquals(
                AuthenticationOutcome.Success(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL),
                restoredResults.poll(10, TimeUnit.SECONDS)
            )
            assertNull(originalResults.poll())
        }
    }

    @Test
    fun recreatedHostAcceptsOnlyTheOriginalRequestIdAndConsumesItsResultOnce() {
        val pin = InstrumentationRegistry.getArguments().getString("credentialPromptTestPin").orEmpty()
        assumeTrue(pin.length in 4..16 && pin.all { it in '0'..'9' })
        val requestId = AuthenticationRequestId.create()
        val otherId = AuthenticationRequestId.create()
        val originalResults = LinkedBlockingQueue<AuthenticationResult>()
        val restoredResults = LinkedBlockingQueue<AuthenticationResult>()
        val otherResults = LinkedBlockingQueue<AuthenticationResult>()

        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    createManager(activity).startAuthentication(requestId, AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                        originalResults.offer(it)
                    }
                )
            }
            awaitPromptVisibility(visible = true)
            scenario.recreate()
            scenario.onActivity { activity ->
                val manager = createManager(activity)
                assertFalse(manager.reconnectAuthentication(otherId) { otherResults.offer(it) })
                assertFalse(manager.reconnectAuthentication { error("An unkeyed listener cannot take a keyed result") })
                assertTrue(manager.reconnectAuthentication(requestId) { restoredResults.offer(it) })
            }

            awaitPromptVisibility(visible = true)
            enterTestCredential(pin)
            assertEquals(
                AuthenticationResult(requestId, AuthenticationOutcome.Success(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL)),
                restoredResults.poll(10, TimeUnit.SECONDS)
            )
            scenario.onActivity { activity ->
                val manager = createManager(activity)
                assertFalse(manager.reconnectAuthentication(requestId) { restoredResults.offer(it) })
                assertFalse(manager.startAuthentication(requestId) { restoredResults.offer(it) })
            }
            assertNull(originalResults.poll())
            assertNull(otherResults.poll())
            assertNull(restoredResults.poll())
        }
    }

    @Test
    fun cancellingAwaitClosesThePromptAndPropagatesCoroutineCancellation() {
        val results = LinkedBlockingQueue<AuthenticationOutcome>()
        val cancelled = CountDownLatch(1)
        lateinit var request: Job

        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = createManager(activity)
                request = activity.lifecycleScope.launch {
                    try {
                        results.offer(manager.awaitAuthentication(AuthenticationMode.DEVICE_CREDENTIAL_ONLY))
                    } catch (error: CancellationException) {
                        cancelled.countDown()
                        throw error
                    }
                }
            }

            awaitPromptVisibility(visible = true)
            request.cancel()

            assertTrue("The coroutine should observe cancellation", cancelled.await(10, TimeUnit.SECONDS))
            awaitPromptVisibility(visible = false)
            assertNull(results.poll())
        }
    }

    @Test
    fun explicitCancellationRequiresTheActiveIdAndPermitsNextAuthenticationAfterDismissal() {
        val pin = InstrumentationRegistry.getArguments().getString("credentialPromptTestPin").orEmpty()
        assumeTrue(pin.length in 4..16 && pin.all { it in '0'..'9' })
        val activeId = AuthenticationRequestId.create()
        val otherId = AuthenticationRequestId.create()
        val firstResults = LinkedBlockingQueue<AuthenticationResult>()
        val nextResults = LinkedBlockingQueue<AuthenticationResult>()

        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    createManager(activity).startAuthentication(activeId, AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                        firstResults.offer(it)
                    }
                )
            }
            awaitPromptVisibility(visible = true)
            scenario.onActivity { activity ->
                val manager = createManager(activity)
                assertFalse(manager.cancelAuthentication(otherId))
                assertFalse(manager.discardAuthenticationResult(otherId))
            }
            assertTrue("Cancelling an unrelated ID must leave the active prompt open", isPromptVisible())
            assertNull(firstResults.poll())

            scenario.onActivity { activity ->
                val manager = createManager(activity)
                assertTrue(manager.cancelAuthentication(activeId))
                assertFalse(manager.cancelAuthentication(activeId))
            }
            assertEquals(
                AuthenticationResult(activeId, AuthenticationOutcome.Cancelled),
                firstResults.poll(10, TimeUnit.SECONDS)
            )
            awaitPromptVisibility(visible = false)
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertTrue(
                    createManager(activity).startAuthentication(otherId, AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                        nextResults.offer(it)
                    }
                )
            }
            awaitPromptVisibility(visible = true)
            enterTestCredential(pin)
            assertEquals(
                AuthenticationResult(otherId, AuthenticationOutcome.Success(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL)),
                nextResults.poll(10, TimeUnit.SECONDS)
            )
            assertNull("The cancelled request must not receive a late result", firstResults.poll())
            assertNull("The next request must complete once", nextResults.poll())
        }
    }

    private fun createManager(activity: ExampleActivity): BiometricsManager =
        BiometricsManager.create(activity, BiometricsConfig(title = PROMPT_TITLE))

    private fun awaitPromptVisibility(visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (isPromptVisible() == visible) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)

        assertEquals("Credential prompt visibility", visible, isPromptVisible())
    }

    private fun isPromptVisible(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(PROMPT_TITLE).isNotEmpty()
    }

    private fun enterTestCredential(pin: String) {
        runShellCommand("input text $pin")
        runShellCommand("input keyevent KEYCODE_ENTER")
    }

    private fun runShellCommand(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val PROMPT_TITLE = "Biometrics credential regression"
    }
}
