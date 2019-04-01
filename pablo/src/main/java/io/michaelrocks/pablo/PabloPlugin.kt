/*
 * Copyright 2019 Michael Rozumyanskiy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.michaelrocks.pablo

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.jfrog.bintray.gradle.BintrayExtension
import groovy.util.Node
import org.gradle.BuildAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.util.GradleVersion
import java.util.Date

class PabloPlugin : Plugin<Project> {
  private lateinit var project: Project
  private lateinit var logger: Logger
  private lateinit var extension: PabloPluginExtension

  override fun apply(project: Project) {
    this.project = project
    this.logger = project.logger
    this.extension = createPluginExtension()

    createRelocateConfiguration()

    project.afterEvaluate {
      configureArtifacts()
    }

    project.gradle.addBuildListener(
        object : BuildAdapter() {
          override fun projectsEvaluated(gradle: Gradle) {
            val resolvedDependencies = DependencyResolver.resolve(project, extension.repackage)
            if (extension.repackage) {
              copyTransitiveDependencies()
              configureShadowJar(resolvedDependencies)
            }

            configureBintray()
            configurePublications(resolvedDependencies)
          }
        }
    )

    applyPlugins()
  }

  fun getArtifactName(): String {
    return extension.artifactName ?: project.rootProject.name + '-' + project.name
  }

  private fun createPluginExtension(): PabloPluginExtension {
    return project.extensions.create("pablo", PabloPluginExtension::class.java)
  }

  private fun applyPlugins() {
    project.plugins.apply("java")
    project.plugins.apply("maven-publish")
    project.plugins.apply("com.jfrog.bintray")
    project.plugins.apply("com.github.johnrengelman.shadow")
  }

  private fun createRelocateConfiguration() {
    val relocate = project.configurations.create(RELOCATE_CONFIGURATION_NAME)
    project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME).extendsFrom(relocate)
    project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(relocate)
    project.configurations.getByName(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME).extendsFrom(relocate)
    project.configurations.getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(relocate)
  }

  private fun copyTransitiveDependencies() {
    val relocate = project.configurations.getByName(RELOCATE_CONFIGURATION_NAME)
    relocate.dependencies.forEach { dependency ->
      processRelocateDependency(dependency)
    }
  }

  private fun processRelocateDependency(dependency: Dependency) {
    if (dependency is ProjectDependency) {
      copyTransitiveDependenciesFromSubproject(dependency.dependencyProject)
    }
  }

  private fun copyTransitiveDependenciesFromSubproject(subproject: Project) {
    val projectShadowJar = project.tasks.getByName(SHADOW_JAR_TASK_NAME) as ShadowJar
    val subprojectShadowJar = subproject.tasks.findByName(SHADOW_JAR_TASK_NAME) as ShadowJar?
    subprojectShadowJar?.relocators?.forEach {
      projectShadowJar.relocate(it)
    }

    subproject.configurations.forEach { configuration ->
      configuration.dependencies.forEach { dependency ->
        project.configurations.getByName(configuration.name).dependencies.add(dependency.copy())
        if (configuration.name == RELOCATE_CONFIGURATION_NAME) {
          processRelocateDependency(dependency)
        }
      }
    }
  }

  private fun configureArtifacts() {
    val sourceSets = project.convention.getPlugin(JavaPluginConvention::class.java).sourceSets

    val sourcesJar = project.tasks.create(SOURCES_JAR_TASK_NAME, Jar::class.java) { task ->
      task.dependsOn(project.tasks.getByName(JavaPlugin.CLASSES_TASK_NAME))
      task.from(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).allSource)
      task.setArchiveClassifier(SOURCES_CLASSIFIER)
    }

    val javadocJar = project.tasks.create(JAVADOC_JAR_TASK_NAME, Jar::class.java) { task ->
      val javadoc = project.tasks.getByName(JavaPlugin.JAVADOC_TASK_NAME) as Javadoc
      task.dependsOn(javadoc)
      task.from(javadoc.destinationDir)
      task.setArchiveClassifier(JAVADOC_CLASSIFIER)
    }

    project.artifacts.add(Dependency.ARCHIVES_CONFIGURATION, sourcesJar)
    project.artifacts.add(Dependency.ARCHIVES_CONFIGURATION, javadocJar)
  }

  private fun configureShadowJar(resolvedDependencies: DependencyResolver.DependencyResolutionResult) {
    val shadowJar = project.tasks.getByName(SHADOW_JAR_TASK_NAME) as ShadowJar
    shadowJar.configurations = listOf(
        project.configurations.findByName(RELOCATE_CONFIGURATION_NAME)
    )

    val filter = shadowJar.dependencyFilter
    filter.include(resolvedDependencies.toIncludeSpec())

    shadowJar.setArchiveClassifier(REPACK_CLASSIFIER)
  }

  private fun DependencyResolver.DependencyResolutionResult.toIncludeSpec(): Spec<ResolvedDependency> {
    val dependenciesToRelocate = scopeToNotationsMap[DependencyResolver.Scope.RELOCATE] ?: emptyList()
    return Spec { dependency ->
      val originalNotation =
          DependencyResolver.DependencyNotation(dependency.moduleGroup, dependency.moduleName, dependency.moduleVersion)
      val notation = dependencyToDependencyMap[originalNotation] ?: originalNotation
      notation in dependenciesToRelocate
    }
  }

  @Suppress("Deprecation")
  private fun Jar.setArchiveClassifier(classifier: String) {
    if (GradleVersion.current() >= GRADLE_VERSION_5_1) {
      archiveClassifier.set(classifier)
    } else {
      setClassifier(classifier)
    }
  }

  private fun configureBintray() {
    project.extensions.getByType(BintrayExtension::class.java).also { bintray ->
      bintray.user = project.findProperty(BINTRAY_USER_PROPERTY)?.toString()
      bintray.key = project.findProperty(BINTRAY_KEY_PROPERTY)?.toString()

      bintray.setPublications(PUBLICATION_NAME)

      bintray.dryRun = readBooleanProperty("dryRun", false)
      bintray.publish = readBooleanProperty("publish", false)

      bintray.pkg.also { pkg ->
        pkg.repo = extension.repository ?: "maven"
        pkg.name = getArtifactName()

        pkg.version.also { version ->
          version.released = Date().toString()
          version.vcsTag = "v$project.version"
        }
      }
    }
  }

  private fun readBooleanProperty(propertyName: String, defaultValue: Boolean): Boolean {
    return project.findProperty(propertyName)?.toString()?.toBoolean() ?: defaultValue
  }

  private fun configurePublications(resolvedDependencies: DependencyResolver.DependencyResolutionResult) {
    val bintray = project.extensions.getByType(BintrayExtension::class.java)
    val publishing = project.extensions.getByType(PublishingExtension::class.java)
    publishing.publications.create(PUBLICATION_NAME, MavenPublication::class.java) { publication ->
      publication.artifactId = bintray.pkg.name
      if (extension.repackage) {
        publication.artifact(project.tasks.getByName(SHADOW_JAR_TASK_NAME)) { artifact ->
          artifact.classifier = null
        }
      } else {
        publication.artifact(project.tasks.getByName(JavaPlugin.JAR_TASK_NAME))
      }

      publication.artifact(project.tasks.getByName(SOURCES_JAR_TASK_NAME))
      publication.artifact(project.tasks.getByName(JAVADOC_JAR_TASK_NAME))

      publication.pom.withXml { xml ->
        val root = xml.asNode()
        val dependencies = root.appendNode("dependencies")
        dependencies.addDependenciesToPom(resolvedDependencies)
      }
    }
  }

  private fun Node.addDependenciesToPom(resolvedDependencies: DependencyResolver.DependencyResolutionResult) {
    resolvedDependencies.scopeToNotationsMap.forEach { (scope, notations) ->
      if (scope != DependencyResolver.Scope.RELOCATE || !extension.repackage) {
        val mavenScope =
            if (scope == DependencyResolver.Scope.RELOCATE) {
              DependencyResolver.Scope.COMPILE.toMavenScope()
            } else {
              scope.toMavenScope()
            }
        notations.forEach { addDependencyNode(it, mavenScope) }
      }
    }
  }

  private fun Node.addDependencyNode(notation: DependencyResolver.DependencyNotation, scope: String) {
    appendNode("dependency").also {
      it.appendNode("groupId", notation.group)
      it.appendNode("artifactId", notation.name)
      it.appendNode("version", notation.version)
      it.appendNode("scope", scope)
    }
  }

  private fun DependencyResolver.Scope.toMavenScope(): String {
    return when (this) {
      DependencyResolver.Scope.RELOCATE -> error("Cannot add a Maven scope for $this")
      DependencyResolver.Scope.COMPILE -> "compile"
      DependencyResolver.Scope.RUNTIME -> "runtime"
      DependencyResolver.Scope.PROVIDED -> "provided"
    }
  }

  companion object {
    const val BINTRAY_USER_PROPERTY = "bintrayUser"
    const val BINTRAY_KEY_PROPERTY = "bintrayKey"
    const val PUBLICATION_NAME = "mavenJava"

    const val SHADOW_JAR_TASK_NAME = "shadowJar"
    const val SOURCES_JAR_TASK_NAME = "sourcesJar"
    const val JAVADOC_JAR_TASK_NAME = "javadocJar"
    const val RELOCATE_CONFIGURATION_NAME = "relocate"

    const val SOURCES_CLASSIFIER = "sources"
    const val JAVADOC_CLASSIFIER = "javadoc"
    const val REPACK_CLASSIFIER = "repack"

    private val GRADLE_VERSION_5_1 = GradleVersion.version("5.1")
  }
}
