plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.delightlabs.consumer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.delightlabs.consumer"
        minSdk = 28
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.github.DELIGHT-LABS.Biometric:biometricslib:0.0.0-local")
}
