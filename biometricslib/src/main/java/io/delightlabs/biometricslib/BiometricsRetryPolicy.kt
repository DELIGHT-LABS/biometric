package io.delightlabs.biometricslib

/**
 * Policy for handling the first biometric failure / 첫 생체인증 실패 처리 정책
 *
 * Controls whether the library should immediately surface an error after the
 * first failed biometric attempt, or allow the user to retry within the same
 * prompt session.
 *
 * 첫 번째 생체인증 실패 이후, 즉시 오류를 전달할지 또는 동일한 프롬프트 세션에서
 * 재시도를 허용할지를 제어합니다.
 */
enum class BiometricsRetryPolicy {
    /**
     * Allow retry on first failure / 첫 실패 시 재시도 허용
     */
    ALLOW_RETRY,

    /**
     * Fail immediately on first failure / 첫 실패 시 즉시 실패 처리
     */
    FAIL_IMMEDIATELY
}
