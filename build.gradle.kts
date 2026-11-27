plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied to modules: only pins the Kotlin version used by AGP's built-in Kotlin
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
