package io.delightlabs.biometricslib

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use identity for one authentication attempt.
 *
 * Create before starting authentication and retain in a ViewModel to reconnect after
 * Activity recreation. Use a new ID for each retry. An ID belongs to the current process.
 */
class AuthenticationRequestId private constructor(private val value: UUID) {
    private val claimed = AtomicBoolean()

    internal fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

    override fun equals(other: Any?): Boolean = other is AuthenticationRequestId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun create(): AuthenticationRequestId = AuthenticationRequestId(UUID.randomUUID())
    }
}

/** A terminal outcome associated with the authentication attempt that produced it. */
data class AuthenticationResult(
    val requestId: AuthenticationRequestId,
    val outcome: AuthenticationOutcome
)
