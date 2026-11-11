package io.delightlabs.biometricslib.authentication.policy

/** How the prompt handles a biometric that was presented but not recognized. */
enum class BiometricsRetryPolicy {
    /** Continue the same prompt until it succeeds, is dismissed, or reports a terminal error. */
    ALLOW_RETRY,

    /** Report the first mismatch once and request that the platform close the prompt. */
    FAIL_IMMEDIATELY
}
