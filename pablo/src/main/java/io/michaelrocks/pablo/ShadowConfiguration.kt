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
import javax.inject.Inject

interface ShadowConfiguration {
  fun relocate(destination: String)
  fun relocate(pattern: String, destination: String)
}

internal open class DefaultShadowConfiguration @Inject constructor(
  private val shadowJar: ShadowJar
) : ShadowConfiguration {

  override fun relocate(destination: String) {
    relocate(destination, destination)
  }

  override fun relocate(pattern: String, destination: String) {
    shadowJar.relocate(pattern, destination)
  }
}
