package io.delightlabs.biometricslib

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
     * @param isBiometricEnabled Whether biometric is enabled in app / 앱에서 생체인증이 활성화되었는지 여부
     * @param onBiometricDisabled Called when biometric is disabled / 생체인증이 비활성화된 경우 호출
     */
    fun authenticate(
        callback: BiometricsCallback,
        biometricsType: BiometricsType,
        retryPolicy: BiometricsRetryPolicy,
        isBiometricEnabled: Boolean = true,
        onBiometricDisabled: (() -> Unit)? = null
    ) {
        // If biometric is disabled in app settings, call the disabled callback
        // 앱 설정에서 생체인증이 비활성화된 경우, 비활성화 콜백을 호출
        if (!isBiometricEnabled) {
            onBiometricDisabled?.invoke()
            return
        }
        val availability = checkAvailability()

        when (availability) {
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
                performAuthentication(callback, availability, biometricsType, retryPolicy)
            }
        }
    }

    /**
     * Performs the actual biometric authentication process /
     * 실제 생체인증 프로세스를 수행합니다
     *
     * Creates BiometricPrompt with appropriate configuration and handles
     * authentication callbacks to delegate results to the provided callback.
     *
     * 적절한 설정으로 BiometricPrompt를 생성하고 인증 콜백을 처리하여
     * 제공된 콜백으로 결과를 전달합니다.
     *
     * @param callback Callback to receive authentication results / 인증 결과를 받을 콜백
     * @param availability Current biometric availability status / 현재 생체인증 가용성 상태
     * @param biometricsType Type of authentication to use / 사용할 인증 타입
     */
    private fun performAuthentication(
        callback: BiometricsCallback,
        availability: BiometricsAvailability,
        biometricsType: BiometricsType,
        biometricsRetryPolicy: BiometricsRetryPolicy
    ) {
        val authenticators = determineAuthenticators(availability, biometricsType)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(config.title)
            .setDescription(config.description)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(config.confirmationRequired)
            .apply {
                if (authenticators == BiometricManager.Authenticators.BIOMETRIC_STRONG) {
                    setNegativeButtonText(config.negativeButtonText)
                }
            }
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> callback.onCancel()
                        else -> callback.onError(errorCode, errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    if(biometricsRetryPolicy == BiometricsRetryPolicy.FAIL_IMMEDIATELY) {
                        callback.onError(
                            ERROR_AUTHENTICATION_FAILED,
                            "인증에 실패했습니다."
                        )
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    callback.onSuccess()
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Determines the appropriate authenticators based on provided biometric type and availability /
     * 제공된 생체인증 타입과 가용성에 따라 적절한 인증 방식을 결정합니다
     *
     * Analyzes the provided biometric type and current device availability
     * to select the most appropriate authentication methods.
     *
     * 제공된 생체인증 타입과 현재 기기 가용성을 분석하여
     * 가장 적절한 인증 방법을 선택합니다.
     *
     * @param availability Current biometric availability status / 현재 생체인증 가용성 상태
     * @param biometricsType Type of authentication to use / 사용할 인증 타입
     * @return Authenticator flags for BiometricPrompt / BiometricPrompt용 인증 방식 플래그
     */
    private fun determineAuthenticators(availability: BiometricsAvailability, biometricsType: BiometricsType): Int {
        return when (biometricsType) {
            BiometricsType.BIOMETRIC_ONLY -> BiometricManager.Authenticators.BIOMETRIC_STRONG
            BiometricsType.CREDENTIAL_ONLY -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            BiometricsType.BIOMETRIC_OR_CREDENTIAL -> {
                when (availability) {
                    BiometricsAvailability.BIOMETRIC_AVAILABLE ->
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE ->
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    else ->
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                }
            }
        }
    }

    /**
     * Checks biometric authentication availability / 생체인증 가용성을 확인합니다
     * * Examines the device's biometric capabilities and security configuration
     * to determine what authentication methods are available.
     * * 기기의 생체인증 기능과 보안 설정을 검사하여 사용 가능한 인증 방법을
     * 확인합니다.
     * * @return BiometricsAvailability status / BiometricsAvailability 상태
     */
    fun checkAvailability(): BiometricsAvailability {
        val biometricStatus = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val hasDeviceCredential = keyguardManager.isDeviceSecure

        return when {
            !hasDeviceCredential -> BiometricsAvailability.NO_SECURITY_CONFIGURED
            biometricStatus == BiometricManager.BIOMETRIC_SUCCESS -> BiometricsAvailability.BIOMETRIC_AVAILABLE
            biometricStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && hasDeviceCredential ->
                BiometricsAvailability.BIOMETRIC_NOT_ENROLLED
            biometricStatus == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricsAvailability.HARDWARE_NOT_SUPPORTED
            biometricStatus == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricsAvailability.TEMPORARILY_LOCKED
            biometricStatus == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricsAvailability.PERMANENTLY_LOCKED
            hasDeviceCredential -> BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE
            else -> BiometricsAvailability.NO_SECURITY_CONFIGURED
        }
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
