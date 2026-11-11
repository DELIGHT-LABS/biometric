package io.delightlabs.biometricslib

import io.delightlabs.biometricslib.authentication.request.AuthenticationRequestId as RequestId

/** Original request-ID API backed by the same single-use token as the feature API. */
class AuthenticationRequestId private constructor(internal val delegate: RequestId) {
    internal val maxBufferedSuccessAgeMillis: Long? get() = delegate.maxBufferedSuccessAgeMillis

    internal fun tryClaim(owner: Any? = null): Boolean = delegate.tryClaim(owner)

    internal fun cancelBeforeRegistration(owner: Any): Boolean = delegate.cancelBeforeRegistration(owner)

    internal val cancelledBeforeRegistration: Boolean get() = delegate.cancelledBeforeRegistration

    internal fun markRegistered() = delegate.markRegistered()

    internal fun markCompleted() = delegate.markCompleted()

    override fun equals(other: Any?): Boolean = other is AuthenticationRequestId && delegate == other.delegate

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = delegate.toString()

    companion object {
        @JvmStatic
        fun create(): AuthenticationRequestId = AuthenticationRequestId(RequestId.create())

        @JvmStatic
        fun create(maxBufferedSuccessAgeMillis: Long): AuthenticationRequestId =
            AuthenticationRequestId(RequestId.create(maxBufferedSuccessAgeMillis))
    }
}

/** A terminal outcome associated with the original request ID supplied by the caller. */
data class AuthenticationResult(
    val requestId: AuthenticationRequestId,
    val outcome: AuthenticationOutcome
)
