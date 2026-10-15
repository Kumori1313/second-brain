import java.util.Properties

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
        versionCode = 4
        versionName = "1.0.3"

        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repo. Each value is read from
    // local.properties (gitignored) as `loam.<name>`, else from the matching
    // LOAM_<NAME> environment variable:
    //   loam.keystore / LOAM_KEYSTORE                    path to the .jks
    //   loam.keystorePassword / LOAM_KEYSTORE_PASSWORD
    //   loam.keyAlias / LOAM_KEY_ALIAS
    //   loam.keyPassword / LOAM_KEY_PASSWORD             defaults to the store password
    //
    // Prefer local.properties. A running Gradle daemon can keep serving the
    // environment it started with, so changing LOAM_* between builds may be
    // ignored until `./gradlew --stop`.
    //
    // Losing this key means no later release can update an existing install,
    // so it is backed up outside this machine, and never committed.
    val signingProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun signingValue(prop: String, env: String): String? =
        signingProps.getProperty("loam.$prop") ?: System.getenv(env)

    val releaseKeystore = signingValue("keystore", "LOAM_KEYSTORE")?.let { rootProject.file(it) }
    val releaseKeystorePassword = signingValue("keystorePassword", "LOAM_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "LOAM_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "LOAM_KEY_PASSWORD") ?: releaseKeystorePassword
    val hasReleaseKey = releaseKeystore?.isFile == true &&
        releaseKeystorePassword != null && releaseKeyAlias != null

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key so a checkout without the release
            // key still builds — but that APK cannot update, or be updated by,
            // a release-signed one (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so it
            // is never a release. The warning below is how that is kept from
            // being forgotten.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: no release signing key configured; signing the release " +
                        "build with the DEBUG key. Set loam.keystore, loam.keystorePassword " +
                        "and loam.keyAlias in local.properties (or LOAM_* env vars).",
                )
                signingConfigs.getByName("debug")
            }
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
    // Overrides the 1.2.5 that biometric resolves to; see libs.versions.toml.
    implementation(libs.androidx.fragment)
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
