// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.ex

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.CoreUiCoroutineScopeHolder
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@TestApplication
@RunInEdt
class ProjectFrameCapabilitiesServiceTest {
  private val project by projectFixture()

  @TestDisposable
  private lateinit var disposable: Disposable

  @Test
  fun uiPolicyUsesAggregatedCapabilitiesAndCapabilitiesAreCached() {
    val uiPolicyRef = AtomicReference(ProjectFrameUiPolicy(projectPaneToActivateId = "pane-1"))
    val capabilitiesComputationCount = AtomicInteger()
    val capabilitiesRef = AtomicReference(setOf(ProjectFrameCapability.WELCOME_EXPERIENCE))
    ExtensionTestUtil.maskExtensions(
      ProjectFrameCapabilitiesService.EP_NAME,
      listOf(
        object : ProjectFrameCapabilitiesProvider {
          override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
            capabilitiesComputationCount.incrementAndGet()
            return capabilitiesRef.get()
          }

          override fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy? {
            return null
          }
        },
        object : ProjectFrameCapabilitiesProvider {
          override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
            return emptySet()
          }

          override fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy? {
            return uiPolicyRef.get().takeIf { capabilities.contains(ProjectFrameCapability.WELCOME_EXPERIENCE) }
          }
        },
      ),
      disposable,
    )

    val service = ProjectFrameCapabilitiesService(service<CoreUiCoroutineScopeHolder>().coroutineScope)
    assertEquals("pane-1", service.getUiPolicy(project)?.projectPaneToActivateId)

    uiPolicyRef.set(ProjectFrameUiPolicy(projectPaneToActivateId = "pane-2"))
    assertEquals("pane-2", service.getUiPolicy(project)?.projectPaneToActivateId)

    assertEquals(setOf(ProjectFrameCapability.WELCOME_EXPERIENCE), service.getAll(project))
    assertTrue(service.has(project, ProjectFrameCapability.WELCOME_EXPERIENCE))

    capabilitiesRef.set(emptySet())
    assertEquals(setOf(ProjectFrameCapability.WELCOME_EXPERIENCE), service.getAll(project))
    assertEquals(1, capabilitiesComputationCount.get())
  }
}
