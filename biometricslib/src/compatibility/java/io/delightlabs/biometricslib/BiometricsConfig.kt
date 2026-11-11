package io.delightlabs.biometricslib

// Compatibility types keep their original JVM names; new integrations use feature packages.

import android.content.Context

/**
 * Prompt text and legacy defaults.
 *
 * [negativeButtonText] is used only when the request excludes device credentials.
 * [confirmationRequired] is a hint for passive biometrics on Android 10 and newer.
 * Android may ignore it. [biometricsType] is the mode used when a request omits a mode.
 */
data class BiometricsConfig(
    val title: String = "Verify your identity",
    val description: String = "Verify your identity to continue",
    val negativeButtonText: String = "Cancel",
    val biometricsType: BiometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL,
    val confirmationRequired: Boolean = false
) {
    companion object {
        /** Loads default prompt text for the supplied context's locale. */
        fun localized(context: Context): BiometricsConfig = BiometricsConfig(
            title = context.getString(R.string.biometrics_prompt_title),
            description = context.getString(R.string.biometrics_prompt_description),
            negativeButtonText = context.getString(R.string.biometrics_prompt_cancel)
        )
    }
}
