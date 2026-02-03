// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

internal object AgentWorkbenchDedicatedFrameProjectManager {
  private val projectPath: Path by lazy {
    PathManager.getConfigDir().resolve("agent-workbench-chat-frame")
  }

  fun dedicatedProjectPath(): String {
    return projectPath.invariantSeparatorsPathString
  }

  fun ensureProjectPath(): Path {
    val path = projectPath
    Files.createDirectories(path)
    return path
  }

  fun isDedicatedProjectPath(path: String): Boolean {
    return normalizePath(path) == dedicatedProjectPath()
  }

  suspend fun configureProject(project: Project) {
    (serviceAsync<RecentProjectsManager>() as RecentProjectsManagerBase).setProjectHidden(project, true)
    TrustedProjects.setProjectTrusted(project, true)
  }

  private fun normalizePath(path: String): String {
    return try {
      Path.of(path).invariantSeparatorsPathString
    }
    catch (_: InvalidPathException) {
      path
    }
  }
}
