plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

// Contract tests against real endpoints are opt-in: they run only when the environment variables
// named in docs/CONTRACT_TESTS.md are set, and are reported as skipped otherwise.
tasks.withType<Test>().configureEach {
    listOf(
        "LLM_TIERS_LAN_BASE_URL", "LLM_TIERS_LAN_API_KEY", "LLM_TIERS_LAN_MODEL",
        "LLM_TIERS_OPENROUTER_API_KEY", "LLM_TIERS_OPENROUTER_MODEL",
    ).forEach { name -> inputs.property(name + "_set", providers.environmentVariable(name).map { "set" }.orElse("unset")) }
}
