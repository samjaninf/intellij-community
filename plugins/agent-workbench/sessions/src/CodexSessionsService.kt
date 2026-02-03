// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment")

package com.intellij.agent.workbench.sessions

import com.intellij.agent.workbench.chat.CodexChatEditorService
import com.intellij.agent.workbench.codex.common.CodexAppServerClient
import com.intellij.agent.workbench.codex.common.CodexAppServerException
import com.intellij.agent.workbench.codex.common.CodexCliNotFoundException
import com.intellij.agent.workbench.codex.common.CodexProjectSessions
import com.intellij.agent.workbench.codex.common.CodexSessionsState
import com.intellij.agent.workbench.codex.common.CodexSubAgent
import com.intellij.agent.workbench.codex.common.CodexThread
import com.intellij.agent.workbench.codex.common.CodexThreadPage
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtilService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.io.FileUtilRt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

private val LOG = logger<CodexSessionsService>()

private const val INITIAL_VISIBLE_THREADS = 3
private const val THREADS_PAGE_SIZE = 50
private const val REFRESH_CONCURRENCY = 4

@Service(Service.Level.APP)
internal class CodexSessionsService(private val coroutineScope: CoroutineScope) {
  private val refreshMutex = Mutex()
  private val onDemandMutex = Mutex()
  private val onDemandLoading = LinkedHashSet<String>()
  private val createThreadMutex = Mutex()
  private val createThreadLoading = LinkedHashSet<String>()
  private val moreThreadsMutex = Mutex()
  private val moreThreadsLoading = LinkedHashSet<String>()
  private val treeUiState = service<CodexSessionsTreeUiStateService>()

  private val mutableState = MutableStateFlow(CodexSessionsState())
  val state: StateFlow<CodexSessionsState> = mutableState.asStateFlow()

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
        val existingProjectsByPath = state.value.projects.associateBy { it.path }
        val openProjectPaths = entries.asSequence()
          .filter { it.project != null }
          .map { it.path }
          .toSet()
        treeUiState.retainOpenProjectThreadPreviews(openProjectPaths)
        val initialProjects = entries.map { entry ->
          val existingProject = existingProjectsByPath[entry.path]
          val cachedThreads = if (entry.project != null) treeUiState.getOpenProjectThreadPreviews(entry.path) else null
          CodexProjectSessions(
            path = entry.path,
            name = entry.name,
            isOpen = entry.project != null,
            isLoading = entry.project != null,
            threads = existingProject?.threads ?: cachedThreads.orEmpty(),
            hasLoaded = existingProject?.hasLoaded == true || cachedThreads != null,
            nextThreadsCursor = existingProject?.nextThreadsCursor,
            loadMoreErrorMessage = existingProject?.loadMoreErrorMessage,
          )
        }
        mutableState.update { it.copy(projects = initialProjects, lastUpdatedAt = System.currentTimeMillis()) }

        val openEntries = entries.filter { it.project != null }
        val refreshSemaphore = Semaphore(REFRESH_CONCURRENCY)
        kotlinx.coroutines.coroutineScope {
          openEntries
            .map { entry ->
              async {
                refreshSemaphore.withPermit {
                  refreshOpenProjectThreads(entry)
                }
              }
            }
            .awaitAll()
        }
      }
      catch (e: Throwable) {
        LOG.error("Failed to load Codex sessions", e)
        mutableState.update {
          it.copy(
            projects = it.projects.map { project ->
              project.copy(
                isLoading = false,
                hasLoaded = project.isOpen,
                errorMessage = resolveErrorMessage(e),
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

  fun openChatThread(path: String, thread: CodexThread) {
    openChat(path, thread, subAgent = null)
  }

  fun openChatSubAgent(path: String, thread: CodexThread, subAgent: CodexSubAgent) {
    openChat(path, thread, subAgent)
  }

  fun createAndOpenThread(path: String) {
    coroutineScope.launch(Dispatchers.IO) {
      val normalized = normalizePath(path)
      if (!markCreateThreadLoading(normalized)) return@launch
      try {
        updateProject(normalized) { project ->
          project.copy(isLoading = true, errorMessage = null)
        }
        val createdThread = createThreadForProjectPath(normalized)
        updateProject(normalized) { project ->
          project.copy(
            isLoading = false,
            hasLoaded = true,
            threads = mergeThread(project.threads, createdThread),
            errorMessage = null,
          )
        }
        cacheOpenProjectThreadsIfNeeded(normalized)
        openChat(normalized, createdThread, subAgent = null)
      }
      catch (e: Throwable) {
        LOG.warn("Failed to create Codex thread for $normalized", e)
        updateProject(normalized) { project ->
          project.copy(
            isLoading = false,
            errorMessage = resolveErrorMessage(e),
          )
        }
      }
      finally {
        clearCreateThreadLoading(normalized)
      }
    }
  }

  fun showAllThreadsForProject(path: String) {
    coroutineScope.launch(Dispatchers.IO) {
      val normalized = normalizePath(path)
      val project = state.value.projects.firstOrNull { it.path == normalized } ?: return@launch
      if (project.loadMoreErrorMessage != null) {
        updateProject(normalized) { current ->
          current.copy(loadMoreErrorMessage = null)
        }
      }
      val visibleThreadCount = treeUiState.getVisibleThreadCount(normalized)
      val loadedThreads = project.threads.sortedByDescending { it.updatedAt }
      val hasMoreLoadedThreads = loadedThreads.size > visibleThreadCount
      if (hasMoreLoadedThreads) {
        if (treeUiState.incrementVisibleThreadCount(normalized, INITIAL_VISIBLE_THREADS)) {
          mutableState.update { it.copy(lastUpdatedAt = System.currentTimeMillis()) }
        }
        return@launch
      }
      if (project.nextThreadsCursor.isNullOrBlank()) return@launch
      if (!markMoreThreadsLoading(normalized)) return@launch
      try {
        updateProject(normalized) { current ->
          current.copy(isPagingThreads = true, loadMoreErrorMessage = null)
        }
        val page = loadThreadsPageForProjectPath(
          path = normalized,
          cursor = project.nextThreadsCursor,
          limit = THREADS_PAGE_SIZE,
        )
        updateProject(normalized) { current ->
          val mergedThreads = mergeThreads(current.threads, page.threads)
          current.copy(
            threads = mergedThreads,
            hasLoaded = true,
            isPagingThreads = false,
            nextThreadsCursor = page.nextCursor,
            errorMessage = null,
            loadMoreErrorMessage = null,
          )
        }
        cacheOpenProjectThreadsIfNeeded(normalized)
        if (treeUiState.incrementVisibleThreadCount(normalized, INITIAL_VISIBLE_THREADS)) {
          mutableState.update { it.copy(lastUpdatedAt = System.currentTimeMillis()) }
        }
      }
      catch (e: Throwable) {
        LOG.warn("Failed to load additional Codex sessions for $normalized", e)
        updateProject(normalized) { current ->
          current.copy(
            isPagingThreads = false,
            loadMoreErrorMessage = resolveLoadMoreErrorMessage(e),
          )
        }
      }
      finally {
        clearMoreThreadsLoading(normalized)
      }
    }
  }

  fun loadProjectThreadsOnDemand(path: String) {
    coroutineScope.launch(Dispatchers.IO) {
      val normalized = normalizePath(path)
      if (!markOnDemandLoading(normalized)) return@launch
      try {
        updateProject(normalized) { project ->
          project.copy(isLoading = true, errorMessage = null)
        }
        val result = try {
          val page = loadInitialThreadsForProjectPath(normalized)
          LoadedResult(
            threads = page.threads,
            nextCursor = page.nextCursor,
            errorMessage = null,
          )
        }
        catch (e: Throwable) {
          LOG.warn("Failed to load Codex sessions for $normalized", e)
          LoadedResult(
            threads = emptyList(),
            nextCursor = null,
            errorMessage = resolveErrorMessage(e),
          )
        }
        updateProject(normalized) { project ->
          project.copy(
            isLoading = false,
            hasLoaded = true,
            threads = result.threads,
            nextThreadsCursor = result.nextCursor,
            errorMessage = result.errorMessage,
            loadMoreErrorMessage = null,
          )
        }
      }
      finally {
        clearOnDemandLoading(normalized)
      }
    }
  }

  private suspend fun refreshOpenProjectThreads(entry: ProjectEntry) {
    val result = try {
      val page = loadInitialThreadsForProjectPath(entry.path)
      LoadedResult(
        threads = page.threads,
        nextCursor = page.nextCursor,
        errorMessage = null,
      )
    }
    catch (e: Throwable) {
      LOG.warn("Failed to load Codex sessions for ${entry.path}", e)
      LoadedResult(
        threads = emptyList(),
        nextCursor = null,
        errorMessage = resolveErrorMessage(e),
      )
    }

    updateProject(entry.path) { project ->
      val refreshSucceeded = result.errorMessage == null
      val nextThreads = if (refreshSucceeded) result.threads else project.threads
      val nextCursor = if (refreshSucceeded) result.nextCursor else project.nextThreadsCursor
      project.copy(
        isLoading = false,
        hasLoaded = true,
        isPagingThreads = false,
        threads = nextThreads,
        nextThreadsCursor = nextCursor,
        errorMessage = result.errorMessage,
        loadMoreErrorMessage = if (refreshSucceeded) null else project.loadMoreErrorMessage,
      )
    }

    if (result.errorMessage == null) {
      treeUiState.setOpenProjectThreadPreviews(entry.path, result.threads)
    }
  }

  private suspend fun loadThreadsPageForProjectPath(path: String, cursor: String?, limit: Int): CodexThreadPage {
    val openProject = findOpenProject(path)
    if (openProject != null) {
      val service = openProject.getService(CodexProjectSessionService::class.java)
      if (service == null || !service.hasWorkingDirectory()) {
        throw CodexAppServerException("Project directory is not available")
      }
      return service.listThreadsPage(cursor = cursor, limit = limit)
    }

    val workingDirectory = resolveProjectDirectoryFromPath(path)
      ?: throw CodexAppServerException("Project directory is not available")
    val client = CodexAppServerClient(coroutineScope = coroutineScope, workingDirectory = workingDirectory)
    try {
      return client.listThreadsPage(
        archived = false,
        cursor = cursor,
        limit = limit,
      )
    }
    finally {
      client.shutdown()
    }
  }

  private suspend fun loadInitialThreadsForProjectPath(path: String): CodexThreadPage {
    val initialPage = loadThreadsPageForProjectPath(
      path = path,
      cursor = null,
      limit = THREADS_PAGE_SIZE,
    )
    return seedInitialVisibleThreads(
      initialPage = initialPage,
      minimumVisibleThreads = INITIAL_VISIBLE_THREADS,
      loadNextPage = { cursor ->
        loadThreadsPageForProjectPath(
          path = path,
          cursor = cursor,
          limit = THREADS_PAGE_SIZE,
        )
      },
    )
  }

  private fun resolveErrorMessage(t: Throwable): String {
    return when (t) {
      is CodexCliNotFoundException -> CodexSessionsBundle.message("toolwindow.error.cli")
      else -> CodexSessionsBundle.message("toolwindow.error")
    }
  }

  private fun resolveLoadMoreErrorMessage(t: Throwable): String {
    return when (t) {
      is CodexCliNotFoundException -> CodexSessionsBundle.message("toolwindow.error.cli")
      else -> CodexSessionsBundle.message("toolwindow.error.more")
    }
  }

  private suspend fun createThreadForProjectPath(path: String): CodexThread {
    val openProject = findOpenProject(path)
    if (openProject != null) {
      val service = openProject.serviceAsync<CodexProjectSessionService>()
      if (!service.hasWorkingDirectory()) {
        throw CodexAppServerException("Project directory is not available")
      }
      return service.createThread()
    }

    val workingDirectory = resolveProjectDirectoryFromPath(path)
      ?: throw CodexAppServerException("Project directory is not available")

    val client = CodexAppServerClient(coroutineScope = coroutineScope, workingDirectory = workingDirectory)
    try {
      return client.createThread()
    }
    finally {
      client.shutdown()
    }
  }

  private fun mergeThread(threads: List<CodexThread>, thread: CodexThread): List<CodexThread> {
    return mergeThreads(threads, listOf(thread))
  }

  private fun mergeThreads(currentThreads: List<CodexThread>, additionalThreads: List<CodexThread>): List<CodexThread> {
    return mergeCodexThreads(currentThreads, additionalThreads)
  }

  private fun cacheOpenProjectThreadsIfNeeded(path: String) {
    val project = state.value.projects.firstOrNull { it.path == path } ?: return
    if (!project.isOpen) return
    treeUiState.setOpenProjectThreadPreviews(path, project.threads)
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

  private fun openChat(path: String, thread: CodexThread, subAgent: CodexSubAgent?) {
    coroutineScope.launch {
      val normalized = normalizePath(path)
      val openProject = findOpenProject(normalized)
      when (resolveChatOpenRoute(
        openInDedicatedFrame = CodexChatOpenModeSettings.openInDedicatedFrame(),
        hasOpenSourceProject = openProject != null,
      )) {
        CodexChatOpenRoute.DedicatedFrame -> {
          openChatInDedicatedFrame(normalized, thread, subAgent)
          return@launch
        }
        CodexChatOpenRoute.CurrentProject -> {
          openChatInProject(openProject ?: return@launch, normalized, thread, subAgent)
          return@launch
        }
        CodexChatOpenRoute.OpenSourceProject -> {
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
    }
  }

  private suspend fun openChatInDedicatedFrame(path: String, thread: CodexThread, subAgent: CodexSubAgent?) {
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
    thread: CodexThread,
    subAgent: CodexSubAgent?,
  ) {
    withContext(Dispatchers.EDT) {
      project.service<CodexChatEditorService>().openChat(
        projectPath = projectPath,
        threadId = thread.id,
        threadTitle = thread.title,
        subAgentId = subAgent?.id,
      )
      ProjectUtilService.getInstance(project).focusProjectWindow()
    }
  }

  private fun updateProject(path: String, update: (CodexProjectSessions) -> CodexProjectSessions) {
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

  private suspend fun markCreateThreadLoading(path: String): Boolean {
    return createThreadMutex.withLock {
      val project = state.value.projects.firstOrNull { it.path == path } ?: return@withLock false
      if (project.isLoading) return@withLock false
      if (!createThreadLoading.add(path)) return@withLock false
      true
    }
  }

  private suspend fun markMoreThreadsLoading(path: String): Boolean {
    return moreThreadsMutex.withLock {
      val project = state.value.projects.firstOrNull { it.path == path } ?: return@withLock false
      if (project.isPagingThreads || project.nextThreadsCursor.isNullOrBlank()) return@withLock false
      if (!moreThreadsLoading.add(path)) return@withLock false
      true
    }
  }

  private suspend fun clearOnDemandLoading(path: String) {
    onDemandMutex.withLock {
      onDemandLoading.remove(path)
    }
  }

  private suspend fun clearCreateThreadLoading(path: String) {
    createThreadMutex.withLock {
      createThreadLoading.remove(path)
    }
  }

  private suspend fun clearMoreThreadsLoading(path: String) {
    moreThreadsMutex.withLock {
      moreThreadsLoading.remove(path)
    }
  }
}

internal enum class CodexChatOpenRoute {
  DedicatedFrame,
  CurrentProject,
  OpenSourceProject,
}

internal fun resolveChatOpenRoute(
  openInDedicatedFrame: Boolean,
  hasOpenSourceProject: Boolean,
): CodexChatOpenRoute {
  if (openInDedicatedFrame) return CodexChatOpenRoute.DedicatedFrame
  if (hasOpenSourceProject) return CodexChatOpenRoute.CurrentProject
  return CodexChatOpenRoute.OpenSourceProject
}

private fun collectProjects(): List<ProjectEntry> {
  val manager = RecentProjectsManager.getInstance() as? RecentProjectsManagerBase ?: return emptyList()
  val dedicatedProjectPath = AgentWorkbenchDedicatedFrameProjectManager.dedicatedProjectPath()
  val openProjects = ProjectManager.getInstance().openProjects
  val openByPath = LinkedHashMap<String, Project>()
  for (project in openProjects) {
    val path = manager.getProjectPath(project)?.invariantSeparatorsPathString
      ?: project.basePath?.let(::normalizePath)
      ?: continue
    if (path == dedicatedProjectPath || AgentWorkbenchDedicatedFrameProjectManager.isDedicatedProjectPath(path)) {
      continue
    }
    openByPath.put(path, project)
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

private data class LoadedResult(
  val threads: List<CodexThread>,
  val nextCursor: String?,
  val errorMessage: String?,
)

internal suspend fun seedInitialVisibleThreads(
  initialPage: CodexThreadPage,
  minimumVisibleThreads: Int,
  loadNextPage: suspend (cursor: String) -> CodexThreadPage,
): CodexThreadPage {
  if (minimumVisibleThreads <= 0) return initialPage

  var mergedThreads = mergeCodexThreads(emptyList(), initialPage.threads)
  var nextCursor = initialPage.nextCursor
  val visitedCursors = LinkedHashSet<String>()

  while (mergedThreads.size < minimumVisibleThreads) {
    val cursor = nextCursor?.takeIf { it.isNotBlank() } ?: break
    if (!visitedCursors.add(cursor)) break

    val page = loadNextPage(cursor)
    val previousSize = mergedThreads.size
    mergedThreads = mergeCodexThreads(mergedThreads, page.threads)
    nextCursor = page.nextCursor

    if (mergedThreads.size == previousSize && nextCursor == cursor) break
  }

  return CodexThreadPage(
    threads = mergedThreads,
    nextCursor = nextCursor,
  )
}

private fun mergeCodexThreads(currentThreads: List<CodexThread>, additionalThreads: List<CodexThread>): List<CodexThread> {
  val merged = LinkedHashMap<String, CodexThread>()
  for (thread in currentThreads) {
    merged[thread.id] = thread
  }
  for (thread in additionalThreads) {
    val existing = merged[thread.id]
    if (existing == null || thread.updatedAt >= existing.updatedAt) {
      merged[thread.id] = thread
    }
  }
  return merged.values.sortedByDescending { it.updatedAt }
}
