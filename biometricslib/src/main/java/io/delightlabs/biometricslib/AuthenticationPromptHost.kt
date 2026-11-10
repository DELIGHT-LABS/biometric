package io.delightlabs.biometricslib

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

    fun start(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        authenticators: Int,
        retryPolicy: BiometricsRetryPolicy,
        delivery: AuthenticationSession.Delivery,
        onResult: (AuthenticationOutcome) -> Unit
    ): AuthenticationSession.Request? {
        val request = session.tryStart(authenticators, retryPolicy, delivery, activity.lifecycle, onResult)
        if (request == null) {
            onResult(AuthenticationOutcome.Failure(AuthenticationFailureReason.IN_PROGRESS))
            return null
        }

        try {
            bind(activity, request).prompt.authenticate(promptInfo)
        } catch (error: RuntimeException) {
            finish(request, AuthenticationOutcome.Failure(AuthenticationFailureReason.PLATFORM_ERROR, message = error.message))
            return null
        }
        return request
    }

    /** Rebinds AndroidX to the recreated Activity without starting the prompt again. */
    fun rebind(activity: FragmentActivity) {
        val request = session.active ?: return
        if (binding?.lifecycle === activity.lifecycle) return

        val prompt = bind(activity, request).prompt
        if (request.cancellationRequested) prompt.cancelAuthentication()
    }

    fun reconnect(activity: FragmentActivity, onResult: (AuthenticationOutcome) -> Unit): Boolean {
        rebind(activity)
        return session.reconnect(activity.lifecycle, onResult)
    }

    fun cancel(request: AuthenticationSession.Request) {
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
                executor.execute { finish(request, result) }
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
        val prompt = BiometricPrompt(activity, executor, callback)
        return Binding(lifecycle, observer, request, prompt).also {
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
        releaseBinding()
        session.clear()
    }

    private data class Binding(
        val lifecycle: Lifecycle,
        val observer: DefaultLifecycleObserver,
        val request: AuthenticationSession.Request,
        val prompt: BiometricPrompt
    )
}
