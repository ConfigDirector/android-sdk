import com.android.build.api.artifact.SingleArtifact
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.configdirector.gradle.registerApiValidation

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

private val publishedVersion = version.toString()

private val REPOSITORY_URL = "https://github.com/ConfigDirector/android-sdk"

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
            isIncludeAndroidResources = true

            // The version the SDK reports to the server is a constant in the source, and this is
            // what lets a test hold it to the version the artifact is published under.
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
    // Only the tests that build a client through its public constructor need an Android
    // environment: that constructor takes a Context, and watching it needs a real Application.
    testImplementation(libs.robolectric)
    // Android ships org.json in the framework, so the SDK adds no JSON dependency. The framework
    // classes are stubs under unit tests, so the tests supply a real implementation instead.
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

    pom {
        name.set("ConfigDirector Android SDK")
        description.set("Android SDK for ConfigDirector, usable from Kotlin and Java. ConfigDirector is a remote configuration and feature flag service.")
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
