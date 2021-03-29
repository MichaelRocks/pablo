[![Build Status](https://travis-ci.org/MichaelRocks/pablo.svg?branch=master)](https://travis-ci.org/MichaelRocks/pablo)

Pablo
=====

A plugin for repackaging build artifacts and publishing them to a maven repository.
I use this plugin in my projects, and you probably don't need it.

Usage
-----
```groovy
buildscript {
  repositories {
    mavenCentral()
  }

  dependencies {
    classpath 'io.michaelrocks:pablo:pablo:1.1.1'
  }
}

apply plugin: 'java'
apply plugin: 'io.michaelrocks.pablo'

pablo {
  propertiesFile = /* A file to load properties from or pablo.properties by default. */
  artifactName = /* A name of the artifact to publish or the project's name by default. */
  
  repositories {
    // Configure Maven repositories to publish to like in maven-publish.
    // Another way to configure a repository is using properties:
    // - pablo.repository.maven.name
    // - pablo.repository.maven.url
    // - pablo.repository.maven.username
    // - pablo.repository.maven.password
  }
  pom {
    // Configure the content of pom.xml like in maven-publish.
    // Another way to configure pom.xml is using properties:
    // - pablo.pom.packaging
    // - pablo.pom.name
    // - pablo.pom.description
    // - pablo.pom.inceptionYear
    // - pablo.pom.url
    // - pablo.pom.license.name
    // - pablo.pom.license.url
    // - pablo.pom.license.distribution
    // - pablo.pom.developer.id
    // - pablo.pom.developer.name
    // - pablo.pom.developer.email
    // - pablo.pom.scm.connection
    // - pablo.pom.scm.developerConnection
    // - pablo.pom.scm.url
  }
  signing {
    keyId = /* A keyId for GPG signing, can be set with pablo.signing.keyId property. */
    password = /* A password for GPG signing, can be set with pablo.signing.password property. */
    secretKeyRingFile = /* A file to load a key ring from, can be set with pablo.signing.secretKeyRingFile property. */
  }
  shadow {
    // Specify what packages should be relocated and how:
    // - relocate(packageName)
    // - relocate(fromPackageName, toPackageName)
  }
}
```

License
=======
    Copyright 2021 Michael Rozumyanskiy

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
