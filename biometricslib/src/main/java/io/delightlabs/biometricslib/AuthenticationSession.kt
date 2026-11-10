package io.delightlabs.biometricslib

/**
 * Tracks the platform request separately from the result delivered to its caller.
 * All operations are serialized on the main thread by the prompt host.
 */
internal class AuthenticationSession {
    internal class Request(
        internal var callback: BiometricsCallback?,
        internal val retryPolicy: BiometricsRetryPolicy
    )

    private var activeRequest: Request? = null

    fun tryStart(callback: BiometricsCallback, retryPolicy: BiometricsRetryPolicy): Request? {
        if (activeRequest != null) return null
        return Request(callback, retryPolicy).also { activeRequest = it }
    }

    fun onMismatch(request: Request, cancelPrompt: () -> Unit) {
        if (activeRequest !== request || request.retryPolicy != BiometricsRetryPolicy.FAIL_IMMEDIATELY) return
        val callback = request.callback ?: return
        // The caller is complete, but AndroidX still owns the prompt until its terminal event.
        request.callback = null
        try {
            cancelPrompt()
        } finally {
            callback.onError(BiometricsManager.ERROR_AUTHENTICATION_FAILED, "인증에 실패했습니다.")
        }
    }

    fun finish(request: Request, deliver: (BiometricsCallback) -> Unit) {
        val callback = request.callback
        if (abandon(request) && callback != null) deliver(callback)
    }

    fun abandon(request: Request): Boolean {
        if (activeRequest !== request) return false
        activeRequest = null
        request.callback = null
        return true
    }
}
