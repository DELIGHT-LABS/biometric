package io.delightlabs.biometricslib

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import io.delightlabs.biometricslib.AuthenticationSession.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationCancellationPolicyTest {
    private var elapsedRealtimeMillis = 1_000L
    private val session = AuthenticationSession { elapsedRealtimeMillis }
    private val success = AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC)
    private val expired = AuthenticationOutcome.Failure(
        reason = AuthenticationFailureReason.TIMEOUT,
        message = "Authentication result expired."
    )

    @Test
    fun `queued cancellation releases the request before delivering Cancelled`() {
        val id = AuthenticationRequestId.create()
        val results = mutableListOf<AuthenticationOutcome>()
        var next: AuthenticationSession.Request? = null
        var cancellationCount = 0
        val request = start(id) { result ->
            assertEquals(1, cancellationCount)
            assertNull(session.active)
            assertFalse(session.canReconnect(id))
            next = start(AuthenticationRequestId.create())
            results += result
        }

        assertTrue(
            session.cancelForCaller(request, releaseBeforeLaunch = true) {
                cancellationCount++
            }
        )
        assertFalse(
            session.cancelForCaller(request, releaseBeforeLaunch = true) {
                error("Repeated cancellation must not cancel the next request")
            }
        )
        session.finish(request, success)

        assertEquals(1, cancellationCount)
        assertEquals(listOf(AuthenticationOutcome.Cancelled), results)
        assertNotNull(next)
        assertSame(next, session.active)
    }

    @Test
    fun `running cancellation completes once and drains before accepting another request`() {
        val results = mutableListOf<AuthenticationOutcome>()
        var cancellationCount = 0
        lateinit var request: AuthenticationSession.Request
        request = start(AuthenticationRequestId.create()) { result ->
            assertEquals(1, cancellationCount)
            assertTrue(session.isCompleted(request))
            assertTrue(session.isCancellationRequested(request))
            assertNull(tryStart(AuthenticationRequestId.create()))
            results += result
        }

        assertTrue(session.cancelForCaller(request, releaseBeforeLaunch = false) { cancellationCount++ })
        assertFalse(session.cancelForCaller(request, releaseBeforeLaunch = false) { cancellationCount++ })
        assertSame(request, session.active)
        session.finish(request, success)
        session.finish(request, AuthenticationOutcome.Cancelled)

        assertEquals(1, cancellationCount)
        assertEquals(listOf(AuthenticationOutcome.Cancelled), results)
        assertNotNull(tryStart(AuthenticationRequestId.create()))
    }

    @Test
    fun `synchronous platform completion cannot suppress or duplicate caller cancellation`() {
        val results = mutableListOf<AuthenticationOutcome>()
        val request = start(AuthenticationRequestId.create(), onResult = results::add)

        assertTrue(
            session.cancelForCaller(request, releaseBeforeLaunch = false) {
                session.finish(request, AuthenticationOutcome.Cancelled)
                session.finish(request, success)
            }
        )
        session.finish(request, success)

        assertEquals(listOf(AuthenticationOutcome.Cancelled), results)
        assertNull(session.active)
        assertNotNull(tryStart(AuthenticationRequestId.create()))
    }

    @Test
    fun `detached cancellation discards authority and never buffers a late success`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        val results = mutableListOf<AuthenticationOutcome>()
        val request = start(id, owner, onResult = results::add)
        session.detach(owner)

        assertTrue(session.cancelForCaller(request, releaseBeforeLaunch = false) {})
        assertFalse(session.canReconnect(id))
        assertFalse(session.reconnect(id, Any(), results::add))
        session.finish(request, success)

        assertFalse(session.reconnect(id, Any(), results::add))
        assertFalse(session.discardResult(id))
        assertTrue(results.isEmpty())
        assertNotNull(tryStart(AuthenticationRequestId.create()))
    }

    @Test
    fun `wrong discard ID leaves the rightful buffered success available`() {
        val id = AuthenticationRequestId.create()
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, success)

        assertFalse(session.discardResult(AuthenticationRequestId.create()))
        assertTrue(session.canReconnect(id))
        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(success), results)
    }

    @Test
    fun `discard consumes a buffered result once without invoking a listener`() {
        val id = AuthenticationRequestId.create()
        buffer(id, success)

        assertTrue(session.discardResult(id))
        assertFalse(session.discardResult(id))
        assertFalse(session.canReconnect(id))
        assertFalse(session.reconnect(id, Any()) { error("Discarded success must never be delivered") })
        assertNotNull(tryStart(AuthenticationRequestId.create()))
    }

    @Test
    fun `discarding a detached mismatch keeps the platform draining without restoring its result`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        val request = start(id, owner, retryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY)
        session.detach(owner)
        session.onMismatch(request) {}

        assertTrue(session.discardResult(id))
        assertFalse(session.discardResult(id))
        assertNull(tryStart(AuthenticationRequestId.create()))
        session.finish(request, success)

        assertFalse(session.reconnect(id, Any()) { error("Discarded mismatch must not become a success") })
        assertNotNull(tryStart(AuthenticationRequestId.create()))
    }

    @Test
    fun `bounded buffered success expires once for its rightful request`() {
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 100L)
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, success)
        elapsedRealtimeMillis += 101L

        assertTrue(
            session.reconnect(id, Any()) { result ->
                results += result
                assertFalse(session.reconnect(id, Any()) { error("Expired result must already be consumed") })
            }
        )
        assertFalse(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(expired), results)
    }

    @Test
    fun `wrong ID cannot consume an expired buffered success`() {
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 100L)
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, success)
        elapsedRealtimeMillis += 101L

        assertFalse(
            session.reconnect(AuthenticationRequestId.create(), Any()) {
                error("A different request must not receive even an expiry failure")
            }
        )
        assertTrue(session.canReconnect(id))
        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(expired), results)
    }

    @Test
    fun `buffered success remains valid exactly at the configured age boundary`() {
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 100L)
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, success)
        elapsedRealtimeMillis += 100L

        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(success), results)
    }

    @Test
    fun `success age starts when the result is buffered rather than when authentication starts`() {
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 100L)
        val owner = Any()
        val results = mutableListOf<AuthenticationOutcome>()
        val request = start(id, owner)
        session.detach(owner)
        elapsedRealtimeMillis += 10_000L
        session.finish(request, success)
        elapsedRealtimeMillis += 100L

        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(success), results)
    }

    @Test
    fun `bounded policy preserves a failure even after the success age has elapsed`() {
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 100L)
        val failure = AuthenticationOutcome.Failure(AuthenticationFailureReason.TEMPORARILY_LOCKED)
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, failure)
        elapsedRealtimeMillis += 10_000L

        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(failure), results)
    }

    @Test
    fun `existing unbounded request factory preserves buffered success compatibility`() {
        val id = AuthenticationRequestId.create()
        val results = mutableListOf<AuthenticationOutcome>()
        buffer(id, success)
        elapsedRealtimeMillis += 30L * 24 * 60 * 60 * 1_000

        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(success), results)
    }

    @Test
    fun `queued token can be cancelled only by the owner that claimed it`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()

        assertTrue(id.tryClaim(owner))
        assertFalse(id.cancelBeforeRegistration(Any()))
        assertFalse(id.cancelledBeforeRegistration)
        assertTrue(id.cancelBeforeRegistration(owner))
        assertTrue(id.cancelledBeforeRegistration)
        assertFalse(id.cancelBeforeRegistration(owner))
    }

    @Test
    fun `cancelling a queued token preserves single use and cannot register it later`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        assertTrue(id.tryClaim(owner))
        assertTrue(id.cancelBeforeRegistration(owner))

        id.markRegistered()

        assertTrue(id.cancelledBeforeRegistration)
        assertFalse(id.tryClaim(owner))
        assertFalse(id.tryClaim(Any()))
    }

    @Test
    fun `pre-registration cancellation cannot cancel a fresh or registered token`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        assertFalse(id.cancelBeforeRegistration(owner))
        assertTrue(id.tryClaim(owner))

        id.markRegistered()

        assertFalse(id.cancelBeforeRegistration(owner))
        assertFalse(id.cancelledBeforeRegistration)
        assertFalse(id.tryClaim(owner))
    }

    @Test
    fun `completed admission cannot be cancelled or reused`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        assertTrue(id.tryClaim(owner))
        id.markRegistered()

        id.markCompleted()

        assertFalse(id.cancelBeforeRegistration(owner))
        assertFalse(id.cancelledBeforeRegistration)
        assertFalse(id.tryClaim(owner))
    }

    @Test
    fun `bounded success age must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = -1L)
        }
    }

    private fun buffer(requestId: AuthenticationRequestId, result: AuthenticationOutcome) {
        val owner = Any()
        val request = start(requestId, owner)
        session.detach(owner)
        session.finish(request, result)
    }

    private fun start(
        requestId: AuthenticationRequestId,
        owner: Any = Any(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
        onResult: (AuthenticationOutcome) -> Unit = {}
    ): AuthenticationSession.Request = checkNotNull(tryStart(requestId, owner, retryPolicy, onResult))

    private fun tryStart(
        requestId: AuthenticationRequestId,
        owner: Any = Any(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
        onResult: (AuthenticationOutcome) -> Unit = {}
    ): AuthenticationSession.Request? = session.tryStart(
        authenticators = BIOMETRIC_STRONG,
        retryPolicy = retryPolicy,
        delivery = Delivery.RECONNECTABLE,
        owner = owner,
        requestId = requestId,
        onResult = onResult
    )
}
