plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Ab Kotlin 2.0 liefert dieses Plugin den Compose-Compiler (ersetzt
    // composeOptions.kotlinCompilerExtensionVersion in android{...}).
    id("org.jetbrains.kotlin.plugin.compose")
}

// LLM-Endpoint (E2BAIService) für Benchmark-Läufe überschreibbar:
//   ./gradlew assembleBenchmark -Pe2bUrl=http://localhost:8080
// zusammen mit `adb reverse tcp:8080 tcp:8080` läuft der Closed-Loop-Benchmark
// damit auch auf physischer Hardware (dort ist der Emulator-Alias 10.0.2.2 nicht
// erreichbar). Default "" => unverändertes Verhalten (Emulator nutzt 10.0.2.2).
val e2bUrl: String = (project.findProperty("e2bUrl") as String?)?.trim() ?: ""

android {
    namespace = "com.example.gemma_live"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gemma_live"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-live"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildConfigField("String", "E2B_API_KEY", "\"\"")
            buildConfigField("String", "E2B_CONTAINER_URL", "\"$e2bUrl\"")
            buildConfigField("boolean", "BENCHMARK_MODE", "false")
        }
        debug {
            buildConfigField("String", "E2B_API_KEY", "\"\"")
            buildConfigField("String", "E2B_CONTAINER_URL", "\"$e2bUrl\"")
            buildConfigField("boolean", "BENCHMARK_MODE", "false")
            isMinifyEnabled = false
        }
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            // -Pe2bUrl muss auch im inkrementellen Build greifen, daher im
            // Init-Block (nach initWith) erneut setzen — matchingFallbacks allein
            // übernimmt den Wert nicht zuverlässig.
            buildConfigField("String", "E2B_CONTAINER_URL", "\"$e2bUrl\"")
            buildConfigField("boolean", "BENCHMARK_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    androidResources {
        noCompress += listOf(".onnx", ".onnx.json", ".litertlm")
    }
}

// Ab Kotlin 2.x ist `kotlinOptions.jvmTarget` ein Fehler; das compilerOptions-DSL
// ist der Ersatz (siehe kotl.in/u1r8ln).
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Retrofit + OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Piper TTS Runtime & Silero VAD (sherpa-onnx)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // LiteRT-LM (On-Device-Inferenz, Gemma 4 E2B) — AAR liegt in app/libs,
    // weil com.google.ai.edge.litertlm:litertlm-android nicht auf Maven Central
    // liegt, sondern auf Google Maven (dl.google.com/dl/android/maven2).
    implementation(files("libs/litertlm-android-0.17.1.aar"))
    // Gson ist eine harte Laufzeit-Abhängigkeit des AAR (Message.toJson) und wird
    // von `files(...)` nicht automatisch mitgezogen.
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
