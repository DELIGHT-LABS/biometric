package io.delightlabs.biometricslib

/** The set of authenticators that a request accepts. */
enum class AuthenticationMode {
    /** Accept a Class 3 biometric. */
    BIOMETRIC_ONLY,

    /** Accept the device's PIN, pattern, or password. Available on Android 11 and later. */
    DEVICE_CREDENTIAL_ONLY,

    /** Accept a Class 2 or Class 3 biometric, or a device credential. */
    BIOMETRIC_OR_DEVICE_CREDENTIAL,

    /** Accept a Class 3 biometric or a device credential. Available on Android 11 and later. */
    STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL
}
