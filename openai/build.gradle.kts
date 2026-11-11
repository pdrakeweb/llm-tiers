plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Contract tests against real endpoints are opt-in: they run only when the environment variables
// named in docs/CONTRACT_TESTS.md are set, and are reported as skipped otherwise. Declaring the
// variables as inputs makes Gradle re-run the tests when one is set or changed. Key values are
// never made a task input, only whether they are set.
tasks.withType<Test>().configureEach {
    listOf("LLM_TIERS_LAN_BASE_URL", "LLM_TIERS_LAN_MODEL", "LLM_TIERS_OPENROUTER_MODEL")
        .forEach { name -> inputs.property(name, providers.environmentVariable(name).orElse("")) }
    listOf("LLM_TIERS_LAN_API_KEY", "LLM_TIERS_OPENROUTER_API_KEY")
        .forEach { name -> inputs.property(name + "_SET", providers.environmentVariable(name).map { "yes" }.orElse("no")) }
    testLogging { showStandardStreams = true }
}
