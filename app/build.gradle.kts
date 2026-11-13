plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cc.skysparkle.sparkledns"
    // The current AndroidX libraries require it
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.skysparkle.sparkledns"
        minSdk = 26
        targetSdk = 36
        // Single source of truth: the About screen reads these through BuildConfig.
        // Kept as plain literals so F-Droid can detect new versions. Code = major * 10000 + minor * 100 + patch.
        versionCode = 10105
        versionName = "1.1.5"
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

    packaging {
        resources {
            // Library files the app never reads: Kotlin metadata for reflection, the coroutines debug
            // agent, version markers for tooling and OkHttp's public suffix list, which only cookies use
            excludes += listOf(
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/kotlin/**",
                "/kotlin-tooling-metadata.json",
                "/DebugProbesKt.bin",
                "/okhttp3/internal/publicsuffix/**",
            )
        }
    }

    testOptions {
        // android.util.Log and friends are no-ops in JVM unit tests
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Already pulled in by Compose at this version; declared because the app uses it directly
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Debug builds only, never part of a release
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit)
}