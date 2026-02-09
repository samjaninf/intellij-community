// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.junit.Rule
import org.junit.Test

class AgentSessionsToolWindowTest {
  @get:Rule
  val composeRule: ComposeContentTestRule = createComposeRule()

  @Test
  fun emptyStateIsShownWhenNoProjects() {
    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = emptyList(), lastUpdatedAt = 1L),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText(AgentSessionsBundle.message("toolwindow.empty.global"))
      .assertIsDisplayed()
  }

  @Test
  fun projectsDoNotShowGlobalEmptyStateWhenNoThreadsLoadedYet() {
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
      ),
      AgentProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText("Project B").assertIsDisplayed()
    composeRule.onAllNodesWithText(AgentSessionsBundle.message("toolwindow.empty.global"))
      .assertCountEquals(0)
  }

  @Test
  fun projectsShowThreadsWithoutInlineOpenAction() {
    val now = 1_700_000_000_000L
    val thread = AgentSessionThread(id = "thread-1", title = "Thread One", updatedAt = now - 10 * 60 * 1000L, archived = false)
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        threads = listOf(thread),
      ),
      AgentProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
        threads = emptyList(),
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText("Project B").assertIsDisplayed()
    composeRule.onNodeWithText("Thread One").assertIsDisplayed()
    composeRule.onNodeWithText("10m").assertIsDisplayed()
    composeRule.onAllNodesWithText(AgentSessionsBundle.message("toolwindow.action.open"))
      .assertCountEquals(0)
  }

  @Test
  fun threadRowShowsProviderMarkerForClaude() {
    val now = 1_700_000_000_000L
    val thread = AgentSessionThread(
      id = "session-1",
      title = "Session One",
      updatedAt = now,
      archived = false,
      provider = AgentSessionProvider.CLAUDE,
    )
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        threads = listOf(thread),
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Session One").assertIsDisplayed()
    composeRule.onNodeWithText(AgentSessionsBundle.message("toolwindow.provider.claude")).assertIsDisplayed()
  }

  @Test
  fun projectErrorShowsRetryAction() {
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-c",
        name = "Project C",
        isOpen = true,
        errorMessage = "Failed",
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Failed").assertIsDisplayed()
    composeRule.onNodeWithText(AgentSessionsBundle.message("toolwindow.error.retry"))
      .assertIsDisplayed()
  }

  @Test
  fun openLoadedEmptyProjectIsExpandedByDefault() {
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        hasLoaded = true,
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
      )
    }

    composeRule.onNodeWithText("Project A").assertIsDisplayed()
    composeRule.onNodeWithText(AgentSessionsBundle.message("toolwindow.empty.project"))
      .assertIsDisplayed()
  }

  @Test
  fun expandingLoadedEmptyProjectShowsEmptyChildRow() {
    val now = 1_700_000_000_000L
    val thread = AgentSessionThread(id = "thread-1", title = "Thread One", updatedAt = now - 10 * 60 * 1000L, archived = false)
    val projects = listOf(
      AgentProjectSessions(
        path = "/work/project-a",
        name = "Project A",
        isOpen = true,
        threads = listOf(thread),
        hasLoaded = true,
      ),
      AgentProjectSessions(
        path = "/work/project-b",
        name = "Project B",
        isOpen = false,
        threads = emptyList(),
        hasLoaded = true,
      ),
    )

    composeRule.setContentWithTheme {
      agentSessionsToolWindowContent(
        state = AgentSessionsState(projects = projects),
        onRefresh = {},
        onOpenProject = {},
        nowProvider = { now },
      )
    }

    composeRule.onNodeWithText("Project B")
      .assertIsDisplayed()
      .performMouseInput { doubleClick() }

    composeRule.onNodeWithText(AgentSessionsBundle.message("toolwindow.empty.project"))
      .assertIsDisplayed()
  }
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
