plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cc.skysparkle.sparkledns"
    compileSdk = 36

    defaultConfig {
        applicationId = "cc.skysparkle.sparkledns"
        minSdk = 26
        targetSdk = 36
        // Single source of truth: the About screen reads these through BuildConfig.
        // Kept as plain literals so F-Droid can detect new versions. Code = major * 10000 + minor * 100 + patch.
        versionCode = 10006
        versionName = "1.0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Builds from a source tarball must match builds from git (reproducible builds)
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // F-Droid rejects the Google-encrypted dependency metadata block in the APK signature
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
