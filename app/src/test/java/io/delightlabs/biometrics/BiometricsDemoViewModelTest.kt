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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `availability report offers credential fallback without claiming biometric availability`() {
        val assessments = mapOf(
            AuthenticationMode.BIOMETRIC_ONLY to AuthenticationAvailability.NOT_ENROLLED,
            AuthenticationMode.DEVICE_CREDENTIAL_ONLY to AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
            AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL to AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE,
            AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL to AuthenticationAvailability.UNKNOWN
        )

        val report = viewModel.updateAvailabilityReport(assessments)

        assertEquals(AuthenticationMode.BIOMETRIC_ONLY, report.selectedMode)
        assertEquals(AuthenticationAvailability.NOT_ENROLLED, report.selectedAvailability)
        assertFalse(report.isSelectedAvailable)
        assertEquals(
            setOf(AuthenticationMode.DEVICE_CREDENTIAL_ONLY, AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL),
            report.usableModes
        )
        assertEquals(setOf(AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL), report.uncertainModes)
        assertEquals(report, viewModel.state.value.availabilityReport)
        assertEquals(AuthenticationAvailability.NOT_ENROLLED, viewModel.state.value.availability)
    }

    @Test
    fun `unknown assessments remain uncertain and refresh removes the old report`() {
        val assessments = AuthenticationMode.entries.associateWith { AuthenticationAvailability.UNKNOWN }.toMutableMap()
        val report = viewModel.updateAvailabilityReport(assessments)
        assessments[AuthenticationMode.BIOMETRIC_ONLY] = AuthenticationAvailability.BIOMETRIC_AVAILABLE

        assertEquals(AuthenticationAvailability.UNKNOWN, report.selectedAvailability)
        assertFalse(report.isSelectedAvailable)
        assertTrue(report.usableModes.isEmpty())
        assertEquals(AuthenticationMode.entries.toSet(), report.uncertainModes)

        viewModel.updateAvailability(AuthenticationMode.BIOMETRIC_ONLY, AuthenticationAvailability.BIOMETRIC_AVAILABLE)
        assertNull(viewModel.state.value.availabilityReport)
        assertEquals(AuthenticationAvailability.BIOMETRIC_AVAILABLE, viewModel.state.value.availability)
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

        assertFalse(viewModel.onAuthenticationResult(wrongResult))

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
        assertTrue(viewModel.onAuthenticationResult(firstResult))
        assertNull(viewModel.activeRequestId)
        assertFalse(viewModel.onAuthenticationResult(firstResult))
        val second = checkNotNull(viewModel.beginAuthentication())

        assertFalse(viewModel.onAuthenticationResult(firstResult))
        assertEquals(second.requestId, viewModel.activeRequestId)
        assertEquals(BiometricsDemoStatus.Authenticating, viewModel.state.value.status)

        assertTrue(viewModel.onAuthenticationResult(AuthenticationResult(second.requestId, AuthenticationOutcome.Cancelled)))
        assertFalse(viewModel.onAuthenticationResult(firstResult))
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
