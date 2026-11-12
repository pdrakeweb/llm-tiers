plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.pdrakeweb.llmtiers.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pdrakeweb.llmtiers.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":android"))
    implementation(project(":openai"))
    implementation(libs.coroutines.android)
}
