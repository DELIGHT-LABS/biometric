package io.delightlabs.biometricslib

/**
 * Configuration for biometric authentication / 생체인증 설정
 *
 * @param title Dialog title / 다이얼로그 제목
 * @param description Description text shown to user / 사용자에게 표시되는 설명 텍스트
 * @param negativeButtonText Text for cancel/negative button / 취소 버튼 텍스트
 * @param biometricsType Type of authentication to use / 사용할 인증 타입
 * @param confirmationRequired Whether to require explicit user confirmation / 명시적 사용자 확인 필요 여부
 */
data class BiometricsConfig(
    val title: String = "Verify your identity",
    val description: String = "Confirm your biometric to continue",
    val negativeButtonText: String = "Cancel",
    val biometricsType: BiometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL,
    val confirmationRequired: Boolean = false
)
