// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@TestApplication
class CodexSessionsGearActionsTest {
  @Test
  fun gearActionsContainOpenFileToggleAndRefresh() {
    val actionManager = ActionManager.getInstance()
    val group = actionManager.getAction("AgentWorkbenchSessions.ToolWindow.GearActions")

    assertThat(group).isNotNull.isInstanceOf(ActionGroup::class.java)

    val children = (group as ActionGroup).getChildren(TestActionEvent.createTestEvent())
    val actionIds = children
      .filterNot { it is Separator }
      .mapNotNull { actionManager.getId(it) }

    assertThat(actionIds).containsExactly(
      "OpenFile",
      "AgentWorkbenchSessions.ToggleDedicatedFrame",
      "AgentWorkbenchSessions.Refresh",
    )
  }

  @Test
  fun toggleActionUpdatesAdvancedSetting() {
    val actionManager = ActionManager.getInstance()
    val action = actionManager.getAction("AgentWorkbenchSessions.ToggleDedicatedFrame")

    assertThat(action).isNotNull
    val toggleAction = action as? CodexSessionsDedicatedFrameToggleAction
      ?: error("Toggle action is missing")

    val initialValue = CodexChatOpenModeSettings.openInDedicatedFrame()
    try {
      runInEdtAndWait {
        toggleAction.setSelected(TestActionEvent.createTestEvent(toggleAction), !initialValue)
      }
      assertThat(CodexChatOpenModeSettings.openInDedicatedFrame()).isEqualTo(!initialValue)
    }
    finally {
      CodexChatOpenModeSettings.setOpenInDedicatedFrame(initialValue)
    }
  }

  @Test
  fun refreshActionTriggersSessionsRefresh() {
    val actionManager = ActionManager.getInstance()
    val refreshAction = actionManager.getAction("AgentWorkbenchSessions.Refresh")

    assertThat(refreshAction).isNotNull
    val notNullRefreshAction = refreshAction ?: error("Refresh action is missing")

    val service = ApplicationManager.getApplication().getService(CodexSessionsService::class.java)
    val initialTimestamp = service.state.value.lastUpdatedAt

    runInEdtAndWait {
      notNullRefreshAction.actionPerformed(TestActionEvent.createTestEvent(notNullRefreshAction))
    }

    val timeoutAt = System.currentTimeMillis() + 5_000
    var refreshed = false
    while (System.currentTimeMillis() < timeoutAt) {
      val updatedAt = service.state.value.lastUpdatedAt
      if (updatedAt != null && updatedAt != initialTimestamp) {
        refreshed = true
        break
      }
      Thread.sleep(20)
    }
    assertThat(refreshed)
      .withFailMessage("Refresh action didn't trigger a sessions state update in time.")
      .isTrue()
  }
}
