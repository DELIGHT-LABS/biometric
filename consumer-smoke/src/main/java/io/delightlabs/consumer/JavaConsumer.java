package io.delightlabs.consumer;

import androidx.fragment.app.FragmentActivity;
import io.delightlabs.biometricslib.authentication.BiometricsCallback;
import io.delightlabs.biometricslib.authentication.BiometricsConfig;
import io.delightlabs.biometricslib.authentication.BiometricsManager;
import io.delightlabs.biometricslib.authentication.policy.AuthenticationMode;
import io.delightlabs.biometricslib.authentication.request.AuthenticationRequestId;
import io.delightlabs.biometricslib.authentication.policy.BiometricsRetryPolicy;
import kotlin.Unit;

final class JavaConsumer {
    static void authenticate(FragmentActivity activity, BiometricsCallback callback) {
        BiometricsManager.Companion.create(activity, new BiometricsConfig())
                .authenticate(callback, true, null);
    }

    static boolean authenticateScoped(FragmentActivity activity) {
        AuthenticationRequestId requestId = AuthenticationRequestId.create();
        return BiometricsManager.Companion.create(activity, new BiometricsConfig()).startAuthentication(
                requestId, AuthenticationMode.BIOMETRIC_ONLY, BiometricsRetryPolicy.FAIL_IMMEDIATELY,
                result -> {
                    if (!requestId.equals(result.getRequestId())) {
                        throw new AssertionError("Authentication result has a different request ID");
                    }
                    return Unit.INSTANCE;
                }
        );
    }

    static boolean discardBufferedResult(FragmentActivity activity, AuthenticationRequestId requestId) {
        return BiometricsManager.Companion.create(activity, new BiometricsConfig())
                .discardAuthenticationResult(requestId);
    }

    static AuthenticationRequestId createExpiringRequest() {
        return AuthenticationRequestId.create(30_000L);
    }
}
