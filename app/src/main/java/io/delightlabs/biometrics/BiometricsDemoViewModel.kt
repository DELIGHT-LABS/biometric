package io.delightlabs.biometrics

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.AuthenticationResult
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_BUFFERED_SUCCESS_AGE_MILLIS = 30_000L

/** Activity events and authentication results update the request ID and render state together. */
@MainThread
internal class BiometricsDemoViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(BiometricsDemoState())
    val state = mutableState.asStateFlow()
    var activeRequestId: AuthenticationRequestId? = null
        private set

    fun selectMode(mode: AuthenticationMode) {
        val current = state.value
        if (current.mode != mode) {
            mutableState.value = current.copy(mode = mode, availability = null, availabilityReport = null)
        }
    }

    fun updateAvailability(mode: AuthenticationMode, availability: AuthenticationAvailability) {
        val current = state.value
        if (current.mode == mode) {
            mutableState.value = current.copy(availability = availability, availabilityReport = null)
        }
    }

    fun updateAvailabilityReport(
        availabilityByMode: Map<AuthenticationMode, AuthenticationAvailability>
    ): DemoAvailabilityReport {
        val current = state.value
        val report = DemoAvailabilityReport(current.mode, availabilityByMode.toMap())
        mutableState.value = current.copy(availability = report.selectedAvailability, availabilityReport = report)
        return report
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        mutableState.value = state.value.copy(biometricsEnabled = enabled)
    }

    fun setRetryEnabled(enabled: Boolean) {
        mutableState.value = state.value.copy(retryEnabled = enabled)
    }

    /** The demo accepts a buffered success for up to 30 seconds after authentication succeeds. */
    fun beginAuthentication(): DemoAuthenticationRequest? {
        val current = state.value
        if (activeRequestId != null) return null

        if (!current.biometricsEnabled && current.mode != AuthenticationMode.DEVICE_CREDENTIAL_ONLY) {
            mutableState.value = current.copy(status = BiometricsDemoStatus.AppPinRequired)
            return null
        }

        val requestId = AuthenticationRequestId.create(
            maxBufferedSuccessAgeMillis = MAX_BUFFERED_SUCCESS_AGE_MILLIS
        )
        activeRequestId = requestId
        mutableState.value = current.copy(status = BiometricsDemoStatus.Authenticating)
        val retryPolicy = if (current.retryEnabled) {
            BiometricsRetryPolicy.ALLOW_RETRY
        } else {
            BiometricsRetryPolicy.FAIL_IMMEDIATELY
        }
        return DemoAuthenticationRequest(requestId, current.mode, retryPolicy)
    }

    fun onAuthenticationResult(result: AuthenticationResult): Boolean {
        if (activeRequestId != result.requestId) return false
        activeRequestId = null
        mutableState.value = state.value.copy(status = BiometricsDemoStatus.Completed(result.outcome))
        return true
    }

    /** Invalidate the app action before asking the platform to cancel its prompt. */
    fun abandonAuthentication(): AuthenticationRequestId? {
        val requestId = activeRequestId ?: return null
        activeRequestId = null
        mutableState.value = state.value.copy(
            status = BiometricsDemoStatus.Completed(AuthenticationOutcome.Cancelled)
        )
        return requestId
    }

    fun onSettingsUnavailable() {
        mutableState.value = state.value.copy(status = BiometricsDemoStatus.SettingsUnavailable)
    }
}

internal data class BiometricsDemoState(
    val mode: AuthenticationMode = AuthenticationMode.BIOMETRIC_ONLY,
    val availability: AuthenticationAvailability? = null,
    val availabilityReport: DemoAvailabilityReport? = null,
    val biometricsEnabled: Boolean = true,
    val retryEnabled: Boolean = false,
    val status: BiometricsDemoStatus = BiometricsDemoStatus.Ready
)

internal data class DemoAvailabilityReport(
    val selectedMode: AuthenticationMode,
    val availabilityByMode: Map<AuthenticationMode, AuthenticationAvailability>
) {
    val selectedAvailability: AuthenticationAvailability
        get() = availabilityByMode[selectedMode] ?: AuthenticationAvailability.UNKNOWN

    val isSelectedAvailable: Boolean
        get() = selectedMode in usableModes

    val usableModes: Set<AuthenticationMode>
        get() = AuthenticationMode.entries.filterTo(linkedSetOf()) { mode ->
            when (availabilityByMode[mode]) {
                AuthenticationAvailability.BIOMETRIC_AVAILABLE,
                AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE -> true
                else -> false
            }
        }

    val uncertainModes: Set<AuthenticationMode>
        get() = AuthenticationMode.entries.filterTo(linkedSetOf()) { mode ->
            val availability = availabilityByMode[mode]
            availability == null || availability == AuthenticationAvailability.UNKNOWN
        }
}

internal data class DemoAuthenticationRequest(
    val requestId: AuthenticationRequestId,
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
