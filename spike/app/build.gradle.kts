// AGP 9 has built-in Kotlin support — applying org.jetbrains.kotlin.android
// here is now an error, not just redundant. The Compose compiler plugin is
// still required separately, though (Kotlin 2.0+).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.loam.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.loam.spike"
        // API 26 floor: AndroidX Biometric wants 23+, and 26 keeps the
        // eventual biometric-unlock story simple. See roadmap prerequisites.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-spike"

        // ONNX Runtime ships four ABIs (~113MB of natives). The spike only
        // ever runs on a real arm64 phone, so drop the rest — a 125MB debug
        // APK is slow to install and reinstall while iterating.
        // Add "x86_64" here if you want to run this on an emulator.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Spike-only: sign release with the debug key so the benchmark can
            // be installed without a keystore. A debuggable build is not a
            // valid place to measure — `debuggable true` forces deoptimization
            // support and blocks ART inlining, so the hot dot-product loop
            // never reaches steady-state codegen. Never ship this config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The spike loads its model from external files dir via adb push, not
    // from assets — keeps a 22MB blob out of the APK and out of git.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.documentfile)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
