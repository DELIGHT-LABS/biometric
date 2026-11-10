package io.delightlabs.biometricslib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import kotlin.jvm.functions.Function0;
import org.junit.Test;

public class BiometricsApiCompatibilityTest {
    @Test
    public void typedSuccessCallsLegacyJavaCallback() throws NoSuchMethodException {
        assertTrue(
                BiometricsCallback.class.getMethod("onSuccess", AuthenticationMethod.class).isDefault()
        );
        LegacyCallback callback = new LegacyCallback();
        BiometricsCallback publicCallback = callback;

        publicCallback.onSuccess(AuthenticationMethod.BIOMETRIC);

        assertEquals(1, callback.successCount);
    }

    @Test
    public void keepsKotlinSuccessDefaultBridges() throws ReflectiveOperationException {
        Class<?> defaults = Class.forName(BiometricsCallback.class.getName() + "$DefaultImpls");
        assertTrue(Modifier.isStatic(
                defaults.getMethod("onSuccess", BiometricsCallback.class).getModifiers()
        ));
        assertTrue(Modifier.isStatic(
                defaults.getMethod("onSuccess", BiometricsCallback.class, AuthenticationMethod.class)
                        .getModifiers()
        ));
    }

    @Test
    public void keepsReleasedAndRetryAwareAuthenticateSignatures() throws NoSuchMethodException {
        assertAuthenticateSignature();
        assertAuthenticateSignature(BiometricsType.class);
        assertAuthenticateSignature(BiometricsRetryPolicy.class);
        assertAuthenticateSignature(BiometricsType.class, BiometricsRetryPolicy.class);
    }

    @Test
    public void preservesReleasedAvailabilityEnumOrdinals() {
        assertEquals(0, BiometricsAvailability.BIOMETRIC_AVAILABLE.ordinal());
        assertEquals(1, BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE.ordinal());
        assertEquals(2, BiometricsAvailability.BIOMETRIC_NOT_ENROLLED.ordinal());
        assertEquals(3, BiometricsAvailability.NO_SECURITY_CONFIGURED.ordinal());
        assertEquals(4, BiometricsAvailability.HARDWARE_NOT_SUPPORTED.ordinal());
        assertEquals(5, BiometricsAvailability.TEMPORARILY_LOCKED.ordinal());
        assertEquals(6, BiometricsAvailability.PERMANENTLY_LOCKED.ordinal());
    }

    private static void assertAuthenticateSignature(Class<?>... options)
            throws NoSuchMethodException {
        Class<?>[] parameters = new Class<?>[options.length + 3];
        parameters[0] = BiometricsCallback.class;
        System.arraycopy(options, 0, parameters, 1, options.length);
        parameters[parameters.length - 2] = boolean.class;
        parameters[parameters.length - 1] = Function0.class;
        Method authenticate = BiometricsManager.class.getMethod("authenticate", parameters);
        assertEquals(void.class, authenticate.getReturnType());

        Class<?>[] defaultParameters = new Class<?>[parameters.length + 3];
        defaultParameters[0] = BiometricsManager.class;
        System.arraycopy(parameters, 0, defaultParameters, 1, parameters.length);
        defaultParameters[defaultParameters.length - 2] = int.class;
        defaultParameters[defaultParameters.length - 1] = Object.class;
        Method defaultBridge = BiometricsManager.class.getMethod("authenticate$default", defaultParameters);
        assertEquals(void.class, defaultBridge.getReturnType());
        assertTrue(Modifier.isStatic(defaultBridge.getModifiers()));
    }

    private static final class LegacyCallback implements BiometricsCallback {
        private int successCount;

        @Override
        @SuppressWarnings("deprecation")
        public void onSuccess() {
            successCount++;
        }

        @Override
        public void onError(int errorCode, String errorMessage) {}

        @Override
        public void onCancel() {}
    }
}
