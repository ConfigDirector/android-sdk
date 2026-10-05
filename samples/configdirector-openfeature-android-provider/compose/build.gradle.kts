import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The sample's key and the identity it evaluates flags against are not committed: put them in
// local.properties, the same file the Android SDK location lives in. See the README.
fun localProperty(name: String): String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty(name, "") }
    .getOrElse("")

android {
    namespace = "com.configdirector.sample.openfeature"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.configdirector.sample.openfeature"
        // The provider itself runs on 21. Compose does not: androidx.navigationevent, which
        // activity-compose pulls in, declares 23 as its floor.
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "CLIENT_SDK_KEY", "\"${localProperty("configdirector.clientSdkKey")}\"")
        buildConfigField("String", "USER_ID", "\"${localProperty("configdirector.userId")}\"")
        buildConfigField("String", "USER_NAME", "\"${localProperty("configdirector.userName")}\"")
        buildConfigField("String", "USER_ROLE", "\"${localProperty("configdirector.userRole")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        allWarningsAsErrors = true
    }
}

// -PuseLocalSdk builds against configdirector-openfeature-android-provider/ and
// configdirector-android-testing/ in this repository, and through them the SDK module, instead of
// the releases in the ConfigDirector Maven repository. CI and the pre-push hook set it, so a
// breaking API change fails here first; locally it is how you try an unreleased change against a
// real consumer.
val useLocalSdk = providers.gradleProperty("useLocalSdk")
    .map { it.isEmpty() || it.toBoolean() }
    .getOrElse(false)

dependencies {
    // The latest released provider, which is what a reader copying this line wants. It
    // deliberately lags the version in gradle.properties between a version bump and the release
    // that publishes it -- naming an unpublished version here leaves the sample unresolvable for
    // everyone who is not passing -PuseLocalSdk above. The OpenFeature Kotlin SDK and the
    // ConfigDirector SDK arrive with it.
    implementation(
        if (useLocalSdk) {
            project(":configdirector-openfeature-android-provider")
        } else {
            "com.configdirector:openfeature-android-provider:1.2.0"
        },
    )

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)

    // The SDK's testing tools, whose test client the provider accepts in place of a client of its
    // own. They must be the same version as the SDK the provider brings, and lag the release the
    // same way. A composable needs a composition to run in, which on the JVM means Robolectric.
    testImplementation(
        if (useLocalSdk) {
            project(":configdirector-android-testing")
        } else {
            "com.configdirector:android-sdk-testing:1.6.0"
        },
    )
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
