plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    api(libs.coroutines.test)
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
