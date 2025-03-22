// Add this at the top:
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
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
        google()  // ✅ Google Maven Repository
        mavenCentral()  // ✅ Maven Central Repository
        maven { url = uri("https://jitpack.io") }  // ✅ JitPack (sometimes needed)
    }
}

rootProject.name = "Camera2TestApp"
include(":app")
