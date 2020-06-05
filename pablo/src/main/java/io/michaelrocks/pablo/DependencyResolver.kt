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

import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.plugins.JavaPlugin

class DependencyResolver private constructor(
  private val project: Project,
  private val traverseRelocateDependencies: Boolean
) {

  private val mapping = buildModuleIdMapping()
  private val builder = DependencyResolutionResult.Builder()

  private fun buildModuleIdMapping(): Map<String, ModuleVersionIdentifier> {
    val mapping = HashMap<String, ModuleVersionIdentifier>()
    buildModuleIdMapping(project.rootProject, mapping)
    return mapping
  }

  private fun buildModuleIdMapping(project: Project, mapping: MutableMap<String, ModuleVersionIdentifier>) {
    project.plugins.findPlugin("io.michaelrocks.pablo")?.also { plugin ->
      val group = project.group.toString()
      // Call the method via reflection to handle the case when two or more instances of the plugin are loaded using
      // different class loaders.
      val name = plugin.javaClass.getMethod("getArtifactName").invoke(plugin).toString()
      val version = project.version.toString()
      val id = SimpleModuleVersionIdentifier(group, name, version)
      mapping[project.path] = id
    }

    project.subprojects.forEach { subproject ->
      buildModuleIdMapping(subproject, mapping)
    }
  }

  private fun resolve(): DependencyResolutionResult {
    resolve(project)
    return builder.build()
  }

  private fun resolve(project: Project) {
    project.configurations.forEach { configuration ->
      val scope = Scope.fromConfigurationName(configuration.name)
      if (scope != null) {
        configuration.dependencies.forEach { resolve(it, scope) }
        configuration.dependencyConstraints.forEach { resolve(it, scope) }
      }
    }
  }

  private fun resolve(dependency: Dependency, scope: Scope) {
    when (dependency) {
      is ProjectDependency -> {
        val project = dependency.dependencyProject
        val projectId = checkNotNull(mapping[project.path]) {
          "Cannot find project ${project.path} in ${this.project.path}: $mapping"
        }

        builder.addModuleId(scope, projectId)
        if (scope == Scope.RELOCATE) {
          val originalProjectId = SimpleModuleVersionIdentifier(project.group.toString(), project.name, project.version.toString())
          builder.addModuleIdMapping(originalProjectId, projectId)
          if (traverseRelocateDependencies) {
            resolve(project)
          }
        }
      }

      is SelfResolvingDependency -> {
        // Do nothing.
      }

      else -> {
        val moduleId = SimpleModuleVersionIdentifier(dependency.group!!, dependency.name, dependency.version!!)
        builder.addModuleId(scope, moduleId)
      }
    }
  }

  private fun resolve(dependencyConstraint: DependencyConstraint, scope: Scope) {
    val moduleId = SimpleModuleVersionIdentifier(dependencyConstraint.group, dependencyConstraint.name, dependencyConstraint.version ?: "")
    builder.addModuleId(scope, moduleId)
  }

  data class DependencyResolutionResult(
    val scopeToModuleIdMap: Map<Scope, List<ModuleVersionIdentifier>>,
    val dependencyToDependencyMap: Map<ModuleVersionIdentifier, ModuleVersionIdentifier>
  ) {

    class Builder {
      private val moduleIdsByScope = mutableMapOf<Scope, MutableList<ModuleVersionIdentifier>>()
      private val moduleIdMapping = mutableMapOf<ModuleVersionIdentifier, ModuleVersionIdentifier>()

      fun addModuleId(scope: Scope, moduleId: ModuleVersionIdentifier) = apply {
        val moduleIds = moduleIdsByScope.getOrPut(scope) { mutableListOf() }
        moduleIds += moduleId
      }

      fun addModuleIdMapping(source: ModuleVersionIdentifier, target: ModuleVersionIdentifier) = apply {
        moduleIdMapping[source] = target
      }

      fun build(): DependencyResolutionResult {
        val versionByModuleId =
          moduleIdsByScope
            .flatMap { it.value }
            .groupBy(
              { it.withVersion("") },
              { DefaultArtifactVersion(it.version) }
            )
            .mapValues { it.value.max() }

        val processedModuleIds = mutableSetOf<ModuleVersionIdentifier>()
        val resolvedModuleIdsByScope = mutableMapOf<Scope, List<ModuleVersionIdentifier>>()
        for (scope in Scope.values()) {
          moduleIdsByScope[scope]?.also { dependencies ->
            resolvedModuleIdsByScope[scope] = dependencies.mapNotNull {
              val moduleId = it.withVersion("")
              if (processedModuleIds.add(moduleId)) {
                val version = checkNotNull(versionByModuleId[moduleId])
                moduleId.withVersion(version.toString())
              } else {
                null
              }
            }
          }
        }

        return DependencyResolutionResult(resolvedModuleIdsByScope, moduleIdMapping.toMap())
      }

      private fun ModuleVersionIdentifier.withVersion(version: String): ModuleVersionIdentifier {
        return if (this.version == version) this else SimpleModuleVersionIdentifier(module, version)
      }
    }
  }

  private data class SimpleModuleVersionIdentifier(
    private val id: ModuleIdentifier,
    private val version: String
  ) : ModuleVersionIdentifier {

    constructor(group: String, name: String, version: String) : this(SimpleModuleIdentifier(group, name), version)

    override fun getModule(): ModuleIdentifier = id
    override fun getGroup(): String = id.group
    override fun getName(): String = id.name
    override fun getVersion(): String = version
  }

  private data class SimpleModuleIdentifier(
    private val group: String,
    private val name: String
  ) : ModuleIdentifier {

    override fun getGroup(): String = group
    override fun getName(): String = name
  }

  enum class Scope {
    RELOCATE,
    COMPILE,
    RUNTIME,
    PROVIDED;

    companion object {
      @Suppress("DEPRECATION")
      fun fromConfigurationName(configurationName: String): Scope? {
        return when (configurationName) {
          PabloPlugin.RELOCATE_CONFIGURATION_NAME ->
            RELOCATE
          JavaPlugin.COMPILE_CONFIGURATION_NAME,
          JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
          JavaPlugin.API_CONFIGURATION_NAME ->
            COMPILE
          JavaPlugin.RUNTIME_CONFIGURATION_NAME ->
            RUNTIME
          JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME,
          JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME ->
            PROVIDED
          else -> null
        }
      }
    }
  }

  companion object {
    fun resolve(project: Project, traverseRelocateDependencies: Boolean): DependencyResolutionResult {
      return DependencyResolver(project, traverseRelocateDependencies).resolve()
    }
  }
}
