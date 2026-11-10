package io.delightlabs.biometricslib

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.Executor

/**
 * Main class for biometric authentication / 생체인증을 위한 메인 클래스
 *
 * This class provides a simple interface for implementing biometric authentication
 * in Android applications with fallback to device credentials.
 * * Android 애플리케이션에서 생체인증을 구현하기 위한 간단한 인터페이스를 제공하며,
 * 기기 자격 증명으로의 대체 기능을 포함합니다.
 */
class BiometricsManager private constructor(
    private val activity: FragmentActivity,
    private val config: BiometricsConfig
) {
    private val biometricManager = BiometricManager.from(activity)
    private val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    companion object {
        // Custom error codes / 사용자 정의 에러 코드
        const val ERROR_NO_SECURITY = -100
        const val ERROR_HARDWARE_NOT_SUPPORTED = -101
        const val ERROR_TEMPORARILY_LOCKED = -102
        const val ERROR_PERMANENTLY_LOCKED = -103
        const val ERROR_AUTHENTICATION_FAILED = -104
        const val ERROR_AUTHENTICATION_IN_PROGRESS = -105

        /**
         * Creates a new BiometricsManager instance / 새로운 BiometricsManager 인스턴스를 생성합니다
         * * @param activity FragmentActivity instance / FragmentActivity 인스턴스
         * @param config Authentication configuration / 인증 설정
         * @return BiometricsManager instance / BiometricsManager 인스턴스
         */
        fun create(activity: FragmentActivity, config: BiometricsConfig = BiometricsConfig()): BiometricsManager {
            return BiometricsManager(activity, config)
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

    /**
     * Starts biometric authentication / 생체인증을 시작합니다
     *
     * Checks device availability first and shows appropriate authentication method
     * based on the configuration. Results are returned through the callback.
     *
     * 먼저 기기 가용성을 확인하고 설정에 따라 적절한 인증 방법을 표시합니다.
     * 결과는 콜백을 통해 반환됩니다.
     *
     * @param callback Callback to receive authentication results / 인증 결과를 받을 콜백
     * @param retryPolicy How to handle an unrecognized biometric attempt
     * @param isBiometricEnabled Whether biometric is enabled in app / 앱에서 생체인증이 활성화되었는지 여부
     * @param onBiometricDisabled Called when biometric is disabled / 생체인증이 비활성화된 경우 호출
     */
    fun authenticate(
        callback: BiometricsCallback,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        // Delegate to the overloaded method with the configured biometric type
        // 설정된 생체인증 타입으로 오버로드된 메서드에 위임
        authenticate(callback, config.biometricsType, retryPolicy, isBiometricEnabled, onBiometricDisabled)
    }

    /**
     * Starts biometric authentication with specific biometric type / 특정 생체인증 타입으로 생체인증을 시작합니다
     *
     * Checks device availability first and shows appropriate authentication method
     * based on the provided biometric type. Results are returned through the callback.
     * This overrides the biometric type configured in BiometricsConfig for this specific call.
     *
     * 먼저 기기 가용성을 확인하고 제공된 생체인증 타입에 따라 적절한 인증 방법을 표시합니다.
     * 결과는 콜백을 통해 반환됩니다. 이 호출에서만 BiometricsConfig에 설정된 생체인증 타입을 오버라이드합니다.
     *
     * @param callback Callback to receive authentication results / 인증 결과를 받을 콜백
     * @param biometricsType Type of authentication to use (overrides config) / 사용할 인증 타입 (설정 오버라이드)
     * @param retryPolicy How to handle an unrecognized biometric attempt
     * @param isBiometricEnabled Whether biometric is enabled in app / 앱에서 생체인증이 활성화되었는지 여부
     * @param onBiometricDisabled Called when biometric is disabled / 생체인증이 비활성화된 경우 호출
     */
    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        retryPolicy: BiometricsRetryPolicy = BiometricsRetryPolicy.FAIL_IMMEDIATELY,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        // If biometric is disabled in app settings, call the disabled callback
        // 앱 설정에서 생체인증이 비활성화된 경우, 비활성화 콜백을 호출
        if (!isBiometricEnabled) {
            onBiometricDisabled?.invoke()
            return
        }
        val availability = checkAvailability(biometricsType)

        when (availability) {
            BiometricsAvailability.AUTHENTICATOR_UNSUPPORTED -> {
                callback.onError(
                    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
                    "요청한 인증 방식은 현재 Android 버전에서 지원되지 않습니다."
                )
                return
            }
            BiometricsAvailability.NO_SECURITY_CONFIGURED -> {
                callback.onError(ERROR_NO_SECURITY, "기기 보안이 설정되어 있지 않습니다.")
                return
            }
            BiometricsAvailability.HARDWARE_NOT_SUPPORTED -> {
                callback.onError(ERROR_HARDWARE_NOT_SUPPORTED, "생체인증을 지원하지 않는 기기입니다.")
                return
            }
            BiometricsAvailability.TEMPORARILY_LOCKED -> {
                callback.onError(ERROR_TEMPORARILY_LOCKED, "너무 많은 시도로 일시적으로 잠겼습니다.")
                return
            }
            BiometricsAvailability.PERMANENTLY_LOCKED -> {
                callback.onError(ERROR_PERMANENTLY_LOCKED, "생체인증이 영구적으로 잠겼습니다. 설정에서 해제하세요.")
                return
            }
            else -> {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    performAuthentication(callback, biometricsType, retryPolicy)
                } else {
                    ContextCompat.getMainExecutor(activity).execute {
                        performAuthentication(callback, biometricsType, retryPolicy)
                    }
                }
            }
        }
    }

    private fun performAuthentication(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        retryPolicy: BiometricsRetryPolicy
    ) {
        if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) {
            callback.onError(BiometricPrompt.ERROR_CANCELED, "현재 화면에서 인증을 시작할 수 없습니다.")
            return
        }

        val session = ViewModelProvider(activity)[AuthenticationSessionViewModel::class.java].session
        val request = session.tryStart(callback, retryPolicy)
        if (request == null) {
            callback.onError(ERROR_AUTHENTICATION_IN_PROGRESS, "이미 인증이 진행 중입니다.")
            return
        }

        val authenticators = BiometricsPolicy.determineAuthenticators(biometricsType)
        val mainExecutor = ContextCompat.getMainExecutor(activity.applicationContext)
        val promptCallback = SessionPromptCallback(session, request, authenticators, mainExecutor)
        try {
            val promptInfo = buildPromptInfo(authenticators)
            val prompt = BiometricPrompt(activity, mainExecutor, promptCallback)
            promptCallback.prompt = prompt
            prompt.authenticate(promptInfo)
        } catch (error: RuntimeException) {
            promptCallback.prompt = null
            session.abandon(request)
            throw error
        }
    }

    private fun buildPromptInfo(authenticators: Int): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(config.title)
            .setDescription(config.description)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(config.confirmationRequired)
            .apply {
                if (!BiometricsPolicy.allowsDeviceCredential(authenticators)) {
                    setNegativeButtonText(config.negativeButtonText)
                }
            }
            .build()

    /**
     * Checks authentication availability using the configured authentication type /
     * 설정된 인증 타입 기준으로 인증 가능 여부를 확인합니다
     *
     * @return BiometricsAvailability status / BiometricsAvailability 상태
     */
    fun checkAvailability(): BiometricsAvailability {
        return checkAvailability(config.biometricsType)
    }

    /**
     * Checks authentication availability for the requested authentication type /
     * 요청한 인증 타입 기준으로 인증 가능 여부를 확인합니다
     *
     * @param biometricsType Type of authentication to evaluate / 확인할 인증 타입
     * @return BiometricsAvailability status / BiometricsAvailability 상태
     */
    fun checkAvailability(biometricsType: BiometricsType): BiometricsAvailability {
        val biometricStatus = when (biometricsType) {
            BiometricsType.CREDENTIAL_ONLY -> BiometricManager.BIOMETRIC_SUCCESS
            BiometricsType.BIOMETRIC_ONLY ->
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            BiometricsType.BIOMETRIC_OR_CREDENTIAL ->
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        val hasDeviceCredential = keyguardManager.isDeviceSecure

        return BiometricsPolicy.mapAvailability(
            biometricStatus = biometricStatus,
            hasDeviceCredential = hasDeviceCredential,
            biometricsType = biometricsType,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    /**
     * Opens device security settings / 기기 보안 설정을 엽니다
     * * Navigates to the system security settings where users can configure
     * biometric authentication, PINs, patterns, or passwords.
     * * 사용자가 생체인증, PIN, 패턴 또는 비밀번호를 설정할 수 있는
     * 시스템 보안 설정으로 이동합니다.
     */
    fun openSecuritySettings() {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        activity.startActivity(intent)
    }
}

internal class AuthenticationSessionViewModel : ViewModel() {
    val session = AuthenticationSession()
}

private class SessionPromptCallback(
    private val session: AuthenticationSession,
    private val request: AuthenticationSession.Request,
    private val authenticators: Int,
    private val mainExecutor: Executor
) : BiometricPrompt.AuthenticationCallback() {
    var prompt: BiometricPrompt? = null

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        finish { callback ->
            when (errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON -> callback.onCancel()
                else -> callback.onError(errorCode, errString.toString())
            }
        }
    }

    override fun onAuthenticationFailed() {
        session.onMismatch(request) { prompt?.cancelAuthentication() }
    }

    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        finish { callback ->
            val method = BiometricsPolicy.resolveAuthenticationMethod(
                authenticationType = result.authenticationType,
                authenticators = authenticators
            )
            callback.onSuccess(method)
        }
    }

    private fun finish(deliver: (BiometricsCallback) -> Unit) {
        // AndroidX queues fragment removal after posting the platform callback.
        // Let that removal run before the caller can start a new request.
        mainExecutor.execute {
            prompt = null
            session.finish(request, deliver)
        }
    }
}
