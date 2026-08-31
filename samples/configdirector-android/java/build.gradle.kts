plugins {
    alias(libs.plugins.android.application)
}

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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

dependencies {
    implementation(project(":configdirector-android"))
}
