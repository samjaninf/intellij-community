// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.whatsNew

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

open class WhatsNewMultipageStartIdProvider(val project: Project) {
  protected open suspend fun getId(): String? = null

  internal suspend fun getIdIfSupported(multipageIds: List<String>): String? {
    if (multipageIds.size == 1) return multipageIds.first()
    val startPageId = getId()
    if (startPageId == null) {
      logger.warn("What's new artifact contains several page ids but getId() returns null. Implement WhatsNewMultipageStartIdProvider.getId() to return one of them. Now trying to show the first page from available.")
      return multipageIds.firstOrNull()
    }
    return startPageId.checkSupported(multipageIds)
  }

  private fun String.checkSupported(multipageIds: List<String>): String {
    if (this in multipageIds) return this
    else {
      logger.warn("What's new multipage id \"$this\" is not supported. Supported ids: ${multipageIds.joinToString()}")
      return this
    }
  }

  companion object {
    fun getInstance(project: Project): WhatsNewMultipageStartIdProvider = project.service()
  }
}

private val logger = logger<WhatsNewMultipageStartIdProvider>()



