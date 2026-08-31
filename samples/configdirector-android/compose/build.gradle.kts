import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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

        buildConfigField("String", "CLIENT_SDK_KEY", "\"$clientSdkKey\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
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

dependencies {
    implementation(project(":configdirector-android"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
