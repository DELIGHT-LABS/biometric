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

/** Activity-scoped owner of the AndroidX prompt and its logical request. */
internal class AuthenticationPromptHost : ViewModel() {
    private val session = AuthenticationSession()
    private var binding: Binding? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launchQueue = AuthenticationLaunchQueue(
        session = session,
        restartDelayMillis = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) ANDROID_10_RESTART_DELAY_MILLIS else 0L,
        uptimeMillis = SystemClock::uptimeMillis,
        postDelayed = { task, delay -> mainHandler.postDelayed(task, delay) },
        removeCallbacks = mainHandler::removeCallbacks,
        onAbandoned = { request -> if (binding?.request === request) releaseBinding() }
    )

    fun start(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        authenticators: Int,
        retryPolicy: BiometricsRetryPolicy,
        delivery: AuthenticationSession.Delivery,
        isCallerActive: () -> Boolean,
        onResult: (AuthenticationOutcome) -> Unit
    ): AuthenticationSession.Request? {
        val request = session.tryStart(authenticators, retryPolicy, delivery, activity.lifecycle, onResult)
        if (request == null) {
            onResult(AuthenticationOutcome.Failure(AuthenticationFailureReason.IN_PROGRESS))
            return null
        }

        try {
            bind(activity, request)
        } catch (error: RuntimeException) {
            finish(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.PLATFORM_ERROR, message = error.message))
            return null
        }
        launchQueue.launch(request, isCallerActive) { launchPlatformPrompt(request, promptInfo) }
        return request.takeIf { session.active === it }
    }

    private fun launchPlatformPrompt(
        request: AuthenticationSession.Request,
        promptInfo: BiometricPrompt.PromptInfo
    ) {
        val current = binding
        if (current?.request !== request || !current.activity.canHostAuthenticationPrompt()) {
            finish(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE))
            return
        }

        try {
            current.prompt.authenticate(promptInfo)
        } catch (error: RuntimeException) {
            finish(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.PLATFORM_ERROR, message = error.message))
        }
    }

    /** Rebinds AndroidX to the recreated Activity without starting the prompt again. */
    fun rebind(activity: FragmentActivity) {
        val request = session.active ?: return
        if (binding?.lifecycle === activity.lifecycle) return

        val current = bind(activity, request)
        if (launchQueue.isPending(request)) return

        val prompt = current.prompt
        if (request.cancellationRequested) prompt.cancelAuthentication()
    }

    fun reconnect(activity: FragmentActivity, onResult: (AuthenticationOutcome) -> Unit): Boolean {
        rebind(activity)
        return session.reconnect(activity.lifecycle, onResult)
    }

    fun cancel(request: AuthenticationSession.Request) {
        if (launchQueue.cancelPending(request)) return
        if (session.cancel(request)) cancelPrompt(request)
    }

    private fun bind(activity: FragmentActivity, request: AuthenticationSession.Request): Binding {
        val lifecycle = activity.lifecycle
        val executor = ContextCompat.getMainExecutor(activity.applicationContext)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                postResult(platformOutcome(errorCode, errString.toString()))
            }

            override fun onAuthenticationFailed() {
                session.onMismatch(request) { cancelPrompt(request) }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val method = BiometricsPolicy.resolveAuthenticationMethod(result.authenticationType, request.authenticators)
                postResult(AuthenticationOutcome.Success(method))
            }

            private fun postResult(result: AuthenticationOutcome) {
                // AndroidX queues removal of the previous Fragment after posting its callback.
                executor.execute {
                    if (session.active === request) {
                        launchQueue.recordPlatformCompletion()
                        finish(request, result)
                    }
                }
            }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                session.detach(lifecycle)
                if (binding?.lifecycle === lifecycle) releaseBinding()
            }
        }

        binding?.let { session.detach(it.lifecycle) }
        releaseBinding()
        return Binding(activity, lifecycle, observer, request) {
            BiometricPrompt(activity, executor, callback)
        }.also {
            binding = it
            lifecycle.addObserver(observer)
        }
    }

    private fun cancelPrompt(request: AuthenticationSession.Request) {
        val current = binding
        if (current?.request === request) current.prompt.cancelAuthentication()
    }

    private fun finish(request: AuthenticationSession.Request, result: AuthenticationOutcome) {
        if (session.active !== request) return
        releaseBinding()
        session.finish(request, result)
    }

    private fun releaseBinding() {
        val current = binding ?: return
        binding = null
        current.lifecycle.removeObserver(current.observer)
    }

    override fun onCleared() {
        launchQueue.clear()
        releaseBinding()
        session.clear()
    }

    private class Binding(
        val activity: FragmentActivity,
        val lifecycle: Lifecycle,
        val observer: DefaultLifecycleObserver,
        val request: AuthenticationSession.Request,
        createPrompt: () -> BiometricPrompt
    ) {
        val prompt: BiometricPrompt by lazy(LazyThreadSafetyMode.NONE, createPrompt)
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
