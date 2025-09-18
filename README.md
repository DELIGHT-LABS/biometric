# BiometricsLib - Android Biometric Authentication Library

A simple, easy-to-use Android library for biometric authentication with smart fallback support.

## Features

- **Easy Integration** – One-line authentication
- **Multiple Authentication Types** – Biometric, credentials, or both  
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
    implementation ("com.github.delight-labs:biometricslib:1.0.0")
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
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Authentication successful!", Toast.LENGTH_SHORT).show()
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

## API Reference

### BiometricsManager

- `create(activity, config?)` → Create instance
- `authenticate(callback, isBiometricEnabled?, onBiometricDisabled?)` → Start authentication
- `checkAvailability()` → Check biometric availability
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
- `CREDENTIAL_ONLY` – Device credentials only
- `BIOMETRIC_OR_CREDENTIAL` – Biometric or credentials (recommended)

### BiometricsCallback

```kotlin
interface BiometricsCallback {
    fun onSuccess()
    fun onError(errorCode: Int, errorMessage: String)
    fun onCancel()
}
```

### BiometricsAvailability

- `BIOMETRIC_AVAILABLE`
- `CREDENTIAL_ONLY_AVAILABLE`
- `BIOMETRIC_NOT_ENROLLED`
- `NO_SECURITY_CONFIGURED`
- `HARDWARE_NOT_SUPPORTED`
- `TEMPORARILY_LOCKED`
- `PERMANENTLY_LOCKED`

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

---

## v1.0.0

- Initial release
- Basic biometric authentication
- Device credential fallback
- Comprehensive error handling
- App-level biometric disable support