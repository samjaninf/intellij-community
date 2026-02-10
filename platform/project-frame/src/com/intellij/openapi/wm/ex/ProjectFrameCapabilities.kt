// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.ex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.containers.CollectionFactory
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus.Experimental
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.VisibleForTesting
import java.util.EnumSet
import java.util.concurrent.CancellationException

@Internal
@Experimental
enum class ProjectFrameCapability {
  /**
   * Marks a project as a welcome-experience frame.
   */
  WELCOME_EXPERIENCE,

  /**
   * Suppresses VCS-oriented UI in the frame.
   */
  SUPPRESS_VCS_UI,

  /**
   * Disables file colors for the frame.
   */
  FORCE_DISABLE_FILE_COLORS,
}

/**
 * Optional startup UI policy for a project frame.
 *
 * This policy is consumed during frame/toolwindow initialization to adjust initial focus and
 * visibility (for example, select a specific Project View pane or activate/hide toolwindows).
 */
@Internal
@Experimental
data class ProjectFrameUiPolicy(
  /** Project View pane id to select on startup. */
  val projectPaneToActivateId: String? = null,

  /** Toolwindow id to activate after toolwindow initialization. */
  val startupToolWindowIdToActivate: String? = null,

  /** Toolwindow ids to hide after startup activation. */
  val toolWindowIdsToHideOnStartup: Set<String> = emptySet(),
) {
  fun isEmpty(): Boolean {
    return projectPaneToActivateId == null && startupToolWindowIdToActivate == null && toolWindowIdsToHideOnStartup.isEmpty()
  }
}

private val LOG = logger<ProjectFrameCapabilitiesService>()

@Internal
@Experimental
interface ProjectFrameCapabilitiesProvider {
  /**
   * Returns frame capabilities contributed by this provider for [project].
   */
  fun getCapabilities(project: Project): Set<ProjectFrameCapability>

  /**
   * Returns optional startup UI policy for [project].
   *
   * [capabilities] contains the aggregated capabilities produced by all providers and can be used
   * as an input signal, so providers avoid duplicating project classification predicates.
   */
  fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy?
}

/**
 * Aggregates project frame capabilities and optional startup UI policy from
 * [ProjectFrameCapabilitiesProvider] extensions.
 */
@Service(Service.Level.APP)
@Internal
@Experimental
class ProjectFrameCapabilitiesService(coroutineScope: CoroutineScope) {
  companion object {
    @VisibleForTesting
    val EP_NAME: ExtensionPointName<ProjectFrameCapabilitiesProvider> = ExtensionPointName("com.intellij.projectFrameCapabilitiesProvider")

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
    return getOrComputeCapabilities(project)
  }

  /**
   * Returns startup UI policy for [project], if any.
   *
   * At most one provider policy is accepted; if multiple policies are contributed,
   * the first one is kept and an error is logged.
   */
  fun getUiPolicy(project: Project): ProjectFrameUiPolicy? {
    if (project.isDisposed) {
      return null
    }

    val providers = EP_NAME.extensionsIfPointIsRegistered
    if (providers.isEmpty()) {
      return null
    }

    val capabilities = getOrComputeCapabilities(project)
    var uiPolicy: ProjectFrameUiPolicy? = null
    var uiPolicyProvider: ProjectFrameCapabilitiesProvider? = null

    forEachProjectFrameCapabilitiesProviderSafe(providers) { provider ->
      val providerUiPolicy = provider.getUiPolicy(project, capabilities)?.takeUnless(ProjectFrameUiPolicy::isEmpty)
      if (providerUiPolicy != null) {
        if (uiPolicy == null) {
          uiPolicy = providerUiPolicy
          uiPolicyProvider = provider
        }
        else {
          LOG.error(
            "Multiple project frame UI policies are provided for project '${project.name}'. " +
            "Only one provider is allowed. Keeping ${uiPolicyProvider?.javaClass?.name}, ignoring ${provider.javaClass.name}."
          )
        }
      }
    }

    return uiPolicy
  }

  private fun getOrComputeCapabilities(project: Project): Set<ProjectFrameCapability> {
    if (project.isDisposed) {
      return emptySet()
    }
    return capabilitiesByProject.computeIfAbsent(project, ::computeProjectFrameCapabilities)
  }
}

private fun computeProjectFrameCapabilities(project: Project): Set<ProjectFrameCapability> {
  val providers = ProjectFrameCapabilitiesService.EP_NAME.extensionsIfPointIsRegistered
  if (providers.isEmpty()) {
    return emptySet()
  }

  val capabilities = EnumSet.noneOf(ProjectFrameCapability::class.java)

  forEachProjectFrameCapabilitiesProviderSafe(providers) { provider ->
    capabilities.addAll(provider.getCapabilities(project))
  }

  return capabilities.takeIf { it.isNotEmpty() }?.toSet() ?: emptySet()
}

private inline fun forEachProjectFrameCapabilitiesProviderSafe(
  providers: List<ProjectFrameCapabilitiesProvider>,
  consumer: (ProjectFrameCapabilitiesProvider) -> Unit,
) {
  for (provider in providers) {
    try {
      consumer(provider)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Throwable) {
      LOG.error("Project frame capabilities provider '${provider.javaClass.name}' failed", e)
    }
  }
}
