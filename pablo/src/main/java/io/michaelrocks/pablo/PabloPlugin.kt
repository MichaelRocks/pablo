/*
 * Copyright 2021 Michael Rozumyanskiy
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
import org.gradle.api.provider.Property
import org.gradle.api.publish.Publication
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.signing.SigningExtension
import java.util.Properties

class PabloPlugin : Plugin<Project> {
  private lateinit var project: Project
  private lateinit var logger: Logger
  private lateinit var extension: DefaultPabloPluginExtension

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
            loadProperties()
            configureShadowJar()
            configurePublication()
          }
        }
    )

    applyPlugins()
  }

  fun getArtifactName(): String {
    return extension.artifactName.orNull ?: project.rootProject.name + '-' + project.name
  }

  private fun createPluginExtension(): DefaultPabloPluginExtension {
    val singingConfiguration = project.objects.newInstance(DefaultSigningConfiguration::class.java, project.objects)
    val extension = project.objects.newInstance(DefaultPabloPluginExtension::class.java, singingConfiguration, project.objects)
    project.extensions.add(PabloPluginExtension::class.java, "pablo", extension)
    return extension
  }

  private fun applyPlugins() {
    project.plugins.apply("java")
    project.plugins.apply("maven-publish")
    project.plugins.apply("signing")
    project.plugins.apply("com.github.johnrengelman.shadow")
  }

  private fun loadProperties() {
    val extras = project.extensions.extraProperties
    extension.signing.keyId.orNull?.also { extras[KEY_SIGNING_KEY_ID] = it }
    extension.signing.password.orNull?.also { extras[KEY_SIGNING_PASSWORD] = it }
    extension.signing.secretKeyRingFile.orNull?.also { extras[KEY_SIGNING_SECRET_KEY_RING_FILE] = it.absolutePath }

    val propertiesFile = extension.propertiesFile.orNull ?: project.file("pablo.properties")
    if (propertiesFile.exists()) {
      val pabloProperties = Properties()
      propertiesFile.inputStream().buffered().use { pabloProperties.load(it) }
      pabloProperties.forEach { entry ->
        val key = entry.key.toString()
        if (key.startsWith(PREFIX_ROOT)) {
          if (key.startsWith(PREFIX_SIGNING)) {
            if (key == "$PREFIX_ROOT$KEY_SIGNING_SECRET_KEY_RING_FILE") {
              extras[KEY_SIGNING_SECRET_KEY_RING_FILE] = project.rootProject.file(entry.value).absolutePath
            } else {
              extras[key.removePrefix(PREFIX_ROOT)] = entry.value
            }
          } else {
            extras[key] = entry.value
          }
        }
      }
    }
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

    shadowJar.archiveClassifier.set(null as String?)
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
      task.archiveClassifier.set(SOURCES_CLASSIFIER)
    }

    val javadocJar = project.tasks.create(JAVADOC_JAR_TASK_NAME, Jar::class.java) { task ->
      val javadoc = project.tasks.getByName(JavaPlugin.JAVADOC_TASK_NAME) as Javadoc
      task.dependsOn(javadoc)
      task.from(javadoc.destinationDir)
      task.archiveClassifier.set(JAVADOC_CLASSIFIER)
    }

    project.artifacts.add(Dependency.ARCHIVES_CONFIGURATION, sourcesJar)
    project.artifacts.add(Dependency.ARCHIVES_CONFIGURATION, javadocJar)
  }

  private fun configurePublication() {
    val publishing = project.extensions.getByType(PublishingExtension::class.java)
    val signing = project.extensions.getByType(SigningExtension::class.java)

    publishing.configureRepositories()
    val publication = publishing.createPublication()
    signing.sign(publication)
  }

  private fun PublishingExtension.configureRepositories() {
    extension.repositoriesActions.forEach { repositories(it) }
    repositories { repositories ->
      val url = project.findProperty("pablo.repository.maven.url")
      if (url != null) {
        repositories.maven { repository ->
          repository.setUrl(url)

          val name = project.findProperty("pablo.repository.maven.name")
          val username = project.findProperty("pablo.repository.maven.username")
          val password = project.findProperty("pablo.repository.maven.password")

          if (name != null) {
            repository.name = name.toString()
          }

          repository.credentials { credentials ->
            credentials.username = username?.toString()
            credentials.password = password?.toString()
          }
        }
      }
    }
  }

  private fun PublishingExtension.createPublication(): Publication {
    return publications.create(PUBLICATION_NAME, MavenPublication::class.java) { publication ->
      publication.artifactId = getArtifactName()

      publication.artifact(project.tasks.getByName(SHADOW_JAR_TASK_NAME))
      publication.artifact(project.tasks.getByName(SOURCES_JAR_TASK_NAME))
      publication.artifact(project.tasks.getByName(JAVADOC_JAR_TASK_NAME))

      publication.setPomDefaults()
      extension.pomActions.forEach { publication.pom(it) }
      publication.pom.withXml { xml ->
        val root = xml.asNode()
        val dependenciesNode = root.appendNode("dependencies")
        val resolvedDependencies = DependencyResolver.resolve(project)
        dependenciesNode.addDependenciesToPom(resolvedDependencies)
      }
    }
  }

  private fun MavenPublication.setPomDefaults() {
    pom { pom ->
      withProjectProperty("pablo.pom.packaging") { pom.packaging = it }
      pom.name.maybeSetFromProjectProperty("pablo.pom.name")
      pom.description.maybeSetFromProjectProperty("pablo.pom.description")
      pom.inceptionYear.maybeSetFromProjectProperty("pablo.pom.inceptionYear")
      pom.url.maybeSetFromProjectProperty("pablo.pom.url")

      val licenseName = findProjectProperty("pablo.pom.license.name")
      val licenseUrl = findProjectProperty("pablo.pom.license.url")
      val licenseDistribution = findProjectProperty("pablo.pom.license.distribution")
      if (licenseName != null || licenseUrl != null || licenseDistribution != null) {
        pom.licenses { licenses ->
          licenses.license { license ->
            license.name.maybeSet(licenseName)
            license.url.maybeSet(licenseUrl)
            license.distribution.maybeSet(licenseDistribution)
          }
        }
      }

      val developerId = findProjectProperty("pablo.pom.developer.id")
      val developerName = findProjectProperty("pablo.pom.developer.name")
      val developerEmail = findProjectProperty("pablo.pom.developer.email")
      if (developerId != null || developerName != null || developerEmail != null) {
        pom.developers { developers ->
          developers.developer { developer ->
            developer.id.maybeSet(developerId)
            developer.name.maybeSet(developerName)
            developer.email.maybeSet(developerEmail)
          }
        }
      }

      val scmConnection = findProjectProperty("pablo.pom.scm.connection")
      val scmDeveloperConnection = findProjectProperty("pablo.pom.scm.developerConnection")
      val scmUrl = findProjectProperty("pablo.pom.scm.url")
      if (scmConnection != null || scmDeveloperConnection != null || scmUrl != null) {
        pom.scm { scm ->
          scm.connection.maybeSet(scmConnection)
          scm.developerConnection.maybeSet(scmDeveloperConnection)
          scm.url.maybeSet(scmUrl)
        }
      }
    }
  }

  private fun Property<String>.maybeSetFromProjectProperty(name: String) {
    withProjectProperty(name) { set(it) }
  }

  private fun Property<String>.maybeSet(value: String?) {
    if (value != null) {
      set(value)
    }
  }

  private fun findProjectProperty(name: String): String? {
    return project.findProperty(name) as? String
  }

  private inline fun withProjectProperty(name: String, action: (String) -> Unit) {
    val value = findProjectProperty(name) ?: return
    action(value)
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
    private const val PREFIX_ROOT = "pablo."
    private const val PREFIX_SIGNING = "pablo.signing."

    private const val KEY_SIGNING_KEY_ID = "signing.keyId"
    private const val KEY_SIGNING_PASSWORD = "signing.password"
    private const val KEY_SIGNING_SECRET_KEY_RING_FILE = "signing.secretKeyRingFile"

    private const val PUBLICATION_NAME = "maven"

    private const val SHADOW_JAR_TASK_NAME = "shadowJar"
    private const val SOURCES_JAR_TASK_NAME = "sourcesJar"
    private const val JAVADOC_JAR_TASK_NAME = "javadocJar"

    private const val SOURCES_CLASSIFIER = "sources"
    private const val JAVADOC_CLASSIFIER = "javadoc"

    const val RELOCATE_CONFIGURATION_NAME = "relocate"
  }
}
