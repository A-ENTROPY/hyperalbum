// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
}

// Task to print the project version banner
tasks.register("printBanner") {
    doLast {
        println("==============================================")
        println("  SmartVision Gallery (智能视界) Build Script")
        println("  Version: V1.0.0")
        println("  Build:   ${'$'}buildNumber")
        println("==============================================")
    }
}