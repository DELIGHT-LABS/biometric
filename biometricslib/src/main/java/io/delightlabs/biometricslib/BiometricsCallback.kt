package io.delightlabs.biometricslib

interface BiometricsCallback {
    /**
     * Called when authentication succeeds / 인증 성공 시 호출
     */
    fun onSuccess()

    /**
     * Called when authentication fails / 인증 실패 시 호출
     * @param errorCode Error code / 에러 코드
     * @param errorMessage Error message / 에러 메시지
     */
    fun onError(errorCode: Int, errorMessage: String)

    /**
     * Called when user cancels authentication / 사용자가 인증을 취소한 경우 호출
     */
    fun onCancel()
}
