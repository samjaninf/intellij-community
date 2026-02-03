// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.agent.workbench.codex.common.CodexAppServerClient
import com.intellij.agent.workbench.codex.common.CodexAppServerException
import com.intellij.agent.workbench.codex.common.CodexProjectSessions
import com.intellij.agent.workbench.codex.common.CodexSessionsState
import com.intellij.agent.workbench.codex.common.CodexThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.BorderColors
import org.jetbrains.jewel.foundation.DisabledAppearanceValues
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.GlobalMetrics
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.TextColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.ThemeColorPalette
import org.jetbrains.jewel.foundation.theme.ThemeDefinition
import org.jetbrains.jewel.foundation.theme.ThemeIconData
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.LocalTypography
import org.jetbrains.jewel.ui.Typography
import org.jetbrains.jewel.ui.icon.LocalNewUiChecker
import org.jetbrains.jewel.ui.icon.NewUiChecker
import org.jetbrains.jewel.ui.theme.BaseJewelTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class CodexSessionsToolWindowTest {
  @get:Rule
  val composeRule: ComposeContentTestRule = createComposeRule()

  @Test
  fun emptyStateIsShownWhenNoProjects() {
    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = emptyList(), lastUpdatedAt = 1L),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.empty.global"))
      .assertIsDisplayed()
  }

  @Test
  fun projectsDoNotShowGlobalEmptyStateWhenNoThreadsLoadedYet() {
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
      ),
      CodexProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText("Project B").assertIsDisplayed()
    composeRule.onAllNodesWithText(CodexSessionsBundle.message("toolwindow.empty.global"))
      .assertCountEquals(0)
  }

  @Test
  fun projectsShowThreadsWithoutInlineOpenAction() {
    val now = 1_700_000_000_000L
    val thread = CodexThread(id = "thread-1", title = "Thread One", updatedAt = now - 10 * 60 * 1000L, archived = false)
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        threads = listOf(thread),
      ),
      CodexProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
        threads = emptyList(),
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText("Project B").assertIsDisplayed()
    composeRule.onNodeWithText("Thread One").assertIsDisplayed()
    composeRule.onNodeWithText("10m").assertIsDisplayed()
    composeRule.onAllNodesWithText(CodexSessionsBundle.message("toolwindow.action.open"))
      .assertCountEquals(0)
  }

  @Test
  fun hoveringClosedProjectRowShowsPlusActionAndDoesNotInvokeOpenCallback() {
    var createdPath: String? = null
    var openedPath: String? = null
    val projectPath = "/work/project-plus"
    val projects = listOf(
      CodexProjectSessions(
        path = projectPath,
        name = "Project Plus",
        isOpen = false,
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = { openedPath = it },
        onCreateThread = { createdPath = it },
      )
    }

    val newThreadLabel = CodexSessionsBundle.message("toolwindow.action.new.thread")
    composeRule.onAllNodesWithContentDescription(newThreadLabel).assertCountEquals(0)

    composeRule.onNodeWithText("Project Plus")
      .assertIsDisplayed()
      .performMouseInput { moveTo(center) }

    composeRule.onNodeWithContentDescription(newThreadLabel)
      .assertIsDisplayed()
      .performClick()

    composeRule.runOnIdle {
      assertEquals(projectPath, createdPath)
      assertEquals(null, openedPath)
    }
  }

  @Test
  fun projectErrorShowsRetryAction() {
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-c",
        name = "Project C",
        isOpen = true,
        errorMessage = "Failed",
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Failed").assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.error.retry"))
      .assertIsDisplayed()
  }

  @Test
  fun projectRefreshErrorKeepsVisibleThreads() {
    val now = 1_700_000_000_000L
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-refresh-error",
        name = "Project Refresh Error",
        isOpen = true,
        hasLoaded = true,
        threads = listOf(
          CodexThread(id = "thread-1", title = "Thread One", updatedAt = now - 10_000L, archived = false),
        ),
        errorMessage = "Refresh failed",
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Thread One").assertIsDisplayed()
    composeRule.onNodeWithText("Refresh failed").assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.error.retry"))
      .assertIsDisplayed()
  }

  @Test
  fun moreErrorRetryRequestsAdditionalThreads() {
    var showMoreRequests = 0
    val now = 1_700_000_000_000L
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-more-error",
        name = "Project More Error",
        isOpen = true,
        hasLoaded = true,
        threads = listOf(
          CodexThread(id = "thread-1", title = "Thread One", updatedAt = now - 3_000L, archived = false),
          CodexThread(id = "thread-2", title = "Thread Two", updatedAt = now - 2_000L, archived = false),
          CodexThread(id = "thread-3", title = "Thread Three", updatedAt = now - 1_000L, archived = false),
        ),
        nextThreadsCursor = "cursor-1",
        loadMoreErrorMessage = CodexSessionsBundle.message("toolwindow.error.more"),
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        onShowMoreThreads = { showMoreRequests += 1 },
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.error.more")).assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.action.more")).assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.error.retry")).performClick()

    composeRule.runOnIdle {
      assertEquals(1, showMoreRequests)
    }
  }

  @Test
  fun openLoadedEmptyProjectIsExpandedByDefault() {
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        hasLoaded = true,
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.empty.project"))
      .assertIsDisplayed()
  }

  @Test
  fun loadingProjectDoesNotShowEmptyProjectMessage() {
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-loading",
        name = "Project Loading",
        isOpen = true,
        isLoading = true,
        hasLoaded = true,
        threads = emptyList(),
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Project Loading").assertIsDisplayed()
    composeRule.onAllNodesWithText(CodexSessionsBundle.message("toolwindow.empty.project"))
      .assertCountEquals(0)
  }

  @Test
  fun emptyProjectWithCursorDoesNotShowMoreAction() {
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-empty-cursor",
        name = "Project Empty Cursor",
        isOpen = true,
        hasLoaded = true,
        threads = emptyList(),
        nextThreadsCursor = "cursor-1",
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.empty.project")).assertIsDisplayed()
    composeRule.onAllNodesWithText(CodexSessionsBundle.message("toolwindow.action.more")).assertCountEquals(0)
  }

  @Test
  fun projectWithTwoThreadsAndCursorDoesNotShowMoreAction() {
    val now = 1_700_000_000_000L
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-two-cursor",
        name = "Project Two Cursor",
        isOpen = true,
        hasLoaded = true,
        threads = listOf(
          CodexThread(id = "thread-1", title = "Thread One", updatedAt = now - 2_000L, archived = false),
          CodexThread(id = "thread-2", title = "Thread Two", updatedAt = now - 1_000L, archived = false),
        ),
        nextThreadsCursor = "cursor-1",
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Thread One").assertIsDisplayed()
    composeRule.onNodeWithText("Thread Two").assertIsDisplayed()
    composeRule.onAllNodesWithText(CodexSessionsBundle.message("toolwindow.action.more")).assertCountEquals(0)
  }

  @Test
  fun closedLoadedEmptyProjectIsExpandedByDefault() {
    val now = 1_700_000_000_000L
    val thread = CodexThread(id = "thread-1", title = "Thread One", updatedAt = now - 10 * 60 * 1000L, archived = false)
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        threads = listOf(thread),
        hasLoaded = true,
      ),
      CodexProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
        threads = emptyList(),
        hasLoaded = true,
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Project B").assertIsDisplayed()
    composeRule.onNodeWithText(CodexSessionsBundle.message("toolwindow.empty.project"))
      .assertIsDisplayed()
  }

  @Test
  fun collapsingProjectPersistsAcrossRemount() {
    val uiState = InMemorySessionsTreeUiState()
    val projects = listOf(
      CodexProjectSessions(
        path = "/work/project-collapsed",
        name = "Project Collapsed",
        isOpen = false,
        hasLoaded = true,
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        treeUiState = uiState,
      )
    }

    val emptyMessage = CodexSessionsBundle.message("toolwindow.empty.project")
    composeRule.onNodeWithText(emptyMessage).assertIsDisplayed()

    composeRule.onNodeWithText("Project Collapsed")
      .assertIsDisplayed()
      .performMouseInput { doubleClick() }

    composeRule.onAllNodesWithText(emptyMessage).assertCountEquals(0)

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = CodexSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        treeUiState = uiState,
      )
    }

    composeRule.onNodeWithText("Project Collapsed").assertIsDisplayed()
    composeRule.onAllNodesWithText(emptyMessage).assertCountEquals(0)
  }

  @Test
  fun projectShowsMostRecentThreeThreadsAndMoreRequestsAdditionalThreads() {
    val now = 1_700_000_000_000L
    val uiState = InMemorySessionsTreeUiState()
    var showMoreRequests = 0
    val threads = listOf(
      CodexThread(id = "thread-4", title = "Thread 4", updatedAt = now - 4_000L, archived = false),
      CodexThread(id = "thread-1", title = "Thread 1", updatedAt = now - 1_000L, archived = false),
      CodexThread(id = "thread-5", title = "Thread 5", updatedAt = now - 5_000L, archived = false),
      CodexThread(id = "thread-3", title = "Thread 3", updatedAt = now - 3_000L, archived = false),
      CodexThread(id = "thread-2", title = "Thread 2", updatedAt = now - 2_000L, archived = false),
    )
    val initialState = CodexSessionsState(
      projects = listOf(
        CodexProjectSessions(
          path = "/work/project-more",
          name = "Project More",
          isOpen = true,
          hasLoaded = true,
          threads = threads,
        ),
      )
    )
    val renderState = mutableStateOf(initialState)

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = renderState.value,
        onRefresh = {},
        onOpenProject = {},
        onShowMoreThreads = { path ->
          showMoreRequests += 1
          uiState.incrementVisibleThreadCount(path, delta = 3)
          val current = renderState.value
          renderState.value = current.copy(lastUpdatedAt = (current.lastUpdatedAt ?: 0L) + 1L)
        },
        treeUiState = uiState,
      )
    }

    composeRule.onNodeWithText("Thread 1").assertIsDisplayed()
    composeRule.onNodeWithText("Thread 2").assertIsDisplayed()
    composeRule.onNodeWithText("Thread 3").assertIsDisplayed()
    composeRule.onAllNodesWithText("Thread 4").assertCountEquals(0)
    composeRule.onAllNodesWithText("Thread 5").assertCountEquals(0)

    val moreLabel = CodexSessionsBundle.message("toolwindow.action.more")
    composeRule.onNodeWithText(moreLabel).assertIsDisplayed()
    composeRule.onNodeWithText(moreLabel).performClick()
    composeRule.waitForIdle()

    composeRule.runOnIdle {
      assertEquals(1, showMoreRequests)
    }
  }

  @Test
  fun savedThreadPreviewRestoresBeforeRefreshWithMockBackend() {
    verifySavedThreadPreviewRestore(createMockBackendDefinition())
  }

  @Test
  fun savedThreadPreviewRestoresBeforeRefreshWithRealBackend() {
    verifySavedThreadPreviewRestore(createRealBackendDefinition())
  }

  @Test
  fun moreLoadsNextBackendPageWithMockBackend() {
    verifyMoreLoadsNextBackendPage(createMockBackendDefinition())
  }

  @Test
  fun moreLoadsNextBackendPageWithRealBackend() {
    verifyMoreLoadsNextBackendPage(createRealBackendDefinition())
  }

  private fun verifySavedThreadPreviewRestore(backend: CodexBackend) {
    val snapshot = loadBackendThreadsSnapshot(backend = backend)
    val savedTitle = "Saved Preview (${backend.name})"
    val projectPath = snapshot.projectPath
    val uiState = InMemorySessionsTreeUiState()
    uiState.setOpenProjectThreadPreviews(
      path = projectPath,
      threads = listOf(
        CodexThread(
          id = "saved-preview-${backend.name}",
          title = savedTitle,
          updatedAt = 1L,
          archived = false,
        ),
      ),
    )
    val cachedThreads = uiState.getOpenProjectThreadPreviews(projectPath).orEmpty()
    val renderState = mutableStateOf(
      CodexSessionsState(
        projects = listOf(
          CodexProjectSessions(
            path = projectPath,
            name = "Project ${backend.name}",
            isOpen = true,
            isLoading = true,
            hasLoaded = cachedThreads.isNotEmpty(),
            threads = cachedThreads,
          ),
        ),
      ),
    )

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = renderState.value,
        onRefresh = {},
        onOpenProject = {},
        treeUiState = uiState,
      )
    }

    composeRule.onNodeWithText(savedTitle).assertIsDisplayed()

    composeRule.runOnIdle {
      val loadedProject = renderState.value.projects.single().copy(
        isLoading = false,
        hasLoaded = true,
        threads = snapshot.threads,
      )
      renderState.value = CodexSessionsState(
        projects = listOf(loadedProject),
        lastUpdatedAt = System.currentTimeMillis(),
      )
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText(snapshot.threads.first().title).assertIsDisplayed()
    composeRule.onAllNodesWithText(savedTitle).assertCountEquals(0)
  }

  private fun verifyMoreLoadsNextBackendPage(backend: CodexBackend) {
    val snapshot = loadBackendPagingSnapshot(backend = backend)
    val uiState = InMemorySessionsTreeUiState()
    var showMoreRequests = 0
    val initialState = CodexSessionsState(
      projects = listOf(
        CodexProjectSessions(
          path = snapshot.projectPath,
          name = "Project ${backend.name}",
          isOpen = true,
          hasLoaded = true,
          threads = snapshot.firstPageThreads,
          nextThreadsCursor = snapshot.firstPageNextCursor,
        ),
      ),
    )
    val renderState = mutableStateOf(initialState)

    composeRule.setContentWithTheme {
      codexSessionsToolWindowContent(
        state = renderState.value,
        onRefresh = {},
        onOpenProject = {},
        onShowMoreThreads = { path ->
          showMoreRequests += 1
          uiState.incrementVisibleThreadCount(path, delta = 3)
          val current = renderState.value.projects.single()
          val mergedThreads = (current.threads + snapshot.secondPageThreads)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.updatedAt }
          renderState.value = renderState.value.copy(
            projects = listOf(
              current.copy(
                threads = mergedThreads,
                nextThreadsCursor = snapshot.secondPageNextCursor,
              ),
            ),
            lastUpdatedAt = (renderState.value.lastUpdatedAt ?: 0L) + 1L,
          )
        },
        treeUiState = uiState,
      )
    }

    val firstLoadedFromSecondPage = snapshot.secondPageThreads.first().title
    composeRule.onAllNodesWithText(firstLoadedFromSecondPage).assertCountEquals(0)

    val moreLabel = CodexSessionsBundle.message("toolwindow.action.more")
    composeRule.onNodeWithText(moreLabel).assertIsDisplayed()
    composeRule.onNodeWithText(moreLabel).performClick()
    composeRule.waitForIdle()

    composeRule.runOnIdle {
      assertEquals(1, showMoreRequests)
    }
    composeRule.onNodeWithText(firstLoadedFromSecondPage).assertIsDisplayed()
  }

  private fun loadBackendThreadsSnapshot(
    backend: CodexBackend,
  ): BackendThreadsSnapshot {
    return withBackendClient(backend = backend) { client, projectPath, _ ->
      val threads = ensureMinimumThreads(client, backendName = backend.name, minimumThreadCount = 1)
      BackendThreadsSnapshot(projectPath = projectPath, threads = threads)
    }
  }

  private fun loadBackendPagingSnapshot(
    backend: CodexBackend,
  ): BackendPagingSnapshot {
    return withBackendClient(backend = backend) { client, projectPath, _ ->
      ensureMinimumThreads(client, backendName = backend.name, minimumThreadCount = 4)
      val firstPage = client.listThreadsPage(archived = false, cursor = null, limit = 3)
      val firstCursor = firstPage.nextCursor
      assumeTrue(
        "${backend.name} backend must return a second page for this scenario",
        !firstCursor.isNullOrBlank(),
      )
      val nonNullFirstCursor = firstCursor ?: throw AssertionError("Missing cursor after assumption")
      val secondPage = client.listThreadsPage(archived = false, cursor = nonNullFirstCursor, limit = 3)
      assumeTrue(
        "${backend.name} backend second page must contain at least one thread",
        secondPage.threads.isNotEmpty(),
      )
      BackendPagingSnapshot(
        projectPath = projectPath,
        firstPageThreads = firstPage.threads,
        firstPageNextCursor = nonNullFirstCursor,
        secondPageThreads = secondPage.threads,
        secondPageNextCursor = secondPage.nextCursor,
      )
    }
  }

  private fun <T> withBackendClient(
    backend: CodexBackend,
    block: suspend (client: CodexAppServerClient, projectPath: String, scope: CoroutineScope) -> T,
  ): T {
    return runBlocking {
      withContext(Dispatchers.IO) {
        val maxAttempts = if (backend.name == "mock") 3 else 1
        var lastTerminationError: Throwable? = null
        repeat(maxAttempts) { attempt ->
          val rootDir = Files.createTempDirectory("codex-sessions-${backend.name}-")
          val projectDir = rootDir.resolve("project")
          Files.createDirectories(projectDir)
          val projectPath = projectDir.toString()
          val configPath = projectDir.resolve("codex-config.json")
          writeConfig(
            path = configPath,
            threads = List(6) { index ->
              val updatedAt = 1_700_000_000_000L + (6 - index) * 1_000L
              ThreadSpec(
                id = "seed-${index + 1}",
                title = "Seed ${index + 1}",
                cwd = projectPath,
                updatedAt = updatedAt,
                archived = false,
              )
            },
          )
          val backendDir = rootDir.resolve("backend")
          Files.createDirectories(backendDir)
          val client = backend.createClient(
            scope = this,
            tempDir = backendDir,
            configPath = configPath,
            workingDirectory = projectDir,
          )
          try {
            return@withContext block(client, projectPath, this)
          }
          catch (t: Throwable) {
            val shouldRetry = backend.name == "mock" && attempt < maxAttempts - 1 && isAppServerTermination(t)
            if (!shouldRetry) throw t
            lastTerminationError = t
          }
          finally {
            client.shutdown()
          }
        }
        throw AssertionError("Mock backend app-server terminated repeatedly", lastTerminationError)
      }
    }
  }

  private fun isAppServerTermination(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
      if (current is CodexAppServerException) {
        return true
      }
      current = current.cause
    }
    return false
  }

  private suspend fun ensureMinimumThreads(
    client: CodexAppServerClient,
    backendName: String,
    minimumThreadCount: Int,
  ): List<CodexThread> {
    var threads = client.listThreads(archived = false)

    if (backendName == "real") {
      assumeTrue(
        "real backend must provide at least $minimumThreadCount active threads; tests do not create real threads",
        threads.size >= minimumThreadCount,
      )
      return threads
    }

    if (threads.size >= minimumThreadCount) return threads

    val missing = minimumThreadCount - threads.size
    repeat(missing) {
      client.createThread()
    }
    threads = client.listThreads(archived = false)
    assumeTrue(
      "$backendName backend must provide at least $minimumThreadCount active threads",
      threads.size >= minimumThreadCount,
    )
    return threads
  }

  private data class BackendThreadsSnapshot(
    val projectPath: String,
    val threads: List<CodexThread>,
  )

  private data class BackendPagingSnapshot(
    val projectPath: String,
    val firstPageThreads: List<CodexThread>,
    val firstPageNextCursor: String,
    val secondPageThreads: List<CodexThread>,
    val secondPageNextCursor: String?,
  )
}

private fun ComposeContentTestRule.setContentWithTheme(content: @Composable () -> Unit) {
  setContent {
    BaseJewelTheme(createTestThemeDefinition(), ComponentStyling.default()) {
      CompositionLocalProvider(
        LocalTypography provides TestTypography,
        LocalNewUiChecker provides TestNewUiChecker,
      ) {
        content()
      }
    }
  }
}

private fun createTestThemeDefinition(): ThemeDefinition {
  return ThemeDefinition(
    name = "Test",
    isDark = false,
    globalColors =
      GlobalColors(
        borders = BorderColors(normal = Color.Black, focused = Color.Black, disabled = Color.Black),
        outlines =
          OutlineColors(
            focused = Color.Black,
            focusedWarning = Color.Black,
            focusedError = Color.Black,
            warning = Color.Black,
            error = Color.Black,
          ),
        text =
          TextColors(
            normal = Color.Black,
            selected = Color.Black,
            disabled = Color.Black,
            disabledSelected = Color.Black,
            info = Color.Black,
            error = Color.Black,
            warning = Color.Black,
          ),
        panelBackground = Color.White,
        toolwindowBackground = Color.White,
      ),
    globalMetrics = GlobalMetrics(outlineWidth = 10.dp, rowHeight = 24.dp),
    defaultTextStyle = TextStyle(fontSize = 13.sp),
    editorTextStyle = TextStyle(fontSize = 13.sp),
    consoleTextStyle = TextStyle(fontSize = 13.sp),
    contentColor = Color.Black,
    colorPalette = ThemeColorPalette.Empty,
    iconData = ThemeIconData.Empty,
    disabledAppearanceValues = DisabledAppearanceValues(brightness = 33, contrast = -35, alpha = 100),
  )
}

private object TestTypography : Typography {
  @get:Composable
  override val labelTextStyle: TextStyle
    get() = JewelTheme.defaultTextStyle

  @get:Composable
  override val labelTextSize
    get() = JewelTheme.defaultTextStyle.fontSize

  @get:Composable
  override val h0TextStyle: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val h1TextStyle: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val h2TextStyle: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val h3TextStyle: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val h4TextStyle: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val regular: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val medium: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val small: TextStyle
    get() = labelTextStyle

  @get:Composable
  override val editorTextStyle: TextStyle
    get() = JewelTheme.editorTextStyle

  @get:Composable
  override val consoleTextStyle: TextStyle
    get() = JewelTheme.consoleTextStyle
}

private object TestNewUiChecker : NewUiChecker {
  override fun isNewUi(): Boolean = true
}
