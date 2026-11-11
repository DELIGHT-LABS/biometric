package io.delightlabs.biometrics

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.delightlabs.biometricslib.AuthenticationFailureReason
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Covers public one-shot request admission without showing a system authentication prompt. */
@RunWith(AndroidJUnit4::class)
class AuthenticationRequestApiTest {
    @Test
    fun mainThreadCancellationCompletesAWorkerQueuedStartOnceBeforeRegistration() {
        val results = LinkedBlockingQueue<AuthenticationResult>()
        val nextResults = LinkedBlockingQueue<AuthenticationResult>()
        val cancelledId = AuthenticationRequestId.create()
        val nextId = AuthenticationRequestId.create()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val promptSeen = AtomicBoolean()
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.text.any { QUEUED_PROMPT_TITLE in it } ||
                event.contentDescription?.contains(QUEUED_PROMPT_TITLE) == true
            ) {
                promptSeen.set(true)
            }
        }

        try {
            ActivityScenario.launch(ExampleActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val manager = BiometricsManager.create(activity, BiometricsConfig(title = QUEUED_PROMPT_TITLE))
                    queueAndCancelBeforeMainDispatch(manager, cancelledId, results)
                }
                assertEquals(
                    AuthenticationResult(cancelledId, AuthenticationOutcome.Cancelled),
                    results.poll(5, TimeUnit.SECONDS)
                )
                observeNoQueuedPrompt(promptSeen)
                assertNull("Cancellation must complete the queued request once", results.poll())

                scenario.onActivity { activity ->
                    assertTrue(createInvalidManager(activity).startAuthentication(nextId, onResult = nextResults::offer))
                }
                val nextResult = checkNotNull(nextResults.poll(5, TimeUnit.SECONDS))
                assertEquals(nextId, nextResult.requestId)
                val failure = nextResult.outcome as AuthenticationOutcome.Failure
                assertTrue("A fresh request must pass admission after cancellation", failure.reason != AuthenticationFailureReason.IN_PROGRESS)
                assertNull(nextResults.poll())
            }
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        }
    }

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

    private fun queueAndCancelBeforeMainDispatch(
        manager: BiometricsManager,
        requestId: AuthenticationRequestId,
        results: LinkedBlockingQueue<AuthenticationResult>
    ) {
        val accepted = AtomicReference<Boolean>()
        val workerError = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                accepted.set(manager.startAuthentication(requestId, onResult = results::offer))
            } catch (error: Throwable) {
                workerError.set(error)
            }
        }, "queued-authentication-test")
        worker.start()
        // This helper is on main, so the worker's posted start cannot run until it returns.
        worker.join(5_000)
        assertFalse("The worker must return after queuing its request", worker.isAlive)
        assertNull(workerError.get())
        assertEquals(true, accepted.get())
        assertTrue(manager.cancelAuthentication(requestId))
        assertFalse(manager.cancelAuthentication(requestId))
        assertTrue(results.isEmpty())
    }

    private fun observeNoQueuedPrompt(promptSeen: AtomicBoolean) {
        val deadline = SystemClock.uptimeMillis() + 800
        do {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val titleVisible = root?.findAccessibilityNodeInfosByText(QUEUED_PROMPT_TITLE)?.isNotEmpty() == true
            assertFalse("The cancelled queued request must not open a prompt", promptSeen.get() || titleVisible)
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private companion object {
        const val QUEUED_PROMPT_TITLE = "Queued request cancellation regression"
    }
}
