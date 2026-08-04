plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.loam.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room's generated schemas are checked in, so a migration that would
        // drop a user's index shows up in review as a schema diff.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ONNX Runtime ships four ABIs (~113MB of natives). Phase 4 will want a
    // proper ABI split; for now arm64 keeps install times sane. Add "x86_64"
    // here to run on an emulator.
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    // Flow appears in :core's public API (IndexStats), so it must be `api`.
    api(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    api(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
