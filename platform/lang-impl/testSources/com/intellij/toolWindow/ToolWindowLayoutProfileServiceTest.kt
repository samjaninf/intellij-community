// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.DesktopLayout
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@TestApplication
@RunInEdt
class ToolWindowLayoutProfileServiceTest {
  private val project by projectFixture()

  @TestDisposable
  private lateinit var disposable: Disposable

  @Test
  fun returnsLayoutFromFirstMatchingProvider() {
    val firstLayout = DesktopLayout()

    ExtensionTestUtil.maskExtensions(
      ToolWindowLayoutProfileProvider.EP_NAME,
      listOf(
        object : ToolWindowLayoutProfileProvider {
          override fun getLayout(project: Project, profileId: String, isNewUi: Boolean): DesktopLayout? {
            return firstLayout.takeIf { profileId == "dedicated" }
          }
        },
        object : ToolWindowLayoutProfileProvider {
          override fun getLayout(project: Project, profileId: String, isNewUi: Boolean): DesktopLayout? {
            return null
          }
        },
      ),
      disposable,
    )

    val result = service<ToolWindowLayoutProfileService>().getLayout(project = project, profileId = "dedicated", isNewUi = true)

    assertThat(result).isSameAs(firstLayout)
  }

  @Test
  fun returnsNullWhenNoProviderMatchesProfileId() {
    ExtensionTestUtil.maskExtensions(
      ToolWindowLayoutProfileProvider.EP_NAME,
      listOf(
        object : ToolWindowLayoutProfileProvider {
          override fun getLayout(project: Project, profileId: String, isNewUi: Boolean): DesktopLayout? {
            return null
          }
        },
      ),
      disposable,
    )

    val result = service<ToolWindowLayoutProfileService>().getLayout(project = project, profileId = "missing", isNewUi = true)
    assertThat(result).isNull()
  }
}
