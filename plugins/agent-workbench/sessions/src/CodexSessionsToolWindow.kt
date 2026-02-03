package com.intellij.agent.workbench.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.agent.workbench.codex.common.CodexSessionsState
import com.intellij.agent.workbench.codex.common.CodexSubAgent
import com.intellij.agent.workbench.codex.common.CodexThread
import com.intellij.openapi.components.service
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun codexSessionsToolWindow() {
  val service = remember { service<CodexSessionsService>() }
  val treeUiState = remember { service<CodexSessionsTreeUiStateService>() }
  val state by service.state.collectAsState()

  LaunchedEffect(Unit) {
    service.refresh()
  }

  codexSessionsToolWindowContent(
    state = state,
    onRefresh = { service.refresh() },
    onOpenProject = { service.openOrFocusProject(it) },
    onProjectExpanded = { service.loadProjectThreadsOnDemand(it) },
    onCreateThread = { service.createAndOpenThread(it) },
    onShowMoreThreads = { service.showAllThreadsForProject(it) },
    onOpenThread = { path, thread -> service.openChatThread(path, thread) },
    onOpenSubAgent = { path, thread, subAgent -> service.openChatSubAgent(path, thread, subAgent) },
    treeUiState = treeUiState,
  )
}

@Composable
internal fun codexSessionsToolWindowContent(
  state: CodexSessionsState,
  onRefresh: () -> Unit,
  onOpenProject: (String) -> Unit,
  onProjectExpanded: (String) -> Unit = {},
  onCreateThread: (String) -> Unit = {},
  onShowMoreThreads: (String) -> Unit = {},
  onOpenThread: (String, CodexThread) -> Unit = { _, _ -> },
  onOpenSubAgent: (String, CodexThread, CodexSubAgent) -> Unit = { _, _, _ -> },
  treeUiState: SessionsTreeUiState? = null,
  nowProvider: () -> Long = { System.currentTimeMillis() },
) {
  val effectiveTreeUiState = treeUiState ?: remember { InMemorySessionsTreeUiState() }
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 10.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    when {
      state.projects.isEmpty() -> emptyState(isLoading = state.lastUpdatedAt == null)
      else -> sessionTree(
        projects = state.projects,
        onRefresh = onRefresh,
        onOpenProject = onOpenProject,
        onProjectExpanded = onProjectExpanded,
        onCreateThread = onCreateThread,
        onShowMoreThreads = onShowMoreThreads,
        onOpenThread = onOpenThread,
        onOpenSubAgent = onOpenSubAgent,
        treeUiState = effectiveTreeUiState,
        nowProvider = nowProvider,
      )
    }
  }
}

@Composable
private fun emptyState(isLoading: Boolean) {
  val messageKey = if (isLoading) "toolwindow.loading" else "toolwindow.empty.global"
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Text(
      text = CodexSessionsBundle.message(messageKey),
      color = JewelTheme.globalColors.text.disabled,
      style = CodexSessionsTextStyles.emptyState(),
    )
  }
}
