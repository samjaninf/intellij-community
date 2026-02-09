// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.WelcomeScreenTabService
import com.intellij.platform.ide.nonModalWelcomeScreen.leftPanel.WelcomeScreenLeftPanel
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeScreenRightTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WelcomeScreenTabServiceImpl(private val project: Project) : WelcomeScreenTabService {
  override suspend fun openTab() {
    if (!project.isWelcomeExperienceProject()) {
      return
    }
    WelcomeScreenRightTab.show(project)
  }

  override suspend fun openProjectView(toolWindowManager: ToolWindowManager) {
    if (!project.isWelcomeExperienceProject()) {
      return
    }
    withContext(Dispatchers.EDT) {
      toolWindowManager.getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null)
    }
  }

  override fun getProjectPaneToActivate(): String? {
    return if (project.isWelcomeExperienceProjectSync()) WelcomeScreenLeftPanel.ID else null
  }
}
