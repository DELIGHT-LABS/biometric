package io.delightlabs.biometricslib.internal.session

import io.delightlabs.biometricslib.authentication.policy.BiometricsRetryPolicy
import io.delightlabs.biometricslib.authentication.request.AuthenticationRequestId
import io.delightlabs.biometricslib.authentication.result.AuthenticationFailureReason
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome

/**
 * Separates the caller's completion from the lifetime of the AndroidX prompt.
 * The Activity prompt host serializes operations on the main thread.
 */
internal class AuthenticationSession(
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    enum class Delivery { LEGACY, RECONNECTABLE, COROUTINE }

    class Request(
        val authenticators: Int,
        val retryPolicy: BiometricsRetryPolicy,
        val delivery: Delivery,
        val requestId: AuthenticationRequestId?
    )

    private var activeState: ActiveRequest? = null
    val active: Request? get() = activeState?.request
    private var pendingResult: PendingResult? = null

    fun tryStart(
        authenticators: Int,
        retryPolicy: BiometricsRetryPolicy,
        delivery: Delivery,
        owner: Any?,
        requestId: AuthenticationRequestId? = null,
        onResult: (AuthenticationOutcome) -> Unit
    ): Request? {
        if (activeState != null) return null
        pendingResult = null
        val request = Request(authenticators, retryPolicy, delivery, requestId)
        activeState = ActiveRequest(request, owner, onResult)
        return request
    }

    fun canReconnect(requestId: AuthenticationRequestId?): Boolean {
        pendingResult?.let { return it.requestId == requestId }
        val current = activeState ?: return false
        return current.request.requestId == requestId &&
            current.request.delivery == Delivery.RECONNECTABLE && !current.completed
    }

    fun reconnect(owner: Any, onResult: (AuthenticationOutcome) -> Unit): Boolean =
        reconnect(null, owner, onResult)

    fun reconnect(
        requestId: AuthenticationRequestId?,
        owner: Any,
        onResult: (AuthenticationOutcome) -> Unit
    ): Boolean {
        if (!canReconnect(requestId)) return false
        pendingResult?.let { result ->
            pendingResult = null
            onResult(result.outcomeAt(elapsedRealtimeMillis()))
            return true
        }
        val current = checkNotNull(activeState)
        current.owner = owner
        current.onResult = onResult
        return true
    }

    fun detach(owner: Any) {
        val current = activeState ?: return
        if (current.request.delivery == Delivery.RECONNECTABLE && current.owner === owner) {
            current.onResult = null
            current.owner = null
        }
    }

    fun onMismatch(request: Request, cancelPrompt: () -> Unit) {
        val current = activeState ?: return
        if (current.request !== request || current.completed ||
            request.retryPolicy != BiometricsRetryPolicy.FAIL_IMMEDIATELY
        ) {
            return
        }

        val onResult = complete(current)
        val failure = AuthenticationOutcome.Failure(AuthenticationFailureReason.MISMATCH)
        if (onResult == null) pendingResult = buffer(request, failure)
        current.cancellationRequested = true
        try {
            cancelPrompt()
        } finally {
            onResult?.invoke(failure)
        }
    }

    /** Caller Job cancellation suppresses its outcome and drains a started platform prompt. */
    fun cancel(request: Request): Boolean {
        val current = activeState ?: return false
        if (current.request !== request || current.completed) return false
        complete(current)
        current.cancellationRequested = true
        return true
    }

    /** Explicit app cancellation completes an attached listener once with Cancelled. */
    fun cancelForCaller(
        request: Request,
        releaseBeforeLaunch: Boolean,
        cancelPlatform: () -> Unit
    ): Boolean {
        val current = activeState ?: return false
        if (current.request !== request || current.completed) return false
        val onResult = complete(current)
        current.cancellationRequested = true
        if (releaseBeforeLaunch) activeState = null
        try {
            cancelPlatform()
        } finally {
            onResult?.invoke(AuthenticationOutcome.Cancelled)
        }
        return true
    }

    fun discardResult(requestId: AuthenticationRequestId): Boolean {
        if (pendingResult?.requestId != requestId) return false
        pendingResult = null
        return true
    }

    fun isCompleted(request: Request): Boolean =
        activeState?.let { it.request !== request || it.completed } ?: true

    fun isCancellationRequested(request: Request): Boolean =
        activeState?.let { it.request === request && it.cancellationRequested } == true

    fun abandonBeforeLaunch(request: Request) {
        val current = activeState ?: return
        if (current.request !== request) return
        activeState = null
        complete(current)
    }

    fun finish(request: Request, result: AuthenticationOutcome) {
        val current = activeState ?: return
        if (current.request !== request) return
        activeState = null
        if (current.completed) return

        val onResult = complete(current)
        if (onResult != null) {
            onResult(result)
        } else if (request.delivery == Delivery.RECONNECTABLE) {
            pendingResult = buffer(request, result)
        }
    }

    fun clear() {
        pendingResult = null
        val current = activeState ?: return
        activeState = null
        val onResult = complete(current)
        if (current.request.delivery == Delivery.COROUTINE) {
            onResult?.invoke(AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE))
        }
    }

    private fun complete(current: ActiveRequest): ((AuthenticationOutcome) -> Unit)? {
        current.completed = true
        current.request.requestId?.markCompleted()
        val onResult = current.onResult
        current.onResult = null
        current.owner = null
        return onResult
    }

    private fun buffer(request: Request, result: AuthenticationOutcome): PendingResult =
        PendingResult(request.requestId, result, elapsedRealtimeMillis())

    private class ActiveRequest(
        val request: Request,
        var owner: Any?,
        var onResult: ((AuthenticationOutcome) -> Unit)?,
        var completed: Boolean = false,
        var cancellationRequested: Boolean = false
    )

    private data class PendingResult(
        val requestId: AuthenticationRequestId?,
        val outcome: AuthenticationOutcome,
        val completedAtMillis: Long
    ) {
        fun outcomeAt(nowMillis: Long): AuthenticationOutcome {
            val maxAge = requestId?.maxBufferedSuccessAgeMillis ?: return outcome
            if (outcome is AuthenticationOutcome.Success && nowMillis - completedAtMillis > maxAge) {
                return AuthenticationOutcome.Failure(
                    AuthenticationFailureReason.TIMEOUT,
                    message = "Authentication result expired."
                )
            }
            return outcome
        }
    }
}
