package io.delightlabs.biometrics

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.delightlabs.biometricslib.authentication.BiometricsConfig
import io.delightlabs.biometricslib.authentication.BiometricsManager
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode
import io.delightlabs.biometricslib.authentication.request.AuthenticationRequestId
import io.delightlabs.biometricslib.authentication.request.AuthenticationResult
import io.delightlabs.biometricslib.authentication.result.AuthenticationFailureReason
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome
import io.delightlabs.biometricslib.authentication.result.VerifiedAuthenticationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import io.delightlabs.biometricslib.AuthenticationMode as LegacyMode
import io.delightlabs.biometricslib.AuthenticationOutcome as LegacyOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId as LegacyRequestId
import io.delightlabs.biometricslib.AuthenticationResult as LegacyResult
import io.delightlabs.biometricslib.BiometricsConfig as LegacyConfig
import io.delightlabs.biometricslib.BiometricsManager as LegacyManager
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod as LegacyMethod

@RunWith(AndroidJUnit4::class)
class AuthenticationCompatibilityIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun legacyAndFeatureApisShareOnePromptAndPreserveRequestIdentityAfterRecreation() {
        val pin = requireTestPin()
        val id = LegacyRequestId.create()
        val original = LinkedBlockingQueue<LegacyResult>()
        val restored = LinkedBlockingQueue<LegacyResult>()
        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            startLegacyRequest(scenario, id, original)
            awaitPrompt()
            assertFeatureRequestIsBusy(scenario)
            scenario.recreate()
            scenario.onActivity { activity ->
                val manager = LegacyManager.create(activity, LegacyConfig(title = PROMPT_TITLE))
                assertTrue(manager.reconnectAuthentication(id) { restored.offer(it) })
                assertFalse(manager.startAuthentication(id) { error("An ID must remain single-use across wrappers") })
            }
            awaitPrompt()
            enterCredential(pin)
            val result = checkNotNull(restored.poll(10, TimeUnit.SECONDS))
            assertSame(id, result.requestId)
            assertEquals(LegacyOutcome.Success(LegacyMethod.DEVICE_CREDENTIAL), result.outcome)
            assertNull(original.poll())
            assertNull(restored.poll())
            authenticateWithFeatureApi(scenario, pin)
        }
    }

    private fun startLegacyRequest(
        scenario: ActivityScenario<ExampleActivity>,
        id: LegacyRequestId,
        results: LinkedBlockingQueue<LegacyResult>
    ) {
        scenario.onActivity { activity ->
            val manager = LegacyManager.create(activity, LegacyConfig(title = PROMPT_TITLE))
            assertTrue(manager.startAuthentication(id, LegacyMode.DEVICE_CREDENTIAL_ONLY) { results.offer(it) })
        }
    }

    private fun assertFeatureRequestIsBusy(scenario: ActivityScenario<ExampleActivity>) {
        val results = LinkedBlockingQueue<AuthenticationResult>()
        scenario.onActivity { activity ->
            val manager = BiometricsManager.create(activity, BiometricsConfig(title = PROMPT_TITLE))
            assertTrue(
                manager.startAuthentication(AuthenticationRequestId.create(), AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                    results.offer(it)
                }
            )
        }
        assertEquals(
            AuthenticationOutcome.Failure(AuthenticationFailureReason.IN_PROGRESS),
            results.poll(5, TimeUnit.SECONDS)?.outcome
        )
    }

    private fun authenticateWithFeatureApi(scenario: ActivityScenario<ExampleActivity>, pin: String) {
        val results = LinkedBlockingQueue<AuthenticationResult>()
        scenario.onActivity { activity ->
            val manager = BiometricsManager.create(activity, BiometricsConfig(title = PROMPT_TITLE))
            assertTrue(
                manager.startAuthentication(AuthenticationRequestId.create(), AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
                    results.offer(it)
                }
            )
        }
        awaitPrompt()
        enterCredential(pin)
        assertEquals(
            AuthenticationOutcome.Success(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL),
            results.poll(10, TimeUnit.SECONDS)?.outcome
        )
        assertNull(results.poll())
    }

    private fun awaitPrompt() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(PROMPT_TITLE)?.isNotEmpty() == true) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The real system credential prompt did not appear")
    }

    private fun enterCredential(pin: String) {
        listOf("input text $pin", "input keyevent KEYCODE_ENTER").forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun requireTestPin(): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runCredentialPromptTests") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assertTrue(instrumentation.targetContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)
        return args.getString("credentialPromptTestPin").orEmpty().also { pin ->
            assertTrue(pin.length in 4..16 && pin.all { it in '0'..'9' })
        }
    }

    private companion object {
        const val PROMPT_TITLE = "Compatibility authentication"
    }
}
