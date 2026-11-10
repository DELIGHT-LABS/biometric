package io.delightlabs.biometrics

import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationFailureReason
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiometricsDemoViewModelTest {
    private val viewModel = BiometricsDemoViewModel()

    @Test
    fun `changing mode discards stale availability and accepts the selected mode result`() {
        viewModel.updateAvailability(
            AuthenticationMode.BIOMETRIC_ONLY,
            AuthenticationAvailability.BIOMETRIC_AVAILABLE
        )

        viewModel.selectMode(AuthenticationMode.DEVICE_CREDENTIAL_ONLY)
        viewModel.updateAvailability(
            AuthenticationMode.BIOMETRIC_ONLY,
            AuthenticationAvailability.BIOMETRIC_AVAILABLE
        )
        assertNull(viewModel.state.value.availability)

        viewModel.updateAvailability(
            AuthenticationMode.DEVICE_CREDENTIAL_ONLY,
            AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE
        )
        assertEquals(AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE, viewModel.state.value.availability)
    }

    @Test
    fun `authentication uses the selected mode and retry setting and rejects duplicate actions`() {
        viewModel.selectMode(AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL)
        viewModel.setRetryEnabled(true)

        assertEquals(
            DemoAuthenticationRequest(
                AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL,
                BiometricsRetryPolicy.ALLOW_RETRY
            ),
            viewModel.beginAuthentication()
        )
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)
        assertNull(viewModel.beginAuthentication())
    }

    @Test
    fun `disabled biometrics routes to app PIN without starting platform authentication`() {
        viewModel.setBiometricsEnabled(false)

        assertNull(viewModel.beginAuthentication())
        assertEquals(BiometricsDemoStatus.AppPinRequired, viewModel.state.value.status)
    }

    @Test
    fun `explicit device credential still works when app biometrics are disabled`() {
        viewModel.setBiometricsEnabled(false)
        viewModel.selectMode(AuthenticationMode.DEVICE_CREDENTIAL_ONLY)

        assertEquals(
            DemoAuthenticationRequest(
                AuthenticationMode.DEVICE_CREDENTIAL_ONLY,
                BiometricsRetryPolicy.FAIL_IMMEDIATELY
            ),
            viewModel.beginAuthentication()
        )
    }

    @Test
    fun `success cancellation and failure become render state and allow another request`() {
        val outcomes = listOf(
            AuthenticationOutcome.Success(VerifiedAuthenticationMethod.UNKNOWN),
            AuthenticationOutcome.Cancelled,
            AuthenticationOutcome.Failure(AuthenticationFailureReason.SECURITY_UPDATE_REQUIRED)
        )
        val request = DemoAuthenticationRequest(
            AuthenticationMode.BIOMETRIC_ONLY,
            BiometricsRetryPolicy.FAIL_IMMEDIATELY
        )

        outcomes.forEach { outcome ->
            assertEquals(request, viewModel.beginAuthentication())
            viewModel.onAuthenticationResult(outcome)
            assertEquals(BiometricsDemoStatus.Completed(outcome), viewModel.state.value.status)
        }
    }
}
