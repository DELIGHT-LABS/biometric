package io.delightlabs.consumer

import androidx.fragment.app.FragmentActivity
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationOutcome
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.AuthenticationResult
import io.delightlabs.biometricslib.BiometricsCallback
import io.delightlabs.biometricslib.BiometricsManager
import io.delightlabs.biometricslib.BiometricsRetryPolicy

internal fun authenticate(activity: FragmentActivity, callback: BiometricsCallback) {
    BiometricsManager.create(activity).authenticate(callback, BiometricsRetryPolicy.ALLOW_RETRY)
}

internal suspend fun awaitAuthentication(activity: FragmentActivity): AuthenticationOutcome =
    BiometricsManager.create(activity).awaitAuthentication(AuthenticationMode.BIOMETRIC_ONLY)

internal suspend fun awaitScopedAuthentication(activity: FragmentActivity): AuthenticationResult =
    BiometricsManager.create(activity).awaitAuthentication(
        AuthenticationRequestId.create(),
        AuthenticationMode.BIOMETRIC_ONLY
    )

internal fun cancelScopedAuthentication(activity: FragmentActivity, requestId: AuthenticationRequestId): Boolean =
    BiometricsManager.create(activity).cancelAuthentication(requestId)

internal fun createExpiringRequest(): AuthenticationRequestId =
    AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 30_000L)
