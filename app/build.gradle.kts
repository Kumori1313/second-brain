plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.loam"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.loam"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    androidResources {
        // ORT maps the model straight off the APK; compressing it would force a
        // full decompress into RAM first.
        noCompress += listOf("onnx", "txt")
    }

    packaging {
        jniLibs {
            // Must be repeated here. :llama sets the same flag, but packaging
            // options are per-APK and do not propagate from a library module —
            // verified by finding extractNativeLibs=false in the built APK
            // after :llama already had it set.
            //
            // llama.cpp's CPU backends are dlopen'd by absolute path, which
            // needs real files on disk. Left at the modern default the
            // libraries stay inside the APK, the JNI shim still loads through
            // the classloader, and the failure appears later as "no backends
            // are loaded" at model load. :llama's own instrumented tests would
            // not have caught this, since they build their own APK.
            useLegacyPackaging = true
        }
    }
}

/**
 * Bundles the embedding model into the APK.
 *
 * The model is not committed — `models/` is gitignored, since carrying weights
 * in-repo muddies the license story Phase 4 has to answer. Copying at build
 * time is what makes Phase 1's "zero network permission" exit criterion
 * reachable: there is no download code to write, because the weights are
 * already inside the APK.
 */
abstract class BundleModel : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val modelFiles: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val src = sourceDir.get().asFile
        val missing = REQUIRED.filterNot { src.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Embedding model incomplete at $src — missing ${missing.joinToString()}.\n" +
                    "Fetch it per the roadmap's 'Model files' prerequisites. The app " +
                    "cannot download it at runtime by design: it holds no INTERNET permission."
            )
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        REQUIRED.forEach { src.resolve(it).copyTo(out.resolve(it), overwrite = true) }
    }

    companion object {
        val REQUIRED = listOf("model_qint8_arm64.onnx", "vocab.txt")
    }
}

val modelDir = rootProject.layout.projectDirectory.dir("models/all-MiniLM-L6-v2")

// `by tasks.registering(T::class)` rather than this was deprecated in Gradle 9
// and is removed in Gradle 10. Since this task is what puts the embedding model
// into the APK, that would have broken the build outright rather than warned.
val bundleModel = tasks.register<BundleModel>("bundleModel") {
    description = "Copies the embedding model into the APK's assets."
    sourceDir.set(modelDir)
    modelFiles.setFrom(BundleModel.REQUIRED.map { modelDir.file(it) })
    outputDir.set(layout.buildDirectory.dir("generated/modelAssets"))
}

androidComponents {
    onVariants { variant ->
        // The Variant API carries the task dependency automatically; adding a
        // plain srcDir provider is rejected outright by AGP 9.
        variant.sources.assets?.addGeneratedSourceDirectory(bundleModel, BundleModel::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llama"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    // The Ask pane is a pure function of UiState, so it can be driven directly
    // with fabricated state — no ViewModel, no engine, no database. That is the
    // whole reason it is worth testing at this level: the state machine is the
    // part with branches, and it needs none of the heavy machinery to exercise.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // androidx.test:monitor 1.8.0 reflects on InputManager.getInstance, which
    // no longer exists on Android 17 — every Compose test fails to start with
    // NoSuchMethodException before reaching an assertion.
    androidTestImplementation("androidx.test:monitor:1.9.0-alpha01")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
