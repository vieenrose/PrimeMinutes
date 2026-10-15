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
        // JitPack: NewPipeExtractor (YouTube source). GPL-3.0 — compatible with this app's
        // GPL-3.0-or-later license. APK-distribution target (not F-Droid reproducible).
        maven {
            url = uri("https://jitpack.io")
            // Only NewPipe's artifacts come from JitPack: any other group resolving there would be a
            // dependency-confusion hole (JitPack builds whatever GitHub repo matches a coordinate).
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}

rootProject.name = "PrimeMinutes"
include(":app")
