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
}

dependencies {
    implementation(project(":configdirector-android"))
}
