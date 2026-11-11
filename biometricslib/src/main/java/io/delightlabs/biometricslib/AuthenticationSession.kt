package io.delightlabs.biometricslib

/**
 * Separates the caller's completion from the lifetime of the AndroidX prompt.
 * The Activity prompt host serializes operations on the main thread.
 */
internal class AuthenticationSession {
    enum class Delivery { LEGACY, RECONNECTABLE, COROUTINE }

    class Request(
        val authenticators: Int,
        val retryPolicy: BiometricsRetryPolicy,
        val delivery: Delivery,
        val requestId: AuthenticationRequestId?,
        var owner: Any?,
        var onResult: ((AuthenticationOutcome) -> Unit)?
    ) {
        var completed = false
        var cancellationRequested = false
    }

    var active: Request? = null
        private set
    private var pendingResult: PendingResult? = null

    fun tryStart(
        authenticators: Int,
        retryPolicy: BiometricsRetryPolicy,
        delivery: Delivery,
        owner: Any?,
        requestId: AuthenticationRequestId? = null,
        onResult: (AuthenticationOutcome) -> Unit
    ): Request? {
        if (active != null) return null
        // Explicitly starting a new request supersedes any unclaimed result.
        pendingResult = null
        return Request(authenticators, retryPolicy, delivery, requestId, owner, onResult).also { active = it }
    }

    fun canReconnect(requestId: AuthenticationRequestId?): Boolean {
        pendingResult?.let { return it.requestId == requestId }
        val request = active ?: return false
        return request.requestId == requestId && request.delivery == Delivery.RECONNECTABLE && !request.completed
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
            onResult(result.outcome)
            return true
        }
        val request = checkNotNull(active)
        request.owner = owner
        request.onResult = onResult
        return true
    }

    fun detach(owner: Any) {
        val request = active ?: return
        if (request.delivery == Delivery.RECONNECTABLE && request.owner === owner) {
            request.onResult = null
            request.owner = null
        }
    }

    fun onMismatch(request: Request, cancelPrompt: () -> Unit) {
        if (active !== request || request.completed ||
            request.retryPolicy != BiometricsRetryPolicy.FAIL_IMMEDIATELY
        ) {
            return
        }

        val onResult = complete(request)
        val failure = AuthenticationOutcome.Failure(AuthenticationFailureReason.MISMATCH)
        if (onResult == null) pendingResult = PendingResult(request.requestId, failure)
        request.cancellationRequested = true
        try {
            cancelPrompt()
        } finally {
            onResult?.invoke(failure)
        }
    }

    fun cancel(request: Request): Boolean {
        if (active !== request || request.completed) return false
        complete(request)
        request.cancellationRequested = true
        return true
    }

    fun abandonBeforeLaunch(request: Request) {
        if (active !== request) return
        active = null
        complete(request)
    }

    fun finish(request: Request, result: AuthenticationOutcome) {
        if (active !== request) return
        active = null
        if (request.completed) return

        val onResult = complete(request)
        if (onResult != null) {
            onResult(result)
        } else if (request.delivery == Delivery.RECONNECTABLE) {
            pendingResult = PendingResult(request.requestId, result)
        }
    }

    fun clear() {
        pendingResult = null
        val request = active ?: return
        active = null
        val onResult = complete(request)
        // A coroutine may be waiting in a scope that outlives the Activity.
        if (request.delivery == Delivery.COROUTINE) {
            onResult?.invoke(AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE))
        }
    }

    private fun complete(request: Request): ((AuthenticationOutcome) -> Unit)? {
        request.completed = true
        val onResult = request.onResult
        request.onResult = null
        request.owner = null
        return onResult
    }

    private data class PendingResult(
        val requestId: AuthenticationRequestId?,
        val outcome: AuthenticationOutcome
    )
}
