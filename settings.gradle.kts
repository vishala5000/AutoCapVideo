plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autocapvideo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autocapvideo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Reads from GitHub Actions environment variables, falls back to debug keystore locally
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // ✅ FFmpeg Kit (Actively maintained fork, drop-in replacement for retired arthenica package)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
    
    // ✅ Sherpa ONNX for Piper TTS (Fetched via JitPack)
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.8")
    
    // OkHttp for downloading models from GitHub Releases
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
