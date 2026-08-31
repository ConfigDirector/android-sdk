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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// The root is the repository; the modules below are the published artifacts.
rootProject.name = "configdirector-android-sdk"

include("configdirector-android")

// Not published. Samples are grouped by the artifact they demonstrate, since this repository will
// hold more than one.
include("samples:configdirector-android:compose")
include("samples:configdirector-android:java")
