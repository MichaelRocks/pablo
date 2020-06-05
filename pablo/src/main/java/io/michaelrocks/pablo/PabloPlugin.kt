/*
 * Copyright 2020 Michael Rozumyanskiy
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
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
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
            configureShadowJar()

            configureBintray()
            configurePublications()
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

  private fun configureShadowJar() {
    val shadowJar = project.tasks.getByName(SHADOW_JAR_TASK_NAME) as ShadowJar
    shadowJar.configurations = listOf(
        project.configurations.getByName(RELOCATE_CONFIGURATION_NAME)
    )

    addProjectDependenciesToShadowJar(shadowJar, project)

    val filter = shadowJar.dependencyFilter
    project.configurations.forEach { configuration ->
      val shouldInclude = configuration.name == RELOCATE_CONFIGURATION_NAME
      configuration.dependencies.forEach { dependency ->
        val spec = filter.dependency(dependency)
        if (shouldInclude) filter.include(spec) else filter.exclude(spec)
      }
    }

    shadowJar.setArchiveClassifier(null)
  }

  private fun addProjectDependenciesToShadowJar(shadowJar: ShadowJar, project: Project) {
    val convention = project.convention.getPlugin(JavaPluginConvention::class.java)
    shadowJar.from(convention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).output)

    val relocateConfiguration = project.configurations.findByName(RELOCATE_CONFIGURATION_NAME) ?: return
    relocateConfiguration.allDependencies.forEach { dependency ->
      if (dependency is ProjectDependency) {
        val projectShadowJar = dependency.dependencyProject.tasks.findByName(SHADOW_JAR_TASK_NAME) as ShadowJar?
        if (projectShadowJar != null) {
          shadowJar.from(projectShadowJar.archiveFile)
        } else {
          addProjectDependenciesToShadowJar(shadowJar, dependency.dependencyProject)
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

  @Suppress("Deprecation")
  private fun Jar.setArchiveClassifier(classifier: String?) {
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

  @Suppress("SameParameterValue")
  private fun readBooleanProperty(propertyName: String, defaultValue: Boolean): Boolean {
    return project.findProperty(propertyName)?.toString()?.toBoolean() ?: defaultValue
  }

  private fun configurePublications() {
    val bintray = project.extensions.getByType(BintrayExtension::class.java)
    val publishing = project.extensions.getByType(PublishingExtension::class.java)
    publishing.publications.create(PUBLICATION_NAME, MavenPublication::class.java) { publication ->
      publication.artifactId = bintray.pkg.name

      publication.artifact(project.tasks.getByName(SHADOW_JAR_TASK_NAME))
      publication.artifact(project.tasks.getByName(SOURCES_JAR_TASK_NAME))
      publication.artifact(project.tasks.getByName(JAVADOC_JAR_TASK_NAME))

      publication.pom.withXml { xml ->
        val root = xml.asNode()
        val dependenciesNode = root.appendNode("dependencies")
        val resolvedDependencies = DependencyResolver.resolve(project)
        dependenciesNode.addDependenciesToPom(resolvedDependencies)
      }
    }
  }

  private fun Node.addDependenciesToPom(resolvedDependencies: DependencyResolver.DependencyResolutionResult) {
    resolvedDependencies.scopeToModuleIdMap.forEach { (scope, notations) ->
      if (scope != DependencyResolver.Scope.RELOCATE) {
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

  private fun Node.addDependencyNode(moduleId: ModuleVersionIdentifier, scope: String) {
    appendNode("dependency").also {
      it.appendNode("groupId", moduleId.group)
      it.appendNode("artifactId", moduleId.name)
      it.appendNode("version", moduleId.version)
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

    private val GRADLE_VERSION_5_1 = GradleVersion.version("5.1")
  }
}
