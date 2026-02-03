package com.intellij.agent.workbench.sessions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.intellij.agent.workbench.codex.common.CodexProjectSessions
import com.intellij.agent.workbench.codex.common.CodexSubAgent
import com.intellij.agent.workbench.codex.common.CodexThread
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.search.SpeedSearchableTree
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.theme.treeStyle

private const val PROJECT_CLICK_SUPPRESSION_MS = 500L

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun sessionTree(
  projects: List<CodexProjectSessions>,
  onRefresh: () -> Unit,
  onOpenProject: (String) -> Unit,
  onProjectExpanded: (String) -> Unit,
  onCreateThread: (String) -> Unit,
  onShowMoreThreads: (String) -> Unit,
  onOpenThread: (String, CodexThread) -> Unit,
  onOpenSubAgent: (String, CodexThread, CodexSubAgent) -> Unit,
  treeUiState: SessionsTreeUiState,
  nowProvider: () -> Long,
) {
  val stateHolder = rememberSessionTreeStateHolder(
    onProjectExpanded = { path ->
      treeUiState.setProjectCollapsed(path, collapsed = false)
      onProjectExpanded(path)
    },
    onProjectCollapsed = { path ->
      treeUiState.setProjectCollapsed(path, collapsed = true)
    },
  )
  val treeState = stateHolder.treeState
  val defaultOpenProjects = projects
    .map { SessionTreeId.Project(it.path) }
    .filterNot { treeUiState.isProjectCollapsed(it.path) }
  LaunchedEffect(defaultOpenProjects) {
    stateHolder.applyDefaultOpenProjects(defaultOpenProjects)
  }
  val visibleThreadCountByProject = projects
    .associate { project -> project.path to treeUiState.getVisibleThreadCount(project.path) }
  val tree = remember(projects, visibleThreadCountByProject) {
    buildSessionTree(
      projects = projects,
      visibleThreadCountByProject = visibleThreadCountByProject,
    )
  }
  var suppressedProjectClick by remember { mutableStateOf<SuppressedProjectClick?>(null) }

  fun suppressNextProjectClick(path: String) {
    suppressedProjectClick = SuppressedProjectClick(
      path = path,
      expiresAt = System.currentTimeMillis() + PROJECT_CLICK_SUPPRESSION_MS,
    )
  }

  fun shouldSuppressProjectClick(path: String): Boolean {
    val suppression = suppressedProjectClick ?: return false
    if (System.currentTimeMillis() > suppression.expiresAt) {
      suppressedProjectClick = null
      return false
    }
    if (suppression.path != path) {
      return false
    }
    suppressedProjectClick = null
    return true
  }

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
          is SessionTreeNode.Project -> {
            if (!shouldSuppressProjectClick(node.project.path)) {
              onOpenProject(node.project.path)
            }
          }
          is SessionTreeNode.Thread -> onOpenThread(node.project.path, node.thread)
          is SessionTreeNode.SubAgent -> onOpenSubAgent(node.project.path, node.thread, node.subAgent)
          is SessionTreeNode.MoreThreads -> onShowMoreThreads(node.projectPath)
          is SessionTreeNode.MoreError -> Unit
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
        onCreateThread = { path ->
          suppressNextProjectClick(path)
          onCreateThread(path)
        },
        onShowMoreThreads = onShowMoreThreads,
        onRefresh = onRefresh,
        nowProvider = nowProvider,
      )
    }
  }
}

private data class SuppressedProjectClick(
  val path: String,
  val expiresAt: Long,
)

private fun buildSessionTree(
  projects: List<CodexProjectSessions>,
  visibleThreadCountByProject: Map<String, Int>,
): Tree<SessionTreeNode> =
  buildTree {
    projects.forEach { project ->
      val projectId = SessionTreeId.Project(project.path)
      addNode(
        data = SessionTreeNode.Project(project),
        id = projectId,
      ) {
        val sortedThreads = project.threads.sortedByDescending { it.updatedAt }
        val visibleThreadCount = visibleThreadCountByProject[project.path] ?: DEFAULT_VISIBLE_THREAD_COUNT
        val visibleThreads = sortedThreads.take(visibleThreadCount)
        visibleThreads.forEach { thread ->
          val threadId = SessionTreeId.Thread(project.path, thread.id)
          if (thread.subAgents.isNotEmpty()) {
            addNode(
              data = SessionTreeNode.Thread(project, thread),
              id = threadId,
            ) {
              thread.subAgents.forEach { subAgent ->
                addLeaf(
                  data = SessionTreeNode.SubAgent(project, thread, subAgent),
                  id = SessionTreeId.SubAgent(project.path, thread.id, subAgent.id),
                )
              }
            }
          }
          else {
            addLeaf(
              data = SessionTreeNode.Thread(project, thread),
              id = threadId,
            )
          }
        }

        val errorMessage = project.errorMessage
        if (errorMessage != null) {
          addLeaf(
            data = SessionTreeNode.Error(project, errorMessage),
            id = SessionTreeId.Error(project.path),
          )
        }
        else if (project.hasLoaded && !project.isLoading && sortedThreads.isEmpty()) {
          addLeaf(
            data = SessionTreeNode.Empty(project, CodexSessionsBundle.message("toolwindow.empty.project")),
            id = SessionTreeId.Empty(project.path),
          )
        }

        val loadMoreErrorMessage = project.loadMoreErrorMessage
        if (loadMoreErrorMessage != null && sortedThreads.isNotEmpty()) {
          addLeaf(
            data = SessionTreeNode.MoreError(project.path, loadMoreErrorMessage),
            id = SessionTreeId.MoreError(project.path),
          )
        }

        val hiddenThreadsCount = sortedThreads.size - visibleThreads.size
        val hasLoadMoreCursor = !project.nextThreadsCursor.isNullOrBlank()
        val canShowCursorDrivenMore = hasLoadMoreCursor && sortedThreads.size >= DEFAULT_VISIBLE_THREAD_COUNT
        if (hiddenThreadsCount > 0 || canShowCursorDrivenMore) {
          addLeaf(
            data = SessionTreeNode.MoreThreads(project.path),
            id = SessionTreeId.MoreThreads(project.path),
          )
        }
      }
    }
  }

private fun sessionTreeNodeText(node: SessionTreeNode): String? =
  when (node) {
    is SessionTreeNode.Project -> node.project.name
    is SessionTreeNode.Thread -> node.thread.title
    is SessionTreeNode.SubAgent -> node.subAgent.name.ifBlank { node.subAgent.id }
    is SessionTreeNode.MoreThreads -> CodexSessionsBundle.message("toolwindow.action.more")
    is SessionTreeNode.MoreError -> node.message
    is SessionTreeNode.Error -> null
    is SessionTreeNode.Empty -> node.message
  }

internal sealed interface SessionTreeNode {
  data class Project(val project: CodexProjectSessions) : SessionTreeNode
  data class Thread(val project: CodexProjectSessions, val thread: CodexThread) : SessionTreeNode
  data class SubAgent(
    val project: CodexProjectSessions,
    val thread: CodexThread,
    val subAgent: CodexSubAgent,
  ) : SessionTreeNode

  data class MoreThreads(val projectPath: String) : SessionTreeNode
  data class MoreError(val projectPath: String, val message: String) : SessionTreeNode
  data class Error(val project: CodexProjectSessions, val message: String) : SessionTreeNode
  data class Empty(val project: CodexProjectSessions, val message: String) : SessionTreeNode
}

internal sealed interface SessionTreeId {
  data class Project(val path: String) : SessionTreeId
  data class Thread(val projectPath: String, val threadId: String) : SessionTreeId
  data class SubAgent(val projectPath: String, val threadId: String, val subAgentId: String) : SessionTreeId
  data class MoreThreads(val projectPath: String) : SessionTreeId
  data class MoreError(val projectPath: String) : SessionTreeId
  data class Error(val projectPath: String) : SessionTreeId
  data class Empty(val projectPath: String) : SessionTreeId
}
