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
    namespace = "com.configdirector.testing"
    compileSdk = 37

    defaultConfig {
        minSdk = 21

        aarMetadata {
            minCompileSdk = 21
        }
    }

    compileOptions {
        // The same bytecode as the SDK, so that a consumer still on Java 8 can test with it.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests {
            // The version this artifact checks the SDK against is a constant in the source, and
            // this is what lets a test hold it to the version the artifact is published under.
            all { test -> test.systemProperty("configdirector.publishedVersion", publishedVersion) }
        }
    }

    lint {
        // org.json below duplicates classes the platform ships, which is exactly why it is here:
        // the platform's copies are stubs under a plain JVM unit test. On a device the platform's
        // win, so the crash this check warns about cannot happen. See the dependency for details.
        disable += "DuplicatePlatformClasses"
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
    // The test client is the SDK's own client, so the SDK reaches the consumer's test classpath
    // through this artifact. Pinned to the same version: this artifact drives an entry point that
    // is not part of the SDK's stable API, and createTestClient refuses a mismatch at runtime.
    api(project(":configdirector-android"))
    constraints {
        api("com.configdirector:android-sdk") {
            version { strictly(publishedVersion) }
        }
    }

    // Android ships org.json in the framework, and the framework classes are stubs under a plain
    // JVM unit test, so JSON configs would not parse without a real implementation. On a device the
    // framework's own wins, because the boot class loader is consulted first, so an instrumented
    // test is unaffected.
    implementation(libs.org.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }

    // The release variant is what consumers get; the debug one carries nothing they can use.
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))

    coordinates(group.toString(), "android-sdk-testing", version.toString())

    pom {
        name.set("ConfigDirector Android SDK testing tools")
        description.set("Testing tools for the ConfigDirector Android SDK: the SDK's real client over an in-memory connection a test controls. ConfigDirector is a remote configuration and feature flag service.")
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

publishing {
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
}
