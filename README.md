# BiometricsLib - Android Biometric Authentication Library

A simple, easy-to-use Android library for biometric authentication with smart fallback support.

## Features

- **Easy Integration** – One-line authentication
- **Multiple Authentication Types** – Biometric, credentials, or both  
- **Resolved Success Method** – Normalize success as biometric vs pin
- **Smart Fallback** – Automatically falls back to device credentials when biometrics are unavailable
- **Comprehensive Error Handling** – Detailed error codes and messages
- **Biometric Disable Support** – App-level biometric toggle

## Installation

**Step 1.** Add JitPack Repository

In your project `settings.gradle` or root `build.gradle`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Step 2.** Add Dependency

In your app module `build.gradle`:

```kotlin
dependencies {
    implementation("com.github.DELIGHT-LABS:biometric:release")
}
```

## Quick Start

### Basic Usage

```kotlin
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val biometricsManager = BiometricsManager.create(this)

        biometricsManager.authenticate(object : BiometricsCallback {
            override fun onSuccess(authenticationMethod: AuthenticationMethod) {
                Toast.makeText(
                    this@MainActivity,
                    "Authentication successful: $authenticationMethod",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                Toast.makeText(this@MainActivity, "Error: $errorMessage", Toast.LENGTH_SHORT).show()
            }

            override fun onCancel() {
                Toast.makeText(this@MainActivity, "Authentication cancelled", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
```

### Custom Configuration

```kotlin
val config = BiometricsConfig(
    title = "Verify Your Identity",
    description = "Please verify your identity to continue",
    negativeButtonText = "Cancel",
    biometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL,
    confirmationRequired = false
)

val biometricsManager = BiometricsManager.create(this, config)
```

`BiometricsConfig()` without overrides defaults to `BiometricsType.BIOMETRIC_OR_CREDENTIAL`.
If your app wants strict biometric-only success, set `BiometricsType.BIOMETRIC_ONLY` explicitly.

### App-Level Biometric Disable

```kotlin
biometricsManager.authenticate(
    callback = callback,
    isBiometricEnabled = userSettings.isBiometricEnabled,
    onBiometricDisabled = {
        showPasswordDialog()
    }
)
```

Use `onBiometricDisabled` for your app-owned PIN screen. The library only reports
system prompt results; your own PIN success should be classified in the app layer.

## API Reference

### BiometricsManager

- `create(activity, config?)` → Create instance
- `authenticate(callback, isBiometricEnabled?, onBiometricDisabled?)` → Start authentication
- `authenticate(callback, biometricsType, isBiometricEnabled?, onBiometricDisabled?)` → Start authentication with an explicit type
- `checkAvailability()` → Check availability using `config.biometricsType`
- `checkAvailability(biometricsType)` → Check availability for a specific type
- `openSecuritySettings()` → Open device security settings

### BiometricsConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `title` | String | "Verify your identity" | Dialog title |
| `description` | String | "Confirm your biometric to continue" | Dialog description |
| `negativeButtonText` | String | "Cancel" | Cancel button text |
| `biometricsType` | BiometricsType | `BIOMETRIC_OR_CREDENTIAL` | Authentication type |
| `confirmationRequired` | Boolean | false | Require explicit confirmation |

### BiometricsType

- `BIOMETRIC_ONLY` – Biometric only
- `CREDENTIAL_ONLY` – Device credentials only (Android 11+)
- `BIOMETRIC_OR_CREDENTIAL` – Biometric or device credential (recommended)

### BiometricsCallback

```kotlin
interface BiometricsCallback {
    fun onSuccess(authenticationMethod: AuthenticationMethod)
    fun onError(errorCode: Int, errorMessage: String)
    fun onCancel()
}
```

Legacy `onSuccess()` is still available for compatibility, but it does not tell you
whether the user passed with a biometric or a pin-based device credential.

### AuthenticationMethod

- `BIOMETRIC`
- `PIN`
- `UNKNOWN`

### BiometricsAvailability

- `AUTHENTICATOR_UNSUPPORTED`
- `BIOMETRIC_AVAILABLE`
- `CREDENTIAL_ONLY_AVAILABLE`
- `BIOMETRIC_NOT_ENROLLED`
- `NO_SECURITY_CONFIGURED`
- `HARDWARE_NOT_SUPPORTED`
- `TEMPORARILY_LOCKED`
- `PERMANENTLY_LOCKED`

## Recommended Policy

For apps that must not classify system PIN or pattern as biometric:

- Treat only `AuthenticationMethod.BIOMETRIC` as `biometric`
- Treat `AuthenticationMethod.PIN` as `pin`
- Treat app-owned PIN screen success as `pin`
- Use `BIOMETRIC_ONLY` explicitly when a flow must reject system PIN fallback
- Use the default `BIOMETRIC_OR_CREDENTIAL` when a flow may accept system PIN fallback

Example mapping:

```kotlin
val resultType = when (authenticationMethod) {
    AuthenticationMethod.BIOMETRIC -> "biometric"
    AuthenticationMethod.PIN -> "pin"
    AuthenticationMethod.UNKNOWN -> "unknown"
}
```

If your product wants:

- biometric success → `biometric`
- system pattern / device PIN / password success → `pin`
- app PIN success → `pin`

then the recommended setup is:

- use `BIOMETRIC_ONLY` for strict biometric-only entry points
- use `BIOMETRIC_OR_CREDENTIAL` only for flows that intentionally allow the system credential fallback
- send `onBiometricDisabled` to your app PIN screen and classify that success as `pin`

Internally, AndroidX still reports system credential success as `DEVICE_CREDENTIAL`.
This library normalizes that raw result to `AuthenticationMethod.PIN`.

`CREDENTIAL_ONLY` is supported on Android 11 (API 30)+. On Android 9-10 (API 28-29),
`checkAvailability(CREDENTIAL_ONLY)` returns `AUTHENTICATOR_UNSUPPORTED`.

## Error Codes

| Code | Constant | Description |
|------|----------|-------------|
| `-100` | `ERROR_NO_SECURITY` | No device security configured |
| `-101` | `ERROR_HARDWARE_NOT_SUPPORTED` | Hardware not supported |
| `-102` | `ERROR_TEMPORARILY_LOCKED` | Temporarily locked |
| `-103` | `ERROR_PERMANENTLY_LOCKED` | Permanently locked |
| `-104` | `ERROR_AUTHENTICATION_FAILED` | Authentication failed |

For system errors, see [BiometricPrompt.ERROR_*](https://developer.android.com/reference/androidx/biometric/BiometricPrompt#constants_1).

## Sample App

A complete sample application is available in the `app` module.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License – see the [LICENSE](LICENSE) file for details.

## v1.0.0

- Initial release
- Basic biometric authentication
- Device credential fallback
- Comprehensive error handling
- App-level biometric disable support
