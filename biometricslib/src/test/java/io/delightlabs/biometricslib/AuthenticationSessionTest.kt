package io.delightlabs.biometricslib

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import io.delightlabs.biometricslib.AuthenticationSession.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationSessionTest {
    private val session = AuthenticationSession()
    private val biometricSuccess = AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC)
    private val mismatch = AuthenticationOutcome.Failure(AuthenticationFailureReason.MISMATCH)

    @Test
    fun `duplicate request cannot replace the active callback`() {
        val firstResults = mutableListOf<AuthenticationOutcome>()
        val secondResults = mutableListOf<AuthenticationOutcome>()
        val first = checkNotNull(start(onResult = firstResults::add))

        assertNull(start(onResult = secondResults::add))

        session.finish(first, biometricSuccess)
        assertEquals(listOf(biometricSuccess), firstResults)
        assertTrue(secondResults.isEmpty())
        assertNotNull(start(onResult = secondResults::add))
    }

    @Test
    fun `first mismatch cancels once and suppresses later success`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(BiometricsRetryPolicy.FAIL_IMMEDIATELY, onResult = results::add))
        var cancelCount = 0

        session.onMismatch(request) { cancelCount++ }
        session.onMismatch(request) { cancelCount++ }
        session.finish(request, biometricSuccess)

        assertEquals(1, cancelCount)
        assertEquals(listOf(mismatch), results)
        assertNotNull(start())
    }

    @Test
    fun `platform cancellation after first mismatch only releases the session`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(BiometricsRetryPolicy.FAIL_IMMEDIATELY, onResult = results::add))

        session.onMismatch(request) {}
        assertNull(start())

        session.finish(request, AuthenticationOutcome.Cancelled)
        assertEquals(listOf(mismatch), results)
        assertNotNull(start())
    }

    @Test
    fun `retry policy lets the platform continue after mismatches`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(onResult = results::add))

        session.onMismatch(request) { error("Retry must leave the prompt open") }
        session.onMismatch(request) { error("Retry must leave the prompt open") }
        assertTrue(results.isEmpty())

        val credentialSuccess = AuthenticationOutcome.Success(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL)
        session.finish(request, credentialSuccess)
        assertEquals(listOf(credentialSuccess), results)
    }

    @Test
    fun `failure callback runs after requesting cancellation and rejects reentrant start`() {
        val results = mutableListOf<AuthenticationOutcome>()
        var cancelRequested = false
        val request = checkNotNull(
            start(BiometricsRetryPolicy.FAIL_IMMEDIATELY) { result ->
                assertTrue(cancelRequested)
                assertNull(start())
                results += result
            }
        )

        session.onMismatch(request) { cancelRequested = true }

        assertEquals(listOf(mismatch), results)
        session.finish(request, AuthenticationOutcome.Cancelled)
        assertNotNull(start())
    }

    @Test
    fun `events from a completed request cannot change the next request`() {
        val firstResults = mutableListOf<AuthenticationOutcome>()
        val first = checkNotNull(start(BiometricsRetryPolicy.FAIL_IMMEDIATELY, onResult = firstResults::add))
        session.finish(first, AuthenticationOutcome.Cancelled)
        val nextResults = mutableListOf<AuthenticationOutcome>()
        val next = checkNotNull(start(onResult = nextResults::add))

        session.onMismatch(first) { error("A stale mismatch must not cancel the current prompt") }
        session.finish(first, biometricSuccess)
        assertFalse(session.cancel(first))
        assertSame(next, session.active)
        assertNull(start())

        session.finish(next, biometricSuccess)
        assertEquals(listOf(AuthenticationOutcome.Cancelled), firstResults)
        assertEquals(listOf(biometricSuccess), nextResults)
    }

    @Test
    fun `start failure completes the request once and permits a retry`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(onResult = results::add))
        val failure = AuthenticationOutcome.Failure(AuthenticationFailureReason.INVALID_CONFIGURATION)

        session.finish(request, failure)
        session.finish(request, biometricSuccess)

        assertEquals(listOf(failure), results)
        assertNotNull(start())
    }

    @Test
    fun `reconnect replaces the old callback and ignores a stale owner detaching`() {
        val firstOwner = Any()
        val secondOwner = Any()
        val firstResults = mutableListOf<AuthenticationOutcome>()
        val secondResults = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(
            start(
                delivery = Delivery.RECONNECTABLE,
                owner = firstOwner,
                onResult = firstResults::add
            )
        )

        assertTrue(session.reconnect(secondOwner, secondResults::add))
        session.detach(firstOwner)
        session.finish(request, biometricSuccess)

        assertTrue(firstResults.isEmpty())
        assertEquals(listOf(biometricSuccess), secondResults)
    }

    @Test
    fun `result completed while detached is delivered to the next owner once`() {
        val firstOwner = Any()
        val firstResults = mutableListOf<AuthenticationOutcome>()
        val nextResults = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(
            start(
                delivery = Delivery.RECONNECTABLE,
                owner = firstOwner,
                onResult = firstResults::add
            )
        )

        session.detach(firstOwner)
        session.finish(request, biometricSuccess)
        assertTrue(session.reconnect(Any(), nextResults::add))
        assertFalse(session.reconnect(Any(), nextResults::add))

        assertTrue(firstResults.isEmpty())
        assertEquals(listOf(biometricSuccess), nextResults)
    }

    @Test
    fun `detached mismatch can be reconnected while the platform is still draining`() {
        val owner = Any()
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(
            start(
                retryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
                delivery = Delivery.RECONNECTABLE,
                owner = owner
            )
        )
        session.detach(owner)

        session.onMismatch(request) {}
        assertTrue(session.reconnect(Any(), results::add))
        assertFalse(session.reconnect(Any(), results::add))
        assertNull(start())
        session.finish(request, AuthenticationOutcome.Cancelled)

        assertEquals(listOf(mismatch), results)
        assertNotNull(start())
    }

    @Test
    fun `starting a new request supersedes an unclaimed outcome`() {
        val owner = Any()
        val first = checkNotNull(start(delivery = Delivery.RECONNECTABLE, owner = owner))
        session.detach(owner)
        session.finish(first, biometricSuccess)
        val nextResults = mutableListOf<AuthenticationOutcome>()
        val next = checkNotNull(start(delivery = Delivery.RECONNECTABLE, owner = Any()))

        assertTrue(session.reconnect(Any(), nextResults::add))
        assertTrue(nextResults.isEmpty())
        session.finish(next, AuthenticationOutcome.Cancelled)

        assertEquals(listOf(AuthenticationOutcome.Cancelled), nextResults)
    }

    @Test
    fun `coroutine cancellation suppresses results but drains the platform before accepting another request`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(delivery = Delivery.COROUTINE, onResult = results::add))

        assertTrue(session.cancel(request))
        assertTrue(session.isCancellationRequested(request))
        assertFalse(session.cancel(request))
        assertFalse(session.reconnect(Any(), results::add))
        assertNull(start())

        session.finish(request, biometricSuccess)
        assertTrue(results.isEmpty())
        assertNotNull(start())
    }

    @Test
    fun `permanent host clear completes waiting coroutine once`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = checkNotNull(start(delivery = Delivery.COROUTINE, onResult = results::add))

        session.clear()
        session.clear()
        session.finish(request, biometricSuccess)

        assertNull(session.active)
        assertEquals(
            listOf(AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE)),
            results
        )
    }

    @Test
    fun `clearing the host discards results retained for reconnectable callbacks`() {
        val owner = Any()
        val request = checkNotNull(start(delivery = Delivery.RECONNECTABLE, owner = owner))
        session.detach(owner)
        session.finish(request, biometricSuccess)

        session.clear()

        assertFalse(session.reconnect(Any()) { error("Cleared outcomes must not be delivered") })
    }

    private fun start(
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
        delivery: Delivery = Delivery.LEGACY,
        owner: Any? = null,
        onResult: (AuthenticationOutcome) -> Unit = {}
    ): AuthenticationSession.Request? = session.tryStart(
        authenticators = BIOMETRIC_STRONG,
        retryPolicy = retryPolicy,
        delivery = delivery,
        owner = owner,
        onResult = onResult
    )
}
