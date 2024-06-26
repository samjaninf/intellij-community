// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.testFramework

import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.testFramework.fixtures.FileTestFixture
import org.jetbrains.plugins.gradle.testFramework.fixtures.GradleProjectTestFixture
import org.jetbrains.plugins.gradle.testFramework.fixtures.impl.GradleProjectTestFixtureImpl
import org.jetbrains.plugins.gradle.tooling.JavaVersionRestriction

interface GradleTestFixtureBuilder {

  val projectName: String

  fun createFixture(gradleVersion: GradleVersion): GradleProjectTestFixture

  companion object {

    fun create(
      projectName: String,
      javaVersionRestriction: JavaVersionRestriction = JavaVersionRestriction.NO,
      configure: FileTestFixture.Builder.(GradleVersion) -> Unit
    ): GradleTestFixtureBuilder {
      return object : GradleTestFixtureBuilder {
        override val projectName: String = projectName
        override fun createFixture(gradleVersion: GradleVersion): GradleProjectTestFixture {
          return GradleProjectTestFixtureImpl(projectName, gradleVersion, javaVersionRestriction) {
            configure(gradleVersion)
          }
        }
      }
    }
  }
}