// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.gradle.configuration

import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.model.GradleBuildScriptClasspathModel
import org.jetbrains.plugins.gradle.model.data.BuildScriptClasspathData
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.util.GradleConstants

fun buildClasspathData(
    gradleModule: IdeaModule,
    resolverCtx: ProjectResolverContext
): BuildScriptClasspathData {
    val classpathModel = resolverCtx.getExtraProject(gradleModule, GradleBuildScriptClasspathModel::class.java)
    val classpathEntries = classpathModel?.classpath?.map {
        BuildScriptClasspathData.ClasspathEntry.create(it.classes, it.sources, it.javadoc)
    } ?: emptyList()
    return BuildScriptClasspathData(GradleConstants.SYSTEM_ID, classpathEntries).also {
        it.gradleHomeDir = classpathModel?.gradleHomeDir
    }
}
