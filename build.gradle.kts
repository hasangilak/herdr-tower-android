plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied (AGP 9 compiles Kotlin itself); declared to pin the Kotlin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
