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
    
    // ✅ CORRECTED: Actively maintained FFmpeg Kit fork (replaces retired com.arthenica)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
    
    // ✅ CORRECTED: Sherpa ONNX via JitPack (Notice the "github" in the group ID)
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.8")
    
    // OkHttp for downloading models
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
