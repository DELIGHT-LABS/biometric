package io.delightlabs.biometricslib

import androidx.annotation.MainThread
import androidx.fragment.app.FragmentActivity
import io.delightlabs.biometricslib.authentication.BiometricsManager as Manager

/**
 * Original package facade. Authentication runs in the feature API's single Activity host.
 * New integrations can import [io.delightlabs.biometricslib.authentication.BiometricsManager].
 */
class BiometricsManager private constructor(
    private val delegate: Manager,
    private val config: BiometricsConfig
) {
    companion object {
        const val ERROR_NO_SECURITY = Manager.ERROR_NO_SECURITY
        const val ERROR_HARDWARE_NOT_SUPPORTED = Manager.ERROR_HARDWARE_NOT_SUPPORTED
        const val ERROR_TEMPORARILY_LOCKED = Manager.ERROR_TEMPORARILY_LOCKED
        const val ERROR_PERMANENTLY_LOCKED = Manager.ERROR_PERMANENTLY_LOCKED
        const val ERROR_AUTHENTICATION_FAILED = Manager.ERROR_AUTHENTICATION_FAILED
        const val ERROR_AUTHENTICATION_IN_PROGRESS = Manager.ERROR_AUTHENTICATION_IN_PROGRESS
        const val ERROR_INVALID_CONFIGURATION = Manager.ERROR_INVALID_CONFIGURATION

        fun create(
            activity: FragmentActivity,
            config: BiometricsConfig = BiometricsConfig.localized(activity)
        ): BiometricsManager = BiometricsManager(Manager.create(activity, config.canonical()), config)
    }

    fun authenticate(
        callback: BiometricsCallback,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        delegate.authenticate(
            callback = callback.canonical(),
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        delegate.authenticate(
            callback = callback.canonical(),
            biometricsType = biometricsType.canonical(),
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    fun authenticate(
        callback: BiometricsCallback,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        delegate.authenticate(
            callback = callback.canonical(),
            retryPolicy = retryPolicy.canonical(),
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        delegate.authenticate(
            callback = callback.canonical(),
            biometricsType = biometricsType.canonical(),
            retryPolicy = retryPolicy.canonical(),
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    fun startAuthentication(
        mode: AuthenticationMode = config.biometricsType.defaultMode(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        onResult: (AuthenticationOutcome) -> Unit
    ) {
        delegate.startAuthentication(mode.canonical(), retryPolicy.canonical()) {
            onResult(it.compatibility())
        }
    }

    fun startAuthentication(
        requestId: AuthenticationRequestId,
        mode: AuthenticationMode = config.biometricsType.defaultMode(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        onResult: (AuthenticationResult) -> Unit
    ): Boolean = delegate.startAuthentication(requestId.delegate, mode.canonical(), retryPolicy.canonical()) {
        onResult(it.compatibility(requestId))
    }

    @MainThread
    fun reconnectAuthentication(onResult: (AuthenticationOutcome) -> Unit): Boolean =
        delegate.reconnectAuthentication { onResult(it.compatibility()) }

    @MainThread
    fun reconnectAuthentication(
        requestId: AuthenticationRequestId,
        onResult: (AuthenticationResult) -> Unit
    ): Boolean = delegate.reconnectAuthentication(requestId.delegate) {
        onResult(it.compatibility(requestId))
    }

    @MainThread
    fun cancelAuthentication(requestId: AuthenticationRequestId): Boolean = delegate.cancelAuthentication(requestId.delegate)

    @MainThread
    fun discardAuthenticationResult(requestId: AuthenticationRequestId): Boolean =
        delegate.discardAuthenticationResult(requestId.delegate)

    suspend fun awaitAuthentication(
        mode: AuthenticationMode = config.biometricsType.defaultMode(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY
    ): AuthenticationOutcome = delegate.awaitAuthentication(mode.canonical(), retryPolicy.canonical()).compatibility()

    suspend fun awaitAuthentication(
        requestId: AuthenticationRequestId,
        mode: AuthenticationMode = config.biometricsType.defaultMode(),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY
    ): AuthenticationResult = delegate.awaitAuthentication(
        requestId.delegate,
        mode.canonical(),
        retryPolicy.canonical()
    ).compatibility(requestId)

    fun checkAvailability(): BiometricsAvailability = delegate.checkAvailability().compatibility()

    fun checkAvailability(biometricsType: BiometricsType): BiometricsAvailability =
        delegate.checkAvailability(biometricsType.canonical()).compatibility()

    fun checkAuthenticationAvailability(
        mode: AuthenticationMode = config.biometricsType.defaultMode()
    ): AuthenticationAvailability = delegate.checkAuthenticationAvailability(mode.canonical()).compatibility()

    fun openSecuritySettings() = delegate.openSecuritySettings()

    @MainThread
    fun tryOpenSecuritySettings(): Boolean = delegate.tryOpenSecuritySettings()
}
