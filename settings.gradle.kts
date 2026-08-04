pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "loam"

// :core holds everything that isn't Android UI — vault reading, chunking,
// embedding, and the encrypted store. Split out from :app so Phase 5's desktop
// companion has something to share, and so the domain logic stays testable
// without an Android runtime.
include(":app")
include(":core")

// `spike/` is a separate Gradle build on purpose. It is throwaway measurement
// code (see spike/README.md) and must not leak into the real app.
