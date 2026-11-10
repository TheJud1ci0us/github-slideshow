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
        applicationId = "com.ascolta.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.11.5"

        // Anonymous usage statistics (TelemetryDeck). Empty = analytics switched off entirely.
        // Set telemetryDeckAppId (and a random telemetryDeckSalt) in gradle.properties.
        fun prop(name: String) = (project.findProperty(name) as String?).orEmpty().replace("\"", "")
        buildConfigField("String", "TELEMETRYDECK_APP_ID", "\"${prop("telemetryDeckAppId")}\"")
        buildConfigField("String", "TELEMETRYDECK_SALT", "\"${prop("telemetryDeckSalt")}\"")

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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release APK can be side-loaded for testing.
            // Replace with your own signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
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
        unitTests.all { test ->
            test.maxHeapSize = "6g"
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

dependencies {
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
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
