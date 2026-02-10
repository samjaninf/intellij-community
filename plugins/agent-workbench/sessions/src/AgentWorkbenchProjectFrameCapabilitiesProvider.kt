// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ex.ProjectFrameCapabilitiesProvider
import com.intellij.openapi.wm.ex.ProjectFrameCapability
import com.intellij.openapi.wm.ex.ProjectFrameUiPolicy

internal class AgentWorkbenchProjectFrameCapabilitiesProvider : ProjectFrameCapabilitiesProvider {
  override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
    if (AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProject(project)) {
      return AGENT_WORKBENCH_FRAME_CAPABILITIES
    }
    else {
      return emptySet()
    }
  }

  /**
   * Applies the startup policy only when aggregated capabilities already classify this frame as
   * VCS-suppressed. The dedicated-project check stays as a defensive guard.
   */
  override fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy? {
    if (!capabilities.contains(ProjectFrameCapability.SUPPRESS_VCS_UI)) {
      return null
    }

    if (!AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProject(project)) {
      return null
    }
    return AGENT_WORKBENCH_FRAME_UI_POLICY
  }
}

private val AGENT_WORKBENCH_FRAME_CAPABILITIES = setOf(ProjectFrameCapability.SUPPRESS_VCS_UI)
private val AGENT_WORKBENCH_FRAME_UI_POLICY = ProjectFrameUiPolicy(
  startupToolWindowIdToActivate = "agent.workbench.sessions",
  toolWindowIdsToHideOnStartup = setOf(ToolWindowId.PROJECT_VIEW),
)
