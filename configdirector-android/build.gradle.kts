plugins {
    alias(libs.plugins.android.library)
}

group = "com.configdirector"
version = "0.1.0"

android {
    namespace = "com.configdirector.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        // What a consumer must compile against. Left alone it follows compileSdk above, which
        // would make every consumer move to the newest SDK to take an SDK update. The SDK touches
        // almost nothing of the framework, so it does not need them to.
        aarMetadata {
            minCompileSdk = 21
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
        allWarningsAsErrors = true
        explicitApi()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Android ships org.json in the framework, so the SDK adds no JSON dependency. The framework
    // classes are stubs under unit tests, so the tests supply a real implementation instead.
    testImplementation(libs.org.json)
}
