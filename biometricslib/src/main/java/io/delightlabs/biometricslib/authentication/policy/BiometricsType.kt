package io.delightlabs.biometricslib.authentication.policy

/** Legacy request modes. Use [AuthenticationMode] to choose biometric strength explicitly. */
enum class BiometricsType {
    /** Class 3 biometric on API 28 and later. */
    BIOMETRIC_ONLY,

    /** The system PIN, pattern, or password on API 30 and later. */
    CREDENTIAL_ONLY,

    /** Class 2 or stronger biometric, or the system device credential, on API 28 and later. */
    BIOMETRIC_OR_CREDENTIAL
}
