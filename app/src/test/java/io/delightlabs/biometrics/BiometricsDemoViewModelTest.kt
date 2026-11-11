package io.delightlabs.biometrics

import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationFailureReason
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.AuthenticationResult
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `authentication keeps its request ID with mode and retry setting and rejects duplicate actions`() {
        viewModel.selectMode(AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL)
        viewModel.setRetryEnabled(true)

        val request = checkNotNull(viewModel.beginAuthentication())

        assertEquals(AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL, request.mode)
        assertEquals(BiometricsRetryPolicy.ALLOW_RETRY, request.retryPolicy)
        assertEquals(request.requestId, viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)
        assertNull(viewModel.beginAuthentication())
        assertEquals(request.requestId, viewModel.activeRequestId)
    }

    @Test
    fun `disabled biometrics routes to app PIN without starting platform authentication`() {
        viewModel.setBiometricsEnabled(false)

        assertNull(viewModel.beginAuthentication())
        assertNull(viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.AppPinRequired, viewModel.state.value.status)
    }

    @Test
    fun `explicit device credential still works when app biometrics are disabled`() {
        viewModel.setBiometricsEnabled(false)
        viewModel.selectMode(AuthenticationMode.DEVICE_CREDENTIAL_ONLY)

        val request = checkNotNull(viewModel.beginAuthentication())

        assertEquals(AuthenticationMode.DEVICE_CREDENTIAL_ONLY, request.mode)
        assertEquals(BiometricsRetryPolicy.FAIL_IMMEDIATELY, request.retryPolicy)
        assertEquals(request.requestId, viewModel.activeRequestId)
    }

    @Test
    fun `success cancellation and failure become render state and allow a fresh request`() {
        val outcomes = listOf(
            AuthenticationOutcome.Success(VerifiedAuthenticationMethod.UNKNOWN),
            AuthenticationOutcome.Cancelled,
            AuthenticationOutcome.Failure(AuthenticationFailureReason.SECURITY_UPDATE_REQUIRED)
        )
        var previousRequestId: AuthenticationRequestId? = null

        outcomes.forEach { outcome ->
            val request = checkNotNull(viewModel.beginAuthentication())
            assertNotEquals(previousRequestId, request.requestId)
            viewModel.onAuthenticationResult(AuthenticationResult(request.requestId, outcome))
            assertNull(viewModel.activeRequestId)
            assertEquals(BiometricsDemoStatus.Completed(outcome), viewModel.state.value.status)
            previousRequestId = request.requestId
        }
    }

    @Test
    fun `another request cannot complete the current request`() {
        val request = checkNotNull(viewModel.beginAuthentication())
        val wrongResult = AuthenticationResult(
            AuthenticationRequestId.create(),
            AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC)
        )

        viewModel.onAuthenticationResult(wrongResult)

        assertEquals(request.requestId, viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)
    }

    @Test
    fun `a consumed result cannot complete the next request or overwrite its displayed result`() {
        val first = checkNotNull(viewModel.beginAuthentication())
        val firstResult = AuthenticationResult(
            first.requestId,
            AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC)
        )
        viewModel.onAuthenticationResult(firstResult)
        val second = checkNotNull(viewModel.beginAuthentication())

        viewModel.onAuthenticationResult(firstResult)
        assertEquals(second.requestId, viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)

        viewModel.onAuthenticationResult(AuthenticationResult(second.requestId, AuthenticationOutcome.Cancelled))
        viewModel.onAuthenticationResult(firstResult)
        assertNull(viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Completed(AuthenticationOutcome.Cancelled), viewModel.state.value.status)
    }

    @Test
    fun `abandoning an action clears its ID before a late result can affect a new action`() {
        val first = checkNotNull(viewModel.beginAuthentication())

        assertEquals(first.requestId, viewModel.abandonAuthentication())
        assertNull(viewModel.activeRequestId)
        assertNull(viewModel.abandonAuthentication())
        assertEquals(BiometricsDemoStatus.Completed(AuthenticationOutcome.Cancelled), viewModel.state.value.status)

        val next = checkNotNull(viewModel.beginAuthentication())
        viewModel.onAuthenticationResult(
            AuthenticationResult(first.requestId, AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC))
        )

        assertEquals(next.requestId, viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)
    }
}
