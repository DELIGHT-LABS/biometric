# BiometricsLib

AndroidX biometric and device-credential authentication for Android 9 (API 28)
and later. Choose the allowed authenticators, check availability, and receive one
typed result per request. Existing callback-based integrations are supported.

## Installation

The library supports Android 9 (API 28) and later. The examples below use the
APIs in this checkout. The historical `release` tag predates them. To try the
current code, use `implementation(project(":biometricslib"))` within this
repository or publish a local AAR for an external project.

### Build and verify a local AAR

Use JDK 17 and set `ANDROID_HOME` to an Android SDK with Platform 36 installed.
Run from the repository root.

```sh
./gradlew :biometricslib:publishReleasePublicationToVerificationRepository
./gradlew -p consumer-smoke assembleDebug
```

The first command writes the AAR and dependency metadata to
`biometricslib/build/verification-repository`. Add that directory as a Maven
repository in an external project's `settings.gradle.kts` and depend on
`com.github.DELIGHT-LABS.Biometric:biometricslib:0.0.0-local`. The
`consumer-smoke` project compiles Java and Kotlin callers against that AAR.

### Install a tagged build from JitPack

After a tag containing this publishing configuration is available on JitPack,
add its repository in `settings.gradle.kts`.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.DELIGHT-LABS.Biometric") }
        }
    }
}
```

Add the library module in the app's `build.gradle.kts`. Replace `<tag>` with the
exact tag you want to use.

```kotlin
dependencies {
    implementation("com.github.DELIGHT-LABS.Biometric:biometricslib:<tag>")
}
```

## Start from a user action

Create a fresh request ID before each authentication attempt and keep it alongside
the protected action in a ViewModel. Reconnect with that same ID in the recreated
Activity's `onCreate`. The sample stores the ID before starting, matches every
result against it, and consumes it before updating the UI or handling success.

```kotlin
class AuthenticationViewModel : ViewModel() {
    var activeRequestId: AuthenticationRequestId? = null
}

class MainActivity : FragmentActivity() {
    private lateinit var biometrics: BiometricsManager
    private lateinit var requests: AuthenticationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requests = ViewModelProvider(this)[AuthenticationViewModel::class.java]
        biometrics = BiometricsManager.create(this)
        requests.activeRequestId?.let { requestId ->
            biometrics.reconnectAuthentication(requestId, ::handleAuthentication)
        }
    }

    fun onAuthenticateClick() {
        if (requests.activeRequestId != null) return
        val requestId = AuthenticationRequestId.create(maxBufferedSuccessAgeMillis = 30_000L)
        requests.activeRequestId = requestId
        biometrics.startAuthentication(
            requestId = requestId,
            mode = AuthenticationMode.BIOMETRIC_ONLY,
            retryPolicy = BiometricsRetryPolicy.ALLOW_RETRY,
            onResult = ::handleAuthentication
        )
    }

    private fun handleAuthentication(result: AuthenticationResult) {
        if (requests.activeRequestId != result.requestId) return
        requests.activeRequestId = null
        when (val outcome = result.outcome) {
            is AuthenticationOutcome.Success -> showSuccess(outcome.method)
            AuthenticationOutcome.Cancelled -> showCancellation()
            is AuthenticationOutcome.Failure -> showRecoveryFor(outcome.reason)
        }
    }
}
```

The Activity owns its AndroidX prompt. The logical request survives Activity
recreation in the same process. `create` rebinds the platform prompt, and
`reconnectAuthentication(requestId, onResult)` installs a listener only for the
matching request. A wrong ID returns `false` and leaves the original listener and
buffered result intact. A buffered result is consumed once before the callback
runs. Starting a fresh request after completion supersedes any unclaimed result.

Request IDs are opaque single-use tokens. `startAuthentication(requestId, ...)`
returns `true` when it claims the ID, including an attempt that ends in a
preflight or busy failure. Reusing that ID returns `false` and invokes no new
callback. Reconnect with the same ID to resume an attempt, and create a fresh ID
to retry. Keep the ID with the original action instead of looking up a mutable
current action when success arrives. An app process restart requires fresh
authentication.

### Cancel an action or discard its result

When the protected action is abandoned, clear its ID in the app before cancelling
the library request. Call cancellation and result-discard APIs on the main thread.

```kotlin
val abandonedId = requests.activeRequestId
requests.activeRequestId = null
abandonedId?.let { biometrics.cancelAuthentication(it) }
```

`cancelAuthentication(id)` affects only that ID. It removes a queued request
before the prompt opens, requests cancellation of a running prompt, or discards
a buffered outcome. An attached callback receives `Cancelled` once. A detached
request is discarded without storing another result. Late platform success cannot
restore a cancelled request. Running prompts retain the admission gate until the
platform finishes dismissing; overlapping attempts can still return `IN_PROGRESS`.
Cancellation before a worker-thread start reaches the main queue also suppresses
prompt launch. Wrong IDs and repeated cancellation return `false`.

Use `discardAuthenticationResult(id)` to consume only a matching buffered outcome
without cancelling an active prompt. It returns `true` only when a buffered result
was removed.

`AuthenticationRequestId.create(maxBufferedSuccessAgeMillis)` bounds the age of a
success buffered while the Activity listener is detached. Time starts when success
is buffered and includes device sleep. Reconnecting after the limit consumes the
success as `Failure(TIMEOUT)` once; it cannot authorize the action. The sample uses
30 seconds. The original `create()` factory keeps its unbounded buffering behavior
for compatibility. This limit applies to buffered success, not to prompt duration
or a result already delivered to the app.

### Coroutine integration

Use `awaitAuthentication` for a single result from a click-driven coroutine.

```kotlin
val requestId = AuthenticationRequestId.create()
lifecycleScope.launch {
    val result = biometrics.awaitAuthentication(requestId, AuthenticationMode.BIOMETRIC_ONLY)
    handleResultFor(requestId, result.outcome)
}
```

The ID overload returns `AuthenticationResult` containing the same ID.
Reusing an ID in `awaitAuthentication` throws `IllegalStateException`.
User dismissal returns `AuthenticationOutcome.Cancelled` as its outcome. Cancelling the calling
coroutine propagates `CancellationException`, suppresses late results, and requests
platform cancellation for that request. Android 9 can keep its separate device
credential screen open until the user exits it. Use the reconnectable callback
API when the same request needs to continue across Activity recreation.

The sample collects screen state through lifecycle-aware `StateFlow`. Starting
authentication remains a user action, so restarting collection after rotation or
backgrounding does not reopen a prompt. Treat displayed success as UI state and
apply protected actions when consuming the individual request result.

## Authentication policy

| Mode | Allowed authenticators | Android support |
| --- | --- | --- |
| `BIOMETRIC_ONLY` | Class 3 biometric | API 28+ |
| `BIOMETRIC_OR_DEVICE_CREDENTIAL` | Class 2 or Class 3 biometric, or device PIN, pattern, or password | API 28+ |
| `DEVICE_CREDENTIAL_ONLY` | Device PIN, pattern, or password | API 30+ |
| `STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL` | Class 3 biometric, or device PIN, pattern, or password | API 30+ |

The default allows Class 2 or stronger biometrics and device credentials.
The strong mixed mode returns `UNSUPPORTED` on Android 9–10. Use
`BIOMETRIC_ONLY` for actions that require a confirmed Class 3 biometric without
credential fallback.

`checkAuthenticationAvailability(mode)` uses the same authenticator policy as
the prompt. Its result distinguishes missing enrollment, missing device
credential, incompatible hardware, temporary hardware unavailability, a required
security update, and an unsupported mode. `UNKNOWN` allows a best-effort prompt.
`DEVICE_CREDENTIAL_AVAILABLE` means a credential is configured and a matching
biometric was not confirmed. Lockout is reported by the running prompt as a
separate failure reason.

`VerifiedAuthenticationMethod.DEVICE_CREDENTIAL` covers PIN, pattern, and
password. When Android 10 reports an ambiguous success for a mixed request,
the result is `UNKNOWN`. Use the reported method to drive method-specific app
behavior.

### Retry, prompt text, and settings

- `FAIL_IMMEDIATELY` (the default) reports `MISMATCH` once and requests that the
  prompt close. An overlapping request while the prompt is active or closing
  receives `IN_PROGRESS`. `ALLOW_RETRY` keeps the prompt active after a mismatch.
- On Android 10, a rapid retry waits for the previous prompt's 600 ms restart
  window to expire. Coroutine cancellation during that wait discards the request
  before AndroidX receives it.
- `BiometricsManager.create(activity)` loads prompt text for the Activity locale.
  Library-generated legacy error text also uses the Activity locale captured at
  request start, without retaining the Activity in its result callback.
  Pass a `BiometricsConfig` with your own resource strings to customize it. The
  title must be non-blank. The cancel button label is required for biometric-only
  prompts. Invalid prompt text is reported as `INVALID_CONFIGURATION`.
- `confirmationRequired` defaults to `false` and is a platform hint for passive
  biometrics on API 29+. The platform can ignore it.
- `tryOpenSecuritySettings()` returns whether the system settings Activity was
  opened. Refresh availability when the app resumes.
- Decide whether to show an app-owned PIN screen before requesting system
  authentication. The library reports only system authentication results.

## Existing callback API

The four `authenticate` overloads, `BiometricsCallback`, `AuthenticationMethod`,
`checkAvailability`, and `openSecuritySettings` remain available. The existing
`startAuthentication`, `reconnectAuthentication`, and `awaitAuthentication`
signatures without an ID also remain available. ID-free reconnection accepts
only ID-free requests, so it cannot consume a result from the request-ID API.
Use the ID overloads for protected actions that need request ownership.
`onSuccess(authenticationMethod)` calls a legacy `onSuccess()` override by
default. `AuthenticationMethod.PIN` remains the legacy name for all system device
credentials. Existing disabled-option calls continue to invoke
`onBiometricDisabled` on the calling thread when provided. Enabled requests
deliver their result on the main thread.

`checkAvailability` adapts the detailed preflight to its historical enum
buckets. Use `checkAuthenticationAvailability` for exact preflight causes and
`AuthenticationOutcome.Failure.reason` for recovery decisions. The legacy
`TEMPORARILY_LOCKED` and `PERMANENTLY_LOCKED` availability buckets represented
hardware and update failures; the typed API distinguishes them from prompt lockout.
Handle `AUTHENTICATOR_UNSUPPORTED` when recompiling an exhaustive `when` written
against the historical `release` enum.

Legacy callbacks receive AndroidX numeric codes for platform errors. Library codes
include `ERROR_NO_SECURITY` (-100), `ERROR_HARDWARE_NOT_SUPPORTED` (-101),
`ERROR_AUTHENTICATION_FAILED` (-104), `ERROR_AUTHENTICATION_IN_PROGRESS` (-105),
and `ERROR_INVALID_CONFIGURATION` (-106). The two historical lockout constants
remain in the API. Actual prompt lockouts retain the AndroidX error codes.
Use the reconnectable typed API when the Activity's UI callback needs to be
replaced across recreation.

## Verification

The `app` module demonstrates mode selection, availability, retry policy, and
app-owned PIN handoff. The library and app have JVM regression tests, and the
sample has Android instrumented tests. The independent `consumer-smoke` project
builds Kotlin and Java callers against the published AAR and its metadata.

The Android verification workflow also runs the demo, request API, and real
credential-prompt test classes on a disposable API 30 Pixel 4 emulator. It sets a
synthetic test PIN, rejects missing or skipped test classes, and retains reports
for seven days. The Android 10 restart-delay regression remains an opt-in test
for the matching AndroidX model-delay configuration described below.

```sh
./gradlew :biometricslib:testDebugUnitTest :biometricslib:lintDebug \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Use `./gradlew :app:connectedDebugAndroidTest` for the sample's device tests.
Run the real credential prompt regressions on a dedicated API 30+ test emulator.
Use its synthetic PIN for `<test-pin>`.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runCredentialPromptTests=true \
  '-Pandroid.testInstrumentationRunnerArguments.credentialPromptTestPin=<test-pin>'
```

On a dedicated Pixel 4 running Android 10, use the same synthetic PIN to run
the regression for cancelling a rapid follow-up request before its prompt opens.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.delightlabs.biometrics.Android10PromptCancellationTest \
  -Pandroid.testInstrumentationRunnerArguments.runAndroid10PromptCancellationTest=true \
  '-Pandroid.testInstrumentationRunnerArguments.credentialPromptTestPin=<test-pin>'
```

For an API 29 emulator with a generic model name, a separate test-only debug
build can add its reported model to AndroidX Biometric 1.1.0's merged
`delay_showing_prompt_models` resource. Pass
`-Pandroid.testInstrumentationRunnerArguments.allowEmulatorModel=true` for
that build. The test checks that AndroidX is actually delaying restarts and uses
the Android system prompt for both successful authentications.

## License

[MIT](LICENSE)
