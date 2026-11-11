package io.delightlabs.biometrics

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.delightlabs.biometrics.ui.theme.BiometricsTheme
import io.delightlabs.biometricslib.authentication.BiometricsConfig
import io.delightlabs.biometricslib.authentication.BiometricsManager
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode
import io.delightlabs.biometricslib.authentication.request.AuthenticationResult
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome

class ExampleActivity : FragmentActivity() {
    private lateinit var viewModel: BiometricsDemoViewModel
    private lateinit var biometrics: BiometricsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[BiometricsDemoViewModel::class.java]
        biometrics = BiometricsManager.create(
            activity = this,
            config = BiometricsConfig(
                title = getString(R.string.prompt_title),
                description = getString(R.string.prompt_description),
                negativeButtonText = getString(R.string.cancel)
            )
        )
        viewModel.activeRequestId?.let { requestId ->
            biometrics.reconnectAuthentication(requestId, ::onAuthenticationResult)
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            BiometricsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BiometricsDemoContent(
                        state = state,
                        onAction = ::onDemoAction,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAvailability()
    }

    private fun onDemoAction(action: BiometricsDemoAction) {
        when (action) {
            is BiometricsDemoAction.SelectMode -> {
                viewModel.selectMode(action.mode)
                refreshAvailability()
            }
            is BiometricsDemoAction.SetBiometricsEnabled -> viewModel.setBiometricsEnabled(action.enabled)
            is BiometricsDemoAction.SetRetryEnabled -> viewModel.setRetryEnabled(action.enabled)
            BiometricsDemoAction.CheckAvailability -> {
                val report = readAvailabilityReport()
                Toast.makeText(this, availabilityFeedbackText(report), Toast.LENGTH_LONG).show()
            }
            BiometricsDemoAction.Authenticate -> startAuthentication()
            BiometricsDemoAction.CancelAuthentication -> {
                viewModel.abandonAuthentication()?.let(biometrics::cancelAuthentication)
            }
            BiometricsDemoAction.OpenSettings -> {
                if (!biometrics.tryOpenSecuritySettings()) viewModel.onSettingsUnavailable()
            }
        }
    }

    private fun refreshAvailability() {
        val current = viewModel.state.value
        if (current.availabilityReport != null) {
            readAvailabilityReport()
        } else {
            viewModel.updateAvailability(current.mode, biometrics.checkAuthenticationAvailability(current.mode))
        }
    }

    private fun readAvailabilityReport(): DemoAvailabilityReport = viewModel.updateAvailabilityReport(
        AuthenticationMode.entries.associateWith(biometrics::checkAuthenticationAvailability)
    )

    private fun startAuthentication() {
        val request = viewModel.beginAuthentication() ?: return
        biometrics.startAuthentication(
            requestId = request.requestId,
            mode = request.mode,
            retryPolicy = request.retryPolicy,
            onResult = ::onAuthenticationResult
        )
    }

    private fun onAuthenticationResult(result: AuthenticationResult) {
        if (!viewModel.onAuthenticationResult(result)) return
        if (result.outcome is AuthenticationOutcome.Success) {
            Toast.makeText(this, R.string.authentication_success_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
