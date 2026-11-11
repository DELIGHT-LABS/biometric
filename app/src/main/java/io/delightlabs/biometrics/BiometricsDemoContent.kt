package io.delightlabs.biometrics

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.delightlabs.biometrics.ui.theme.BiometricsTheme
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode
import io.delightlabs.biometricslib.authentication.result.AuthenticationFailureReason
import io.delightlabs.biometricslib.authentication.result.AuthenticationOutcome
import io.delightlabs.biometricslib.authentication.result.VerifiedAuthenticationMethod
import io.delightlabs.biometricslib.availability.AuthenticationAvailability

internal sealed interface BiometricsDemoAction {
    data class SelectMode(val mode: AuthenticationMode) : BiometricsDemoAction
    data class SetBiometricsEnabled(val enabled: Boolean) : BiometricsDemoAction
    data class SetRetryEnabled(val enabled: Boolean) : BiometricsDemoAction
    data object CheckAvailability : BiometricsDemoAction
    data object Authenticate : BiometricsDemoAction
    data object CancelAuthentication : BiometricsDemoAction
    data object OpenSettings : BiometricsDemoAction
}

@Composable
internal fun BiometricsDemoContent(
    state: BiometricsDemoState,
    onAction: (BiometricsDemoAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val canInteract = state.status != BiometricsDemoStatus.Authenticating

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineSmall)
                Text(statusText(state.status), style = MaterialTheme.typography.bodyLarge)
                AuthenticationModeSelector(state.mode, canInteract) { mode ->
                    onAction(BiometricsDemoAction.SelectMode(mode))
                }
                AvailabilitySummary(state)
                DemoToggle(
                    label = stringResource(R.string.enable_biometrics),
                    checked = state.biometricsEnabled,
                    enabled = canInteract,
                    onCheckedChange = { onAction(BiometricsDemoAction.SetBiometricsEnabled(it)) }
                )
                DemoToggle(
                    label = stringResource(R.string.allow_retry),
                    checked = state.retryEnabled,
                    enabled = canInteract,
                    onCheckedChange = { onAction(BiometricsDemoAction.SetRetryEnabled(it)) }
                )
                DemoActions(canInteract = canInteract, onAction = onAction)
            }
        }
    }
}

@Composable
private fun AvailabilitySummary(state: BiometricsDemoState) {
    val report = state.availabilityReport
    if (report != null) {
        Text(LocalContext.current.availabilityReportText(report))
    } else {
        state.availability?.let { availability ->
            Text(stringResource(R.string.availability_status, stringResource(availability.label)))
        }
    }
}

internal fun Context.availabilityReportText(report: DemoAvailabilityReport): String {
    val selected = getString(
        R.string.availability_report_selected,
        getString(report.selectedMode.label),
        getString(report.selectedAvailability.label)
    )
    val usable = if (report.usableModes.isEmpty()) {
        getString(R.string.availability_report_none)
    } else {
        val options = report.usableModes.joinToString("\n") { mode ->
            getString(
                R.string.availability_report_option,
                getString(mode.label),
                getString(report.availabilityByMode.getValue(mode).label)
            )
        }
        getString(R.string.availability_report_usable, options)
    }
    val uncertain = report.uncertainModes.takeIf { it.isNotEmpty() }?.let { modes ->
        getString(R.string.availability_report_uncertain, modes.joinToString("\n") { getString(it.label) })
    }
    return listOfNotNull(selected, usable, uncertain).joinToString("\n\n")
}

internal fun Context.availabilityFeedbackText(report: DemoAvailabilityReport): String {
    val message = when {
        report.isSelectedAvailable -> R.string.availability_feedback_selected
        report.selectedAvailability == AuthenticationAvailability.UNKNOWN -> R.string.availability_feedback_unknown
        report.usableModes.isNotEmpty() -> R.string.availability_feedback_alternatives
        report.uncertainModes.isNotEmpty() -> R.string.availability_feedback_unknown
        else -> R.string.availability_feedback_unavailable
    }
    return getString(message)
}

@Composable
private fun DemoActions(canInteract: Boolean, onAction: (BiometricsDemoAction) -> Unit) {
    Button(
        onClick = { onAction(BiometricsDemoAction.Authenticate) },
        enabled = canInteract
    ) {
        Text(stringResource(R.string.authenticate))
    }
    if (!canInteract) {
        OutlinedButton(onClick = { onAction(BiometricsDemoAction.CancelAuthentication) }) {
            Text(stringResource(R.string.cancel_authentication))
        }
    }
    OutlinedButton(
        onClick = { onAction(BiometricsDemoAction.CheckAvailability) },
        enabled = canInteract
    ) {
        Text(stringResource(R.string.check_availability))
    }
    OutlinedButton(
        onClick = { onAction(BiometricsDemoAction.OpenSettings) },
        enabled = canInteract
    ) {
        Text(stringResource(R.string.open_security_settings))
    }
}

@Composable
private fun AuthenticationModeSelector(
    selectedMode: AuthenticationMode,
    enabled: Boolean,
    onSelect: (AuthenticationMode) -> Unit
) {
    Column(Modifier.selectableGroup()) {
        AuthenticationMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Text(stringResource(mode.label))
            }
        }
    }
}

@Composable
private fun DemoToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun statusText(status: BiometricsDemoStatus): String {
    return when (status) {
        BiometricsDemoStatus.Ready -> stringResource(R.string.status_ready)
        BiometricsDemoStatus.Authenticating -> stringResource(R.string.status_authenticating)
        BiometricsDemoStatus.AppPinRequired -> stringResource(R.string.status_app_pin_required)
        BiometricsDemoStatus.SettingsUnavailable -> stringResource(R.string.status_settings_unavailable)
        is BiometricsDemoStatus.Completed -> when (val outcome = status.outcome) {
            is AuthenticationOutcome.Success -> stringResource(outcome.method.label)
            AuthenticationOutcome.Cancelled -> stringResource(R.string.status_cancelled)
            is AuthenticationOutcome.Failure -> failureText(outcome)
        }
    }
}

@Composable
private fun failureText(failure: AuthenticationOutcome.Failure): String {
    val detail = if (failure.reason == AuthenticationFailureReason.PLATFORM_ERROR) {
        failure.message?.takeIf { it.isNotBlank() }
    } else {
        stringResource(failure.reason.label)
    }
    return detail?.let { stringResource(R.string.status_failure_details, it) }
        ?: stringResource(R.string.status_failure)
}

@get:StringRes
private val AuthenticationMode.label: Int
    get() = when (this) {
        AuthenticationMode.BIOMETRIC_ONLY -> R.string.mode_biometric_only
        AuthenticationMode.DEVICE_CREDENTIAL_ONLY -> R.string.mode_credential_only
        AuthenticationMode.BIOMETRIC_OR_DEVICE_CREDENTIAL -> R.string.mode_biometric_or_credential
        AuthenticationMode.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL -> R.string.mode_strong_biometric_or_credential
    }

@get:StringRes
private val AuthenticationAvailability.label: Int
    get() = when (this) {
        AuthenticationAvailability.BIOMETRIC_AVAILABLE -> R.string.availability_biometric
        AuthenticationAvailability.DEVICE_CREDENTIAL_AVAILABLE -> R.string.availability_credential
        AuthenticationAvailability.NOT_ENROLLED -> R.string.availability_not_enrolled
        AuthenticationAvailability.NO_DEVICE_CREDENTIAL -> R.string.availability_no_credential
        AuthenticationAvailability.NO_HARDWARE -> R.string.availability_no_hardware
        AuthenticationAvailability.HARDWARE_UNAVAILABLE -> R.string.availability_hardware_unavailable
        AuthenticationAvailability.SECURITY_UPDATE_REQUIRED -> R.string.availability_security_update
        AuthenticationAvailability.UNSUPPORTED -> R.string.availability_unsupported
        AuthenticationAvailability.UNKNOWN -> R.string.availability_unknown
    }

@get:StringRes
private val VerifiedAuthenticationMethod.label: Int
    get() = when (this) {
        VerifiedAuthenticationMethod.BIOMETRIC -> R.string.status_success_biometric
        VerifiedAuthenticationMethod.DEVICE_CREDENTIAL -> R.string.status_success_credential
        VerifiedAuthenticationMethod.UNKNOWN -> R.string.status_success_unknown
    }

@get:StringRes
private val AuthenticationFailureReason.label: Int
    get() = when (this) {
        AuthenticationFailureReason.IN_PROGRESS -> R.string.failure_in_progress
        AuthenticationFailureReason.HOST_UNAVAILABLE -> R.string.failure_host_unavailable
        AuthenticationFailureReason.INVALID_CONFIGURATION -> R.string.failure_invalid_configuration
        AuthenticationFailureReason.NOT_ENROLLED -> R.string.availability_not_enrolled
        AuthenticationFailureReason.NO_DEVICE_CREDENTIAL -> R.string.availability_no_credential
        AuthenticationFailureReason.NO_HARDWARE -> R.string.availability_no_hardware
        AuthenticationFailureReason.HARDWARE_UNAVAILABLE -> R.string.availability_hardware_unavailable
        AuthenticationFailureReason.SECURITY_UPDATE_REQUIRED -> R.string.availability_security_update
        AuthenticationFailureReason.UNSUPPORTED -> R.string.availability_unsupported
        AuthenticationFailureReason.MISMATCH -> R.string.failure_mismatch
        AuthenticationFailureReason.TEMPORARILY_LOCKED -> R.string.failure_temporarily_locked
        AuthenticationFailureReason.PERMANENTLY_LOCKED -> R.string.failure_permanently_locked
        AuthenticationFailureReason.INTERRUPTED -> R.string.failure_interrupted
        AuthenticationFailureReason.TIMEOUT -> R.string.failure_timeout
        AuthenticationFailureReason.PLATFORM_ERROR -> R.string.status_failure
    }

@Preview(showBackground = true)
@Composable
private fun BiometricsDemoPreview() {
    BiometricsTheme {
        BiometricsDemoContent(
            state = BiometricsDemoState(availability = AuthenticationAvailability.BIOMETRIC_AVAILABLE),
            onAction = {}
        )
    }
}
