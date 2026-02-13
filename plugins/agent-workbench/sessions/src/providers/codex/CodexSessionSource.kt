// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions.providers.codex

import com.intellij.agent.workbench.sessions.AgentSessionProvider
import com.intellij.agent.workbench.sessions.AgentSessionThread
import com.intellij.agent.workbench.sessions.AgentSubAgent
import com.intellij.agent.workbench.sessions.providers.BaseAgentSessionSource
import com.intellij.openapi.project.Project

internal class CodexSessionSource(
  private val sessionBackend: CodexSessionBackend = CodexRolloutSessionBackend(),
) : BaseAgentSessionSource(provider = AgentSessionProvider.CODEX, canReportExactThreadCount = false) {
  override suspend fun listThreads(path: String, openProject: Project?): List<AgentSessionThread> {
    return sessionBackend.listThreads(path = path, openProject = openProject).map { it.toAgentSessionThread() }
  }
}

private fun CodexBackendThread.toAgentSessionThread(): AgentSessionThread {
  val thread = thread
  return AgentSessionThread(
    id = thread.id,
    title = thread.title,
    updatedAt = thread.updatedAt,
    archived = thread.archived,
    activity = activity,
    provider = AgentSessionProvider.CODEX,
    subAgents = thread.subAgents.map { AgentSubAgent(it.id, it.name) },
    originBranch = thread.gitBranch,
  )
}
