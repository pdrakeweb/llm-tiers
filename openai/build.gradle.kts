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

// Contract tests against real endpoints are opt-in: they run only when the variables named in
// docs/CONTRACT_TESTS.md are set, and are reported as skipped otherwise. The variables come from
// the real environment or, failing that, from a git-ignored ".env" file in the repository root.
val contractUrls = listOf("LLM_TIERS_LAN_BASE_URL", "LLM_TIERS_LAN_MODEL", "LLM_TIERS_OPENROUTER_MODEL")
val contractKeys = listOf("LLM_TIERS_LAN_API_KEY", "LLM_TIERS_OPENROUTER_API_KEY")

val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { line ->
        line.removePrefix("export ").substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
    }
    .filterKeys { it in contractUrls + contractKeys }
    .filterValues { it.isNotEmpty() }

tasks.withType<Test>().configureEach {
    // The real environment wins over the file.
    dotEnv.forEach { (name, value) -> if (System.getenv(name).isNullOrBlank()) environment(name, value) }

    // Declared as inputs so Gradle re-runs the tests when one is set or changed. Key values are
    // never made a task input, only whether they are set.
    fun value(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotEnv[name]
    contractUrls.forEach { name -> inputs.property(name, value(name) ?: "") }
    contractKeys.forEach { name -> inputs.property(name + "_SET", if (value(name) != null) "yes" else "no") }
    testLogging { showStandardStreams = true }
}
