package io.delightlabs.biometricslib

/** Final outcome of a single authentication request. */
sealed interface AuthenticationOutcome {
    data class Success(val method: VerifiedAuthenticationMethod) : AuthenticationOutcome

    /** The user dismissed the prompt. A cancelled coroutine instead throws CancellationException. */
    data object Cancelled : AuthenticationOutcome

    /**
     * [platformCode] and [message] may contain AndroidX diagnostics.
     * Use [reason] to choose the app's recovery action and localized message.
     */
    data class Failure(
        val reason: AuthenticationFailureReason,
        val platformCode: Int? = null,
        val message: String? = null
    ) : AuthenticationOutcome
}

enum class VerifiedAuthenticationMethod {
    BIOMETRIC,
    DEVICE_CREDENTIAL,
    UNKNOWN
}

enum class AuthenticationFailureReason {
    IN_PROGRESS,
    HOST_UNAVAILABLE,
    INVALID_CONFIGURATION,
    NOT_ENROLLED,
    NO_DEVICE_CREDENTIAL,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    MISMATCH,
    TEMPORARILY_LOCKED,
    PERMANENTLY_LOCKED,
    INTERRUPTED,
    TIMEOUT,
    PLATFORM_ERROR
}
