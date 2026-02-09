// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.sessions

import com.intellij.agent.workbench.chat.AgentChatEditorService
import com.intellij.agent.workbench.codex.common.CodexCliNotFoundException
import com.intellij.agent.workbench.sessions.providers.AgentSessionSource
import com.intellij.agent.workbench.sessions.providers.createDefaultAgentSessionSources
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtilService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.io.FileUtilRt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

private val LOG = logger<AgentSessionsService>()

@Service(Service.Level.APP)
internal class AgentSessionsService(private val coroutineScope: CoroutineScope) {
  private val refreshMutex = Mutex()
  private val onDemandMutex = Mutex()
  private val onDemandLoading = LinkedHashSet<String>()
  private val sessionSources: List<AgentSessionSource> = createDefaultAgentSessionSources(coroutineScope)

  private val mutableState = MutableStateFlow(AgentSessionsState())
  val state: StateFlow<AgentSessionsState> = mutableState.asStateFlow()

  init {
    ApplicationManager.getApplication().messageBus.connect(coroutineScope)
      .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
        @Deprecated("Deprecated in Java")
        @Suppress("removal")
        override fun projectOpened(project: Project) {
          refresh()
        }

        override fun projectClosed(project: Project) {
          refresh()
        }
      })
  }

  fun refresh() {
    coroutineScope.launch(Dispatchers.IO) {
      if (!refreshMutex.tryLock()) return@launch
      try {
        val entries = collectProjects()
        val initialProjects = entries.map { entry ->
          AgentProjectSessions(
            path = entry.path,
            name = entry.name,
            isOpen = entry.project != null,
            isLoading = entry.project != null,
            hasLoaded = false,
          )
        }
        mutableState.update { it.copy(projects = initialProjects, lastUpdatedAt = System.currentTimeMillis()) }

        for (entry in entries) {
          val project = entry.project ?: continue
          val result = loadThreadsForProject(path = entry.path, project = project)
          updateProject(entry.path) { result }
        }
      }
      catch (e: Throwable) {
        LOG.error("Failed to load agent sessions", e)
        mutableState.update {
          it.copy(
            projects = it.projects.map { project ->
              project.copy(
                isLoading = false,
                hasLoaded = project.isOpen,
                errorMessage = AgentSessionsBundle.message("toolwindow.error"),
              )
            },
            lastUpdatedAt = System.currentTimeMillis(),
          )
        }
      }
      finally {
        refreshMutex.unlock()
      }
    }
  }

  fun openOrFocusProject(path: String) {
    coroutineScope.launch {
      openOrFocusProjectInternal(path)
    }
  }

  fun openChatThread(path: String, thread: AgentSessionThread) {
    openChat(path, thread, subAgent = null)
  }

  fun openChatSubAgent(path: String, thread: AgentSessionThread, subAgent: AgentSubAgent) {
    openChat(path, thread, subAgent)
  }

  fun loadProjectThreadsOnDemand(path: String) {
    coroutineScope.launch(Dispatchers.IO) {
      val normalized = normalizePath(path)
      if (!markOnDemandLoading(normalized)) return@launch
      try {
        updateProject(normalized) { project ->
          project.copy(isLoading = true, errorMessage = null)
        }
        val result = loadThreadsFromClosedProject(path = normalized)
        updateProject(normalized) { project ->
          project.copy(
            isLoading = false,
            hasLoaded = true,
            threads = result.threads,
            errorMessage = result.errorMessage,
          )
        }
      }
      finally {
        clearOnDemandLoading(normalized)
      }
    }
  }

  private suspend fun loadThreadsForProject(path: String, project: Project): AgentProjectSessions {
    val loadedResult = loadThreadsFromOpenProject(path = path, project = project)
    return AgentProjectSessions(
      path = path,
      name = resolveProjectName(path = path, project = project),
      isOpen = true,
      isLoading = false,
      threads = loadedResult.threads,
      hasLoaded = true,
      errorMessage = loadedResult.errorMessage,
    )
  }

  private suspend fun loadThreadsFromOpenProject(path: String, project: Project): AgentSessionLoadResult {
    return loadThreads(path) { source ->
      source.listThreadsFromOpenProject(path = path, project = project)
    }
  }

  private suspend fun loadThreadsFromClosedProject(path: String): AgentSessionLoadResult {
    return loadThreads(path) { source ->
      source.listThreadsFromClosedProject(path = path)
    }
  }

  private suspend fun loadThreads(
    path: String,
    loadOperation: suspend (AgentSessionSource) -> List<AgentSessionThread>,
  ): AgentSessionLoadResult {
    val sourceResults = buildList {
      for (source in sessionSources) {
        val result = try {
          Result.success(loadOperation(source))
        }
        catch (throwable: Throwable) {
          LOG.warn("Failed to load ${source.provider.name} sessions for $path", throwable)
          Result.failure(throwable)
        }
        add(AgentSessionSourceLoadResult(provider = source.provider, result = result))
      }
    }
    return mergeAgentSessionSourceLoadResults(sourceResults, ::resolveErrorMessage)
  }

  private fun resolveErrorMessage(provider: AgentSessionProvider, t: Throwable): @NlsContexts.DialogMessage String {
    return when (t) {
      is CodexCliNotFoundException -> resolveCliMissingMessage(provider)
      else -> AgentSessionsBundle.message("toolwindow.error")
    }
  }

  private fun resolveCliMissingMessage(provider: AgentSessionProvider): @NlsContexts.DialogMessage String {
    return AgentSessionsBundle.message(agentSessionCliMissingMessageKey(provider))
  }

  private suspend fun openOrFocusProjectInternal(path: String) {
    val normalized = normalizePath(path)
    val openProject = findOpenProject(normalized)
    if (openProject != null) {
      withContext(Dispatchers.EDT) {
        ProjectUtilService.getInstance(openProject).focusProjectWindow()
      }
      return
    }
    val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase ?: return
    val projectPath = try {
      Path.of(path)
    }
    catch (_: InvalidPathException) {
      return
    }
    manager.openProject(projectFile = projectPath, options = OpenProjectTask())
  }

  private fun openChat(path: String, thread: AgentSessionThread, subAgent: AgentSubAgent?) {
    coroutineScope.launch {
      val normalized = normalizePath(path)
      if (AgentChatOpenModeSettings.openInDedicatedFrame()) {
        openChatInDedicatedFrame(normalized, thread, subAgent)
        return@launch
      }
      val openProject = findOpenProject(normalized)
      if (openProject != null) {
        openChatInProject(openProject, normalized, thread, subAgent)
        return@launch
      }
      val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase ?: return@launch
      val projectPath = try {
        Path.of(path)
      }
      catch (_: InvalidPathException) {
        return@launch
      }
      val connection = ApplicationManager.getApplication().messageBus.connect(coroutineScope)
      connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
        @Deprecated("Deprecated in Java")
        @Suppress("removal")
        override fun projectOpened(project: Project) {
          if (resolveProjectPath(manager, project) != normalized) return
          coroutineScope.launch {
            openChatInProject(project, normalized, thread, subAgent)
            connection.disconnect()
          }
        }
      })
      manager.openProject(projectFile = projectPath, options = OpenProjectTask())
    }
  }

  private suspend fun openChatInDedicatedFrame(path: String, thread: AgentSessionThread, subAgent: AgentSubAgent?) {
    val dedicatedProjectPath = AgentWorkbenchDedicatedFrameProjectManager.dedicatedProjectPath()
    val openProject = findOpenProject(dedicatedProjectPath)
    if (openProject != null) {
      AgentWorkbenchDedicatedFrameProjectManager.configureProject(openProject)
      openChatInProject(openProject, path, thread, subAgent)
      return
    }

    val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase ?: return
    val dedicatedProjectDir = try {
      AgentWorkbenchDedicatedFrameProjectManager.ensureProjectPath()
    }
    catch (e: Throwable) {
      LOG.warn("Failed to prepare dedicated chat frame project", e)
      return
    }

    val connection = ApplicationManager.getApplication().messageBus.connect(coroutineScope)
    connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
      @Deprecated("Deprecated in Java")
      @Suppress("removal")
      override fun projectOpened(project: Project) {
        if (resolveProjectPath(manager, project) != dedicatedProjectPath) return
        coroutineScope.launch {
          AgentWorkbenchDedicatedFrameProjectManager.configureProject(project)
          openChatInProject(project, path, thread, subAgent)
          connection.disconnect()
        }
      }
    })
    manager.openProject(projectFile = dedicatedProjectDir, options = OpenProjectTask(forceOpenInNewFrame = true))
  }

  private suspend fun openChatInProject(
    project: Project,
    projectPath: String,
    thread: AgentSessionThread,
    subAgent: AgentSubAgent?,
  ) {
    withContext(Dispatchers.EDT) {
      project.service<AgentChatEditorService>().openChat(
        projectPath = projectPath,
        threadIdentity = buildAgentSessionIdentity(provider = thread.provider, sessionId = thread.id),
        shellCommand = buildAgentSessionResumeCommand(provider = thread.provider, sessionId = thread.id),
        threadId = thread.id,
        threadTitle = thread.title,
        subAgentId = subAgent?.id,
      )
      ProjectUtilService.getInstance(project).focusProjectWindow()
    }
  }

  private fun updateProject(path: String, update: (AgentProjectSessions) -> AgentProjectSessions) {
    mutableState.update { state ->
      val next = state.projects.map { project ->
        if (project.path == path) update(project) else project
      }
      state.copy(projects = next, lastUpdatedAt = System.currentTimeMillis())
    }
  }

  private suspend fun markOnDemandLoading(path: String): Boolean {
    return onDemandMutex.withLock {
      val project = state.value.projects.firstOrNull { it.path == path } ?: return@withLock false
      if (project.isOpen || project.isLoading || project.hasLoaded) return@withLock false
      if (!onDemandLoading.add(path)) return@withLock false
      true
    }
  }

  private suspend fun clearOnDemandLoading(path: String) {
    onDemandMutex.withLock {
      onDemandLoading.remove(path)
    }
  }

  private fun collectProjects(): List<ProjectEntry> {
    val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase
      ?: return emptyList()
    val dedicatedProjectPath = AgentWorkbenchDedicatedFrameProjectManager.dedicatedProjectPath()
    val openProjects = ProjectManager.getInstance().openProjects
    val openByPath = LinkedHashMap<String, Project>()
    for (project in openProjects) {
      val path = manager.getProjectPath(project)?.invariantSeparatorsPathString
        ?: project.basePath?.let(::normalizePath)
        ?: continue
      if (path == dedicatedProjectPath || AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProjectPath(path)) continue
      openByPath[path] = project
    }
    val seen = LinkedHashSet<String>()
    val entries = mutableListOf<ProjectEntry>()
    for (path in manager.getRecentPaths()) {
      val normalized = normalizePath(path)
      if (normalized == dedicatedProjectPath || AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProjectPath(normalized)) continue
      if (!seen.add(normalized)) continue
      entries.add(
        ProjectEntry(
          path = normalized,
          name = resolveProjectName(manager, normalized, openByPath[normalized]),
          project = openByPath[normalized],
        )
      )
    }
    for ((path, project) in openByPath) {
      if (!seen.add(path)) continue
      entries.add(
        ProjectEntry(
          path = path,
          name = resolveProjectName(manager, path, project),
          project = project,
        )
      )
    }
    return entries
  }

  private fun resolveProjectName(path: String, project: Project?): String {
    val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase
      ?: return resolveProjectNameWithoutManager(path, project)
    return resolveProjectName(manager, path, project)
  }

  private fun resolveProjectName(
    manager: RecentProjectsManagerBase,
    path: String,
    project: Project?,
  ): String {
    val displayName = manager.getDisplayName(path).takeIf { !it.isNullOrBlank() }
    if (displayName != null) return displayName
    val projectName = manager.getProjectName(path)
    if (projectName.isNotBlank()) return projectName
    if (project != null) return project.name
    return resolveProjectNameWithoutManager(path, project)
  }

  private fun resolveProjectNameWithoutManager(path: String, project: Project?): String {
    if (project != null) return project.name
    val fileName = try {
      Path.of(path).name
    }
    catch (_: InvalidPathException) {
      null
    }
    return fileName ?: FileUtilRt.toSystemDependentName(path)
  }

  private fun findOpenProject(path: String): Project? {
    val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase ?: return null
    val normalized = normalizePath(path)
    return ProjectManager.getInstance().openProjects.firstOrNull { project ->
      resolveProjectPath(manager, project) == normalized
    }
  }

  private fun resolveProjectPath(manager: RecentProjectsManagerBase, project: Project): String? {
    return manager.getProjectPath(project)?.invariantSeparatorsPathString
      ?: project.basePath?.let(::normalizePath)
  }

  private fun normalizePath(path: String): String {
    return try {
      Path.of(path).invariantSeparatorsPathString
    }
    catch (_: InvalidPathException) {
      path
    }
  }

  private data class ProjectEntry(
    val path: String,
    val name: String,
    val project: Project?,
  )
}

internal data class AgentSessionLoadResult(
  val threads: List<AgentSessionThread>,
  val errorMessage: String?,
)

internal data class AgentSessionSourceLoadResult(
  val provider: AgentSessionProvider,
  val result: Result<List<AgentSessionThread>>,
)

internal fun mergeAgentSessionSourceLoadResults(
  sourceResults: List<AgentSessionSourceLoadResult>,
  resolveErrorMessage: (AgentSessionProvider, Throwable) -> String,
): AgentSessionLoadResult {
  val mergedThreads = buildList {
    sourceResults.forEach { sourceResult ->
      addAll(sourceResult.result.getOrElse { emptyList() })
    }
  }.sortedByDescending { it.updatedAt }

  val firstError = sourceResults.firstNotNullOfOrNull { sourceResult ->
    sourceResult.result.exceptionOrNull()?.let { throwable ->
      resolveErrorMessage(sourceResult.provider, throwable)
    }
  }
  val allSourcesFailed = sourceResults.isNotEmpty() && sourceResults.all { it.result.isFailure }
  val errorMessage = if (allSourcesFailed) firstError else null
  return AgentSessionLoadResult(threads = mergedThreads, errorMessage = errorMessage)
}
