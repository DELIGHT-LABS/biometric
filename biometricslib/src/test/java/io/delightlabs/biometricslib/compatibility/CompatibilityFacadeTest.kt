package io.delightlabs.biometricslib.compatibility

import io.delightlabs.biometricslib.AuthenticationAvailability
import io.delightlabs.biometricslib.AuthenticationFailureReason
import io.delightlabs.biometricslib.AuthenticationMethod
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.BiometricsAvailability
import io.delightlabs.biometricslib.BiometricsCallback
import io.delightlabs.biometricslib.BiometricsConfig
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import io.delightlabs.biometricslib.BiometricsType
import io.delightlabs.biometricslib.VerifiedAuthenticationMethod
import io.delightlabs.biometricslib.canonical
import io.delightlabs.biometricslib.compatibility
import io.delightlabs.biometricslib.defaultMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode as Mode
import io.delightlabs.biometricslib.authentication.policy.BiometricsRetryPolicy as RetryPolicy
import io.delightlabs.biometricslib.authentication.policy.BiometricsType as Type
import io.delightlabs.biometricslib.authentication.request.AuthenticationResult as Result
import io.delightlabs.biometricslib.authentication.result.AuthenticationFailureReason as FailureReason
import io.delightlabs.biometricslib.authentication.result.AuthenticationMethod as Method
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome as Outcome
import io.delightlabs.biometricslib.authentication.result.VerifiedAuthenticationMethod as VerifiedMethod
import io.delightlabs.biometricslib.availability.AuthenticationAvailability as Availability
import io.delightlabs.biometricslib.availability.BiometricsAvailability as LegacyAvailability

class CompatibilityFacadeTest {
    @Test
    fun `root and feature IDs share admission cancellation and buffered success policy`() {
        val owner = Any()
        val id = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 45L)
        assertEquals(45L, id.maxBufferedSuccessAgeMillis)
        assertEquals(id.maxBufferedSuccessAgeMillis, id.delegate.maxBufferedSuccessAgeMillis)
        assertTrue(id.delegate.tryClaim(owner))
        assertFalse(id.tryClaim(owner))
        assertTrue(id.cancelBeforeRegistration(owner))
        assertTrue(id.delegate.cancelledBeforeRegistration)
        assertFalse(id.delegate.cancelBeforeRegistration(owner))

        val reverse = AuthenticationRequestId.create()
        assertNull(reverse.delegate.maxBufferedSuccessAgeMillis)
        assertTrue(reverse.tryClaim(owner))
        assertTrue(reverse.delegate.cancelBeforeRegistration(owner))
        assertTrue(reverse.cancelledBeforeRegistration)
        assertFalse(reverse.tryClaim(owner))
        assertThrows(IllegalArgumentException::class.java) { AuthenticationRequestId.create(0L) }
    }

    @Test
    fun `result adaptation retains exact caller ID and rejects a different ID`() {
        val id = AuthenticationRequestId.create()
        val original = Result(id.delegate, Outcome.Success(VerifiedMethod.BIOMETRIC))

        val result = original.compatibility(id)

        assertSame(id, result.requestId)
        assertEquals(AuthenticationOutcome.Success(VerifiedAuthenticationMethod.BIOMETRIC), result.outcome)
        assertThrows(IllegalStateException::class.java) {
            original.compatibility(AuthenticationRequestId.create())
        }
    }

    @Test
    fun `outcome adaptation preserves methods cancellation and every failure payload`() {
        VerifiedMethod.entries.forEach { method ->
            val outcome = Outcome.Success(method).compatibility() as AuthenticationOutcome.Success
            assertEquals(method.name, outcome.method.name)
        }
        assertSame(AuthenticationOutcome.Cancelled, Outcome.Cancelled.compatibility())
        FailureReason.entries.forEach { reason ->
            val outcome = Outcome.Failure(reason, -77, "original diagnostic").compatibility()
                as AuthenticationOutcome.Failure
            assertEquals(reason.name, outcome.reason.name)
            assertEquals(-77, outcome.platformCode)
            assertEquals("original diagnostic", outcome.message)
        }
        assertEquals(AuthenticationFailureReason.entries.map { it.name }.toSet(), FailureReason.entries.map { it.name }.toSet())
    }

    @Test
    fun `configuration preserves custom text confirmation and each default mode`() {
        val expectedModes = mapOf(
            BiometricsType.BIOMETRIC_ONLY to AuthenticationMode.BIOMETRIC_ONLY,
            BiometricsType.CREDENTIAL_ONLY to AuthenticationMode.DEVICE_CREDENTIAL_ONLY,
            BiometricsType.BIOMETRIC_OR_CREDENTIAL to AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL
        )
        expectedModes.forEach { (type, mode) ->
            val original = BiometricsConfig("Title", "Details", "Stop", type, confirmationRequired = true)
            val config = original.canonical()
            assertEquals("Title", config.title)
            assertEquals("Details", config.description)
            assertEquals("Stop", config.negativeButtonText)
            assertTrue(config.confirmationRequired)
            assertEquals(type.name, config.biometricsType.name)
            assertEquals(mode, type.defaultMode())
        }
        assertEquals(BiometricsType.BIOMETRIC_OR_CREDENTIAL, BiometricsConfig().biometricsType)
    }

    @Test
    fun `policy and availability adapters preserve named cases across namespaces`() {
        AuthenticationMode.entries.forEach { assertEquals(it.name, it.canonical().name) }
        BiometricsRetryPolicy.entries.forEach { assertEquals(it.name, it.canonical().name) }
        BiometricsType.entries.forEach { assertEquals(it.name, it.canonical().name) }
        Availability.entries.forEach { assertEquals(it.name, it.compatibility().name) }
        LegacyAvailability.entries.forEach { assertEquals(it.name, it.compatibility().name) }
        assertEquals(AuthenticationMode.entries.map { it.name }.toSet(), Mode.entries.map { it.name }.toSet())
        assertEquals(BiometricsRetryPolicy.entries.map { it.name }.toSet(), RetryPolicy.entries.map { it.name }.toSet())
        assertEquals(BiometricsType.entries.map { it.name }.toSet(), Type.entries.map { it.name }.toSet())
        assertEquals(AuthenticationAvailability.entries.map { it.name }.toSet(), Availability.entries.map { it.name }.toSet())
        assertEquals(BiometricsAvailability.entries.map { it.name }.toSet(), LegacyAvailability.entries.map { it.name }.toSet())
    }

    @Test
    fun `typed callback invokes only typed override or the legacy default bridge once`() {
        val both = CallbackRecorder()
        both.canonical().onSuccess(Method.PIN)
        assertEquals(listOf(AuthenticationMethod.PIN), both.methods)
        assertEquals(0, both.parameterlessSuccesses)

        var legacySuccesses = 0
        val legacy = object : BiometricsCallback {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onSuccess() {
                legacySuccesses++
            }

            override fun onError(errorCode: Int, errorMessage: String) = Unit

            override fun onCancel() = Unit
        }
        legacy.canonical().onSuccess(Method.BIOMETRIC)
        assertEquals(1, legacySuccesses)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `parameterless success error and cancellation forward once with original data`() {
        val recorder = CallbackRecorder()
        val callback = recorder.canonical()

        callback.onSuccess()
        callback.onError(-81, "keep this message")
        callback.onCancel()

        assertEquals(1, recorder.parameterlessSuccesses)
        assertTrue(recorder.methods.isEmpty())
        assertEquals(listOf(-81 to "keep this message"), recorder.errors)
        assertEquals(1, recorder.cancellations)
    }

    private class CallbackRecorder : BiometricsCallback {
        var parameterlessSuccesses = 0
        val methods = mutableListOf<AuthenticationMethod>()
        val errors = mutableListOf<Pair<Int, String>>()
        var cancellations = 0

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onSuccess() {
            parameterlessSuccesses++
        }

        override fun onSuccess(authenticationMethod: AuthenticationMethod) {
            methods += authenticationMethod
        }

        override fun onError(errorCode: Int, errorMessage: String) {
            errors += errorCode to errorMessage
        }

        override fun onCancel() {
            cancellations++
        }
    }
}
