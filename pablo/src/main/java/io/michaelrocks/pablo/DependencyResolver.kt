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

import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.plugins.JavaPlugin

internal class DependencyResolver private constructor(
  private val project: Project
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
    relocateTransitively(project)
    return builder.build()
  }

  private fun resolve(project: Project) {
    project.configurations.forEach { configuration ->
      val scope = Scope.fromConfigurationName(configuration.name)
      if (scope != null) {
        configuration.dependencies.forEach { resolve(it, scope) }
        configuration.dependencyConstraints.forEach { resolve(it, scope) }
      }

      if (scope == Scope.RELOCATE) {
        configuration.resolvedConfiguration
          .getFirstLevelModuleDependencies() { it !is SelfResolvingDependency }
          .forEach { resolvedDependency ->
            resolvedDependency.children.forEach { childResolvedDependency ->
              val childScope = Scope.fromConfigurationName(childResolvedDependency.configuration)
              if (childScope != null) {
                builder.addModuleId(childScope, SimpleModuleVersionIdentifier.from(childResolvedDependency))
              }
            }
          }
      }
    }
  }

  private fun resolve(dependency: Dependency, scope: Scope) {
    when (dependency) {
      is ProjectDependency -> {
        val dependencyProject = dependency.dependencyProject
        val projectId = checkNotNull(mapping[dependencyProject.path]) {
          "Cannot find project ${dependencyProject.path} in ${project.path}: $mapping"
        }

        builder.addModuleId(scope, projectId)
        if (scope == Scope.RELOCATE) {
          resolve(dependencyProject)
        }
      }

      is SelfResolvingDependency -> {
        // Do nothing.
      }

      else -> {
        val moduleId = SimpleModuleVersionIdentifier.from(dependency)
        builder.addModuleId(scope, moduleId)
      }
    }
  }

  private fun resolve(dependencyConstraint: DependencyConstraint, scope: Scope) {
    val moduleId = SimpleModuleVersionIdentifier.from(dependencyConstraint)
    builder.addModuleId(scope, moduleId)
  }

  private fun relocateTransitively(project: Project) {
    project.configurations.forEach { configuration ->
      if (configuration.isCanBeResolved) {
        val scope = Scope.fromConfigurationName(configuration.name)
        if (scope != null && scope != Scope.PROVIDED) {
          configuration.resolvedConfiguration.firstLevelModuleDependencies.forEach { resolvedDependency ->
            relocateTransitively(resolvedDependency)
          }
        }
      }
    }

    val relocateConfiguration = project.configurations.findByName(PabloPlugin.RELOCATE_CONFIGURATION_NAME) ?: return
    relocateConfiguration.dependencies.forEach { dependency ->
      if (dependency is ProjectDependency) {
        relocateTransitively(dependency.dependencyProject)
      }
    }
  }

  private fun relocateTransitively(resolvedDependency: ResolvedDependency): Boolean {
    val moduleId = SimpleModuleVersionIdentifier.from(resolvedDependency)
    if (resolvedDependency.children.any { relocateTransitively(it) }) {
      builder.addModuleId(Scope.RELOCATE, moduleId)
      return true
    }

    return builder.hasModuleId(Scope.RELOCATE, moduleId)
  }

  class DependencyResolutionResult(
    val scopeToModuleIdMap: Map<Scope, Collection<ModuleVersionIdentifier>>,
  ) {

    private val relocatableIds: Set<ModuleVersionIdentifier> by lazy {
      scopeToModuleIdMap[Scope.RELOCATE].orEmpty().toSet()
    }

    fun shouldBeRelocated(resolvedDependency: ResolvedDependency): Boolean {
      return SimpleModuleVersionIdentifier.from(resolvedDependency) in relocatableIds
    }

    class Builder {
      private val moduleIdsByScope = mutableMapOf<Scope, MutableSet<ModuleVersionIdentifier>>()

      fun addModuleId(scope: Scope, moduleId: ModuleVersionIdentifier) = apply {
        val moduleIds = moduleIdsByScope.getOrPut(scope) { mutableSetOf() }
        moduleIds += moduleId
      }

      fun hasModuleId(scope: Scope, moduleId: ModuleVersionIdentifier): Boolean {
        val moduleIds = moduleIdsByScope[scope] ?: return false
        return moduleId in moduleIds
      }

      fun build(): DependencyResolutionResult {
        val versionByModuleId =
          moduleIdsByScope
            .flatMap { it.value }
            .groupBy(
              { it.withVersion("") },
              { DefaultArtifactVersion(it.version) }
            )
            .mapValues { it.value.maxOrNull() }

        val processedModuleIds = mutableSetOf<ModuleVersionIdentifier>()
        val resolvedModuleIdsByScope = mutableMapOf<Scope, MutableSet<ModuleVersionIdentifier>>()
        for (scope in Scope.values()) {
          moduleIdsByScope[scope]?.also { dependencies ->
            resolvedModuleIdsByScope[scope] = dependencies.mapNotNullTo(mutableSetOf()) {
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

        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.COMPILE, Scope.RELOCATE)
        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.RUNTIME, Scope.RELOCATE)
        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.PROVIDED, Scope.RELOCATE)
        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.RUNTIME, Scope.COMPILE)
        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.PROVIDED, Scope.COMPILE)
        resolvedModuleIdsByScope.removeRedundantModuleIds(Scope.PROVIDED, Scope.RUNTIME)

        return DependencyResolutionResult(resolvedModuleIdsByScope)
      }

      private fun ModuleVersionIdentifier.withVersion(version: String): ModuleVersionIdentifier {
        return if (this.version == version) this else SimpleModuleVersionIdentifier(module, version)
      }

      private fun Map<Scope, MutableSet<ModuleVersionIdentifier>>.removeRedundantModuleIds(targetScope: Scope, sourceScope: Scope) {
        val targetModuleIds = get(targetScope) ?: return
        val sourceModuleIds = get(sourceScope) ?: return
        targetModuleIds -= sourceModuleIds
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

    override fun toString(): String {
      return "$group:$name:${version.ifEmpty { "*" }}"
    }

    companion object {
      fun from(project: Project): SimpleModuleVersionIdentifier {
        return SimpleModuleVersionIdentifier(project.group.toString(), project.name, project.version.toString())
      }

      fun from(dependency: Dependency): SimpleModuleVersionIdentifier {
        return SimpleModuleVersionIdentifier(dependency.group!!, dependency.name, dependency.version!!)
      }

      fun from(constraint: DependencyConstraint): SimpleModuleVersionIdentifier {
        return SimpleModuleVersionIdentifier(constraint.group, constraint.name, constraint.version ?: "")
      }

      fun from(dependency: ResolvedDependency): SimpleModuleVersionIdentifier {
        return SimpleModuleVersionIdentifier(dependency.moduleGroup, dependency.moduleName, dependency.moduleVersion)
      }
    }
  }

  private data class SimpleModuleIdentifier(
    private val group: String,
    private val name: String
  ) : ModuleIdentifier {

    override fun getGroup(): String = group
    override fun getName(): String = name

    override fun toString(): String {
      return "$group:$name"
    }
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
    fun resolve(project: Project): DependencyResolutionResult {
      return DependencyResolver(project).resolve()
    }
  }
}
