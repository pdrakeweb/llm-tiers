plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.pdrakeweb.llmtiers.example"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pdrakeweb.llmtiers.example"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        // The downloaded-model runtime (llm-tiers-litert) has one 23-27 MB native library per
        // processor type. Phones are arm64-v8a; the x86_64 copy is only for emulators, so it
        // stays out of release builds. Debug builds keep both.
        release { ndk { abiFilters += "arm64-v8a" } }
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.llmtiers.core)
    implementation(libs.llmtiers.openai)
    implementation(libs.llmtiers.android)
    implementation(libs.llmtiers.litert)
    implementation(libs.llmtiers.modelmanager)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.llmtiers.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
}
