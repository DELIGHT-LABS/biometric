package io.delightlabs.biometrics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricsDemoTest {
    @get:Rule
    val compose = createAndroidComposeRule<ExampleActivity>()

    @Test
    fun recreatingHostKeepsSelectedModeWithoutStartingAuthentication() {
        node(R.string.status_ready).assertIsDisplayed()
        node(R.string.mode_credential_only).performScrollTo().performClick()

        compose.activityRule.scenario.recreate()

        node(R.string.status_ready).assertIsDisplayed()
        node(R.string.mode_credential_only).assertIsSelected()
    }

    @Test
    fun disabledBiometricsShowsAppOwnedFallback() {
        node(R.string.enable_biometrics).performScrollTo().performClick()
        node(R.string.authenticate).performScrollTo().performClick()

        node(R.string.status_app_pin_required).performScrollTo().assertIsDisplayed()
    }

    private fun node(label: Int) = compose.onNodeWithText(compose.activity.getString(label))
}
