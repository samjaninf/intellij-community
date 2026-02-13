// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions.providers.codex

import com.intellij.agent.workbench.codex.common.CodexAppServerClient
import com.intellij.agent.workbench.codex.common.CodexAppServerException
import com.intellij.agent.workbench.sessions.codex.CodexProjectSessionService
import com.intellij.agent.workbench.sessions.codex.resolveProjectDirectoryFromPath
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

@Suppress("unused")
internal class CodexAppServerSessionBackend(
  private val coroutineScope: CoroutineScope,
) : CodexSessionBackend {
  override suspend fun listThreads(path: String, openProject: Project?): List<CodexBackendThread> {
    if (openProject != null) {
      return listThreadsFromOpenProject(openProject)
    }
    return listThreadsFromClosedPath(path)
  }

  private suspend fun listThreadsFromOpenProject(project: Project): List<CodexBackendThread> {
    val service = project.getService(CodexProjectSessionService::class.java)
    if (service == null || !service.hasWorkingDirectory()) {
      throw CodexAppServerException("Project directory is not available")
    }
    return service.listThreads().map(::CodexBackendThread)
  }

  private suspend fun listThreadsFromClosedPath(path: String): List<CodexBackendThread> {
    val workingDirectory = resolveProjectDirectoryFromPath(path)
      ?: return emptyList()
    val client = CodexAppServerClient(coroutineScope = coroutineScope, workingDirectory = workingDirectory)
    try {
      return client.listThreads(archived = false).map(::CodexBackendThread)
    }
    finally {
      client.shutdown()
    }
  }
}
