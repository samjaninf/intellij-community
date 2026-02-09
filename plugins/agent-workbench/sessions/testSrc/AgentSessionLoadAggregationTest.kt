// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentSessionLoadAggregationTest {
  @Test
  fun returnsErrorWhenAllSourcesFail() {
    val codexFailure = IllegalStateException("codex failed")
    val claudeFailure = IllegalStateException("claude failed")

    val result = mergeAgentSessionSourceLoadResults(
      sourceResults = listOf(
        AgentSessionSourceLoadResult(AgentSessionProvider.CODEX, Result.failure(codexFailure)),
        AgentSessionSourceLoadResult(AgentSessionProvider.CLAUDE, Result.failure(claudeFailure)),
      ),
      resolveErrorMessage = { provider, _ -> "${provider.name} unavailable" },
    )

    assertThat(result.threads).isEmpty()
    assertThat(result.errorMessage).isEqualTo("CODEX unavailable")
  }

  @Test
  fun doesNotReportErrorWhenAnySourceSucceeds() {
    val codexFailure = IllegalStateException("codex failed")

    val result = mergeAgentSessionSourceLoadResults(
      sourceResults = listOf(
        AgentSessionSourceLoadResult(AgentSessionProvider.CODEX, Result.failure(codexFailure)),
        AgentSessionSourceLoadResult(AgentSessionProvider.CLAUDE, Result.success(emptyList())),
      ),
      resolveErrorMessage = { provider, _ -> "${provider.name} unavailable" },
    )

    assertThat(result.threads).isEmpty()
    assertThat(result.errorMessage).isNull()
  }

  @Test
  fun mergesSuccessfulResultsAndSortsByUpdatedTime() {
    val codexThread = AgentSessionThread(
      id = "codex-1",
      title = "Codex Session",
      updatedAt = 100,
      archived = false,
      provider = AgentSessionProvider.CODEX,
    )
    val claudeThread = AgentSessionThread(
      id = "claude-1",
      title = "Claude Session",
      updatedAt = 200,
      archived = false,
      provider = AgentSessionProvider.CLAUDE,
    )

    val result = mergeAgentSessionSourceLoadResults(
      sourceResults = listOf(
        AgentSessionSourceLoadResult(AgentSessionProvider.CODEX, Result.success(listOf(codexThread))),
        AgentSessionSourceLoadResult(AgentSessionProvider.CLAUDE, Result.success(listOf(claudeThread))),
      ),
      resolveErrorMessage = { _, _ -> "error" },
    )

    assertThat(result.errorMessage).isNull()
    assertThat(result.threads.map { it.id }).containsExactly("claude-1", "codex-1")
  }
}
