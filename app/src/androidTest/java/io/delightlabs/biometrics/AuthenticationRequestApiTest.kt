package io.delightlabs.biometrics

import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.AuthenticationResult
import io.delightlabs.biometricslib.BiometricsConfig
import io.delightlabs.biometricslib.BiometricsManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers public one-shot request admission without showing a system authentication prompt. */
@RunWith(AndroidJUnit4::class)
class AuthenticationRequestApiTest {
    @Test
    fun preflightFailureConsumesTheIdBeforeCallbackAndRejectsReentrantReuse() {
        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = createInvalidManager(activity)
                val id = AuthenticationRequestId.create()
                val results = mutableListOf<AuthenticationResult>()
                val duplicateResults = mutableListOf<AuthenticationResult>()

                val accepted = manager.startAuthentication(id, AuthenticationMode.BIOMETRIC_ONLY) { result ->
                    results += result
                    assertFalse(manager.startAuthentication(id, AuthenticationMode.BIOMETRIC_ONLY, onResult = duplicateResults::add))
                }

                assertTrue(accepted)
                assertEquals(1, results.size)
                assertSame(id, results.single().requestId)
                assertTrue(results.single().outcome is AuthenticationOutcome.Failure)
                assertFalse(manager.reconnectAuthentication(id, duplicateResults::add))
                assertTrue(duplicateResults.isEmpty())

                val freshId = AuthenticationRequestId.create()
                val freshResults = mutableListOf<AuthenticationResult>()
                assertTrue(manager.startAuthentication(freshId, AuthenticationMode.BIOMETRIC_ONLY, onResult = freshResults::add))
                assertEquals(1, freshResults.size)
                assertSame(freshId, freshResults.single().requestId)
                assertTrue(freshResults.single().outcome is AuthenticationOutcome.Failure)
            }
        }
    }

    @Test
    fun awaitRejectsAnAlreadyConsumedIdBeforeStartingAnotherAttempt() {
        ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = createInvalidManager(activity)
                val id = AuthenticationRequestId.create()
                assertTrue(manager.startAuthentication(id, AuthenticationMode.BIOMETRIC_ONLY) {})
                var duplicateError: IllegalStateException? = null

                val duplicate = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        manager.awaitAuthentication(id, AuthenticationMode.BIOMETRIC_ONLY)
                    } catch (error: IllegalStateException) {
                        duplicateError = error
                    }
                }

                assertTrue("A reused ID must be rejected before suspension", duplicate.isCompleted)
                assertNotNull(duplicateError)
                assertFalse(manager.reconnectAuthentication(id) { error("The failed attempt already completed") })
            }
        }
    }

    private fun createInvalidManager(activity: ExampleActivity): BiometricsManager =
        BiometricsManager.create(activity, BiometricsConfig(title = ""))
}
