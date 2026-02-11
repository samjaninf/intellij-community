package com.intellij.agent.workbench.sessions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.search.SpeedSearchableTree
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.theme.treeStyle

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun sessionTree(
  projects: List<AgentProjectSessions>,
  onRefresh: () -> Unit,
  onOpenProject: (String) -> Unit,
  onProjectExpanded: (String) -> Unit,
  onOpenThread: (String, AgentSessionThread) -> Unit,
  onOpenSubAgent: (String, AgentSessionThread, AgentSubAgent) -> Unit,
  nowProvider: () -> Long,
  visibleProjectCount: Int = Int.MAX_VALUE,
  onShowMoreProjects: () -> Unit = {},
) {
  val stateHolder = rememberSessionTreeStateHolder(
    onProjectExpanded = onProjectExpanded,
    onProjectCollapsed = {},
  )
  val treeState = stateHolder.treeState
  val autoOpenNodes = remember(projects, visibleProjectCount) {
    projects.take(visibleProjectCount)
      .filter { it.isOpen || it.errorMessage != null || it.threads.isNotEmpty() }
      .map { SessionTreeId.Project(it.path) }
  }
  LaunchedEffect(autoOpenNodes) {
    stateHolder.applyDefaultOpenProjects(autoOpenNodes)
  }
  val tree = remember(projects, visibleProjectCount) { buildSessionTree(projects, visibleProjectCount) }
  val treeStyle = run {
    val baseStyle = JewelTheme.treeStyle
    val metrics = baseStyle.metrics
    // Reduce indent to offset the chevron width so depth feels like a single step.
    val indentSize = (metrics.indentSize - metrics.simpleListItemMetrics.iconTextGap)
      .coerceAtLeast(metrics.indentSize * 0.5f)
    LazyTreeStyle(
      colors = baseStyle.colors,
      metrics = LazyTreeMetrics(
        indentSize = indentSize,
        elementMinHeight = metrics.elementMinHeight,
        chevronContentGap = metrics.chevronContentGap,
        simpleListItemMetrics = metrics.simpleListItemMetrics,
      ),
      icons = baseStyle.icons,
    )
  }
  SpeedSearchArea(Modifier.fillMaxSize()) {
    SpeedSearchableTree(
      tree = tree,
      modifier = Modifier.fillMaxSize().focusable(),
      treeState = treeState,
      nodeText = { element -> sessionTreeNodeText(element.data) },
      style = treeStyle,
      onElementClick = { element ->
        when (val node = element.data) {
          is SessionTreeNode.Project -> onOpenProject(node.project.path)
          is SessionTreeNode.Thread -> onOpenThread(node.project.path, node.thread)
          is SessionTreeNode.SubAgent -> onOpenSubAgent(node.project.path, node.thread, node.subAgent)
          is SessionTreeNode.MoreProjects -> onShowMoreProjects()
          is SessionTreeNode.Error -> Unit
          is SessionTreeNode.Empty -> Unit
        }
      },
      onElementDoubleClick = {},
      onSelectionChange = {},
    ) { element ->
      sessionTreeNodeContent(
        element = element,
        onOpenProject = onOpenProject,
        onRefresh = onRefresh,
        nowProvider = nowProvider,
      )
    }
  }
}

private fun buildSessionTree(projects: List<AgentProjectSessions>, visibleProjectCount: Int): Tree<SessionTreeNode> =
  buildTree {
    val visibleProjects = projects.take(visibleProjectCount)
    val hiddenCount = (projects.size - visibleProjectCount).coerceAtLeast(0)
    visibleProjects.forEach { project ->
      val projectId = SessionTreeId.Project(project.path)
      addNode(
        data = SessionTreeNode.Project(project),
        id = projectId,
      ) {
        val errorMessage = project.errorMessage
        if (errorMessage != null) {
          addLeaf(
            data = SessionTreeNode.Error(project, errorMessage),
            id = SessionTreeId.Error(project.path),
          )
        } else if (project.hasLoaded && project.threads.isEmpty()) {
          addLeaf(
            data = SessionTreeNode.Empty(project, AgentSessionsBundle.message("toolwindow.empty.project")),
            id = SessionTreeId.Empty(project.path),
          )
        } else {
          project.threads.forEach { thread ->
            val threadId = SessionTreeId.Thread(project.path, thread.provider, thread.id)
            if (thread.subAgents.isNotEmpty()) {
              addNode(
                data = SessionTreeNode.Thread(project, thread),
                id = threadId,
              ) {
                thread.subAgents.forEach { subAgent ->
                  addLeaf(
                    data = SessionTreeNode.SubAgent(project, thread, subAgent),
                    id = SessionTreeId.SubAgent(project.path, thread.provider, thread.id, subAgent.id),
                  )
                }
              }
            } else {
              addLeaf(
                data = SessionTreeNode.Thread(project, thread),
                id = threadId,
              )
            }
          }
        }
      }
    }
    if (hiddenCount > 0) {
      addLeaf(
        data = SessionTreeNode.MoreProjects(hiddenCount),
        id = SessionTreeId.MoreProjects,
      )
    }
  }

private fun sessionTreeNodeText(node: SessionTreeNode): String? =
  when (node) {
    is SessionTreeNode.Project -> node.project.name
    is SessionTreeNode.Thread -> node.thread.title
    is SessionTreeNode.SubAgent -> node.subAgent.name.ifBlank { node.subAgent.id }
    is SessionTreeNode.Error -> null
    is SessionTreeNode.Empty -> node.message
    is SessionTreeNode.MoreProjects -> null
  }

internal sealed interface SessionTreeNode {
  data class Project(val project: AgentProjectSessions) : SessionTreeNode
  data class Thread(val project: AgentProjectSessions, val thread: AgentSessionThread) : SessionTreeNode
  data class SubAgent(
    val project: AgentProjectSessions,
    val thread: AgentSessionThread,
    val subAgent: AgentSubAgent,
  ) : SessionTreeNode
  data class Error(val project: AgentProjectSessions, val message: String) : SessionTreeNode
  data class Empty(val project: AgentProjectSessions, val message: String) : SessionTreeNode
  data class MoreProjects(val hiddenCount: Int) : SessionTreeNode
}

internal sealed interface SessionTreeId {
  data class Project(val path: String) : SessionTreeId
  data class Thread(val projectPath: String, val provider: AgentSessionProvider, val threadId: String) : SessionTreeId
  data class SubAgent(
    val projectPath: String,
    val provider: AgentSessionProvider,
    val threadId: String,
    val subAgentId: String,
  ) : SessionTreeId
  data class Error(val projectPath: String) : SessionTreeId
  data class Empty(val projectPath: String) : SessionTreeId
  data object MoreProjects : SessionTreeId
}
