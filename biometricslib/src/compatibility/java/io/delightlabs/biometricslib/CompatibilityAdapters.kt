package io.delightlabs.biometricslib

import io.delightlabs.biometricslib.authentication.BiometricsCallback as Callback
import io.delightlabs.biometricslib.authentication.BiometricsConfig as Config
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode as Mode
import io.delightlabs.biometricslib.authentication.policy.BiometricsRetryPolicy as RetryPolicy
import io.delightlabs.biometricslib.authentication.policy.BiometricsType as Type
import io.delightlabs.biometricslib.authentication.request.AuthenticationResult as Result
import io.delightlabs.biometricslib.authentication.result.AuthenticationMethod as Method
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome as Outcome
import io.delightlabs.biometricslib.availability.AuthenticationAvailability as Availability
import io.delightlabs.biometricslib.availability.BiometricsAvailability as LegacyAvailability

internal fun AuthenticationMode.canonical(): Mode = Mode.valueOf(name)

internal fun BiometricsRetryPolicy.canonical(): RetryPolicy = RetryPolicy.valueOf(name)

internal fun BiometricsType.canonical(): Type = Type.valueOf(name)

internal fun Availability.compatibility(): AuthenticationAvailability = AuthenticationAvailability.valueOf(name)

internal fun LegacyAvailability.compatibility(): BiometricsAvailability = BiometricsAvailability.valueOf(name)

internal fun BiometricsConfig.canonical(): Config = Config(
    title = title,
    description = description,
    negativeButtonText = negativeButtonText,
    biometricsType = biometricsType.canonical(),
    confirmationRequired = confirmationRequired
)

internal fun BiometricsType.defaultMode(): AuthenticationMode = when (this) {
    BiometricsType.BIOMETRIC_ONLY -> AuthenticationMode.BIOMETRIC_ONLY
    BiometricsType.CREDENTIAL_ONLY -> AuthenticationMode.DEVICE_CREDENTIAL_ONLY
    BiometricsType.BIOMETRIC_OR_CREDENTIAL -> AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL
}

internal fun Outcome.compatibility(): AuthenticationOutcome = when (this) {
    is Outcome.Success -> AuthenticationOutcome.Success(VerifiedAuthenticationMethod.valueOf(method.name))
    Outcome.Cancelled -> AuthenticationOutcome.Cancelled
    is Outcome.Failure -> AuthenticationOutcome.Failure(
        reason = AuthenticationFailureReason.valueOf(reason.name),
        platformCode = platformCode,
        message = message
    )
}

internal fun Result.compatibility(requestId: AuthenticationRequestId): AuthenticationResult {
    check(this.requestId == requestId.delegate) { "Authentication result belongs to another request." }
    return AuthenticationResult(requestId, outcome.compatibility())
}

internal fun BiometricsCallback.canonical(): Callback {
    val original = this
    return object : Callback {
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onSuccess() {
            original.onSuccess()
        }

        override fun onSuccess(authenticationMethod: Method) {
            original.onSuccess(AuthenticationMethod.valueOf(authenticationMethod.name))
        }

        override fun onError(errorCode: Int, errorMessage: String) {
            original.onError(errorCode, errorMessage)
        }

        override fun onCancel() {
            original.onCancel()
        }
    }
}
