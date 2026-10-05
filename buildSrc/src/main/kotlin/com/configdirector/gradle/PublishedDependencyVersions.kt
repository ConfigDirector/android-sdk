package com.configdirector.gradle

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

fun Project.registerPublishedDependencyVersionsCheck() {
    val check = tasks.register("publishedDependencyVersionsCheck", PublishedDependencyVersionsCheckTask::class.java)
    check.configure {
        group = "verification"
        description = "Fails when the published Gradle module metadata lists a dependency without a version."
        moduleMetadataFiles.from(tasks.withType(GenerateModuleMetadata::class.java))
    }

    tasks.named("check").configure { dependsOn(check) }
}

abstract class PublishedDependencyVersionsCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleMetadataFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val unversionedDependencies = moduleMetadataFiles.files.flatMap(::unversionedDependenciesOf)
        if (unversionedDependencies.isEmpty()) return

        throw GradleException(
            "The published Gradle module metadata lists dependencies without a version, which a " +
                "consumer cannot resolve unless their own build happens to supply one.\n\n" +
                unversionedDependencies.joinToString("\n", postfix = "\n") { "  $it" },
        )
    }
}

private data class UnversionedDependency(val artifact: String, val variant: String, val dependency: String) {
    override fun toString() = "$artifact, variant $variant: $dependency"
}

private fun unversionedDependenciesOf(moduleMetadataFile: File): List<UnversionedDependency> {
    val moduleMetadata = JsonSlurper().parse(moduleMetadataFile) as Map<*, *>
    val component = moduleMetadata["component"] as Map<*, *>
    val artifact = coordinatesOf(component)

    return (moduleMetadata["variants"] as List<*>).map { it as Map<*, *> }.flatMap { variant ->
        (variant["dependencies"] as List<*>?).orEmpty()
            .map { it as Map<*, *> }
            .filter { dependency -> dependency["version"] == null }
            .map { dependency ->
                UnversionedDependency(artifact, variant["name"].toString(), coordinatesOf(dependency))
            }
    }
}

private fun coordinatesOf(module: Map<*, *>) = "${module["group"]}:${module["module"]}"
