package io.delightlabs.biometricslib

enum class BiometricsAvailability {
    /**
     * Biometric authentication available / 생체인증 사용 가능
     */
    BIOMETRIC_AVAILABLE,

    /**
     * Only device credential available / 기기 패스워드만 사용 가능
     */
    CREDENTIAL_ONLY_AVAILABLE,

    /**
     * Biometric not enrolled / 생체인증 미등록 (설정 필요)
     */
    BIOMETRIC_NOT_ENROLLED,

    /**
     * No security configured / 기기 보안 설정 없음 (PIN/패턴/비밀번호 미설정)
     */
    NO_SECURITY_CONFIGURED,

    /**
     * Hardware not supported / 하드웨어 지원 안함
     */
    HARDWARE_NOT_SUPPORTED,

    /**
     * Temporarily locked / 일시적으로 사용 불가 (너무 많은 실패)
     */
    TEMPORARILY_LOCKED,

    /**
     * Permanently locked / 영구적으로 사용 불가
     */
    PERMANENTLY_LOCKED;
}