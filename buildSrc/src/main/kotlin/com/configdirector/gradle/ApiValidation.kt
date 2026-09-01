package com.configdirector.gradle

import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Registers `apiDump` and `apiCheck` over the release AAR, and makes `check` depend on the latter,
 * so a change to what the SDK publishes fails the build instead of a release.
 */
fun Project.registerApiValidation(releaseAar: Provider<RegularFile>) {
    val signatures = layout.projectDirectory.file("api/$name.api")

    val dump = tasks.register("apiDump", ApiDumpTask::class.java)
    dump.configure {
        group = "verification"
        description = "Writes the public API of the release AAR to api/${project.name}.api."
        aar.set(releaseAar)
        apiFile.set(signatures)
    }

    val check = tasks.register("apiCheck", ApiCheckTask::class.java)
    check.configure {
        group = "verification"
        description = "Fails when the release AAR no longer matches api/${project.name}.api."
        aar.set(releaseAar)
        apiFile.set(signatures)
        dumpTaskPath.set("${project.path}:${dump.name}")
    }

    tasks.named("check").configure { dependsOn(check) }
}

@CacheableTask
abstract class ApiDumpTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:OutputFile
    abstract val apiFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val file = apiFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(publicApiOf(aar.get().asFile, temporaryDir))
        logger.lifecycle("Wrote ${file.name}")
    }
}

@CacheableTask
abstract class ApiCheckTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiFile: RegularFileProperty

    @get:Input
    abstract val dumpTaskPath: Property<String>

    @TaskAction
    fun check() {
        val expected = apiFile.get().asFile.readText()
        val actual = publicApiOf(aar.get().asFile, temporaryDir)
        if (expected == actual) return

        throw GradleException(
            "The public API no longer matches ${apiFile.get().asFile.name}.\n\n" +
                diff(expected, actual) +
                "\nIf the change is intended, run ${dumpTaskPath.get()} and commit the result.",
        )
    }
}

private const val CLASSES_JAR = "classes.jar"

private fun publicApiOf(aar: File, workingDir: File): String {
    val classesJar = File(workingDir, CLASSES_JAR)
    ZipFile(aar).use { archive ->
        val entry = archive.getEntry(CLASSES_JAR)
            ?: throw GradleException("No $CLASSES_JAR inside ${aar.name}.")
        archive.getInputStream(entry).use { source ->
            classesJar.outputStream().use(source::copyTo)
        }
    }

    return JarFile(classesJar).use { jar ->
        buildString { jar.loadApiFromJvmClasses().filterOutNonPublic().dump(this) }
    }
}

private fun diff(expected: String, actual: String): String {
    val expectedLines = expected.lines().toSet()
    val actualLines = actual.lines().toSet()
    val removed = expectedLines.filterNot { it.isBlank() || it in actualLines }.map { "- $it" }
    val added = actualLines.filterNot { it.isBlank() || it in expectedLines }.map { "+ $it" }

    return (removed + added).joinToString("\n", postfix = "\n")
}
