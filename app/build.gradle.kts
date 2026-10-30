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
        versionCode = 10100
        versionName = "1.1.0"
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

    androidResources {
        // Only the languages the app itself is translated to; library strings for other
        // languages were unused weight, as the app falls back to English there anyway
        localeFilters += listOf("en", "de", "ja", "lt", "ru", "zh")
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

    testOptions {
        // android.util.Log and friends are no-ops in JVM unit tests
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Already pulled in by Compose at this version; declared because the app uses it directly
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Debug builds only, never part of a release
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    testImplementation("junit:junit:4.13.2")
}
