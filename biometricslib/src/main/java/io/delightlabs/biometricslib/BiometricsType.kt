package io.delightlabs.biometricslib

/**
 * Types of authentication methods / 인증 방법의 유형
 */
enum class BiometricsType {
    /**
     * Use only biometric authentication (fingerprint, face recognition, etc.) / * 생체인증만 사용 (지문, 얼굴인식 등)
     */
    BIOMETRIC_ONLY,

    /**
     * Use only device credentials (PIN, pattern, password) /
     * 기기 패스워드만 사용 (PIN, 패턴, 비밀번호)
     */
    CREDENTIAL_ONLY,

    /**
     * Use biometric authentication or device credentials (recommended) /
     * 생체인증 또는 기기 패스워드 사용 (권장)
     */
    BIOMETRIC_OR_CREDENTIAL
}
