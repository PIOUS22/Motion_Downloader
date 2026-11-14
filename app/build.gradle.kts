plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version bumps automatically on every GitHub build: 1.0.<run number>
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.mediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mediadownloader"
        minSdk = 24
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        // GitHub repo that publishes releases (used by the in-app updater). Must be public.
        buildConfigField("String", "UPDATE_REPO", "\"PIOUS22/Motion_Downloader\"")
        // Phones only (arm). Add "x86_64" if you want to run it on an emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Fixed key kept in the repo so every build can update the previous install.
        create("release") {
            storeFile = file("release.jks")
            storePassword = "mediadl123"
            keyAlias = "mediadl"
            keyPassword = "mediadl123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Required by youtubedl-android (python + ffmpeg are shipped as native libs).
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
