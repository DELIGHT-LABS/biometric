package io.delightlabs.biometricslib

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import io.delightlabs.biometricslib.AuthenticationSession.Delivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Provides one AndroidX authentication prompt per Activity. */
class BiometricsManager private constructor(
    private val activity: FragmentActivity,
    private val config: BiometricsConfig
) {
    private val appContext = activity.applicationContext
    private val biometricManager = BiometricManager.from(appContext)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val promptHost: AuthenticationPromptHost
        get() = ViewModelProvider(activity)[AuthenticationPromptHost::class.java]

    companion object {
        const val ERROR_NO_SECURITY = -100
        const val ERROR_HARDWARE_NOT_SUPPORTED = -101
        const val ERROR_TEMPORARILY_LOCKED = -102
        const val ERROR_PERMANENTLY_LOCKED = -103
        const val ERROR_AUTHENTICATION_FAILED = -104
        const val ERROR_AUTHENTICATION_IN_PROGRESS = -105
        const val ERROR_INVALID_CONFIGURATION = -106

        /**
         * Create for the current Activity. Call from its main-thread onCreate to rebind
         * an existing platform prompt after a configuration change.
         */
        fun create(
            activity: FragmentActivity,
            config: BiometricsConfig = BiometricsConfig.localized(activity)
        ): BiometricsManager = BiometricsManager(activity, config).also { manager ->
            if (Looper.myLooper() == Looper.getMainLooper() && activity.canHostAuthenticationPrompt()) {
                manager.promptHost.rebindToActivity(activity)
            }
        }
    }

    /** Starts authentication using the configured type and the default retry policy. */
    fun authenticate(
        callback: BiometricsCallback,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        authenticate(
            callback = callback,
            retryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    /** Starts authentication using an explicit type and the default retry policy. */
    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        authenticate(
            callback = callback,
            biometricsType = biometricsType,
            retryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
            isBiometricEnabled = isBiometricEnabled,
            onBiometricDisabled = onBiometricDisabled
        )
    }

    /** Starts authentication using the configured legacy type and retry policy. */
    fun authenticate(
        callback: BiometricsCallback,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        authenticate(callback, config.biometricsType, retryPolicy, isBiometricEnabled, onBiometricDisabled)
    }

    /**
     * Legacy callback entry point. When [isBiometricEnabled] is false, only
     * [onBiometricDisabled] is invoked, on the calling thread, regardless of the selected type.
     * Otherwise, prompt operations and results are dispatched on the main thread.
     */
    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        if (!isBiometricEnabled) {
            onBiometricDisabled?.invoke()
            return
        }
        val context = appContext
        onMain {
            startAuthenticationRequest(BiometricsPolicy.modeFor(biometricsType), retryPolicy, Delivery.LEGACY) {
                it.deliverLegacy(context, callback)
            }
        }
    }

    /**
     * Starts a single authentication from a user action. The result is delivered on the main
     * thread. Call [reconnectAuthentication] on the recreated Activity to replace its listener.
     */
    fun startAuthentication(
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        onResult: (AuthenticationOutcome) -> Unit
    ) {
        onMain { startAuthenticationRequest(mode, retryPolicy, Delivery.RECONNECTABLE, onResult = onResult) }
    }

    /**
     * Starts one attempt identified by [requestId]. Retain that ID alongside the protected action
     * in a ViewModel and supply it to [reconnectAuthentication] after Activity recreation.
     *
     * Returns false for an already used ID and invokes no callback for that duplicate call.
     * Returns true when the ID is accepted for processing, including preflight or busy failures.
     * A retry uses a fresh ID. Results are delivered on the main thread.
     */
    fun startAuthentication(
        requestId: AuthenticationRequestId,
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        onResult: (AuthenticationResult) -> Unit
    ): Boolean {
        if (!requestId.tryClaim()) return false
        onMain {
            startAuthenticationRequest(mode, retryPolicy, Delivery.RECONNECTABLE, requestId = requestId) {
                onResult(AuthenticationResult(requestId, it))
            }
        }
        return true
    }

    /**
     * Reconnects an existing ID-free request or consumes its buffered result once.
     * Requests started with an ID require the ID overload. Returns false if there is no
     * matching reconnectable request.
     */
    @MainThread
    fun reconnectAuthentication(onResult: (AuthenticationOutcome) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return activity.canHostAuthenticationPrompt() && promptHost.reconnectListener(activity, onResult)
    }

    /**
     * Reconnects only the active or buffered result with [requestId].
     * A different ID returns false without changing the existing request or consuming its result.
     * A buffered result is consumed before invoking [onResult], so a second reconnect returns false.
     */
    @MainThread
    fun reconnectAuthentication(
        requestId: AuthenticationRequestId,
        onResult: (AuthenticationResult) -> Unit
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return activity.canHostAuthenticationPrompt() && promptHost.reconnectListener(activity, requestId) {
            onResult(AuthenticationResult(requestId, it))
        }
    }

    /**
     * Waits for one authentication. Cancelling the calling coroutine cancels its request and
     * propagates cancellation. A user dismissing the prompt returns [AuthenticationOutcome.Cancelled].
     */
    suspend fun awaitAuthentication(
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY
    ): AuthenticationOutcome = awaitAuthenticationOutcome(mode, retryPolicy)

    /**
     * Waits for one outcome tied to [requestId]. Each ID can start only one attempt, including
     * failed or cancelled attempts. Reusing an ID throws IllegalStateException.
     */
    suspend fun awaitAuthentication(
        requestId: AuthenticationRequestId,
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY
    ): AuthenticationResult =
        AuthenticationResult(requestId, awaitAuthenticationOutcome(mode, retryPolicy, requestId))

    private suspend fun awaitAuthenticationOutcome(
        mode: AuthenticationMode,
        retryPolicy: BiometricsRetryPolicy,
        requestId: AuthenticationRequestId? = null
    ): AuthenticationOutcome = withContext(Dispatchers.Main.immediate) {
        check(requestId == null || requestId.tryClaim()) { "Authentication request ID has already been used." }
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val request = startAuthenticationRequest(
                mode,
                retryPolicy,
                Delivery.COROUTINE,
                isCallerActive = { continuation.isActive },
                requestId = requestId
            ) { continuation.resume(it) }
            if (request != null) {
                val host = promptHost
                val executor = mainExecutor
                continuation.invokeOnCancellation { executor.execute { host.cancelRequest(request) } }
            }
        }
    }

    private fun startAuthenticationRequest(
        mode: AuthenticationMode,
        retryPolicy: BiometricsRetryPolicy,
        delivery: Delivery,
        isCallerActive: () -> Boolean = { true },
        requestId: AuthenticationRequestId? = null,
        onResult: (AuthenticationOutcome) -> Unit
    ): AuthenticationSession.Request? {
        if (!activity.canHostAuthenticationPrompt()) {
            onResult(AuthenticationOutcome.Failure(AuthenticationFailureReason.HOST_UNAVAILABLE))
            return null
        }

        val plan = BiometricsPolicy.plan(mode)
        assessAvailability(plan).failureReason()?.let { reason ->
            onResult(AuthenticationOutcome.Failure(reason))
            return null
        }

        val promptInfo = try {
            buildPromptInfo(plan)
        } catch (error: IllegalArgumentException) {
            onResult(AuthenticationOutcome.Failure(AuthenticationFailureReason.INVALID_CONFIGURATION, message = error.message))
            return null
        }
        val promptRequest = AuthenticationPromptHost.PromptRequest(promptInfo, retryPolicy, delivery, isCallerActive, requestId)
        return promptHost.startRequest(activity, promptRequest, onResult)
    }

    private fun buildPromptInfo(plan: AuthenticatorPlan): BiometricPrompt.PromptInfo {
        require(config.title.isNotBlank()) { "Authentication title must not be blank." }
        require(plan.allowsDeviceCredential || config.negativeButtonText.isNotBlank()) {
            "Biometric-only authentication requires a cancel button label."
        }
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(config.title)
            .setDescription(config.description)
            .setAllowedAuthenticators(plan.authenticators)
            .setConfirmationRequired(config.confirmationRequired)
            .apply {
                if (!plan.allowsDeviceCredential) setNegativeButtonText(config.negativeButtonText)
            }
            .build()
    }

    /** Checks availability using the configured legacy mode. */
    fun checkAvailability(): BiometricsAvailability = checkAvailability(config.biometricsType)

    /** Coarse availability for existing callers. Prefer [checkAuthenticationAvailability]. */
    fun checkAvailability(biometricsType: BiometricsType): BiometricsAvailability =
        BiometricsPolicy.legacyAvailability(checkAuthenticationAvailability(BiometricsPolicy.modeFor(biometricsType)))

    /** Checks the same authenticator plan that [startAuthentication] would use. */
    fun checkAuthenticationAvailability(
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType)
    ): AuthenticationAvailability = assessAvailability(BiometricsPolicy.plan(mode))

    private fun assessAvailability(plan: AuthenticatorPlan): AuthenticationAvailability {
        val sdkInt = Build.VERSION.SDK_INT
        val biometricStatus = if (plan.isSupported(sdkInt)) {
            plan.biometricAuthenticators?.let { biometricManager.canAuthenticate(it) }
        } else {
            null
        }
        return BiometricsPolicy.assessAvailability(
            plan,
            sdkInt,
            plan.allowsDeviceCredential && keyguardManager?.isDeviceSecure == true,
            biometricStatus
        )
    }

    /** Opens security settings if the device offers the screen. */
    fun openSecuritySettings() {
        tryOpenSecuritySettings()
    }

    /** Returns false if the device cannot open a security settings screen. */
    @MainThread
    fun tryOpenSecuritySettings(): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainExecutor.execute(action)
    }
}
