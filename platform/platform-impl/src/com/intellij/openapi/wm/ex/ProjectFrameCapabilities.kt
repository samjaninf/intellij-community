// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.ex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.containers.CollectionFactory
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus.Experimental
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.EnumSet

@Internal
@Experimental
enum class ProjectFrameCapability {
  WELCOME_EXPERIENCE,
  SUPPRESS_VCS_UI,
  FORCE_DISABLE_FILE_COLORS,
}

private val EP_NAME: ExtensionPointName<ProjectFrameCapabilitiesProvider> =
  ExtensionPointName("com.intellij.projectFrameCapabilitiesProvider")

@Internal
@Experimental
interface ProjectFrameCapabilitiesProvider {
  fun getCapabilities(project: Project): Set<ProjectFrameCapability>
}

@Service(Service.Level.APP)
@Internal
@Experimental
class ProjectFrameCapabilitiesService(coroutineScope: CoroutineScope) {
  companion object {
    suspend fun getInstance(): ProjectFrameCapabilitiesService = serviceAsync()

    @Deprecated("Use getInstance instead", ReplaceWith("getInstance()"))
    fun getInstanceSync(): ProjectFrameCapabilitiesService = service()
  }

  private val capabilitiesByProject = CollectionFactory.createConcurrentWeakMap<Project, Set<ProjectFrameCapability>>()

  init {
    ApplicationManager.getApplication().messageBus.connect(coroutineScope).subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
      override fun projectClosed(project: Project) {
        capabilitiesByProject.remove(project)
      }
    })
  }

  fun has(project: Project, capability: ProjectFrameCapability): Boolean {
    return getAll(project).contains(capability)
  }

  fun getAll(project: Project): Set<ProjectFrameCapability> {
    if (project.isDisposed) {
      return emptySet()
    }
    return capabilitiesByProject.computeIfAbsent(project, ::computeCapabilities)
  }
}

private fun computeCapabilities(project: Project): Set<ProjectFrameCapability> {
  val capabilities = EnumSet.noneOf(ProjectFrameCapability::class.java)
  EP_NAME.forEachExtensionSafe { provider ->
    capabilities.addAll(provider.getCapabilities(project))
  }
  if (capabilities.isEmpty()) {
    return emptySet()
  }
  return capabilities
}