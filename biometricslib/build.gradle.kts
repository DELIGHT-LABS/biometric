plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.delightlabs.biometricslib"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions.targetSdk = 36
    lint.targetSdk = 36

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // Keep DefaultImpls for existing Kotlin callers while exposing JVM default methods.
        freeCompilerArgs += "-Xjvm-default=all-compatibility"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.androidx.fragment)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    repositories {
        maven {
            name = "verification"
            url = uri(layout.buildDirectory.dir("verification-repository"))
        }
    }
}

afterEvaluate {
    publishing.publications.register<MavenPublication>("release") {
        from(components["release"])
        groupId = "com.github.DELIGHT-LABS.Biometric"
        artifactId = "biometricslib"
        version = if (providers.environmentVariable("JITPACK").orNull == "true") {
            providers.environmentVariable("VERSION").get()
        } else {
            "0.0.0-local"
        }
    }
}
