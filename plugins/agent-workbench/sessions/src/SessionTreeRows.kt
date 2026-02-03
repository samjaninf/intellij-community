package com.intellij.agent.workbench.sessions

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.intellij.agent.workbench.codex.common.CodexProjectSessions
import com.intellij.agent.workbench.codex.common.CodexSubAgent
import com.intellij.agent.workbench.codex.common.CodexThread
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.SelectableLazyItemScope
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.search.highlightSpeedSearchMatches
import org.jetbrains.jewel.ui.component.search.highlightTextSearch
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun SelectableLazyItemScope.sessionTreeNodeContent(
  element: Tree.Element<SessionTreeNode>,
  onOpenProject: (String) -> Unit,
  onCreateThread: (String) -> Unit,
  onShowMoreThreads: (String) -> Unit,
  onRefresh: () -> Unit,
  nowProvider: () -> Long,
) {
  val node = element.data
  when (node) {
    is SessionTreeNode.Project -> projectNodeRow(
      project = node.project,
      onOpenProject = onOpenProject,
      onCreateThread = onCreateThread,
    )
    is SessionTreeNode.Thread -> threadNodeRow(
      thread = node.thread,
      nowProvider = nowProvider,
    )
    is SessionTreeNode.SubAgent -> subAgentNodeRow(
      subAgent = node.subAgent,
    )
    is SessionTreeNode.MoreThreads -> moreThreadsNodeRow()
    is SessionTreeNode.MoreError -> errorNodeRow(
      message = node.message,
      onRetry = { onShowMoreThreads(node.projectPath) },
    )
    is SessionTreeNode.Error -> errorNodeRow(
      message = node.message,
      onRetry = onRefresh,
    )
    is SessionTreeNode.Empty -> emptyNodeRow(
      message = node.message,
    )
  }
}

private data class TreeRowChrome(
  val interactionSource: MutableInteractionSource,
  val isHovered: Boolean,
  val background: Color,
  val shape: Shape,
  val spacing: Dp,
  val indicatorPadding: Dp,
)

@Composable
private fun rememberTreeRowChrome(
  isSelected: Boolean,
  isActive: Boolean,
  baseTint: Color = Color.Unspecified,
): TreeRowChrome {
  val interactionSource = remember { MutableInteractionSource() }
  val isHovered by interactionSource.collectIsHoveredAsState()
  val background = treeRowBackground(
    isHovered = isHovered,
    isSelected = isSelected,
    isActive = isActive,
    baseTint = baseTint,
  )
  val shape = treeRowShape()
  val spacing = treeRowSpacing()
  val indicatorPadding = spacing * 0.4f
  return TreeRowChrome(
    interactionSource = interactionSource,
    isHovered = isHovered,
    background = background,
    shape = shape,
    spacing = spacing,
    indicatorPadding = indicatorPadding,
  )
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SelectableLazyItemScope.projectNodeRow(
  project: CodexProjectSessions,
  onOpenProject: (String) -> Unit,
  onCreateThread: (String) -> Unit,
) {
  val chrome = rememberTreeRowChrome(
    isSelected = isSelected,
    isActive = isActive,
    baseTint = projectRowTint(),
  )
  val openLabel = CodexSessionsBundle.message("toolwindow.action.open")
  val newThreadLabel = CodexSessionsBundle.message("toolwindow.action.new.thread")
  ContextMenuArea(
    items = {
      if (!project.isOpen) {
        listOf(
          ContextMenuItemOption(
            label = openLabel,
            action = { onOpenProject(project.path) },
          ),
        )
      } else {
        emptyList()
      }
    },
    enabled = !project.isOpen,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(chrome.background, chrome.shape)
        .hoverable(chrome.interactionSource),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(chrome.spacing)
    ) {
      var titleLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
      Text(
        text = project.name.highlightTextSearch(),
        style = CodexSessionsTextStyles.projectTitle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { titleLayoutResult = it },
        modifier = Modifier
          .weight(1f)
          .highlightSpeedSearchMatches(titleLayoutResult),
      )
      Box(
        modifier = Modifier.size(projectActionSlotSize()),
        contentAlignment = Alignment.Center,
      ) {
        if (project.isLoading) {
          CircularProgressIndicator(Modifier.size(loadingIndicatorSize()))
        }
        else if (chrome.isHovered) {
          Icon(
            key = AllIconsKeys.General.Add,
            contentDescription = newThreadLabel,
            modifier = Modifier
              .size(projectActionIconSize())
              .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
              .clickable(onClick = { onCreateThread(project.path) }),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SelectableLazyItemScope.threadNodeRow(
  thread: CodexThread,
  nowProvider: () -> Long,
) {
  val timestamp = thread.updatedAt.takeIf { it > 0 }
  val timeLabel = timestamp?.let { formatRelativeTimeShort(it, nowProvider()) }
  val chrome = rememberTreeRowChrome(isSelected = isSelected, isActive = isActive)
  val titleColor = if (isSelected || isActive) Color.Unspecified else {
    JewelTheme.globalColors.text.normal.copy(alpha = 0.84f)
  }
  val timeColor = LocalContentColor.current
    .takeOrElse { JewelTheme.globalColors.text.disabled }
    .copy(alpha = 0.55f)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(chrome.background, chrome.shape)
      .hoverable(chrome.interactionSource),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(chrome.spacing)
  ) {
    Box(
      modifier = Modifier
        .padding(end = chrome.indicatorPadding)
        .size(threadIndicatorSize())
        .background(threadIndicatorColor(thread), CircleShape)
    )
    var titleLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
      text = thread.title.highlightTextSearch(),
      style = CodexSessionsTextStyles.threadTitle(),
      color = titleColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { titleLayoutResult = it },
      modifier = Modifier
        .weight(1f)
        .highlightSpeedSearchMatches(titleLayoutResult),
    )
    if (timeLabel != null) {
      Text(
        text = timeLabel,
        color = timeColor,
        style = CodexSessionsTextStyles.threadTime(),
      )
    }
  }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SelectableLazyItemScope.subAgentNodeRow(
  subAgent: CodexSubAgent,
) {
  val chrome = rememberTreeRowChrome(isSelected = isSelected, isActive = isActive)
  val displayName = subAgent.name.ifBlank { subAgent.id }
  val titleColor = if (isSelected || isActive) Color.Unspecified else JewelTheme.globalColors.text.disabled
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(chrome.background, chrome.shape)
      .hoverable(chrome.interactionSource),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(chrome.spacing)
  ) {
    Box(
      modifier = Modifier
        .padding(end = chrome.indicatorPadding)
        .size(subAgentIndicatorSize())
        .background(subAgentIndicatorColor(), CircleShape)
    )
    var titleLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
      text = displayName.highlightTextSearch(),
      style = CodexSessionsTextStyles.subAgentTitle(),
      color = titleColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { titleLayoutResult = it },
      modifier = Modifier
        .weight(1f)
        .highlightSpeedSearchMatches(titleLayoutResult),
    )
  }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun SelectableLazyItemScope.moreThreadsNodeRow() {
  val chrome = rememberTreeRowChrome(isSelected = isSelected, isActive = isActive)
  val label = CodexSessionsBundle.message("toolwindow.action.more")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(chrome.background, chrome.shape)
      .hoverable(chrome.interactionSource),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(chrome.spacing),
  ) {
    Text(
      text = label.highlightTextSearch(),
      color = JewelTheme.globalColors.text.info,
      style = CodexSessionsTextStyles.subAgentTitle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun errorNodeRow(message: String, onRetry: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth()) {
    val rowSpacing = treeRowSpacing()
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
      Text(
        text = message,
        color = JewelTheme.globalColors.text.warning,
        style = CodexSessionsTextStyles.error(),
      )
      OutlinedButton(onClick = onRetry) {
        Text(CodexSessionsBundle.message("toolwindow.error.retry"))
      }
    }
  }
}

@Composable
private fun emptyNodeRow(message: String) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = message,
      color = JewelTheme.globalColors.text.disabled,
      style = CodexSessionsTextStyles.emptyState(),
    )
  }
}
