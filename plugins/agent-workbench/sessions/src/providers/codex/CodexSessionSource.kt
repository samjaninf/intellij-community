// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions.providers.codex

// @spec community/plugins/agent-workbench/spec/agent-sessions.spec.md
// @spec community/plugins/agent-workbench/spec/agent-sessions-codex-rollout-source.spec.md

import com.intellij.agent.workbench.codex.common.CodexThread
import com.intellij.agent.workbench.codex.common.normalizeRootPath
import com.intellij.agent.workbench.sessions.AgentSessionProvider
import com.intellij.agent.workbench.sessions.AgentSessionThread
import com.intellij.agent.workbench.sessions.AgentSubAgent
import com.intellij.agent.workbench.sessions.codex.SharedCodexAppServerService
import com.intellij.agent.workbench.sessions.codex.resolveProjectDirectoryFromPath
import com.intellij.agent.workbench.sessions.providers.BaseAgentSessionSource
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlin.io.path.invariantSeparatorsPathString

internal class CodexSessionSource : BaseAgentSessionSource(provider = AgentSessionProvider.CODEX, canReportExactThreadCount = false) {
  override suspend fun listThreads(path: String, openProject: Project?): List<AgentSessionThread> {
    val workingDirectory = resolveProjectDirectoryFromPath(path) ?: return emptyList()
    val codexService = service<SharedCodexAppServerService>()
    val threads = codexService.listThreads(workingDirectory)
    return threads.map { it.toAgentSessionThread() }
  }

  override suspend fun prefetchThreads(paths: List<String>): Map<String, List<AgentSessionThread>> {
    if (paths.isEmpty()) return emptyMap()
    val codexService = service<SharedCodexAppServerService>()
    val allThreads = codexService.listAllThreads()
    val pathToCwd = paths.mapNotNull { path ->
      resolveProjectDirectoryFromPath(path)?.let { dir ->
        path to normalizeRootPath(dir.invariantSeparatorsPathString)
      }
    }
    return pathToCwd.associate { (path, cwdFilter) ->
      val matching = allThreads.filter { it.cwd == cwdFilter }
      path to matching.map { it.toAgentSessionThread() }
    }
  }
}

private fun CodexThread.toAgentSessionThread(): AgentSessionThread {
  return AgentSessionThread(
    id = id,
    title = title,
    updatedAt = updatedAt,
    archived = archived,
    provider = AgentSessionProvider.CODEX,
    subAgents = subAgents.map { AgentSubAgent(it.id, it.name) },
    originBranch = gitBranch,
  )
}
