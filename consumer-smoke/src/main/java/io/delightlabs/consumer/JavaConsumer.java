package io.delightlabs.consumer;

import androidx.fragment.app.FragmentActivity;
import io.delightlabs.biometricslib.BiometricsCallback;
import io.delightlabs.biometricslib.BiometricsConfig;
import io.delightlabs.biometricslib.BiometricsManager;

final class JavaConsumer {
    static void authenticate(FragmentActivity activity, BiometricsCallback callback) {
        BiometricsManager.Companion.create(activity, new BiometricsConfig())
                .authenticate(callback, true, null);
    }
}
