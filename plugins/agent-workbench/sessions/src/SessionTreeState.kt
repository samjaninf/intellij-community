package com.intellij.agent.workbench.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState

/**
 * Tracks tree open nodes and auto-open behavior for on-demand project loading.
 *
 * Spec: `community/plugins/codex/spec/codex-sessions.spec.md`
 */
@Stable
internal class SessionTreeStateHolder(
  val treeState: TreeState,
) {
  private var openedProjects by mutableStateOf<Set<SessionTreeId.Project>>(emptySet())

  fun updateOpenedProjects(
    openProjects: Set<SessionTreeId.Project>,
    onProjectExpanded: (String) -> Unit,
    onProjectCollapsed: (String) -> Unit,
  ) {
    val newlyOpened = openProjects - openedProjects
    val newlyCollapsed = openedProjects - openProjects
    if (newlyOpened.isNotEmpty()) {
      newlyOpened.forEach { onProjectExpanded(it.path) }
    }
    if (newlyCollapsed.isNotEmpty()) {
      newlyCollapsed.forEach { onProjectCollapsed(it.path) }
    }
    if (openProjects != openedProjects) {
      openedProjects = openProjects
    }
  }

  fun applyDefaultOpenProjects(defaultOpenProjects: List<SessionTreeId.Project>) {
    val currentlyOpenProjects = treeState.openNodes.filterIsInstance<SessionTreeId.Project>().toSet()
    val projectsToOpen = defaultOpenProjects.filterNot { it in currentlyOpenProjects }
    if (projectsToOpen.isNotEmpty()) {
      treeState.openNodes(projectsToOpen)
    }
  }
}

@Composable
internal fun rememberSessionTreeStateHolder(
  onProjectExpanded: (String) -> Unit,
  onProjectCollapsed: (String) -> Unit,
): SessionTreeStateHolder {
  val treeState = rememberTreeState()
  val stateHolder = remember(treeState) { SessionTreeStateHolder(treeState) }
  LaunchedEffect(treeState, stateHolder, onProjectExpanded, onProjectCollapsed) {
    snapshotFlow { treeState.openNodes }
      .collect { nodes ->
        val openProjects = nodes.filterIsInstance<SessionTreeId.Project>().toSet()
        stateHolder.updateOpenedProjects(openProjects, onProjectExpanded, onProjectCollapsed)
      }
  }
  return stateHolder
}
