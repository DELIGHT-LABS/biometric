package io.delightlabs.biometricslib.internal.prompt

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import io.delightlabs.biometricslib.authentication.policy.BiometricsRetryPolicy
import io.delightlabs.biometricslib.authentication.result.AuthenticationFailureReason
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome
import io.delightlabs.biometricslib.internal.session.AuthenticationSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationLaunchQueueTest {
    @Test
    fun `cancelling before launch releases the session without invoking the platform or delivering a result`() {
        val fixture = Fixture()
        val request = fixture.startAfterPlatformCompletion()
        fixture.scheduler.advanceBy(100)

        assertTrue(fixture.queue.isPending(request))
        assertTrue(fixture.queue.cancelPending(request))
        assertFalse(fixture.queue.isPending(request))
        assertNull(fixture.session.active)

        fixture.scheduler.advanceBy(600)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.outcomes.isEmpty())
        assertEquals(listOf(request), fixture.abandoned)
    }

    @Test
    fun `a cancelled launch does not reset the deadline or interfere with the next request`() {
        val fixture = Fixture()
        val first = fixture.startAfterPlatformCompletion()
        val staleTask = fixture.scheduler.onlyPendingTask()
        fixture.scheduler.advanceBy(200)
        assertTrue(fixture.queue.cancelPending(first))

        val next = fixture.start()
        assertTrue(fixture.queue.isPending(next))
        assertFalse(fixture.queue.cancelPending(first))
        staleTask.run()
        fixture.scheduler.advanceBy(399)
        assertTrue(fixture.launches.isEmpty())
        assertSame(next, fixture.session.active)

        fixture.scheduler.advanceBy(1)
        assertEquals(listOf(next), fixture.launches)
        assertFalse(fixture.queue.isPending(next))
        assertSame(next, fixture.session.active)
        assertEquals(listOf(first), fixture.abandoned)
    }

    @Test
    fun `a due launch observes coroutine cancellation before main thread cleanup runs`() {
        val fixture = Fixture()
        var callerActive = true
        val request = fixture.startAfterPlatformCompletion { callerActive }

        // The coroutine is cancelled before its cleanup callback reaches the main thread.
        callerActive = false
        fixture.scheduler.advanceBy(600)
        assertFalse(fixture.queue.cancelPending(request))

        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.outcomes.isEmpty())
        assertNull(fixture.session.active)
        assertEquals(listOf(request), fixture.abandoned)
    }

    @Test
    fun `first request starts immediately even on Android 10`() {
        val fixture = Fixture()

        val request = fixture.start()

        assertEquals(listOf(request), fixture.launches)
        assertFalse(fixture.queue.isPending(request))
        assertTrue(fixture.scheduler.isEmpty)
    }

    @Test
    fun `a zero restart delay starts a follow up prompt immediately`() {
        val fixture = Fixture(restartDelayMillis = 0)

        val request = fixture.startAfterPlatformCompletion()

        assertEquals(listOf(request), fixture.launches)
        assertFalse(fixture.queue.isPending(request))
        assertTrue(fixture.scheduler.isEmpty)
    }

    @Test
    fun `host cleanup unschedules the launch and completes its waiting coroutine once`() {
        val fixture = Fixture()
        fixture.startAfterPlatformCompletion()
        val staleTask = fixture.scheduler.onlyPendingTask()

        fixture.queue.clear()
        fixture.session.clear()
        staleTask.run()
        fixture.scheduler.advanceBy(600)

        assertTrue(fixture.scheduler.isEmpty)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.abandoned.isEmpty())
        assertNull(fixture.session.active)
        assertEquals(
            listOf(AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE)),
            fixture.outcomes
        )
    }

    private class Fixture(restartDelayMillis: Long = 600) {
        val session = AuthenticationSession()
        val scheduler = FakeScheduler()
        val outcomes = mutableListOf<AuthenticationOutcome>()
        val abandoned = mutableListOf<AuthenticationSession.Request>()
        val launches = mutableListOf<AuthenticationSession.Request>()
        val queue = AuthenticationLaunchQueue(
            session = session,
            restartDelayMillis = restartDelayMillis,
            uptimeMillis = { scheduler.uptimeMillis },
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            onAbandoned = abandoned::add
        )

        fun startAfterPlatformCompletion(isCallerActive: () -> Boolean = { true }): AuthenticationSession.Request {
            queue.recordPlatformCompletion()
            return start(isCallerActive)
        }

        fun start(isCallerActive: () -> Boolean = { true }): AuthenticationSession.Request {
            val request = checkNotNull(
                session.tryStart(
                    authenticators = BIOMETRIC_STRONG,
                    retryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
                    delivery = AuthenticationSession.Delivery.COROUTINE,
                    owner = null,
                    onResult = outcomes::add
                )
            )
            queue.launch(request, isCallerActive) { launches += request }
            return request
        }
    }

    private class FakeScheduler {
        private val scheduled = mutableMapOf<Runnable, Long>()

        var uptimeMillis = 1_000L
            private set

        val isEmpty: Boolean get() = scheduled.isEmpty()

        fun postDelayed(task: Runnable, delayMillis: Long) {
            scheduled[task] = uptimeMillis + delayMillis
        }

        fun removeCallbacks(task: Runnable) {
            scheduled.remove(task)
        }

        fun onlyPendingTask(): Runnable = scheduled.keys.single()

        fun advanceBy(millis: Long) {
            uptimeMillis += millis
            while (true) {
                val dueTask = scheduled.minByOrNull { it.value } ?: return
                if (dueTask.value > uptimeMillis) return
                scheduled.remove(dueTask.key)
                dueTask.key.run()
            }
        }
    }
}
