// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
class AgentSessionsServiceOnDemandIntegrationTest {
  @Test
  fun loadProjectThreadsOnDemandDeduplicatesConcurrentRequests() = runBlocking {
    val invocationCount = AtomicInteger(0)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    withService(
      sessionSources = listOf(
        ScriptedSessionSource(
          provider = AgentSessionProvider.CODEX,
          canReportExactThreadCount = false,
          listFromClosedProject = { path ->
            if (path != PROJECT_PATH) {
              emptyList()
            }
            else {
              invocationCount.incrementAndGet()
              started.complete(Unit)
              release.await()
              listOf(thread(id = "codex-1", updatedAt = 100, provider = AgentSessionProvider.CODEX))
            }
          },
        ),
      ),
      projectEntriesProvider = {
        listOf(closedProjectEntry(PROJECT_PATH, "Project A"))
      },
    ) { service ->
      service.refresh()
      waitForCondition {
        service.state.value.projects.any { it.path == PROJECT_PATH }
      }

      service.loadProjectThreadsOnDemand(PROJECT_PATH)
      started.await()
      service.loadProjectThreadsOnDemand(PROJECT_PATH)
      release.complete(Unit)

      waitForCondition {
        service.state.value.projects.firstOrNull { it.path == PROJECT_PATH }?.hasLoaded == true
      }

      val project = service.state.value.projects.single { it.path == PROJECT_PATH }
      assertThat(invocationCount.get()).isEqualTo(1)
      assertThat(project.threads.map { it.id }).containsExactly("codex-1")
    }
  }

  @Test
  fun loadWorktreeThreadsOnDemandDeduplicatesConcurrentRequestsWhileRefreshLoadsWorktree() = runBlocking {
    val invocationCount = AtomicInteger(0)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    withService(
      sessionSources = listOf(
        ScriptedSessionSource(
          provider = AgentSessionProvider.CODEX,
          canReportExactThreadCount = false,
          listFromClosedProject = { path ->
            if (path != WORKTREE_PATH) {
              emptyList()
            }
            else {
              invocationCount.incrementAndGet()
              started.complete(Unit)
              release.await()
              listOf(thread(id = "wt-codex-1", updatedAt = 100, provider = AgentSessionProvider.CODEX))
            }
          },
        ),
      ),
      projectEntriesProvider = {
        listOf(
          closedProjectEntry(
            PROJECT_PATH,
            "Project A",
            worktrees = listOf(
              AgentSessionsService.WorktreeEntry(
                path = WORKTREE_PATH,
                name = "project-feature",
                branch = "feature",
                project = null,
              )
            ),
          )
        )
      },
    ) { service ->
      service.refresh()
      waitForCondition {
        service.state.value.projects.firstOrNull { it.path == PROJECT_PATH }?.worktrees?.isNotEmpty() == true
      }

      service.loadWorktreeThreadsOnDemand(PROJECT_PATH, WORKTREE_PATH)
      started.await()
      service.loadWorktreeThreadsOnDemand(PROJECT_PATH, WORKTREE_PATH)
      release.complete(Unit)

      waitForCondition {
        service.state.value.projects.firstOrNull { it.path == PROJECT_PATH }
          ?.worktrees
          ?.firstOrNull { it.path == WORKTREE_PATH }
          ?.hasLoaded == true
      }

      val worktree = service.state.value.projects
        .single { it.path == PROJECT_PATH }
        .worktrees
        .single { it.path == WORKTREE_PATH }
      // refresh() always loads closed worktrees once; the second on-demand request should still be deduplicated.
      assertThat(invocationCount.get()).isEqualTo(2)
      assertThat(worktree.threads.map { it.id }).containsExactly("wt-codex-1")
    }
  }
}

