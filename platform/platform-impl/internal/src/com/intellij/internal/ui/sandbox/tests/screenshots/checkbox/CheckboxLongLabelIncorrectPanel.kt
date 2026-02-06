// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.ui.sandbox.tests.screenshots.checkbox

import com.intellij.internal.ui.sandbox.UISandboxScreenshotPanel
import com.intellij.openapi.Disposable
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * @author Konstantin Bulenkov
 */
internal class CheckboxLongLabelIncorrectPanel : UISandboxScreenshotPanel() {
  override val title: String = "Incorrect"

  override fun createContentForScreenshot(disposable: Disposable): JComponent {
    return panel {
      row {
        checkBox("""<html>Insert selected suggestion by pressing<br/>space, dot, or other context-dependent<br/>keys. Suggestions will appear as you type<br/>and can help you complete words and<br/>phrases more quickly</html>""").apply {
          component.isSelected = true
        }
      }
    }
  }
}