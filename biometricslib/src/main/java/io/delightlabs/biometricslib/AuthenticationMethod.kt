package io.delightlabs.biometricslib

/**
 * Authentication method reported after a successful authentication / 인증 성공 후 보고되는 인증 수단
 */
enum class AuthenticationMethod {
    /**
     * The user authenticated with biometrics / 생체인증으로 성공
     */
    BIOMETRIC,

    /**
     * The user authenticated with system pattern, PIN, or password,
     * normalized as pin / 시스템 패턴, PIN, 비밀번호 성공을 pin으로 정규화
     */
    PIN,

    /**
     * The library could not determine the exact method / 정확한 인증 수단을 판별할 수 없음
     */
    UNKNOWN
}
