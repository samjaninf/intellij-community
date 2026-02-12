// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.toolWindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.DesktopLayout
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.VisibleForTesting

/**
 * Resolves a toolwindow layout for a specific project frame profile.
 *
 * The layout is used to seed a frame when no project-specific layout was persisted yet.
 */
@Internal
interface ToolWindowLayoutProfileProvider {
  companion object {
    @VisibleForTesting
    val EP_NAME: ExtensionPointName<ToolWindowLayoutProfileProvider> = ExtensionPointName("com.intellij.toolWindowLayoutProfileProvider")
  }

  fun getLayout(project: Project, profileId: String, isNewUi: Boolean): DesktopLayout?
}

@Service(Service.Level.APP)
@Internal
class ToolWindowLayoutProfileService {
  fun getLayout(project: Project, profileId: String, isNewUi: Boolean): DesktopLayout? {
    if (project.isDisposed) {
      return null
    }

    val providers = ToolWindowLayoutProfileProvider.EP_NAME.extensionList
    if (providers.isEmpty()) {
      return null
    }

    var layout: DesktopLayout? = null
    var layoutProvider: ToolWindowLayoutProfileProvider? = null
    for (provider in providers) {
      try {
        val providerLayout = provider.getLayout(project = project, profileId = profileId, isNewUi = isNewUi)
        if (providerLayout != null) {
          if (layout == null) {
            layout = providerLayout
            layoutProvider = provider
          }
          else {
            LOG.error(
              "Multiple tool window layouts are provided for profile '$profileId'. " +
              "Keeping ${layoutProvider?.javaClass?.name}, ignoring ${provider.javaClass.name}."
            )
          }
        }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        LOG.error("Tool window layout profile provider '${provider.javaClass.name}' failed", e)
      }
    }

    return layout
  }
}

private val LOG = logger<ToolWindowLayoutProfileService>()
