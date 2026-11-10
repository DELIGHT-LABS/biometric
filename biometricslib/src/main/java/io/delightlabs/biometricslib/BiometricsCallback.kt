package io.delightlabs.biometricslib

/**
 * Legacy result callback delivered on the main thread for enabled authentication requests.
 * [BiometricsManager.startAuthentication] provides typed failures and Activity reconnection.
 */
interface BiometricsCallback {
    /** Compatibility callback for consumers that only need to know whether authentication succeeded. */
    @Deprecated(
        message = "Use onSuccess(authenticationMethod) to distinguish biometrics from pin-based device credentials."
    )
    fun onSuccess() {}

    /** Called once on success. The default implementation forwards to the legacy overload. */
    @Suppress("DEPRECATION")
    fun onSuccess(authenticationMethod: AuthenticationMethod) {
        onSuccess()
    }

    /** Called with a library error code or an AndroidX prompt code and a display message. */
    fun onError(errorCode: Int, errorMessage: String)

    /** Called when the user dismisses the prompt. */
    fun onCancel()
}
