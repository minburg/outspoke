import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

configure<ApplicationExtension> {
    namespace = "dev.brgr.outspoke"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.brgr.outspoke"
        minSdk = 30
        targetSdk = 37
        versionCode = 12
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }

    packaging {
        // Deflate native libs in the APK (extractNativeLibs=true): IzzyOnDroid
        // budgets ~30 MB per app, and libonnxruntime.so alone is ~28 MB when
        // stored uncompressed. Costs ~28 MB extra on-device after install —
        // negligible next to the 700 MB ASR model the app downloads at runtime.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}



val abiVersionCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a"   to 2,
    "x86"         to 3,
    "x86_64"      to 4
)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiFilter = output.filters
                .firstOrNull {
                    it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
                }?.identifier
            val offset = abiVersionCodes[abiFilter] ?: 0
            output.versionCode.set((android.defaultConfig.versionCode ?: 1) * 10 + offset)
        }
    }
}

// ── Generate synthetic test WAV fixtures ──────────────────────────────────
// Run: ./gradlew generateTestAudio
tasks.register<DefaultTask>("generateTestAudio") {
    description = "Generate synthetic WAV files for real-audio integration tests."
    doLast {
        val audioDir = layout.buildDirectory.dir("generated/test-audio/audio").get().asFile
        audioDir.mkdirs()

        fun writeWav(path: String, samples: ShortArray, sampleRate: Int = 16_000, channels: Int = 1) {
            val dataLen = samples.size * 2
            val fileLen = 36 + dataLen
            val out = BufferedOutputStream(FileOutputStream(path))
            val bb = ByteBuffer.allocate(44 + dataLen)
            bb.order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header — tags are 4 ASCII bytes in a little-endian buffer,
            // so the int constants are the LE encoding of the tag (e.g. "RIFF" → 0x46464952).
            bb.putInt(0x46464952) // "RIFF"
            bb.putInt(fileLen)
            bb.putInt(0x45564157) // "WAVE"
            // fmt chunk
            bb.putInt(0x20746D66) // "fmt "
            bb.putInt(16)         // chunk size
            bb.putShort(1.toShort()) // PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * channels * 2) // byte rate
            bb.putShort((channels * 2).toShort()) // block align
            bb.putShort(16.toShort())             // bits per sample
            // data chunk
            bb.putInt(0x61746164) // "data"
            bb.putInt(dataLen)
            for (s in samples) bb.putShort(s)
            out.write(bb.array())
            out.close()
            println("  wrote $path (${samples.size} samples, ${samples.size / sampleRate / channels}s)")
        }

        println("Generating test WAV fixtures...")
        // silence-only.wav — 3 s of silence at 16 kHz mono
        writeWav("$audioDir/silence-only.wav", ShortArray(16_000 * 3))

        // noise-only.wav — 3 s of white noise (uniform random ±1000) at 16 kHz mono
        val noise = ShortArray(16_000 * 3) { (Math.random() * 2000 - 1000).toInt().toShort() }
        writeWav("$audioDir/noise-only.wav", noise)

        // Speech fixtures (hello-world, single-yes, silence-then-speech, …) are
        // pre-recorded WAVs checked into app/src/test/resources/audio/ and are NOT
        // regenerated here.

        // whispered.wav — 0.5 s of very low energy (near-silence ±20) for low-energy speech test
        val whispered = ShortArray(16_000 / 2) { (Math.random() * 40 - 20).toInt().toShort() }
        writeWav("$audioDir/whispered.wav", whispered)

        println("Done. Place real speech WAVs alongside these in app/src/test/resources/audio/")
    }
}

// Copy generated test audio into the test resources so they are on the classpath
tasks.register<Copy>("copyGeneratedTestAudio") {
    description = "Copy generated test WAV files into test resources."
    dependsOn("generateTestAudio")
    from(layout.buildDirectory.dir("generated/test-audio/audio"))
    into("src/test/resources/audio")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // Real-audio integration tests: JVM ONNX Runtime + Robolectric for android.util.Log shim
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ── Test task configuration ───────────────────────────────────────────────
// Real-audio tests need extra heap for the ONNX model (~700 MB). They skip
// themselves (JUnit Assume) when no model directory is found — see
// RealAudioTestUtils.resolveModelDir — so CI always runs the model-free suite.
tasks.withType<Test> {
    maxHeapSize = "2G"
    systemProperty("test.model.dir", project.findProperty("test.model.dir") as String?)
}
