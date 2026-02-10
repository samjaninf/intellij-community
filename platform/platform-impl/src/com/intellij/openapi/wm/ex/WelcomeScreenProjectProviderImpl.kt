// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.ex

import com.intellij.diagnostic.WindowsDefenderChecker
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.util.TipAndTrickManager
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.PlatformProjectOpenProcessor
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.EnumSet
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val LOG = logger<WelcomeScreenProjectSupportImpl>()

internal class WelcomeScreenProjectSupportImpl : WelcomeScreenProjectSupport {
  override suspend fun createOrOpenWelcomeScreenProject(extension: WelcomeScreenProjectProvider): Project {
    val projectPath = extension.getWelcomeScreenProjectPathForInternalUsage()

    if (!projectPath.exists(LinkOption.NOFOLLOW_LINKS)) {
      projectPath.createDirectories()
    }
    TrustedProjects.setProjectTrusted(projectPath, true)
    serviceAsync<WindowsDefenderChecker>().markProjectPath(projectPath, /*skip =*/ true)

    val project = extension.doCreateOrOpenWelcomeScreenProjectForInternalUsage(projectPath)
    LOG.info("Opened the welcome screen project at $projectPath")
    LOG.debug("Project: ", project)

    val recentProjectsManager = serviceAsync<RecentProjectsManager>() as RecentProjectsManagerBase
    recentProjectsManager.setProjectHidden(project, extension.isHiddenInRecentProjectsForInternalUsage())
    TipAndTrickManager.DISABLE_TIPS_FOR_PROJECT.set(project, true)

    return project
  }

  override suspend fun openProject(path: Path): Project {
    return PlatformProjectOpenProcessor.openProjectAsync(path)
           ?: throw IllegalStateException("Cannot open project at $path (not expected that user can cancel welcome-project loading)")
  }
}

internal class WelcomeScreenProjectFrameCapabilitiesProvider : ProjectFrameCapabilitiesProvider {
  /**
   * Maps welcome-screen project classification to generic frame capabilities.
   *
   * Startup UI policy is intentionally not provided here; it is contributed by module-specific
   * providers that consume [ProjectFrameCapability.WELCOME_EXPERIENCE].
   */
  override fun getCapabilities(project: Project): Set<ProjectFrameCapability> {
    if (!WelcomeScreenProjectProvider.isWelcomeScreenProject(project)) {
      return emptySet()
    }

    if (WelcomeScreenProjectProvider.isForceDisabledFileColors()) {
      return WELCOME_CAPABILITIES_WITH_DISABLED_FILE_COLORS
    }
    else {
      return WELCOME_CAPABILITIES
    }
  }

  override fun getUiPolicy(project: Project, capabilities: Set<ProjectFrameCapability>): ProjectFrameUiPolicy? {
    return null
  }
}

private val WELCOME_CAPABILITIES: EnumSet<ProjectFrameCapability> =
  EnumSet.of(
    ProjectFrameCapability.WELCOME_EXPERIENCE,
    ProjectFrameCapability.SUPPRESS_VCS_UI,
  )

private val WELCOME_CAPABILITIES_WITH_DISABLED_FILE_COLORS: EnumSet<ProjectFrameCapability> =
  EnumSet.copyOf(WELCOME_CAPABILITIES).apply {
    add(ProjectFrameCapability.FORCE_DISABLE_FILE_COLORS)
  }
