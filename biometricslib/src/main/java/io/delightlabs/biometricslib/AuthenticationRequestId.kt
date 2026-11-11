package io.delightlabs.biometricslib

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-use identity for one authentication attempt.
 *
 * Retain in a ViewModel with the original action to reconnect after Activity recreation.
 * A retry uses a new ID. IDs and their admission state belong to the current process.
 */
class AuthenticationRequestId private constructor(
    private val value: UUID,
    internal val maxBufferedSuccessAgeMillis: Long?
) {
    private val state = AtomicReference<Admission>(Admission.Fresh)

    internal fun tryClaim(owner: Any? = null): Boolean =
        state.compareAndSet(Admission.Fresh, Admission.Waiting(owner))

    internal fun cancelBeforeRegistration(owner: Any): Boolean {
        val current = state.get()
        return current is Admission.Waiting && current.owner === owner &&
            state.compareAndSet(current, Admission.Cancelled)
    }

    internal val cancelledBeforeRegistration: Boolean get() = state.get() === Admission.Cancelled

    internal fun markRegistered() {
        val current = state.get()
        if (current is Admission.Waiting) state.compareAndSet(current, Admission.Registered)
    }

    internal fun markCompleted() {
        state.set(Admission.Completed)
    }

    override fun equals(other: Any?): Boolean = other is AuthenticationRequestId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()

    companion object {
        /** Creates an ID whose buffered success stays available for the host's lifetime. */
        @JvmStatic
        fun create(): AuthenticationRequestId = AuthenticationRequestId(UUID.randomUUID(), null)

        /**
         * Limits the age of a success buffered during Activity recreation.
         * The age starts when authentication succeeds and includes device sleep.
         * An expired success is consumed as Failure(TIMEOUT) on the rightful reconnect.
         */
        @JvmStatic
        fun create(maxBufferedSuccessAgeMillis: Long): AuthenticationRequestId {
            require(maxBufferedSuccessAgeMillis > 0) { "Buffered success age must be positive." }
            return AuthenticationRequestId(UUID.randomUUID(), maxBufferedSuccessAgeMillis)
        }
    }

    private sealed interface Admission {
        data object Fresh : Admission
        data class Waiting(val owner: Any?) : Admission
        data object Registered : Admission
        data object Cancelled : Admission
        data object Completed : Admission
    }
}

/** A terminal outcome associated with the authentication attempt that produced it. */
data class AuthenticationResult(
    val requestId: AuthenticationRequestId,
    val outcome: AuthenticationOutcome
)
