package io.delightlabs.biometricslib.internal.session

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import io.delightlabs.biometricslib.AuthenticationFailureReason
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod
import io.delightlabs.biometricslib.internal.session.AuthenticationSession.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthenticationRequestIdentityTest {
    private val session = AuthenticationSession()
    private val success = AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC)

    @Test
    fun `a different request cannot replace the active listener`() {
        val idA = AuthenticationRequestId.create()
        val idB = AuthenticationRequestId.create()
        val resultsA = mutableListOf<AuthenticationOutcome>()
        val resultsB = mutableListOf<AuthenticationOutcome>()
        val request = start(idA, onResult = resultsA::add)

        assertFalse(session.canReconnect(idB))
        assertFalse(session.reconnect(idB, Any(), resultsB::add))
        assertTrue(session.canReconnect(idA))
        session.finish(request, success)

        assertEquals(listOf(success), resultsA)
        assertTrue(resultsB.isEmpty())
    }

    @Test
    fun `wrong ID does not consume a detached result belonging to another request`() {
        val idA = AuthenticationRequestId.create()
        val idB = AuthenticationRequestId.create()
        val owner = Any()
        val resultsA = mutableListOf<AuthenticationOutcome>()
        val resultsB = mutableListOf<AuthenticationOutcome>()
        val request = start(idA, owner)
        session.detach(owner)
        session.finish(request, success)

        assertFalse(session.canReconnect(idB))
        assertFalse(session.reconnect(idB, Any(), resultsB::add))
        assertTrue(session.canReconnect(idA))
        assertTrue(session.reconnect(idA, Any(), resultsA::add))
        assertFalse(session.reconnect(idA, Any(), resultsA::add))

        assertEquals(listOf(success), resultsA)
        assertTrue(resultsB.isEmpty())
    }

    @Test
    fun `legacy reconnect cannot receive an identified active or buffered result`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        val results = mutableListOf<AuthenticationOutcome>()
        val request = start(id, owner)

        assertFalse(session.canReconnect(null))
        assertFalse(session.reconnect(Any()) { error("Legacy listener must not replace identified listener") })
        session.detach(owner)
        session.finish(request, success)
        assertFalse(session.reconnect(Any()) { error("Legacy listener must not consume identified result") })
        assertTrue(session.reconnect(id, Any(), results::add))

        assertEquals(listOf(success), results)
    }

    @Test
    fun `identified reconnect cannot take a legacy active or buffered result`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        val legacyResults = mutableListOf<AuthenticationOutcome>()
        val request = start(null, owner)

        assertFalse(session.canReconnect(id))
        assertFalse(session.reconnect(id, Any()) { error("Identified listener must not replace legacy listener") })
        session.detach(owner)
        session.finish(request, success)
        assertFalse(session.reconnect(id, Any()) { error("Identified listener must not consume legacy result") })
        assertTrue(session.reconnect(Any(), legacyResults::add))

        assertEquals(listOf(success), legacyResults)
    }

    @Test
    fun `rotation reconnects only the same request and ignores old owner detachment`() {
        val id = AuthenticationRequestId.create()
        val oldOwner = Any()
        val newOwner = Any()
        val oldResults = mutableListOf<AuthenticationOutcome>()
        val newResults = mutableListOf<AuthenticationOutcome>()
        val request = start(id, oldOwner, onResult = oldResults::add)

        session.detach(oldOwner)
        assertTrue(session.reconnect(id, newOwner, newResults::add))
        session.detach(oldOwner)
        session.finish(request, success)
        session.finish(request, success)

        assertTrue(oldResults.isEmpty())
        assertEquals(listOf(success), newResults)
        assertFalse(session.canReconnect(id))
    }

    @Test
    fun `buffered result is consumed before a reentrant reconnect`() {
        val id = AuthenticationRequestId.create()
        val owner = Any()
        val results = mutableListOf<AuthenticationOutcome>()
        val request = start(id, owner)
        session.detach(owner)
        session.finish(request, success)

        val reconnected = session.reconnect(id, Any()) { result ->
            results += result
            assertFalse(session.canReconnect(id))
            assertFalse(session.reconnect(id, Any()) { error("Result must be consumed before callback") })
        }

        assertTrue(reconnected)
        assertEquals(listOf(success), results)
    }

    @Test
    fun `failure remains bound to A and late A success cannot complete B`() {
        val idA = AuthenticationRequestId.create()
        val idB = AuthenticationRequestId.create()
        val resultsA = mutableListOf<AuthenticationOutcome>()
        val resultsB = mutableListOf<AuthenticationOutcome>()
        val requestA = start(idA, retryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY, onResult = resultsA::add)

        session.onMismatch(requestA) {}
        session.finish(requestA, success)
        val requestB = start(idB, onResult = resultsB::add)
        session.finish(requestA, success)
        assertSame(requestB, session.active)
        session.finish(requestB, success)

        assertEquals(listOf(AuthenticationOutcome.Failure(AuthenticationFailureReason.MISMATCH)), resultsA)
        assertEquals(listOf(success), resultsB)
    }

    @Test
    fun `cancelled A cannot be reconnected and late A events leave B intact`() {
        val idA = AuthenticationRequestId.create()
        val idB = AuthenticationRequestId.create()
        val resultsA = mutableListOf<AuthenticationOutcome>()
        val resultsB = mutableListOf<AuthenticationOutcome>()
        val requestA = start(idA, onResult = resultsA::add)

        assertTrue(session.cancel(requestA))
        assertFalse(session.canReconnect(idA))
        assertFalse(session.reconnect(idA, Any(), resultsA::add))
        session.finish(requestA, success)
        val requestB = start(idB, onResult = resultsB::add)
        session.finish(requestA, success)
        session.onMismatch(requestA) { error("Stale A must not cancel B") }
        assertSame(requestB, session.active)
        session.finish(requestB, success)

        assertTrue(resultsA.isEmpty())
        assertEquals(listOf(success), resultsB)
    }

    @Test
    fun `starting B discards an unclaimed A result without relabelling it`() {
        val idA = AuthenticationRequestId.create()
        val idB = AuthenticationRequestId.create()
        val ownerA = Any()
        val resultsB = mutableListOf<AuthenticationOutcome>()
        val requestA = start(idA, ownerA)
        session.detach(ownerA)
        session.finish(requestA, success)

        val requestB = start(idB, onResult = resultsB::add)
        assertFalse(session.canReconnect(idA))
        assertFalse(session.reconnect(idA, Any()) { error("Superseded A must not be delivered") })
        session.finish(requestB, AuthenticationOutcome.Cancelled)

        assertEquals(listOf(AuthenticationOutcome.Cancelled), resultsB)
    }

    @Test
    fun `a generated request ID can be claimed only once`() {
        val id = AuthenticationRequestId.create()

        assertTrue(id.tryClaim())
        assertFalse(id.tryClaim())
        assertNotEquals(id, AuthenticationRequestId.create())
    }

    @Test
    fun `concurrent starts can claim one request ID only once`() {
        val id = AuthenticationRequestId.create()
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val claims = List(8) {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    id.tryClaim()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
            assertFalse(id.tryClaim())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun start(
        requestId: AuthenticationRequestId?,
        owner: Any = Any(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
        onResult: (AuthenticationOutcome) -> Unit = {}
    ): AuthenticationSession.Request = checkNotNull(
        session.tryStart(
            authenticators = BIOMETRIC_STRONG,
            retryPolicy = retryPolicy,
            delivery = Delivery.RECONNECTABLE,
            owner = owner,
            requestId = requestId,
            onResult = onResult
        )
    )
}
