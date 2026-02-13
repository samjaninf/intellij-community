// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions.providers.codex

import com.intellij.agent.workbench.codex.common.CodexThread
import com.intellij.agent.workbench.sessions.AgentSessionActivity
import com.intellij.openapi.project.Project

internal data class CodexBackendThread(
  val thread: CodexThread,
  val activity: AgentSessionActivity = AgentSessionActivity.READY,
)

internal interface CodexSessionBackend {
  suspend fun listThreads(path: String, openProject: Project?): List<CodexBackendThread>
}

