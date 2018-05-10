[![Build Status](https://travis-ci.org/MichaelRocks/pablo.svg?branch=master)](https://travis-ci.org/MichaelRocks/pablo)

Pablo
=====

A plugin for repackaging build artifacts and publishing them to Bintray.
I use this plugin in my projects and you probably don't need it.

Usage
-----
```groovy
buildscript {
  repositories {
    jcenter()
  }

  dependencies {
    classpath 'io.michaelrocks:pablo:pablo:1.0.1'
  }
}

apply plugin: 'java'
apply plugin: 'io.michaelrocks.pablo'
```

License
=======
    Copyright 2018 Michael Rozumyanskiy

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
