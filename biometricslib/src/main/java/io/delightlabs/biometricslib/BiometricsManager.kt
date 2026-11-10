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
                manager.promptHost.rebind(activity)
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
            start(BiometricsPolicy.modeFor(biometricsType), retryPolicy, Delivery.LEGACY) {
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
        onMain { start(mode, retryPolicy, Delivery.RECONNECTABLE, onResult = onResult) }
    }

    /**
     * Replaces the current Activity's typed listener or consumes its buffered result once.
     * Returns false if there is no reconnectable request. Never starts authentication.
     */
    @MainThread
    fun reconnectAuthentication(onResult: (AuthenticationOutcome) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return activity.canHostAuthenticationPrompt() && promptHost.reconnect(activity, onResult)
    }

    /**
     * Waits for one authentication. Cancelling the calling coroutine cancels its request and
     * propagates cancellation. A user dismissing the prompt returns [AuthenticationOutcome.Cancelled].
     */
    suspend fun awaitAuthentication(
        mode: AuthenticationMode = BiometricsPolicy.modeFor(config.biometricsType),
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY
    ): AuthenticationOutcome = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val request = start(
                mode,
                retryPolicy,
                Delivery.COROUTINE,
                isCallerActive = { continuation.isActive }
            ) { continuation.resume(it) }
            if (request != null) {
                val host = promptHost
                val executor = mainExecutor
                continuation.invokeOnCancellation { executor.execute { host.cancel(request) } }
            }
        }
    }

    private fun start(
        mode: AuthenticationMode,
        retryPolicy: BiometricsRetryPolicy,
        delivery: Delivery,
        isCallerActive: () -> Boolean = { true },
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
        return promptHost.start(activity, promptInfo, plan.authenticators, retryPolicy, delivery, isCallerActive, onResult)
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
