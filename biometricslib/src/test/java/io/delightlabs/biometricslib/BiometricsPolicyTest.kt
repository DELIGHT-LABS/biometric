package io.delightlabs.biometricslib

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricsPolicyTest {
    @Test
    fun `default config uses biometric or credential`() {
        assertEquals(BiometricsType.BIOMETRIC_OR_CREDENTIAL, BiometricsConfig().biometricsType)
    }

    @Test
    fun `or credential uses biometric weak plus device credential when biometric is available`() {
        val authenticators = BiometricsPolicy.determineAuthenticators(
            biometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL
        )

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            authenticators
        )
    }

    @Test
    fun `or credential keeps weak plus device credential when biometric is unavailable`() {
        val authenticators = BiometricsPolicy.determineAuthenticators(
            biometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL
        )

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            authenticators
        )
    }

    @Test
    fun `biometric only returns not enrolled when biometric is missing`() {
        val availability = BiometricsPolicy.mapAvailability(
            biometricStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            hasDeviceCredential = true,
            biometricsType = BiometricsType.BIOMETRIC_ONLY,
            sdkInt = 30
        )

        assertEquals(BiometricsAvailability.BIOMETRIC_NOT_ENROLLED, availability)
    }

    @Test
    fun `or credential returns credential only when biometric is locked`() {
        val availability = BiometricsPolicy.mapAvailability(
            biometricStatus = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            hasDeviceCredential = true,
            biometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL,
            sdkInt = 29
        )

        assertEquals(BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE, availability)
    }

    @Test
    fun `credential only is unsupported before android 11`() {
        val availability = BiometricsPolicy.mapAvailability(
            biometricStatus = BiometricManager.BIOMETRIC_SUCCESS,
            hasDeviceCredential = true,
            biometricsType = BiometricsType.CREDENTIAL_ONLY,
            sdkInt = 29
        )

        assertEquals(BiometricsAvailability.AUTHENTICATOR_UNSUPPORTED, availability)
    }

    @Test
    fun `reported device credential result in combined flow resolves to pin`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL,
            authenticators = BiometricsPolicy.determineAuthenticators(BiometricsType.BIOMETRIC_OR_CREDENTIAL)
        )

        assertEquals(AuthenticationMethod.PIN, method)
    }

    @Test
    fun `reported biometric result in combined flow resolves to biometric`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
            authenticators = BiometricsPolicy.determineAuthenticators(BiometricsType.BIOMETRIC_OR_CREDENTIAL)
        )

        assertEquals(AuthenticationMethod.BIOMETRIC, method)
    }

    @Test
    fun `unknown result with only biometrics allowed resolves to biometric`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.determineAuthenticators(BiometricsType.BIOMETRIC_ONLY)
        )

        assertEquals(AuthenticationMethod.BIOMETRIC, method)
    }

    @Test
    fun `unknown result with only device credentials allowed resolves to pin`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.determineAuthenticators(BiometricsType.CREDENTIAL_ONLY)
        )

        assertEquals(AuthenticationMethod.PIN, method)
    }

    @Test
    fun `unknown result in combined flow remains unknown`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.determineAuthenticators(BiometricsType.BIOMETRIC_OR_CREDENTIAL)
        )

        assertEquals(AuthenticationMethod.UNKNOWN, method)
    }
}
