package io.delightlabs.biometricslib.availability

import io.delightlabs.biometricslib.authentication.BiometricsManager

/** Compatibility buckets returned by [BiometricsManager.checkAvailability]. */
enum class BiometricsAvailability {
    BIOMETRIC_AVAILABLE,
    CREDENTIAL_ONLY_AVAILABLE,
    BIOMETRIC_NOT_ENROLLED,
    NO_SECURITY_CONFIGURED,
    HARDWARE_NOT_SUPPORTED,

    /** Historical bucket for temporarily unavailable hardware. */
    TEMPORARILY_LOCKED,

    /** Historical bucket for hardware requiring a security update. */
    PERMANENTLY_LOCKED,

    /** The requested authenticator combination is unsupported on this Android version. */
    AUTHENTICATOR_UNSUPPORTED
}
