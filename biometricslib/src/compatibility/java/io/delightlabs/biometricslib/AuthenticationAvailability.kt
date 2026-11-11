package io.delightlabs.biometricslib

// Compatibility types keep their original JVM names; new integrations use feature packages.

/**
 * A preflight assessment for a requested [AuthenticationMode]. The platform can still reject
 * a prompt after this assessment if its state changes or the user is locked out.
 */
enum class AuthenticationAvailability {
    /** A biometric that meets the request's strength requirement is available. */
    BIOMETRIC_AVAILABLE,

    /** A permitted device credential is configured and a biometric has not been confirmed. */
    DEVICE_CREDENTIAL_AVAILABLE,

    /** No biometric that meets the request's strength requirement is enrolled. */
    NOT_ENROLLED,

    /** No required device PIN, pattern, or password is configured. */
    NO_DEVICE_CREDENTIAL,

    /** No biometric hardware satisfies the request. */
    NO_HARDWARE,

    /** The biometric hardware is temporarily unavailable. */
    HARDWARE_UNAVAILABLE,

    /** Biometric authentication requires a security update. */
    SECURITY_UPDATE_REQUIRED,

    /** The requested authenticator combination is unsupported. */
    UNSUPPORTED,

    /** The platform could not confirm availability. The prompt may still be attempted. */
    UNKNOWN
}
