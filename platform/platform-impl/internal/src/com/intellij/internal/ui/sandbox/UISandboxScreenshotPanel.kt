// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.ui.sandbox

import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JComponent

/**
 * @author Konstantin Bulenkov
 */
abstract class UISandboxScreenshotPanel: UISandboxPanel {
  companion object {
    val SCREENSHOT_BACKGROUND: Color = JBColor(0xF7F8FA, 0x2B2D30)
  }

  override fun createContent(disposable: Disposable): JComponent {
    return panel {
      row {
        cell(createContentForScreenshot(disposable).apply { isOpaque = false })
          .align(Align.CENTER)
      }.resizableRow()
    }.apply {
      background = SCREENSHOT_BACKGROUND
    }
  }

  abstract fun createContentForScreenshot(disposable: Disposable): JComponent
}