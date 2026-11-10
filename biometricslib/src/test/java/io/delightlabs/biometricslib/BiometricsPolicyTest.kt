package io.delightlabs.biometricslib

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricsPolicyTest {
    @Test
    fun `legacy defaults use class 2 biometrics or device credential`() {
        val mode = BiometricsPolicy.modeFor(BiometricsConfig().biometricsType)
        val plan = BiometricsPolicy.plan(mode)

        assertEquals(AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL, mode)
        assertEquals(BIOMETRIC_WEAK, plan.biometricAuthenticators)
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, plan.authenticators)
    }

    @Test
    fun `strong mixed mode checks class 3 biometrics and allows device credential`() {
        val plan = BiometricsPolicy.plan(AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL)

        assertEquals(BIOMETRIC_STRONG, plan.biometricAuthenticators)
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, plan.authenticators)
    }

    @Test
    fun `biometric only does not require a separate credential for confirmed biometrics`() {
        val availability = assess(
            mode = AuthenticationMode.BIOMETRIC_ONLY,
            biometricStatus = BiometricManager.BIOMETRIC_SUCCESS,
            hasDeviceCredential = false
        )

        assertEquals(AuthenticationAvailability.BIOMETRIC_AVAILABLE, availability)
    }

    @Test
    fun `mixed mode with no enrolled biometric or device credential requires setup`() {
        val availability = assess(
            mode = AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL,
            biometricStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            hasDeviceCredential = false
        )

        assertEquals(AuthenticationAvailability.NO_DEVICE_CREDENTIAL, availability)
    }

    @Test
    fun `mixed mode can proceed when a credential is available and biometrics cannot be confirmed`() {
        val unavailableBiometricStatuses = listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN
        )

        for (sdkInt in listOf(28, 29, 30)) {
            for (status in unavailableBiometricStatuses) {
                assertEquals(
                    "SDK $sdkInt with biometric status $status",
                    AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
                    assess(
                        mode = AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL,
                        biometricStatus = status,
                        sdkInt = sdkInt
                    )
                )
            }
        }
    }

    @Test
    fun `preflight separates sensor errors and unknown status from prompt lockout`() {
        val expectedByStatus = mapOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to AuthenticationAvailability.NOT_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to AuthenticationAvailability.NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to AuthenticationAvailability.HARDWARE_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED to AuthenticationAvailability.SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED to AuthenticationAvailability.UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN to AuthenticationAvailability.UNKNOWN,
            Int.MAX_VALUE to AuthenticationAvailability.UNKNOWN
        )

        for ((status, expected) in expectedByStatus) {
            assertEquals(
                "Biometric status $status",
                expected,
                assess(AuthenticationMode.BIOMETRIC_ONLY, biometricStatus = status)
            )
        }
    }

    @Test
    fun `credential only probes no biometric and is supported from android 11`() {
        val mode = AuthenticationMode.DEVICE_CREDENTIAL_ONLY
        val plan = BiometricsPolicy.plan(mode)

        assertEquals(null, plan.biometricAuthenticators)
        assertEquals(DEVICE_CREDENTIAL, plan.authenticators)
        for (sdkInt in listOf(28, 29)) {
            assertEquals(AuthenticationAvailability.UNSUPPORTED, assess(mode, sdkInt = sdkInt))
        }
        assertEquals(AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE, assess(mode))
        assertEquals(
            AuthenticationAvailability.NO_DEVICE_CREDENTIAL,
            assess(mode, hasDeviceCredential = false)
        )
    }

    @Test
    fun `strong mixed mode is rejected on android 9 and 10 without lowering biometric strength`() {
        val mode = AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL

        for (sdkInt in listOf(28, 29)) {
            assertEquals(
                AuthenticationAvailability.UNSUPPORTED,
                assess(mode, BiometricManager.BIOMETRIC_SUCCESS, sdkInt = sdkInt)
            )
        }
        assertEquals(
            AuthenticationAvailability.BIOMETRIC_AVAILABLE,
            assess(mode, BiometricManager.BIOMETRIC_SUCCESS)
        )
        assertEquals(
            AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
            assess(mode, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        )
    }

    @Test
    fun `legacy availability keeps its existing status buckets`() {
        assertEquals(
            BiometricsAvailability.TEMPORARILY_LOCKED,
            BiometricsPolicy.legacyAvailability(AuthenticationAvailability.HARDWARE_UNAVAILABLE)
        )
        assertEquals(
            BiometricsAvailability.PERMANENTLY_LOCKED,
            BiometricsPolicy.legacyAvailability(AuthenticationAvailability.SECURITY_UPDATE_REQUIRED)
        )
    }

    @Test
    fun `reported device credential result in combined flow resolves to pin`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL,
            authenticators = BiometricsPolicy.plan(AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL).authenticators
        )

        assertEquals(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL, method)
    }

    @Test
    fun `reported biometric result in combined flow resolves to biometric`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
            authenticators = BiometricsPolicy.plan(AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL).authenticators
        )

        assertEquals(VerifiedAuthenticationMethod.BIOMETRIC, method)
    }

    @Test
    fun `unknown result with only biometrics allowed resolves to biometric`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.plan(AuthenticationMode.BIOMETRIC_ONLY).authenticators
        )

        assertEquals(VerifiedAuthenticationMethod.BIOMETRIC, method)
    }

    @Test
    fun `unknown result with only device credentials allowed resolves to pin`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.plan(AuthenticationMode.DEVICE_CREDENTIAL_ONLY).authenticators
        )

        assertEquals(VerifiedAuthenticationMethod.DEVICE_CREDENTIAL, method)
    }

    @Test
    fun `unknown result in combined flow remains unknown`() {
        val method = BiometricsPolicy.resolveAuthenticationMethod(
            authenticationType = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
            authenticators = BiometricsPolicy.plan(AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL).authenticators
        )

        assertEquals(VerifiedAuthenticationMethod.UNKNOWN, method)
    }

    private fun assess(
        mode: AuthenticationMode,
        biometricStatus: Int? = null,
        hasDeviceCredential: Boolean = true,
        sdkInt: Int = 30
    ): AuthenticationAvailability = BiometricsPolicy.assessAvailability(
        plan = BiometricsPolicy.plan(mode),
        sdkInt = sdkInt,
        hasDeviceCredential = hasDeviceCredential,
        biometricStatus = biometricStatus
    )
}
