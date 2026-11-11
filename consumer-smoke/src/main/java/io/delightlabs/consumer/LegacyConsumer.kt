package io.delightlabs.consumer

import androidx.fragment.app.FragmentActivity
import io.delightlabs.biometricslib.AuthenticationMode
import io.delightlabs.biometricslib.AuthenticationRequestId
import io.delightlabs.biometricslib.AuthenticationResult
import io.delightlabs.biometricslib.BiometricsCallback
import io.delightlabs.biometricslib.BiometricsManager
import io.delightlabs.biometricslib.BiometricsRetryPolicy
import io.delightlabs.biometricslib.BiometricsType

internal fun authenticateLegacy(activity: FragmentActivity, callback: BiometricsCallback) {
    val manager = BiometricsManager.create(activity)
    manager.authenticate(callback)
    manager.authenticate(callback, BiometricsType.BIOMETRIC_ONLY)
    manager.authenticate(callback, BiometricsRetryPolicy.ALLOW_RETRY)
    manager.authenticate(callback, BiometricsType.BIOMETRIC_ONLY, BiometricsRetryPolicy.ALLOW_RETRY)
}

internal suspend fun awaitLegacy(activity: FragmentActivity): AuthenticationResult =
    BiometricsManager.create(activity).awaitAuthentication(
        AuthenticationRequestId.create(),
        AuthenticationMode.BIOMETRIC_ONLY
    )
