import com.android.build.api.artifact.SingleArtifact
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.configdirector.gradle.registerApiValidation

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

private val REPOSITORY_URL = "https://github.com/ConfigDirector/android-sdk"

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

androidComponents {
    onVariants(selector().withName("release")) { variant ->
        registerApiValidation(variant.artifacts.get(SingleArtifact.AAR))
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

mavenPublishing {
    // Uploads a signed bundle to the Central Portal and stops there: the deployment is released by
    // hand, so a green pipeline is not by itself a published version.
    publishToMavenCentral()

    // Central requires signatures, and only the release pipeline holds the key. Publishing to the
    // local cache to try a build against a consumer must not need one, so this is conditional; an
    // unsigned bundle is rejected by the Portal rather than published.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }

    // The release variant is what consumers get; the debug one carries nothing they can use.
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))

    pom {
        name.set("ConfigDirector Android Compose bindings")
        description.set("Jetpack Compose bindings for the ConfigDirector Android SDK. ConfigDirector is a remote configuration and feature flag service.")
        url.set(REPOSITORY_URL)
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("$REPOSITORY_URL/blob/main/LICENSE")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("configdirector")
                name.set("ConfigDirector")
                url.set("https://www.configdirector.com")
            }
        }

        scm {
            url.set(REPOSITORY_URL)
            connection.set("scm:git:$REPOSITORY_URL.git")
            developerConnection.set("scm:git:ssh://git@github.com/ConfigDirector/android-sdk.git")
        }
    }
}
