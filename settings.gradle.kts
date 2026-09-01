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
include("configdirector-android-compose")

// Not published. Each depends on the release of the artifact it demonstrates, the way a real
// consumer does; -PuseLocalSdk swaps in the modules above instead, which is how CI and the
// pre-push hook make a breaking API change fail here first. Samples are grouped by the artifact
// they demonstrate, since this repository will hold more than one.
include("samples:configdirector-android:compose")
include("samples:configdirector-android:java")
