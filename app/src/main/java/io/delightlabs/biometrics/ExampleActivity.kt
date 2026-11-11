package io.delightlabs.biometrics

import android.os.Bundle
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
import io.delightlabs.biometricslib.BiometricsConfig
import io.delightlabs.biometricslib.BiometricsManager

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
            biometrics.reconnectAuthentication(requestId, viewModel::onAuthenticationResult)
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
            BiometricsDemoAction.CheckAvailability -> refreshAvailability()
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
        val mode = viewModel.state.value.mode
        viewModel.updateAvailability(mode, biometrics.checkAuthenticationAvailability(mode))
    }

    private fun startAuthentication() {
        val request = viewModel.beginAuthentication() ?: return
        biometrics.startAuthentication(
            requestId = request.requestId,
            mode = request.mode,
            retryPolicy = request.retryPolicy,
            onResult = viewModel::onAuthenticationResult
        )
    }
}
