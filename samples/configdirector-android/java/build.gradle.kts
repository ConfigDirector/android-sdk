import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The sample's key and the identity it evaluates configs against are not committed: put them in
// local.properties, the same file the Android SDK location lives in. See the README.
fun localProperty(name: String): String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty(name, "") }
    .getOrElse("")

// Deliberately Java only, on the SDK's oldest supported Android and Java: no Kotlin sources, no
// AndroidX, no Compose. If the SDK's Java surface breaks, this module stops compiling.
android {
    namespace = "com.configdirector.sample.java"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.configdirector.sample.java"
        minSdk = 21
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))

    // The generated BuildConfig.java trips dangling-doc-comments, a category that arrived in JDK
    // 22. Older compilers never warn about it, and reject the flag that turns it off.
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        options.compilerArgs.add("-Xlint:-dangling-doc-comments")
    }

    // Robolectric's classes carry annotations whose types are not on a unit test's classpath, which
    // javac reports as a classfile warning. The app's own sources stay under every lint category.
    if (name.contains("UnitTest")) {
        options.compilerArgs.add("-Xlint:-classfile")
    }
}

// -PuseLocalSdk builds against configdirector-android/ and configdirector-android-testing/ in this
// repository instead of the releases in the ConfigDirector Maven repository. CI and the pre-push
// hook set it, so a breaking API change fails here first; locally it is how you try an unreleased
// SDK change against a real consumer.
val useLocalSdk = providers.gradleProperty("useLocalSdk")
    .map { it.isEmpty() || it.toBoolean() }
    .getOrElse(false)

dependencies {
    // The latest released SDK, which is what a reader copying this line wants. It deliberately
    // lags the version in gradle.properties between a version bump and the release that publishes
    // it -- naming an unpublished version here leaves the sample unresolvable for everyone who is
    // not passing -PuseLocalSdk above.
    implementation(
        if (useLocalSdk) {
            project(":configdirector-android")
        } else {
            "com.configdirector:android-sdk:1.6.0"
        },
    )

    // The SDK's testing tools, which must be the same version as the SDK above and lag the
    // release the same way. The screen is an Activity, which on the JVM needs Robolectric; that
    // is the test classpath only, and the app itself still has no AndroidX.
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
}
