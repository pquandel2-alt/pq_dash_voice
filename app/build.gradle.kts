plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.quandel.dashvoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.quandel.dashvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 32
        versionName = "2.8.3"

        // MatePad DBY-W09 is arm64; restrict to keep the APK small (ONNX libs are large).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Android-Stubs (Log, Handler, Looper …) geben Defaultwerte statt "Stub!"-Exception —
            // AppLog nutzt android.util.Log/Handler und läuft sonst in reinen JVM-Unittests nicht.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    // On-Device-Spracherkennung für Sofort-Befehle (offline, an Whisper vorbei)
    implementation("com.alphacephei:vosk-android:0.3.47")

    testImplementation("junit:junit:4.13.2")
}
