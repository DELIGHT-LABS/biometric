package io.delightlabs.consumer;

import androidx.fragment.app.FragmentActivity;
import io.delightlabs.biometricslib.AuthenticationMode;
import io.delightlabs.biometricslib.AuthenticationRequestId;
import io.delightlabs.biometricslib.BiometricsCallback;
import io.delightlabs.biometricslib.BiometricsConfig;
import io.delightlabs.biometricslib.BiometricsManager;
import io.delightlabs.biometricslib.BiometricsRetryPolicy;
import kotlin.Unit;

final class LegacyJavaConsumer {
    static void authenticate(FragmentActivity activity, BiometricsCallback callback) {
        BiometricsManager.Companion.create(activity, new BiometricsConfig())
                .authenticate(callback, true, null);
    }

    static boolean authenticateScoped(FragmentActivity activity) {
        AuthenticationRequestId id = AuthenticationRequestId.create(30_000L);
        return BiometricsManager.Companion.create(activity, new BiometricsConfig()).startAuthentication(
                id, AuthenticationMode.BIOMETRIC_ONLY, BiometricsRetryPolicy.FAIL_IMMEDIATELY,
                result -> {
                    if (id != result.getRequestId()) {
                        throw new AssertionError("The legacy request identity changed");
                    }
                    return Unit.INSTANCE;
                }
        );
    }
}
