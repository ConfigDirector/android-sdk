plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

group = "com.configdirector"
version = "0.1.0"

android {
    namespace = "com.configdirector.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 21

        aarMetadata {
            minCompileSdk = 21
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        // AndroidX ships Java 11 bytecode. The core artifact stays on 8, for consumers who have not
        // moved; a consumer already using Compose is on 11 by definition.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        allWarningsAsErrors = true
        explicitApi()
    }
}

dependencies {
    api(project(":configdirector-android"))

    implementation(platform(libs.androidx.compose.bom))
    // Only the runtime: these are bindings over the client, with no UI of their own, so nothing
    // here should pull compose-ui or material into a consumer's build.
    api(libs.androidx.compose.runtime)

    // Composables need a composition to run in, which on the JVM means Robolectric.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
