pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // llm-tiers is not on Maven Central yet. Publish it to the local Maven cache first:
        // in the llm-tiers checkout run `./gradlew publishToMavenLocal`.
        mavenLocal { content { includeGroup("io.github.pdrakeweb") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "llm-tiers-example"
include(":app")
