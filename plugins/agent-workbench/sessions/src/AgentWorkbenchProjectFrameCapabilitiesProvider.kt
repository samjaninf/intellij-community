// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ProjectFrameCapabilitiesProvider
import com.intellij.openapi.wm.ex.ProjectFrameCapability

internal class AgentWorkbenchProjectFrameCapabilitiesProvider : ProjectFrameCapabilitiesProvider {
  override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
    if (AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProject(project)) {
      return AGENT_WORKBENCH_FRAME_CAPABILITIES
    }
    else {
      return emptySet()
    }
  }
}

private val AGENT_WORKBENCH_FRAME_CAPABILITIES = setOf(ProjectFrameCapability.SUPPRESS_VCS_UI)
