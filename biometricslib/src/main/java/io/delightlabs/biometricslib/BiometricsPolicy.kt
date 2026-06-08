package io.delightlabs.biometricslib

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

internal object BiometricsPolicy {
    private const val ANDROID_10 = 29
    private const val ANDROID_11 = 30

    fun determineAuthenticators(
        biometricsType: BiometricsType
    ): Int {
        return when (biometricsType) {
            BiometricsType.BIOMETRIC_ONLY -> BiometricManager.Authenticators.BIOMETRIC_STRONG
            BiometricsType.CREDENTIAL_ONLY -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            BiometricsType.BIOMETRIC_OR_CREDENTIAL ->
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    fun mapAvailability(
        biometricStatus: Int,
        hasDeviceCredential: Boolean,
        biometricsType: BiometricsType,
        sdkInt: Int
    ): BiometricsAvailability {
        return when (biometricsType) {
            BiometricsType.CREDENTIAL_ONLY -> {
                when {
                    sdkInt < ANDROID_11 -> BiometricsAvailability.AUTHENTICATOR_UNSUPPORTED
                    hasDeviceCredential -> {
                        BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE
                    }
                    else -> BiometricsAvailability.NO_SECURITY_CONFIGURED
                }
            }
            BiometricsType.BIOMETRIC_OR_CREDENTIAL -> {
                when {
                    !hasDeviceCredential -> BiometricsAvailability.NO_SECURITY_CONFIGURED
                    biometricStatus == BiometricManager.BIOMETRIC_SUCCESS ||
                        biometricStatus == BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                        BiometricsAvailability.BIOMETRIC_AVAILABLE
                    else -> BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE
                }
            }
            BiometricsType.BIOMETRIC_ONLY -> {
                when {
                    !hasDeviceCredential -> BiometricsAvailability.NO_SECURITY_CONFIGURED
                    biometricStatus == BiometricManager.BIOMETRIC_SUCCESS ||
                        biometricStatus == BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                        BiometricsAvailability.BIOMETRIC_AVAILABLE
                    biometricStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                        BiometricsAvailability.BIOMETRIC_NOT_ENROLLED
                    biometricStatus == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
                        biometricStatus == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                        BiometricsAvailability.HARDWARE_NOT_SUPPORTED
                    biometricStatus == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                        BiometricsAvailability.TEMPORARILY_LOCKED
                    biometricStatus == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                        BiometricsAvailability.PERMANENTLY_LOCKED
                    else -> BiometricsAvailability.HARDWARE_NOT_SUPPORTED
                }
            }
        }
    }

    fun resolveAuthenticationMethod(
        authenticationType: Int,
        authenticators: Int,
        sdkInt: Int
    ): AuthenticationMethod {
        return when (authenticationType) {
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> AuthenticationMethod.BIOMETRIC
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL ->
                AuthenticationMethod.PIN
            else -> inferAuthenticationMethod(authenticators, sdkInt)
        }
    }

    fun allowsDeviceCredential(authenticators: Int): Boolean {
        return authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0
    }

    private fun inferAuthenticationMethod(authenticators: Int, sdkInt: Int): AuthenticationMethod {
        return when {
            authenticators == BiometricManager.Authenticators.DEVICE_CREDENTIAL ->
                AuthenticationMethod.PIN
            !allowsDeviceCredential(authenticators) -> AuthenticationMethod.BIOMETRIC
            sdkInt == ANDROID_10 -> AuthenticationMethod.BIOMETRIC
            else -> AuthenticationMethod.UNKNOWN
        }
    }
}
