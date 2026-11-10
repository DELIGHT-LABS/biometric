package io.delightlabs.biometricslib

import androidx.biometric.BiometricPrompt
import io.delightlabs.biometricslib.AuthenticationFailureReason as Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticationErrorsTest {
    @Test
    fun `platform failures preserve raw diagnostics and distinguish lockout from hardware errors`() {
        val expected = mapOf(
            BiometricPrompt.ERROR_LOCKOUT to Reason.TEMPORARILY_LOCKED,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT to Reason.PERMANENTLY_LOCKED,
            BiometricPrompt.ERROR_HW_UNAVAILABLE to Reason.HARDWARE_UNAVAILABLE,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED to Reason.SECURITY_UPDATE_REQUIRED,
            BiometricPrompt.ERROR_CANCELED to Reason.INTERRUPTED,
            BiometricPrompt.ERROR_TIMEOUT to Reason.TIMEOUT,
            BiometricPrompt.ERROR_VENDOR to Reason.PLATFORM_ERROR
        )

        for ((code, reason) in expected) {
            assertEquals(AuthenticationOutcome.Failure(reason, code, "diagnostic"), platformOutcome(code, "diagnostic"))
        }
    }

    @Test
    fun `user cancellation is distinct from system interruption`() {
        assertEquals(AuthenticationOutcome.Cancelled, platformOutcome(BiometricPrompt.ERROR_USER_CANCELED, "cancelled"))
        assertEquals(AuthenticationOutcome.Cancelled, platformOutcome(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "cancelled"))
    }

    @Test
    fun `unknown availability permits attempting the prompt`() {
        assertNull(AuthenticationAvailability.UNKNOWN.failureReason())
        assertEquals(Reason.NOT_ENROLLED, AuthenticationAvailability.NOT_ENROLLED.failureReason())
        assertEquals(Reason.UNSUPPORTED, AuthenticationAvailability.UNSUPPORTED.failureReason())
    }
}
