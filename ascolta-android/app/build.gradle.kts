import java.util.Properties

import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ascolta.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // The store ID (permanent). The code's package stays com.ascolta.app.
        applicationId = "app.lingopod"
        minSdk = 26
        targetSdk = 35
        versionCode = 170
        versionName = "1.7.0"

        // Anonymous usage statistics (TelemetryDeck). Empty = analytics switched off entirely.
        // Set telemetryDeckAppId (and a random telemetryDeckSalt) in gradle.properties.
        fun prop(name: String) = (project.findProperty(name) as String?).orEmpty().replace("\"", "")
        buildConfigField("String", "TELEMETRYDECK_APP_ID", "\"${prop("telemetryDeckAppId")}\"")
        buildConfigField("String", "TELEMETRYDECK_SALT", "\"${prop("telemetryDeckSalt")}\"")
        // Purchase-check server (store/server-setup.md) and its Cloud project number (Play Integrity).
        // Paywall switch: off while testing (everything unlocked, nothing asked to Play). Set
        // lingopodPaywall=true in gradle.properties before publishing.
        buildConfigField("boolean", "PAYWALL_ENABLED", "${prop("lingopodPaywall") == "true"}")
        buildConfigField("String", "PRO_SERVER_URL", "\"${prop("lingopodServerUrl")}\"")
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", "${prop("lingopodCloudProjectNumber").ifBlank { "0" }}L")
        // Public half of the key the server signs Pro passes with (the private half is only on the server).
        buildConfigField("String", "PRO_PASS_PUBLIC_KEY", "\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvMp+KvbL7Wj2Vbv4onr8S93kVMfRSRYeYxN1O3hQmaHEnHIseZ3FV2CtgD6PG7pbPiDL7SOWrdbaEqOBInId9g==\"")

        ndk {
            // arm64 for real phones, x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Upload key for Google Play, read from keystore.properties (git-ignored, never committed):
    //   storeFile=/path/to/lingopod-upload.jks  storePassword=…  keyAlias=upload  keyPassword=…
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Shrinks and obfuscates the code (harder to tamper with); native-called classes are kept.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The upload key when present; otherwise the debug key, for side-loaded test builds.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Screenshot tests (Robolectric + Roborazzi) draw real screens with the app's resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "6g"
            test.systemProperty("roborazzi.test.record", "true")
            // Robolectric's Android runtime comes through Gradle (its own downloader is not used).
            test.dependsOn("robolectricAndroid")
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric").get().asFile.absolutePath)
        }
    }

    androidResources {
        // Lists the app's translations so Android's per-app language setting offers them.
        generateLocaleConfig = true
    }

    packaging {
        resources {
            // Kuromoji's two jars both carry these; the notices are in the app's credits screen.
            pickFirsts += listOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
        jniLibs {
            // Only linked against at build time; the phone's own OpenCL driver is used.
            excludes += listOf("**/libOpenCL.so")
            // sherpa-onnx and onnxruntime-android both carry ONNX Runtime; the versions match (1.24.3).
            pickFirsts += listOf("**/libonnxruntime.so")
            // Extract native libraries to disk: ggml looks for its CPU-variant
            // plug-ins (libggml-cpu-*.so) in the app's native library folder.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// sherpa-onnx (runs NVIDIA Parakeet on the phone) is published as an AAR on its
// GitHub releases, not on Maven. Download the pinned release once and verify it.
val sherpaVersion = "1.12.40"
val sherpaSha256 = "a7699d6a2e638a38791b2459cb0099c7577e6e5fca6b8ae916742c872a618b82"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    val tmp = file("${sherpaAar.path}.tmp")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val digest = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes())
        .joinToString("") { "%02x".format(it) }
    check(digest == sherpaSha256) { "sherpa-onnx AAR checksum mismatch: $digest" }
    tmp.renameTo(sherpaAar)
}

tasks.withType<Test>().configureEach {
    // Folder with downloaded Opus-MT models (hy-en/, en-hy/) for MarianTranslatorTest; skipped if unset.
    System.getenv("OPUS_DIR")?.let { systemProperty("opus.dir", it) }
}

val robolectricAndroid: Configuration by configurations.creating
tasks.register<Copy>("robolectricAndroid") {
    from(robolectricAndroid)
    into(layout.buildDirectory.dir("robolectric"))
}

dependencies {
    robolectricAndroid("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    implementation(files(sherpaAar))
    implementation("androidx.appcompat:appcompat:1.7.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.work:work-runtime-ktx:2.10.2")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")

    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Japanese readings (furigana / romaji): morphological analyzer with the IPADIC dictionary.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // ONNX Runtime's Java API, for the Helsinki-NLP (Opus-MT) translation models. Same version
    // as the runtime inside sherpa-onnx, so one copy of the library serves both.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    // LingoPod Pro: Google Play Billing, and Play Integrity for the server's purchase check.
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.google.android.play:integrity:1.4.0")
    // In-app rating prompt (asked once, after a good review session).
    implementation("com.google.android.play:review-ktx:2.0.2")
    // The desktop build of the same runtime, to test the translator on the computer.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.24.3")
    testImplementation("org.json:json:20240303") // the real JSON parser (Android's is a stub in unit tests)
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
