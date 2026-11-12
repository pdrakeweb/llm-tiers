import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "io.github.pdrakeweb"
    version = "0.1.0-SNAPSHOT"
}

// Shared settings for the pure-JVM library modules. Bytecode and API level are pinned to 17 with
// `-Xjdk-release`, so the build gives the same output on any JDK from 17 upwards.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<BasePluginExtension> { archivesName.set("llm-tiers-${project.name}") }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
                allWarningsAsErrors.set(true)
            }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed", "skipped") }
        }
    }
}

// Build guard: this library must not know about any app that consumes it. Sources, resources and
// build files are scanned; docs/ (design history) and CLAUDE.md are not.
val consumerNames = Regex("nextrep|sea-?whisper|openhelm", RegexOption.IGNORE_CASE)
val checkNoConsumerReferences = tasks.register("checkNoConsumerReferences") {
    description = "Fails if code, resources or build files reference a consumer app."
    group = "verification"
    val scanned = fileTree(rootDir) {
        include("*/src/**", "**/*.gradle.kts", "gradle/**", "README.md", "testing/*.md")
        exclude("**/build/**", ".gradle/**", "gradle/wrapper/*.jar")
    }
    inputs.files(scanned)
    val root = rootDir
    val self = file("build.gradle.kts")
    doLast {
        val hits = scanned.files.filter { it != self }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (consumerNames.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}" else null
            }
        }
        if (hits.isNotEmpty()) throw GradleException("Consumer app referenced in:\n" + hits.joinToString("\n") { "  $it" })
    }
}
tasks.named("check") { dependsOn(checkNoConsumerReferences) }
