plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.hemel.lenspilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hemel.lenspilot"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0-classic-signin"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Firebase (BOM keeps every Firebase library on matching versions)
    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))
    implementation("com.google.firebase:firebase-auth-ktx")

    // Google Sign-In via the classic GoogleSignInClient API (more
    // compatible with non-standard/sideloaded Google Play services)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Play Integrity (Classic API — matches the Space backend's
    // decodeIntegrityToken verification in verify_integrity_token())
    implementation("com.google.android.play:integrity:1.4.0")

    // Talking to the Hugging Face Space backend
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lets us .await() a Firebase Task (getIdToken) inside a coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
