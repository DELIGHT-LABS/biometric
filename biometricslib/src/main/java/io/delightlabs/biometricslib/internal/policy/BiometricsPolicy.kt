package io.delightlabs.biometricslib.internal.policy

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.BiometricsAvailability
import io.delightlabs.biometricslib.BiometricsType
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod

internal class AuthenticatorPlan(
    val biometricAuthenticators: Int?,
    val allowsDeviceCredential: Boolean,
    private val minSdk: Int = 28
) {
    val authenticators: Int = (biometricAuthenticators ?: 0) or
        (if (allowsDeviceCredential) DEVICE_CREDENTIAL else 0)

    fun isSupported(sdkInt: Int): Boolean = sdkInt >= minSdk
}

internal object BiometricsPolicy {
    private const val ANDROID_11 = 30

    fun plan(mode: AuthenticationMode): AuthenticatorPlan =
        when (mode) {
            AuthenticationMode.BIOMETRIC_ONLY -> AuthenticatorPlan(BIOMETRIC_STRONG, false)
            AuthenticationMode.DEVICE_CREDENTIAL_ONLY -> AuthenticatorPlan(null, true, ANDROID_11)
            AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL -> AuthenticatorPlan(BIOMETRIC_WEAK, true)
            AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL ->
                AuthenticatorPlan(BIOMETRIC_STRONG, true, ANDROID_11)
        }

    fun modeFor(type: BiometricsType): AuthenticationMode =
        when (type) {
            BiometricsType.BIOMETRIC_ONLY -> AuthenticationMode.BIOMETRIC_ONLY
            BiometricsType.CREDENTIAL_ONLY -> AuthenticationMode.DEVICE_CREDENTIAL_ONLY
            BiometricsType.BIOMETRIC_OR_CREDENTIAL -> AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL
        }

    fun assessAvailability(
        plan: AuthenticatorPlan,
        sdkInt: Int,
        hasDeviceCredential: Boolean,
        biometricStatus: Int?
    ): AuthenticationAvailability {
        if (!plan.isSupported(sdkInt)) return AuthenticationAvailability.UNSUPPORTED
        if (plan.biometricAuthenticators == null) {
            return if (hasDeviceCredential) {
                AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE
            } else {
                AuthenticationAvailability.NO_DEVICE_CREDENTIAL
            }
        }
        if (biometricStatus == BiometricManager.BIOMETRIC_SUCCESS) {
            return AuthenticationAvailability.BIOMETRIC_AVAILABLE
        }
        if (plan.allowsDeviceCredential && hasDeviceCredential) {
            return AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE
        }

        return when (biometricStatus) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                if (plan.allowsDeviceCredential) {
                    AuthenticationAvailability.NO_DEVICE_CREDENTIAL
                } else {
                    AuthenticationAvailability.NOT_ENROLLED
                }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthenticationAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AuthenticationAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> AuthenticationAvailability.SECURITY_UPDATE_REQUIRED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> AuthenticationAvailability.UNSUPPORTED
            else -> AuthenticationAvailability.UNKNOWN
        }
    }

    /** Keeps the coarser status buckets of the existing public API. */
    fun legacyAvailability(availability: AuthenticationAvailability): BiometricsAvailability =
        when (availability) {
            AuthenticationAvailability.BIOMETRIC_AVAILABLE,
            AuthenticationAvailability.UNKNOWN -> BiometricsAvailability.BIOMETRIC_AVAILABLE
            AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE -> BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE
            AuthenticationAvailability.NOT_ENROLLED -> BiometricsAvailability.BIOMETRIC_NOT_ENROLLED
            AuthenticationAvailability.NO_DEVICE_CREDENTIAL -> BiometricsAvailability.NO_SECURITY_CONFIGURED
            AuthenticationAvailability.NO_HARDWARE -> BiometricsAvailability.HARDWARE_NOT_SUPPORTED
            AuthenticationAvailability.HARDWARE_UNAVAILABLE -> BiometricsAvailability.TEMPORARILY_LOCKED
            AuthenticationAvailability.SECURITY_UPDATE_REQUIRED -> BiometricsAvailability.PERMANENTLY_LOCKED
            AuthenticationAvailability.UNSUPPORTED -> BiometricsAvailability.AUTHENTICATOR_UNSUPPORTED
        }

    fun resolveAuthenticationMethod(
        authenticationType: Int,
        authenticators: Int
    ): VerifiedAuthenticationMethod {
        return when (authenticationType) {
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> VerifiedAuthenticationMethod.BIOMETRIC
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL ->
                VerifiedAuthenticationMethod.DEVICE_CREDENTIAL
            else -> inferAuthenticationMethod(authenticators)
        }
    }

    private fun inferAuthenticationMethod(authenticators: Int): VerifiedAuthenticationMethod {
        return when (authenticators) {
            DEVICE_CREDENTIAL -> VerifiedAuthenticationMethod.DEVICE_CREDENTIAL
            BIOMETRIC_STRONG, BIOMETRIC_WEAK -> VerifiedAuthenticationMethod.BIOMETRIC
            else -> VerifiedAuthenticationMethod.UNKNOWN
        }
    }
}
