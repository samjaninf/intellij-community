// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSessionsOpenModeRoutingTest {
  @Test
  fun dedicatedModeRoutesToDedicatedFrameWhenSourceProjectIsOpen() {
    val route = resolveChatOpenRoute(
      openInDedicatedFrame = true,
      hasOpenSourceProject = true,
    )

    assertEquals(CodexChatOpenRoute.DedicatedFrame, route)
  }

  @Test
  fun dedicatedModeRoutesToDedicatedFrameWhenSourceProjectIsClosed() {
    val route = resolveChatOpenRoute(
      openInDedicatedFrame = true,
      hasOpenSourceProject = false,
    )

    assertEquals(CodexChatOpenRoute.DedicatedFrame, route)
  }

  @Test
  fun currentProjectModeRoutesToOpenProjectWhenAlreadyOpen() {
    val route = resolveChatOpenRoute(
      openInDedicatedFrame = false,
      hasOpenSourceProject = true,
    )

    assertEquals(CodexChatOpenRoute.CurrentProject, route)
  }

  @Test
  fun currentProjectModeRoutesToOpenSourceProjectWhenClosedAndPathValid() {
    val route = resolveChatOpenRoute(
      openInDedicatedFrame = false,
      hasOpenSourceProject = false,
    )

    assertEquals(CodexChatOpenRoute.OpenSourceProject, route)
  }

  @Test
  fun threadAndSubAgentUseTheSameRouteDecision() {
    val threadRoute = resolveChatOpenRoute(
      openInDedicatedFrame = false,
      hasOpenSourceProject = false,
    )
    val subAgentRoute = resolveChatOpenRoute(
      openInDedicatedFrame = false,
      hasOpenSourceProject = false,
    )

    assertEquals(CodexChatOpenRoute.OpenSourceProject, threadRoute)
    assertEquals(threadRoute, subAgentRoute)
  }
}
