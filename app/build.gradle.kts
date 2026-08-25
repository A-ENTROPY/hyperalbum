import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.smartvision.gallery"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.smartvision.gallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "V1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Native .so is prebuilt under src/main/jniLibs/ — skip CMake to
        // work around STATUS_STACK_BUFFER_OVERFLOW on this dev machine.
        // To rebuild: run cmake + ninja from bash manually.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // for unsigned demo builds
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            useLegacyPackaging = false
            // Keep our native libs uncompressed so the system can mmap them.
            keepDebugSymbols += listOf("**/libsmartvision_decoder.so", "**/libavif.so", "**/libjxl.so")
        }
    }

    androidResources {
        // Disable per-app language config; we ship via in-app language switcher instead.
        generateLocaleConfig = false
    }

    aaptOptions {
        // Keep large model binaries uncompressed so the APK-install path can mmap them
        // instead of holding a full bytearray. Cuts first-load OOM risk for the 376MB
        // WD-tagger ONNX + 68MB MobileCLIP FP16.
        noCompress("tflite", "onnx")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
        // Lint 自身的 UAST bug（NonNullableMutableLiveDataDetector 与新版 Kotlin
        // 不兼容，IncompatibleClassChangeError）。与业务代码无关，禁用以免阻塞 release 构建。
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    // Core / Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)

    // Biometric + Fragment for privacy vault
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Media / Imaging
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.palette)

    // Room (DB layer for scanned media metadata)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // AI / ML — Real model loading. Three on-device TFLite models (~113MB total)
    // plus MLKit Face Detection (bundled, no GMS required).
    implementation(libs.tflite)
    // tflite-support omitted: latest 0.5.0 pulls litert 1.0.1 which conflicts with tflite 2.14.0
    implementation(libs.tflite.gpu)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.image.labeling)

    // ONNX Runtime — WD-ConvNeXt-v3 Danbooru tagger ships as ONNX (376MB).
    // FP16 model file stays uncompressed in APK to mmap on load.
    implementation(libs.onnxruntime)

    // Liquid Glass — Kyant0 backdrop + Capsule shape (the iOS 26 pill).
    // Real GPU backdrop sampling via GraphicsLayer; works on API 26+
    // (with software fallback to vibrancy+tint when AGSL is unavailable).
    implementation(libs.backdrop)
    implementation(libs.capsule)

    // Lucide icons (SF-Symbols-style open-source icon set) for the photo viewer chrome.
    implementation(libs.lucideCompose)

    // Networking (cloud sync + LAN sharing)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.nanohttpd)

    // Logging
    implementation(libs.timber)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    // Telephoto — ZoomableAsyncImage + SubSamplingImage (tiled 40× zoom)
    implementation(libs.telephoto.zoomable.image.coil)
    implementation(libs.telephoto.sub.sampling.image)

    // Media3 (ExoPlayer) for video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // SMB/CIFS client for LAN shared folder access
    implementation(libs.jcifsng)
    implementation(libs.slf4j.nop)
    implementation(libs.security.crypto)

    // osmdroid — interactive map tiles for photo location
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Core library desugaring (for java.time on minSdk 26)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.truth)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// (Removed: AGP 8.7.3 + Kotlin 2.2 unit test classpath workaround attempts did not resolve the
// pre-existing ClassNotFoundException issue, which also blocks every other test in the project.
// Test code compiles cleanly; execution requires a project-wide AGP/Kotlin upgrade.)