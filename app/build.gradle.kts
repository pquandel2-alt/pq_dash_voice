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
        versionCode = 53
        versionName = "2.11.0"

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
    // Video-Avatar: gapless Clip-Wiedergabe + HA-WebSocket
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // echte org.json-Implementierung für JVM-Unittests (android.jar enthält nur Stubs)
    testImplementation("org.json:json:20240303")
}
