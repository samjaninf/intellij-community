// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.whatsNew

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider.Request.Companion.html
import com.intellij.openapi.project.Project

open class WhatsNewResourceProvider(val project: Project) {
  open fun getRequest(content: String): HTMLEditorProvider.Request = html(content)

  companion object {
    fun getInstance(project: Project): WhatsNewResourceProvider = project.service()
  }
}
