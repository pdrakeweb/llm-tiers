plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Build guard (ARCHITECTURE §1): :core never depends on Android or a network library, and its
// sources never import Android, java.net or an HTTP client.
val forbiddenGroups = listOf(
    "com.android", "androidx.", "com.google.android", "com.squareup.okhttp", "com.squareup.retrofit",
    "io.ktor", "org.apache.httpcomponents", "com.google.firebase", "com.google.mlkit",
)
val forbiddenImports = Regex(
    """^\s*import\s+(android\.|androidx\.|java\.net\.|javax\.net\.|okhttp3\.|okio\.|io\.ktor\.|retrofit2\.)"""
)

val checkCorePurity = tasks.register("checkCorePurity") {
    description = "Fails if :core depends on Android or a network library."
    group = "verification"
    val runtime = configurations.runtimeClasspath
    val compile = configurations.compileClasspath
    val sources = fileTree("src/main") { include("**/*.kt", "**/*.java") }
    inputs.files(sources)
    inputs.property("deps", provider {
        (runtime.get().incoming.resolutionResult.allComponents + compile.get().incoming.resolutionResult.allComponents)
            .mapNotNull { it.moduleVersion?.let { m -> "${m.group}:${m.name}" } }.toSortedSet().toList()
    })
    doLast {
        @Suppress("UNCHECKED_CAST")
        val deps = inputs.properties["deps"] as List<String>
        val badDeps = deps.filter { d -> forbiddenGroups.any { d.startsWith(it) } }
        val badImports = sources.files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (forbiddenImports.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        if (badDeps.isNotEmpty() || badImports.isNotEmpty()) {
            throw GradleException(
                ":core must stay free of Android and network types.\n" +
                    (badDeps.map { "  dependency: $it" } + badImports.map { "  import: $it" }).joinToString("\n")
            )
        }
    }
}

tasks.named("check") { dependsOn(checkCorePurity) }
