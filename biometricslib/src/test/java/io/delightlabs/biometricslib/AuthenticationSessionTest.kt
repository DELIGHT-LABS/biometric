package io.delightlabs.biometricslib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationSessionTest {
    private val session = AuthenticationSession()

    @Test
    fun `duplicate request cannot replace the active callback`() {
        val firstCallback = RecordingCallback()
        val secondCallback = RecordingCallback()
        val first = checkNotNull(session.tryStart(firstCallback, BiometricsRetryPolicy.ALLOW_RETRY))

        assertNull(session.tryStart(secondCallback, BiometricsRetryPolicy.ALLOW_RETRY))

        session.finish(first) { it.onSuccess(AuthenticationMethod.BIOMETRIC) }
        assertEquals(listOf("success:BIOMETRIC"), firstCallback.events)
        assertEquals(emptyList<String>(), secondCallback.events)
        assertNotNull(session.tryStart(secondCallback, BiometricsRetryPolicy.ALLOW_RETRY))
    }

    @Test
    fun `first mismatch cancels once and suppresses later success`() {
        val callback = RecordingCallback()
        val request = checkNotNull(session.tryStart(callback, BiometricsRetryPolicy.FAIL_IMMEDIATELY))
        var cancelCount = 0

        session.onMismatch(request) { cancelCount++ }
        session.onMismatch(request) { cancelCount++ }
        session.finish(request) { it.onSuccess(AuthenticationMethod.BIOMETRIC) }

        assertEquals(1, cancelCount)
        assertEquals(listOf("error:${BiometricsManager.ERROR_AUTHENTICATION_FAILED}"), callback.events)
        assertNotNull(session.tryStart(RecordingCallback(), BiometricsRetryPolicy.ALLOW_RETRY))
    }

    @Test
    fun `platform cancellation after first mismatch only releases the session`() {
        val callback = RecordingCallback()
        val nextCallback = RecordingCallback()
        val request = checkNotNull(session.tryStart(callback, BiometricsRetryPolicy.FAIL_IMMEDIATELY))

        session.onMismatch(request) {}
        assertNull(session.tryStart(nextCallback, BiometricsRetryPolicy.ALLOW_RETRY))

        session.finish(request) { it.onCancel() }
        assertEquals(listOf("error:${BiometricsManager.ERROR_AUTHENTICATION_FAILED}"), callback.events)
        assertNotNull(session.tryStart(nextCallback, BiometricsRetryPolicy.ALLOW_RETRY))
    }

    @Test
    fun `retry policy lets the platform continue after mismatches`() {
        val callback = RecordingCallback()
        val request = checkNotNull(session.tryStart(callback, BiometricsRetryPolicy.ALLOW_RETRY))

        session.onMismatch(request) { error("Retry must leave the prompt open") }
        session.onMismatch(request) { error("Retry must leave the prompt open") }
        assertEquals(emptyList<String>(), callback.events)

        session.finish(request) { it.onSuccess(AuthenticationMethod.PIN) }
        assertEquals(listOf("success:PIN"), callback.events)
    }

    @Test
    fun `failure callback runs after requesting cancellation and rejects reentrant start`() {
        val nextCallback = RecordingCallback()
        var cancelRequested = false
        val callback = RecordingCallback {
            assertTrue(cancelRequested)
            assertNull(session.tryStart(nextCallback, BiometricsRetryPolicy.ALLOW_RETRY))
        }
        val request = checkNotNull(session.tryStart(callback, BiometricsRetryPolicy.FAIL_IMMEDIATELY))

        session.onMismatch(request) { cancelRequested = true }

        assertEquals(listOf("error:${BiometricsManager.ERROR_AUTHENTICATION_FAILED}"), callback.events)
        session.finish(request) { it.onCancel() }
        assertNotNull(session.tryStart(nextCallback, BiometricsRetryPolicy.ALLOW_RETRY))
    }

    @Test
    fun `events from a completed request cannot change the next request`() {
        val firstCallback = RecordingCallback()
        val first = checkNotNull(session.tryStart(firstCallback, BiometricsRetryPolicy.FAIL_IMMEDIATELY))
        session.finish(first) { it.onCancel() }
        val nextCallback = RecordingCallback()
        val next = checkNotNull(session.tryStart(nextCallback, BiometricsRetryPolicy.ALLOW_RETRY))

        session.onMismatch(first) { error("A stale mismatch must not cancel the current prompt") }
        session.finish(first) { error("The first request already completed") }
        session.abandon(first)
        assertNull(session.tryStart(RecordingCallback(), BiometricsRetryPolicy.ALLOW_RETRY))

        session.finish(next) { it.onSuccess(AuthenticationMethod.BIOMETRIC) }
        assertEquals(listOf("cancel"), firstCallback.events)
        assertEquals(listOf("success:BIOMETRIC"), nextCallback.events)
    }

    @Test
    fun `abandoning a request that could not launch allows retry`() {
        val callback = RecordingCallback()
        val request = checkNotNull(session.tryStart(callback, BiometricsRetryPolicy.ALLOW_RETRY))

        session.abandon(request)
        session.finish(request) { error("An abandoned request must not deliver an event") }

        assertEquals(emptyList<String>(), callback.events)
        assertNotNull(session.tryStart(callback, BiometricsRetryPolicy.ALLOW_RETRY))
    }

    private class RecordingCallback(private val afterError: () -> Unit = {}) : BiometricsCallback {
        val events = mutableListOf<String>()

        override fun onSuccess(authenticationMethod: AuthenticationMethod) {
            events += "success:$authenticationMethod"
        }

        override fun onError(errorCode: Int, errorMessage: String) {
            events += "error:$errorCode"
            afterError()
        }

        override fun onCancel() {
            events += "cancel"
        }
    }
}
