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
        maven("https://jitpack.io")

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")

        // 👇 ADICIONE ESTE BLOCO PARA O FILAMENT 👇
        maven {
            url = uri("https://google.github.io/filament/maven/")
        }
    }
}

rootProject.name = "HeadTracker"
include(":app")