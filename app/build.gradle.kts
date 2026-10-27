plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.ams.mgr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ams.mgr"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "1.56"
        // The production phones targeted by this app are ARM Android devices.
        // Keep ARM32 + ARM64 support; exclude x86/x86_64 native libraries to reduce APK size.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Keep bundled face/model/report files outside a folder named "assets" in the source ZIP.
    // Android still packages them as application assets at build time.
    sourceSets["main"].assets.srcDir("src/main/app_files")

    // Release is optimized to reduce the final APK size.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Release signing is supplied by GitHub Actions through environment variables.
    // Keep the keystore out of the repository.
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

    if (!releaseStoreFile.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes.getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // Keep Java, Kotlin, and KSP on the same JVM target.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

kotlin {
    jvmToolchain(17)
}
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Offline face pipeline: bundled ML Kit detector + TensorFlow Lite inference.
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // CameraX ProcessCameraProvider exposes Guava ListenableFuture on the public API.
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    // Make Room migration/database support types explicit for KSP on CI.
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    ksp("androidx.room:room-compiler:2.7.2")
}
