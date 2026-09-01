plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // The engine behind the Kotlin binary compatibility validator. Its Gradle plugin only wires
    // itself up for the Kotlin plugin ids, and the Android Gradle plugin brings its own Kotlin, so
    // the tasks in this directory feed the same engine the AAR we publish.
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
    // The validator expects these from the Gradle plugin classpath, which is not where these
    // tasks run.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.10")
}
