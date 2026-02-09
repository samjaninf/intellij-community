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
import com.intellij.openapi.components.service
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun agentSessionsToolWindow() {
  val service = remember { service<AgentSessionsService>() }
  val state by service.state.collectAsState()

  LaunchedEffect(Unit) {
    service.refresh()
  }

  agentSessionsToolWindowContent(
    state = state,
    onRefresh = { service.refresh() },
    onOpenProject = { service.openOrFocusProject(it) },
    onProjectExpanded = { service.loadProjectThreadsOnDemand(it) },
    onOpenThread = { path, thread -> service.openChatThread(path, thread) },
    onOpenSubAgent = { path, thread, subAgent -> service.openChatSubAgent(path, thread, subAgent) },
  )
}

@Composable
internal fun agentSessionsToolWindowContent(
  state: AgentSessionsState,
  onRefresh: () -> Unit,
  onOpenProject: (String) -> Unit,
  onProjectExpanded: (String) -> Unit = {},
  onOpenThread: (String, AgentSessionThread) -> Unit = { _, _ -> },
  onOpenSubAgent: (String, AgentSessionThread, AgentSubAgent) -> Unit = { _, _, _ -> },
  nowProvider: () -> Long = { System.currentTimeMillis() },
) {
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
        onOpenThread = onOpenThread,
        onOpenSubAgent = onOpenSubAgent,
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
      text = AgentSessionsBundle.message(messageKey),
      color = JewelTheme.globalColors.text.disabled,
      style = AgentSessionsTextStyles.emptyState(),
    )
  }
}
