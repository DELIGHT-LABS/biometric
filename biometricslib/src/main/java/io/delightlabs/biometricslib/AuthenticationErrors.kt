package io.delightlabs.biometricslib

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import io.delightlabs.biometricslib.AuthenticationFailureReason as Reason

internal fun platformOutcome(code: Int, message: String): AuthenticationOutcome {
    val reason = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> return AuthenticationOutcome.Cancelled
        BiometricPrompt.ERROR_CANCELED -> Reason.INTERRUPTED
        BiometricPrompt.ERROR_HW_UNAVAILABLE -> Reason.HARDWARE_UNAVAILABLE
        BiometricPrompt.ERROR_HW_NOT_PRESENT -> Reason.NO_HARDWARE
        BiometricPrompt.ERROR_NO_BIOMETRICS -> Reason.NOT_ENROLLED
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> Reason.NO_DEVICE_CREDENTIAL
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> Reason.SECURITY_UPDATE_REQUIRED
        BiometricPrompt.ERROR_LOCKOUT -> Reason.TEMPORARILY_LOCKED
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> Reason.PERMANENTLY_LOCKED
        BiometricPrompt.ERROR_TIMEOUT -> Reason.TIMEOUT
        else -> Reason.PLATFORM_ERROR
    }
    return AuthenticationOutcome.Failure(reason, code, message)
}

internal fun AuthenticationAvailability.failureReason(): Reason? = when (this) {
    AuthenticationAvailability.BIOMETRIC_AVAILABLE,
    AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
    AuthenticationAvailability.UNKNOWN -> null
    AuthenticationAvailability.NOT_ENROLLED -> Reason.NOT_ENROLLED
    AuthenticationAvailability.NO_DEVICE_CREDENTIAL -> Reason.NO_DEVICE_CREDENTIAL
    AuthenticationAvailability.NO_HARDWARE -> Reason.NO_HARDWARE
    AuthenticationAvailability.HARDWARE_UNAVAILABLE -> Reason.HARDWARE_UNAVAILABLE
    AuthenticationAvailability.SECURITY_UPDATE_REQUIRED -> Reason.SECURITY_UPDATE_REQUIRED
    AuthenticationAvailability.UNSUPPORTED -> Reason.UNSUPPORTED
}

/** Snapshots Activity-localized strings without retaining the Activity in a prompt callback. */
internal class LegacyAuthenticationMessages(context: Context) {
    private val textByReason = Reason.values().associateWith { context.getString(it.messageResource()) }

    operator fun get(reason: Reason): String = textByReason.getValue(reason)
}

internal fun AuthenticationOutcome.deliverLegacy(messages: LegacyAuthenticationMessages, callback: BiometricsCallback) {
    when (this) {
        is AuthenticationOutcome.Success -> callback.onSuccess(method.toLegacyMethod())
        AuthenticationOutcome.Cancelled -> callback.onCancel()
        is AuthenticationOutcome.Failure -> {
            val code = platformCode ?: reason.legacyCode()
            val text = if (platformCode != null && !message.isNullOrBlank()) {
                message
            } else {
                messages[reason]
            }
            callback.onError(code, text)
        }
    }
}

private fun VerifiedAuthenticationMethod.toLegacyMethod(): AuthenticationMethod = when (this) {
    VerifiedAuthenticationMethod.BIOMETRIC -> AuthenticationMethod.BIOMETRIC
    VerifiedAuthenticationMethod.DEVICE_CREDENTIAL -> AuthenticationMethod.PIN
    VerifiedAuthenticationMethod.UNKNOWN -> AuthenticationMethod.UNKNOWN
}

private fun Reason.legacyCode(): Int = when (this) {
    Reason.IN_PROGRESS -> BiometricsManager.ERROR_AUTHENTICATION_IN_PROGRESS
    Reason.HOST_UNAVAILABLE, Reason.INTERRUPTED -> BiometricPrompt.ERROR_CANCELED
    Reason.INVALID_CONFIGURATION -> BiometricsManager.ERROR_INVALID_CONFIGURATION
    Reason.NOT_ENROLLED -> BiometricPrompt.ERROR_NO_BIOMETRICS
    Reason.NO_DEVICE_CREDENTIAL -> BiometricsManager.ERROR_NO_SECURITY
    Reason.NO_HARDWARE -> BiometricsManager.ERROR_HARDWARE_NOT_SUPPORTED
    Reason.HARDWARE_UNAVAILABLE -> BiometricPrompt.ERROR_HW_UNAVAILABLE
    Reason.SECURITY_UPDATE_REQUIRED -> BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED
    Reason.UNSUPPORTED -> BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
    Reason.MISMATCH -> BiometricsManager.ERROR_AUTHENTICATION_FAILED
    Reason.TEMPORARILY_LOCKED -> BiometricPrompt.ERROR_LOCKOUT
    Reason.PERMANENTLY_LOCKED -> BiometricPrompt.ERROR_LOCKOUT_PERMANENT
    Reason.TIMEOUT -> BiometricPrompt.ERROR_TIMEOUT
    Reason.PLATFORM_ERROR -> BiometricPrompt.ERROR_VENDOR
}

private fun Reason.messageResource(): Int = when (this) {
    Reason.IN_PROGRESS -> R.string.biometrics_error_in_progress
    Reason.HOST_UNAVAILABLE -> R.string.biometrics_error_host
    Reason.INVALID_CONFIGURATION -> R.string.biometrics_error_config
    Reason.NOT_ENROLLED -> R.string.biometrics_error_enrollment
    Reason.NO_DEVICE_CREDENTIAL -> R.string.biometrics_error_credential
    Reason.NO_HARDWARE -> R.string.biometrics_error_no_hardware
    Reason.HARDWARE_UNAVAILABLE -> R.string.biometrics_error_hardware_unavailable
    Reason.SECURITY_UPDATE_REQUIRED -> R.string.biometrics_error_update_required
    Reason.UNSUPPORTED -> R.string.biometrics_error_unsupported
    Reason.MISMATCH -> R.string.biometrics_error_mismatch
    Reason.TEMPORARILY_LOCKED -> R.string.biometrics_error_lockout
    Reason.PERMANENTLY_LOCKED -> R.string.biometrics_error_permanent_lockout
    Reason.INTERRUPTED -> R.string.biometrics_error_interrupted
    Reason.TIMEOUT -> R.string.biometrics_error_timeout
    Reason.PLATFORM_ERROR -> R.string.biometrics_error_platform
}
