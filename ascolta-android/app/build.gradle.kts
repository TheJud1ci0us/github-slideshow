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
        versionCode = 10
        versionName = "0.5.0"

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
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            listOf("parakeetNpuDir", "parakeetTokens").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
            test.maxHeapSize = "6g"
        }
    }

    androidResources {
        // Lists the app's translations so Android's per-app language setting offers them.
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // Qualcomm runtime: only the NPU (HTP) backend is used; drop the others.
            excludes += listOf("**/libQnnGpu*.so", "**/libQnnDsp*.so", "**/libQnnCpu.so", "**/libQnnSaver.so", "**/libQnnLpai*.so")
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

// ONNX Runtime with Qualcomm's QNN execution provider, for the NPU version of Parakeet.
// sherpa-onnx ships its own ONNX Runtime (1.24, symbol-versioned) under the same file name,
// so this copy is renamed to libonnxruntimq.so (same length, patched in place) and the app
// tells ONNX Runtime's Java code to skip loading the default name. Both then coexist.
val ortQnnVersion = "1.29.0"
val ortQnnSha256 = "cc4cfd72a47703aee65d8e6ce7448fa7488e3fff6556f9d6d354ec1687347cfd"
val ortQnnAar = file("libs/onnxruntime-android-qnn-$ortQnnVersion-renamed.aar")
if (!ortQnnAar.exists()) {
    ortQnnAar.parentFile.mkdirs()
    val url = "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/" +
        "$ortQnnVersion/onnxruntime-android-qnn-$ortQnnVersion.aar"
    logger.lifecycle("Downloading $url")
    val bytes = URI(url).toURL().openStream().use { it.readBytes() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(digest == ortQnnSha256) { "onnxruntime-android-qnn checksum mismatch: $digest" }
    val from = "libonnxruntime.so\u0000".toByteArray(Charsets.ISO_8859_1)
    val to = "libonnxruntimq.so\u0000".toByteArray(Charsets.ISO_8859_1)
    fun ByteArray.replaceAll(a: ByteArray, b: ByteArray): ByteArray {
        val out = copyOf()
        var i = 0
        while (i <= out.size - a.size) {
            var match = true
            for (k in a.indices) if (out[i + k] != a[k]) { match = false; break }
            if (match) { System.arraycopy(b, 0, out, i, b.size); i += a.size } else i++
        }
        return out
    }
    ZipInputStream(bytes.inputStream()).use { zin ->
        ZipOutputStream(ortQnnAar.outputStream()).use { zout ->
            while (true) {
                val entry = zin.nextEntry ?: break
                var name = entry.name
                var data = zin.readBytes()
                if (name.endsWith("/libonnxruntime.so") || name.endsWith("/libonnxruntime4j_jni.so")) {
                    data = data.replaceAll(from, to)
                    name = name.replace("/libonnxruntime.so", "/libonnxruntimq.so")
                }
                zout.putNextEntry(ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
        }
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(files(ortQnnAar))
    // Qualcomm's NPU runtime (HTP backend) used by ONNX Runtime's QNN provider.
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0") // the version Qualcomm exported the NPU model with
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
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop ONNX Runtime so the NPU engine's code can be checked on a computer (CPU).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
