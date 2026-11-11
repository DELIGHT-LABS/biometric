package io.delightlabs.consumer;

import androidx.fragment.app.FragmentActivity;
import io.delightlabs.biometricslib.BiometricsCallback;
import io.delightlabs.biometricslib.BiometricsConfig;
import io.delightlabs.biometricslib.BiometricsManager;
import io.delightlabs.biometricslib.AuthenticationMode;
import io.delightlabs.biometricslib.AuthenticationRequestId;
import io.delightlabs.biometricslib.BiometricsRetryPolicy;
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
}
