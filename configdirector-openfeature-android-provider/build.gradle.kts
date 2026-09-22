import com.android.build.api.artifact.SingleArtifact
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.configdirector.gradle.registerApiValidation

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("OPENFEATURE_PROVIDER_VERSION_NAME").get()

private val publishedVersion = version.toString()

private val REPOSITORY_URL = "https://github.com/ConfigDirector/android-sdk"

android {
    namespace = "com.configdirector.openfeature"
    compileSdk = 37

    defaultConfig {
        minSdk = 21

        aarMetadata {
            minCompileSdk = 21
        }
    }

    compileOptions {
        // The OpenFeature Kotlin SDK ships Java 11 bytecode, which cannot be inlined into Java 8.
        // The core artifact stays on 8, for consumers who have not moved.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true

            // The version the provider reports to the server is a constant in the source, and this
            // is what lets a test hold it to the version the artifact is published under.
            all { test -> test.systemProperty("configdirector.publishedVersion", publishedVersion) }
        }
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
    // Both are what a consumer programs against: the OpenFeature API to read flags, and the SDK's
    // options to configure the client behind the provider.
    api(project(":configdirector-android"))
    api(libs.openfeature.kotlin.sdk)

    // The provider is exercised through a real client against a fake server, which on the JVM
    // means Robolectric for the Context the client takes.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.org.json)
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

    // The plugin reads the VERSION_NAME Gradle property on its own and lets it override the
    // project version, which would publish the provider under the SDK's version. Naming the
    // coordinates here is what makes the version above the one that ships.

    // The plugin reads the VERSION_NAME Gradle property on its own and lets it override the
    // project version, which would publish the provider under the SDK's version. Naming the
    // coordinates here is what makes the version above the one that ships.
    coordinates(group.toString(), name, version.toString())

    pom {
        name.set("ConfigDirector OpenFeature Android Provider")
        description.set("OpenFeature provider for Android and Kotlin, built on the ConfigDirector Android SDK. ConfigDirector is a remote configuration and feature flag service.")
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
