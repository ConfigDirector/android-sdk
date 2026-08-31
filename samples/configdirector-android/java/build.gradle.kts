import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The sample's key is not committed: put it in local.properties as
// configdirector.clientSdkKey=YOUR-KEY, the same file the Android SDK location lives in.
val clientSdkKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text ->
        Properties().apply { load(text.reader()) }
            .getProperty("configdirector.clientSdkKey", "")
    }
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

        buildConfigField("String", "CLIENT_SDK_KEY", "\"$clientSdkKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // dangling-doc-comments is off because the generated BuildConfig.java trips it.
    options.compilerArgs.addAll(
        listOf("-Xlint:all", "-Xlint:-options", "-Xlint:-dangling-doc-comments", "-Werror"),
    )
}

dependencies {
    implementation(project(":configdirector-android"))
}
