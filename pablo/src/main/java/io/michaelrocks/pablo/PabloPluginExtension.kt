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

import org.gradle.api.Action
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPom
import java.io.File
import javax.inject.Inject

interface PabloPluginExtension {
  val propertiesFile: Property<File>
  val artifactName: Property<String>

  fun repositories(configure: Action<in RepositoryHandler>)
  fun pom(configure: Action<in MavenPom>)
  fun signing(configure: Action<in SigningConfiguration>)
  fun shadow(configure: Action<in ShadowConfiguration>)
}

internal open class DefaultPabloPluginExtension @Inject constructor(
  val signing: SigningConfiguration,
  val shadow: InternalShadowConfiguration,
  objectFactory: ObjectFactory
) : PabloPluginExtension {

  override val propertiesFile: Property<File> = objectFactory.property(File::class.java)
  override val artifactName: Property<String> = objectFactory.property(String::class.java)

  val repositoriesActions: MutableList<Action<in RepositoryHandler>> = ArrayList()
  val pomActions: MutableList<Action<in MavenPom>> = ArrayList()

  override fun repositories(configure: Action<in RepositoryHandler>) {
    repositoriesActions += configure
  }

  override fun pom(configure: Action<in MavenPom>) {
    pomActions += configure
  }

  override fun signing(configure: Action<in SigningConfiguration>) {
    configure.execute(signing)
  }

  override fun shadow(configure: Action<in ShadowConfiguration>) {
    configure.execute(shadow)
  }
}
