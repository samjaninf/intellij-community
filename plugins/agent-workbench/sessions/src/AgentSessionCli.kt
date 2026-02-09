// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

internal fun buildAgentSessionResumeCommand(provider: AgentSessionProvider, sessionId: String): List<String> {
  return when (provider) {
    AgentSessionProvider.CODEX -> listOf("codex", "resume", sessionId)
    AgentSessionProvider.CLAUDE -> listOf("claude", "--resume", sessionId)
  }
}

internal fun buildAgentSessionIdentity(provider: AgentSessionProvider, sessionId: String): String {
  return "${provider.name}:$sessionId"
}

internal fun agentSessionCliMissingMessageKey(provider: AgentSessionProvider): String {
  return when (provider) {
    AgentSessionProvider.CODEX -> "toolwindow.error.cli"
    AgentSessionProvider.CLAUDE -> "toolwindow.error.claude.cli"
  }
}
