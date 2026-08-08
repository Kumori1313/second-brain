plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.loam.llama"
    compileSdk = 37

    // Pinned rather than left to AGP's default, which picked r28 here. The
    // roadmap's prerequisites specify the r27 LTS, every Phase 2 measurement
    // was taken with it, and a codegen difference in these kernels would be
    // invisible except as a throughput change — which is exactly the kind of
    // thing this project has already been caught by.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // 16 KB page alignment. Android 15+ can run with 16 KB
                    // pages, and a library whose LOAD segments are aligned to
                    // 4 KB will not load there at all — the app does not
                    // degrade, it fails. Every prebuilt dependency here
                    // (ONNX Runtime, SQLCipher, libc++) already ships aligned;
                    // only what this module compiles was not.
                    //
                    // Opt-in on NDK r27 and the default from r28. The pin stays
                    // at r27 because every Phase 2 measurement was taken with
                    // it, and this flag changes linking rather than codegen.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Measured, not assumed: the armv9 CPU backends load fine and run 1.79x
    // SLOWER than armv8.6_1 on a Tensor G3, because its SVE is 128-bit and the
    // SVE2 kernels lose to NEON+i8mm. ggml picks by feature count, so the only
    // way to stop it choosing them is not to ship them. Dispatch then falls
    // back to armv8.6_1. See the roadmap for the numbers.
    packaging {
        jniLibs {
            excludes += "**/libggml-cpu-android_armv9*.so"

            // Required, not a preference. A GGML_BACKEND_DL build dlopen's its
            // CPU backends by absolute path, and the modern default keeps
            // native libraries compressed inside the APK and maps them in
            // place — the loader logs `base.apk!/lib/arm64-v8a/...`, with no
            // file on disk to open. System.loadLibrary still works through the
            // classloader, so the JNI shim loads fine and then ggml reports
            // "no backends are loaded" at model load, several layers away from
            // the cause. Extracting the libraries costs install footprint and
            // makes runtime dispatch possible at all.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The LlmEngine interface this module implements.
    api(project(":core"))

    // Native code cannot be unit-tested on the JVM, and the failures worth
    // catching here — a missing CPU backend, mmap refusing an fd path, a wrong
    // chat template — only appear on a real device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
