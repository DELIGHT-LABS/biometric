package io.delightlabs.biometricslib

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import java.util.concurrent.Executor

/** Activity-scoped owner of the AndroidX prompt and its logical request. */
internal class AuthenticationPromptHost : ViewModel() {
    class PromptRequest(
        val promptInfo: BiometricPrompt.PromptInfo,
        val retryPolicy: BiometricsRetryPolicy,
        val delivery: AuthenticationSession.Delivery,
        val isCallerActive: () -> Boolean
    )

    private val session = AuthenticationSession()
    private var binding: ActivityPromptBinding? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launchQueue = AuthenticationLaunchQueue(
        session = session,
        restartDelayMillis = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) ANDROID_10_RESTART_DELAY_MILLIS else 0L,
        uptimeMillis = SystemClock::uptimeMillis,
        postDelayed = { task, delay -> mainHandler.postDelayed(task, delay) },
        removeCallbacks = mainHandler::removeCallbacks,
        onAbandoned = { request -> if (binding?.request === request) releaseActivityBinding() }
    )

    fun startRequest(
        activity: FragmentActivity,
        promptRequest: PromptRequest,
        onResult: (AuthenticationOutcome) -> Unit
    ): AuthenticationSession.Request? {
        val request = session.tryStart(
            authenticators = promptRequest.promptInfo.allowedAuthenticators,
            retryPolicy = promptRequest.retryPolicy,
            delivery = promptRequest.delivery,
            owner = activity.lifecycle,
            onResult = onResult
        )
        if (request == null) {
            onResult(AuthenticationOutcome.Failure(AuthenticationFailureReason.IN_PROGRESS))
            return null
        }

        try {
            bindPromptToActivity(activity, request)
        } catch (error: RuntimeException) {
            failPlatformRequest(request, error)
            return null
        }
        launchQueue.launch(request, promptRequest.isCallerActive) {
            launchPlatformPrompt(request, promptRequest.promptInfo)
        }
        return request.takeIf { session.active === it }
    }

    /** Transfers the request to the recreated Activity without restarting an open prompt. */
    fun rebindToActivity(activity: FragmentActivity) {
        val request = session.active ?: return
        if (binding?.activity === activity) return

        val current = bindPromptToActivity(activity, request)
        if (launchQueue.isPending(request)) return

        val prompt = current.prompt
        if (request.cancellationRequested) prompt.cancelAuthentication()
    }

    fun reconnectListener(activity: FragmentActivity, onResult: (AuthenticationOutcome) -> Unit): Boolean {
        rebindToActivity(activity)
        return session.reconnect(activity.lifecycle, onResult)
    }

    fun cancelRequest(request: AuthenticationSession.Request) {
        if (launchQueue.cancelPending(request)) return
        if (session.cancel(request)) cancelPlatformPrompt(request)
    }

    private fun launchPlatformPrompt(
        request: AuthenticationSession.Request,
        promptInfo: BiometricPrompt.PromptInfo
    ) {
        val current = binding
        if (current?.request !== request || !current.activity.canHostAuthenticationPrompt()) {
            finishRequest(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE))
            return
        }

        try {
            current.prompt.authenticate(promptInfo)
        } catch (error: RuntimeException) {
            failPlatformRequest(request, error)
        }
    }

    private fun bindPromptToActivity(
        activity: FragmentActivity,
        request: AuthenticationSession.Request
    ): ActivityPromptBinding {
        detachFromActivity()
        return ActivityPromptBinding(activity, request).also {
            binding = it
            it.lifecycle.addObserver(it)
        }
    }

    private fun createPlatformPrompt(
        activity: FragmentActivity,
        request: AuthenticationSession.Request
    ): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(activity.applicationContext)
        return BiometricPrompt(activity, executor, PlatformPromptCallback(request, executor))
    }

    private fun cancelPlatformPrompt(request: AuthenticationSession.Request) {
        val current = binding
        if (current?.request === request) current.prompt.cancelAuthentication()
    }

    private fun finishPlatformRequest(request: AuthenticationSession.Request, result: AuthenticationOutcome) {
        if (session.active !== request) return
        launchQueue.recordPlatformCompletion()
        finishRequest(request, result)
    }

    private fun failPlatformRequest(request: AuthenticationSession.Request, error: RuntimeException) {
        finishRequest(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.PLATFORM_ERROR, message = error.message))
    }

    private fun finishRequest(request: AuthenticationSession.Request, result: AuthenticationOutcome) {
        if (session.active !== request) return
        releaseActivityBinding()
        session.finish(request, result)
    }

    private fun detachFromActivity() {
        val current = binding ?: return
        session.detach(current.lifecycle)
        releaseActivityBinding()
    }

    private fun releaseActivityBinding() {
        val current = binding ?: return
        binding = null
        current.lifecycle.removeObserver(current)
    }

    override fun onCleared() {
        launchQueue.clear()
        releaseActivityBinding()
        session.clear()
    }

    private inner class ActivityPromptBinding(
        val activity: FragmentActivity,
        val request: AuthenticationSession.Request
    ) : DefaultLifecycleObserver {
        val lifecycle: Lifecycle = activity.lifecycle
        val prompt: BiometricPrompt by lazy(LazyThreadSafetyMode.NONE) {
            createPlatformPrompt(activity, request)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            if (binding === this) detachFromActivity()
        }
    }

    private inner class PlatformPromptCallback(
        private val request: AuthenticationSession.Request,
        private val executor: Executor
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            postTerminalResult(platformOutcome(errorCode, errString.toString()))
        }

        override fun onAuthenticationFailed() {
            session.onMismatch(request) { cancelPlatformPrompt(request) }
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val method = BiometricsPolicy.resolveAuthenticationMethod(result.authenticationType, request.authenticators)
            postTerminalResult(AuthenticationOutcome.Success(method))
        }

        private fun postTerminalResult(result: AuthenticationOutcome) {
            // AndroidX queues removal of the previous Fragment after posting its callback.
            executor.execute { finishPlatformRequest(request, result) }
        }
    }

    private companion object {
        // AndroidX Biometric 1.1.0 delays restarts on some Android 10 models. Wait on our
        // own main queue so a pending request can be cancelled before AndroidX sees it.
        const val ANDROID_10_RESTART_DELAY_MILLIS = 600L
    }
}

internal fun FragmentActivity.canHostAuthenticationPrompt(): Boolean =
    !isFinishing && !isDestroyed && application != null &&
        lifecycle.currentState != Lifecycle.State.DESTROYED && !supportFragmentManager.isStateSaved
