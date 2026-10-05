import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The sample's key and the identity it evaluates configs against are not committed: put them in
// local.properties, the same file the Android SDK location lives in. See the README.
fun localProperty(name: String): String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty(name, "") }
    .getOrElse("")

android {
    namespace = "com.configdirector.sample.compose"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.configdirector.sample.compose"
        // The SDK itself runs on 21. Compose does not: androidx.navigationevent, which
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
        // AndroidX ships Java 11 bytecode, which cannot be inlined into Java 8. The SDK itself
        // stays on 8, for consumers who have not moved.
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

// -PuseLocalSdk builds against configdirector-android-compose/ and configdirector-android-testing/
// in this repository instead of the releases in the ConfigDirector Maven repository. CI and the
// pre-push hook set it, so a breaking API change fails here first; locally it is how you try an
// unreleased SDK change against a real consumer.
val useLocalSdk = providers.gradleProperty("useLocalSdk")
    .map { it.isEmpty() || it.toBoolean() }
    .getOrElse(false)

dependencies {
    // The latest released bindings, which is what a reader copying this line wants. They
    // deliberately lag the version in gradle.properties between a version bump and the release
    // that publishes it -- naming an unpublished version here leaves the sample unresolvable for
    // everyone who is not passing -PuseLocalSdk above. The core arrives with them.
    implementation(
        if (useLocalSdk) {
            project(":configdirector-android-compose")
        } else {
            "com.configdirector:android-sdk-compose:1.7.1"
        },
    )

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)

    // The SDK's testing tools, which must be the same version as the SDK above and lag the
    // release the same way. A composable needs a composition to run in, which on the JVM means
    // Robolectric.
    testImplementation(
        if (useLocalSdk) {
            project(":configdirector-android-testing")
        } else {
            "com.configdirector:android-sdk-testing:1.7.1"
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
