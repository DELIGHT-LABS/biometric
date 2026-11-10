package io.delightlabs.biometrics

import androidx.lifecycle.ViewModel
import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BiometricsDemoViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(BiometricsDemoState())
    val state = mutableState.asStateFlow()

    fun selectMode(mode: AuthenticationMode) {
        val current = state.value
        if (current.mode != mode) mutableState.value = current.copy(mode = mode, availability = null)
    }

    fun updateAvailability(mode: AuthenticationMode, availability: AuthenticationAvailability) {
        val current = state.value
        if (current.mode == mode) mutableState.value = current.copy(availability = availability)
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        mutableState.value = state.value.copy(biometricsEnabled = enabled)
    }

    fun setRetryEnabled(enabled: Boolean) {
        mutableState.value = state.value.copy(retryEnabled = enabled)
    }

    fun beginAuthentication(): DemoAuthenticationRequest? {
        val current = state.value
        if (current.status == BiometricsDemoStatus.Authenticating) return null

        if (!current.biometricsEnabled && current.mode != AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
            mutableState.value = current.copy(status = BiometricsDemoStatus.AppPinRequired)
            return null
        }

        mutableState.value = current.copy(status = BiometricsDemoStatus.Authenticating)
        val retryPolicy = if (current.retryEnabled) {
            BiometricsRetryPolicy.ALLOW_RETRY
        } else {
            BiometricsRetryPolicy.FAIL_IMMEDIATELY
        }
        return DemoAuthenticationRequest(current.mode, retryPolicy)
    }

    fun onAuthenticationResult(outcome: AuthenticationOutcome) {
        mutableState.value = state.value.copy(status = BiometricsDemoStatus.Completed(outcome))
    }

    fun onSettingsUnavailable() {
        mutableState.value = state.value.copy(status = BiometricsDemoStatus.SettingsUnavailable)
    }
}

internal data class BiometricsDemoState(
    val mode: AuthenticationMode = AuthenticationMode.BIOMETRIC_ONLY,
    val availability: AuthenticationAvailability? = null,
    val biometricsEnabled: Boolean = true,
    val retryEnabled: Boolean = false,
    val status: BiometricsDemoStatus = BiometricsDemoStatus.Ready
)

internal data class DemoAuthenticationRequest(
    val mode: AuthenticationMode,
    val retryPolicy: BiometricsRetryPolicy
)

internal sealed interface BiometricsDemoStatus {
    data object Ready : BiometricsDemoStatus
    data object Authenticating : BiometricsDemoStatus
    data object AppPinRequired : BiometricsDemoStatus
    data object SettingsUnavailable : BiometricsDemoStatus
    data class Completed(val outcome: AuthenticationOutcome) : BiometricsDemoStatus
}
