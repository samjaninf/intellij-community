@file:Suppress("FunctionName", "unused")

package com.intellij.mcpserver.toolsets.general

import com.intellij.find.FindBundle
import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.find.impl.SearchEverywhereItem
import com.intellij.ide.rpc.rpcId
import com.intellij.mcpserver.McpServerBundle
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.toolsets.Constants.MAX_USAGE_TEXT_CHARS
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Segment
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.getPathMatcher
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeItemData
import com.intellij.platform.searchEverywhere.SeItemsProvider
import com.intellij.platform.searchEverywhere.SeLegacyItem
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.platform.searchEverywhere.SeProviderId
import com.intellij.platform.searchEverywhere.SeProviderIdUtils
import com.intellij.platform.searchEverywhere.SeSession
import com.intellij.platform.searchEverywhere.SeSessionEntity
import com.intellij.platform.searchEverywhere.SeTransferItem
import com.intellij.platform.searchEverywhere.asRef
import com.intellij.platform.searchEverywhere.backend.impl.SeBackendService
import com.intellij.platform.searchEverywhere.isCommand
import com.intellij.platform.searchEverywhere.providers.SeEverywhereFilter
import com.intellij.platform.searchEverywhere.providers.SeTextFilterKeys
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopes
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import fleet.kernel.change
import fleet.kernel.rebase.shared
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.PatternSyntaxException
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_RESULTS_UPPER_BOUND = 5000
private const val SEARCH_SCOPE_MULTIPLIER = 5
private const val PATHS_DESCRIPTION = "Optional list of project-relative glob patterns to filter results. " +
                                      "Supports '!' excludes. Trailing '/' expands to '**'. " +
                                      "Patterns without '/' are treated as '**/pattern'. Empty strings are ignored."

private val DEFAULT_FILE_SYSTEM = FileSystems.getDefault()

/**
 * Unified search toolset for MCP.
 *
 * The public API stays intentionally small for LLM friendliness, while the
 * implementation maps requests to the most efficient IDE backends and applies
 * precise post-filtering where needed.
 */
internal class SearchToolset : McpToolset {
  @McpTool
  @McpDescription("""
        |Searches for a text substring within project files.
        |Use this tool for fast text search with snippet results.
        |Results include match coordinates when available (1-based line/column, 0-based offsets).
        |
        |Paths are glob patterns relative to the project root.
        |Examples: ["src/**", "!**/test/**"], ["**/*.kt"], ["foo/"].
    """)
  suspend fun search_text(
    @McpDescription("Text to search for") q: String,
    @McpDescription(PATHS_DESCRIPTION) paths: List<String>? = null,
    @McpDescription("Maximum number of results to return") limit: Int = 1000,
  ): SearchResult {
    if (q.isBlank()) mcpFail("Search text is empty")
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.searching.files.for.text", q))
    return searchInFiles(q, paths, limit, isRegex = false)
  }

  @McpTool
  @McpDescription("""
        |Searches for regex matches within project files.
        |Use this tool when you need regex search with snippet results.
        |Results include match coordinates when available (1-based line/column, 0-based offsets).
        |
        |Paths are glob patterns relative to the project root.
        |Examples: ["src/**", "!**/test/**"], ["**/*.kt"], ["foo/"].
    """)
  suspend fun search_regex(
    @McpDescription("Regex pattern to search for") q: String,
    @McpDescription(PATHS_DESCRIPTION) paths: List<String>? = null,
    @McpDescription("Maximum number of results to return") limit: Int = 1000,
  ): SearchResult {
    if (q.isBlank()) mcpFail("Regex pattern is empty")
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.searching.content.with.regex", q))
    return searchInFiles(q, paths, limit, isRegex = true)
  }

  @McpTool
  @McpDescription("""
        |Searches for symbols (classes, methods, fields).
        |Use this tool for semantic lookup by identifier fragments.
        |Results include match coordinates when available (1-based line/column, 0-based offsets).
        |
        |Paths are glob patterns relative to the project root.
    """)
  suspend fun search_symbol(
    @McpDescription("Symbol query text") q: String,
    @McpDescription(PATHS_DESCRIPTION) paths: List<String>? = null,
    @McpDescription("Maximum number of results to return") limit: Int = 1000,
  ): SearchResult {
    if (q.isBlank()) mcpFail("Search query is empty")
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.searching.files.for.text", q))
    return try {
      searchSymbols(q, paths, limit)
    }
    catch (e: LinkageError) {
      mcpFail("search_symbol is not supported by this IDE version")
    }
  }

  @McpTool
  @McpDescription("""
        |Searches for files by glob pattern within the project.
        |Use this tool when you need to match file paths using glob syntax.
        |
        |Glob patterns are relative to the project root.
        |Examples: "**/*.kt", "src/**/Foo*.java", "build.gradle.kts".
        |Patterns without '/' are treated as "**/pattern".
        |Paths are optional additional glob filters relative to the project root.
    """)
  suspend fun search_file(
    @McpDescription("Glob pattern to search for") q: String,
    @McpDescription(PATHS_DESCRIPTION) paths: List<String>? = null,
    @McpDescription("Whether to include excluded/ignored files in results") includeExcluded: Boolean = false,
    @McpDescription("Maximum number of results to return") limit: Int = 1000,
  ): SearchResult {
    if (q.isBlank()) mcpFail("Glob pattern is empty")
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.finding.files.by.glob", q))
    return searchFiles(q, paths, includeExcluded, limit)
  }
}

/**
 * Runs Find-in-Project for text or regex search.
 *
 * Path scoping is applied in two stages:
 * 1) Best-effort narrowing via `FindModel.directoryName` and `FindModel.fileFilter`.
 * 2) Exact post-filtering via [PathScope.matches].
 */
private suspend fun searchInFiles(
  q: String,
  paths: List<String>?,
  limit: Int,
  isRegex: Boolean,
): SearchResult {
  val effectiveLimit = normalizeLimit(limit)
  val project = currentCoroutineContext().project
  val projectDir = project.projectDirectory
  val pathScope = buildPathScope(projectDir, paths)
  val directoryFilterPath = resolveDirectoryFilter(project, pathScope)
  val fileFilterText = pathScope?.fileFilter

  val findModel = FindManager.getInstance(project).findInProjectModel.clone().apply {
    stringToFind = q
    isCaseSensitive = true
    isWholeWordsOnly = false
    isRegularExpressions = isRegex
    isProjectScope = directoryFilterPath == null
    isSearchInProjectFiles = false
    fileFilter = fileFilterText
    if (directoryFilterPath != null) {
      directoryName = directoryFilterPath.pathString
    }
  }

  val usages = ArrayList<UsageInfo>(minOf(effectiveLimit, 256))
  val usageLock = Any()
  val timedOut = withTimeoutOrNull(Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE.milliseconds) {
    val processor = Processor<UsageInfo> { usageInfo ->
      val file = usageInfo.virtualFile ?: return@Processor true
      val filePath = file.toNioPathOrNull() ?: return@Processor true
      if (!filePath.startsWith(projectDir)) return@Processor true
      val relativePath = projectDir.relativize(filePath)
      if (pathScope != null && !pathScope.matches(relativePath)) return@Processor true
      synchronized(usageLock) {
        if (usages.size >= effectiveLimit) return@Processor false
        usages.add(usageInfo)
        return@Processor usages.size < effectiveLimit
      }
    }

    withBackgroundProgress(
      project,
      FindBundle.message(
        "find.searching.for.string.in.file.progress",
        q,
        findModel.directoryName ?: FindBundle.message("find.scope.project.title")
      ),
      cancellable = true
    ) {
      coroutineToIndicator { indicator ->
        try {
          FindInProjectUtil.findUsages(
            findModel,
            project,
            indicator,
            FindUsagesProcessPresentation(UsageViewPresentation()),
            setOf(),
            processor,
          )
        }
        catch (e: PatternSyntaxException) {
          if (isRegex) {
            mcpFail("Invalid regex pattern: $q")
          }
          throw e
        }
      }
    }
  } == null

  val items = mapUsagesToItems(usages, projectDir)
  val reachedLimit = usages.size >= effectiveLimit
  return SearchResult(items = items, more = timedOut || reachedLimit)
}

/**
 * Converts usage hits into [SearchItem]s with snippet text.
 */
private suspend fun mapUsagesToItems(usages: List<UsageInfo>, projectDir: Path): List<SearchItem> {
  val fileDocumentManager = serviceAsync<FileDocumentManager>()
  return readAction {
    usages.mapNotNull { usage ->
      val file = usage.virtualFile ?: return@mapNotNull null
      val document = fileDocumentManager.getDocument(file) ?: return@mapNotNull null
      val textRange = usage.navigationRange ?: return@mapNotNull null
      val snippet = buildSearchSnippet(document, textRange, MAX_USAGE_TEXT_CHARS)
      val filePath = projectDir.relativizeIfPossible(file)
      if (filePath.isBlank()) return@mapNotNull null
      SearchItem(
        filePath = filePath,
        startLine = snippet.startLine,
        startColumn = snippet.startColumn,
        endLine = snippet.endLine,
        endColumn = snippet.endColumn,
        startOffset = snippet.startOffset,
        endOffset = snippet.endOffset,
        lineText = snippet.lineText,
      )
    }
  }
}

/**
 * Searches for files by glob pattern.
 *
 * Uses project indexes for content roots and optionally scans excluded roots
 * to include ignored/excluded files when requested.
 */
private suspend fun searchFiles(
  q: String,
  paths: List<String>?,
  includeExcluded: Boolean,
  limit: Int,
): SearchResult {
  val effectiveLimit = normalizeLimit(limit)
  val project = currentCoroutineContext().project
  val projectDir = project.projectDirectory
  val pathScope = buildPathScope(projectDir, paths)
  val normalizedPattern = normalizeGlobPattern(q, projectDir)
  val matcher = createPathMatcher(normalizedPattern)
  val fileIndex = project.serviceAsync<ProjectRootManager>().fileIndex
  val results = ArrayList<SearchItem>(minOf(effectiveLimit, 256))
  val seenPaths = if (includeExcluded) HashSet<String>(minOf(effectiveLimit, 256)) else null
  var reachedLimit = false
  val searchRoot = resolveSearchRoot(project, pathScope, normalizedPattern)
  val exactFileName = extractExactFileName(normalizedPattern)

  /**
   * Shared candidate processing for both indexed and excluded-root scans.
   * Returns false when the global limit is reached.
   */
  fun processCandidate(file: VirtualFile): Boolean {
    if (file.isDirectory) return true
    val filePath = file.toNioPathOrNull() ?: return true
    if (!filePath.startsWith(projectDir)) return true
    val relativePath = projectDir.relativize(filePath)
    if (!matcher.matches(toGlobPath(relativePath))) return true
    if (pathScope != null && !pathScope.matches(relativePath)) return true
    val relativePathString = relativePath.pathString
    if (seenPaths != null && !seenPaths.add(relativePathString)) return true
    results.add(SearchItem(filePath = relativePathString))
    if (results.size >= effectiveLimit) {
      reachedLimit = true
      return false
    }
    return true
  }

  val timedOut = withTimeoutOrNull(Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE.milliseconds) {
    withBackgroundProgress(
      project,
      McpServerBundle.message("progress.title.searching.for.files.by.glob.pattern", q),
      cancellable = true
    ) {
      // Fast path: for patterns like "**/Foo.kt" (exact file name match), prefer FilenameIndex over full content traversal.
      // This is dramatically faster on large repos and still keeps semantics identical thanks to post-filtering via `matcher` and `pathScope`.
      if (!includeExcluded && exactFileName != null) {
        val baseScope = GlobalSearchScope.projectScope(project)
        val scope = if (searchRoot != null) GlobalSearchScopes.directoryScope(project, searchRoot, true).intersectWith(baseScope) else baseScope
        val usedIndex = try {
          val candidates = readAction { FilenameIndex.getVirtualFilesByName(exactFileName, scope).toList() }
          for (file in candidates) {
            ensureActive()
            if (!processCandidate(file)) break
          }
          true
        }
        catch (_: IndexNotReadyException) {
          false
        }

        if (usedIndex) return@withBackgroundProgress
      }

      val contentIterator = ContentIterator { file ->
        processCandidate(file)
      }

      if (searchRoot != null) {
        fileIndex.iterateContentUnderDirectory(searchRoot, contentIterator)
      }
      else {
        fileIndex.iterateContent(contentIterator)
      }

      if (includeExcluded && !reachedLimit) {
        val excludedRoots = collectExcludedRoots(project)
        for (excludedRoot in excludedRoots) {
          val rootToScan = resolveExcludedSearchRoot(searchRoot, excludedRoot) ?: continue
          val completed = VfsUtilCore.iterateChildrenRecursively(rootToScan, null, ContentIterator { file ->
            processCandidate(file)
          })
          if (!completed || reachedLimit) break
        }
      }
    }
  } == null

  return SearchResult(items = results.toList(), more = timedOut || reachedLimit)
}

private fun extractExactFileName(globPattern: String): String? {
  val lastSegment = globPattern.substringAfterLast('/')
  if (lastSegment.isBlank()) return null
  return lastSegment.takeIf { indexOfGlobChar(it) < 0 }
}

/**
 * Searches for symbols via Search Everywhere and maps them to [SearchItem]s.
 */
private suspend fun searchSymbols(
  q: String,
  paths: List<String>?,
  limit: Int,
): SearchResult {
  val effectiveLimit = normalizeLimit(limit)
  val project = currentCoroutineContext().project
  val projectDir = project.projectDirectory
  val pathScope = buildPathScope(projectDir, paths)
  val directoryFilterPath = resolveDirectoryFilter(project, pathScope)
  val directoryFilterFile = directoryFilterPath?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

  val session = SeSessionEntity.createSession()
  try {
    val dataContextId = SimpleDataContext.getProjectContext(project).rpcId()
    val backendService = project.serviceAsync<SeBackendService>()
    val fileDocumentManager = serviceAsync<FileDocumentManager>()
    val providerIds = listOf(
      SeProviderId(SeProviderIdUtils.CLASSES_ID),
      SeProviderId(SeProviderIdUtils.SYMBOLS_ID),
    )

    val requestedCount = (effectiveLimit * SEARCH_SCOPE_MULTIPLIER).coerceAtMost(MAX_RESULTS_UPPER_BOUND)
    val requestedCountChannel = Channel<Int>(capacity = 1)
    requestedCountChannel.trySend(requestedCount)
    requestedCountChannel.close()

    val filterState = buildFilterState()
    val params = SeParams(q, filterState)

    val providerCache = HashMap<SeProviderId, SeItemsProvider?>()
    val items = LinkedHashSet<SearchItem>()
    var seenCount = 0
    val timedOut = withTimeoutOrNull(Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE.milliseconds) {
      backendService.getItems(session, providerIds, false, params, dataContextId, requestedCountChannel)
        .filterIsInstance<SeTransferItem>().mapNotNull { event ->
          seenCount++
          val item = mapSearchEverywhereItem(
            projectDir = projectDir,
            backendService = backendService,
            session = session,
            providerCache = providerCache,
            itemData = event.itemData,
            isAllTab = false,
            directoryFilterPath = directoryFilterPath,
            directoryFilterFile = directoryFilterFile,
            fileDocumentManager = fileDocumentManager,
            includeDetails = true,
          ) ?: return@mapNotNull null
          if (!matchesPathScope(pathScope, projectDir, item.filePath)) return@mapNotNull null
          item
        }.take(effectiveLimit).collect { item ->
          items.add(item)
        }
    } == null

    return SearchResult(
      items = items.toList(),
      more = timedOut || items.size >= effectiveLimit || seenCount >= requestedCount,
    )
  }
  finally {
    withContext(NonCancellable) {
      change {
        shared {
          session.asRef().derefOrNull()?.delete()
        }
      }
    }
  }
}

private fun toGlobPath(relativePath: Path): Path {
  return if (relativePath.fileSystem === DEFAULT_FILE_SYSTEM) relativePath else Path.of(relativePath.toString())
}

/**
 * Normalized include/exclude glob scope for project-relative paths.
 *
 * - [commonDirectory] is the longest shared prefix used for backend narrowing.
 * - [fileFilter] is a best-effort conversion to Find-in-Project file masks.
 */
internal data class PathScope(
  val includeMatchers: List<PathMatcher>,
  val excludeMatchers: List<PathMatcher>,
  val commonDirectory: Path?,
  val fileFilter: String?,
) {
  fun matches(relativePath: Path): Boolean {
    val matchPath = toGlobPath(relativePath)
    val included = includeMatchers.any { it.matches(matchPath) }
    return included && excludeMatchers.none { it.matches(matchPath) }
  }
}

private data class PathPattern(
  val pattern: String,
  val isExclude: Boolean,
)

/**
 * Builds include/exclude matchers and derives common prefix + file mask.
 */
internal fun buildPathScope(projectDir: Path, paths: List<String>?): PathScope? {
  if (paths == null) return null
  val normalized = paths.mapNotNull { normalizePattern(it, projectDir) }
  if (normalized.isEmpty()) return null

  val includes = normalized.filterNot { it.isExclude }.map { it.pattern }
  val excludes = normalized.filter { it.isExclude }.map { it.pattern }
  val includePatterns = includes.ifEmpty { listOf("**/*") }
  val includeMatchers = includePatterns.map(::createPathMatcher)
  val excludeMatchers = excludes.map(::createPathMatcher)
  val commonDirectory = computeCommonDirectory(includePatterns)
  val fileFilter = buildFileFilter(includes, excludes)

  return PathScope(includeMatchers, excludeMatchers, commonDirectory, fileFilter)
}

/**
 * Converts simple include/exclude globs into a Find-in-Project file mask string.
 * Complex patterns fall back to post-filtering only.
 */
private fun buildFileFilter(includePatterns: List<String>, excludePatterns: List<String>): String? {
  val includeMasks = mutableListOf<String>()
  var hasNonMaskInclude = false
  for (pattern in includePatterns) {
    val mask = extractFileMask(pattern)
    if (mask == null) {
      hasNonMaskInclude = true
    }
    else {
      includeMasks.add(mask)
    }
  }

  val excludeMasks = excludePatterns.mapNotNull { extractFileMask(it) }.map { "!$it" }
  if (includePatterns.isEmpty() || hasNonMaskInclude) {
    return excludeMasks.distinct().joinToString(",").takeIf { it.isNotEmpty() }
  }

  val masks = (includeMasks + excludeMasks).distinct()
  return masks.joinToString(",").takeIf { it.isNotEmpty() }
}

/**
 * Extracts a file-name mask from a normalized glob when safe.
 */
private fun extractFileMask(pattern: String): String? {
  val lastSegment = pattern.substringAfterLast('/')
  if (lastSegment.isEmpty()) return null
  if (lastSegment == "**") return null
  if (lastSegment.contains("**")) return null
  if (lastSegment.any { it == '{' || it == '}' || it == '[' || it == ']' }) return null
  return lastSegment
}

/**
 * Normalizes a single include/exclude pattern against the project root.
 */
private fun normalizePattern(raw: String, projectDir: Path): PathPattern? {
  var value = raw.trim()
  if (value.isEmpty()) return null

  var isExclude = false
  if (value.startsWith("!")) {
    isExclude = true
    value = value.drop(1).trim()
    if (value.isEmpty()) mcpFail("Exclude pattern is empty")
  }
  val normalized = normalizeGlobPattern(value, projectDir, raw)
  val expanded = expandDirectoryPatternIfNeeded(normalized, projectDir)
  return PathPattern(expanded, isExclude)
}

private fun expandDirectoryPatternIfNeeded(pattern: String, projectDir: Path): String {
  if (indexOfGlobChar(pattern) >= 0) return pattern
  val resolved = projectDir.resolve(pattern).normalize()
  if (!resolved.startsWith(projectDir)) return pattern
  return if (resolved.isDirectory()) "$pattern/**" else pattern
}

/**
 * Normalizes a glob pattern and guards against escaping the project root.
 */
internal fun normalizeGlobPattern(raw: String, projectDir: Path, originalPattern: String = raw): String {
  var value = raw.trim()
  if (value.isEmpty()) {
    mcpFail("Glob pattern is empty")
  }
  value = FileUtilRt.toSystemIndependentName(value)
  while (value.startsWith("./")) {
    value = value.removePrefix("./")
  }

  if (value.endsWith('/')) {
    value = value.trimEnd('/')
    value = if (value.isEmpty()) "**" else "$value/**"
  }

  if (!value.contains('/')) {
    value = "**/$value"
  }

  value = normalizePathPattern(value, projectDir)
  if (value.isEmpty()) {
    mcpFail("Invalid glob pattern: $originalPattern")
  }
  return value
}

/**
 * Resolves the non-glob prefix to a project-relative path.
 */
private fun normalizePathPattern(pattern: String, projectDir: Path): String {
  val globIndex = indexOfGlobChar(pattern)
  val prefix = if (globIndex < 0) pattern else pattern.substring(0, globIndex)
  val prefixTrimmed = prefix.trimEnd('/')
  if (prefixTrimmed.isEmpty()) {
    if (isAbsolutePattern(pattern)) {
      mcpFail("Specified path '$pattern' points to the location outside of the project directory")
    }
    return pattern
  }

  val prefixPath = try {
    Path.of(prefixTrimmed)
  }
  catch (e: InvalidPathException) {
    mcpFail("Invalid path: $prefixTrimmed")
  }
  val absolutePrefix = if (prefixPath.isAbsolute) prefixPath.normalize() else projectDir.resolve(prefixPath).normalize()
  if (!absolutePrefix.startsWith(projectDir)) {
    mcpFail("Specified path '$pattern' points to the location outside of the project directory")
  }

  val relativePrefix = FileUtilRt.toSystemIndependentName(projectDir.relativize(absolutePrefix).toString())
  val suffix = pattern.substring(prefix.length).trimStart('/')
  return when {
    relativePrefix.isEmpty() -> suffix
    suffix.isEmpty() -> relativePrefix
    else -> "$relativePrefix/$suffix"
  }
}

private fun isAbsolutePattern(pattern: String): Boolean {
  if (pattern.startsWith("/")) return true
  return pattern.length >= 3 && pattern[1] == ':' && pattern[2] == '/'
}

private fun indexOfGlobChar(pattern: String): Int {
  for (index in pattern.indices) {
    when (pattern[index]) {
      '*', '?', '[', ']', '{', '}' -> return index
    }
  }
  return -1
}

/**
 * Creates a glob matcher with correct "**" semantics.
 */
private fun createPathMatcher(pattern: String): PathMatcher {
  return try {
    getPathMatcher(pattern, ignorePatternSyntaxException = false)
  }
  catch (e: PatternSyntaxException) {
    mcpFail("Invalid glob pattern: $pattern")
  }
}

/**
 * Computes the longest shared directory prefix across include patterns.
 */
private fun computeCommonDirectory(patterns: List<String>): Path? {
  val prefixes = patterns.mapNotNull(::extractDirectoryPrefix)
  if (prefixes.isEmpty()) return null
  val segments = prefixes.map { it.split('/').filter(String::isNotEmpty) }
  var common = segments.first()
  for (parts in segments.drop(1)) {
    val max = minOf(common.size, parts.size)
    var index = 0
    while (index < max && common[index] == parts[index]) {
      index++
    }
    if (index == 0) return null
    common = common.subList(0, index)
  }
  return if (common.isEmpty()) null else Path.of(common.joinToString("/"))
}

/**
 * Collects excluded roots declared on module content entries.
 */
private suspend fun collectExcludedRoots(project: Project): List<VirtualFile> {
  val moduleManager = project.serviceAsync<ModuleManager>()
  return readAction {
    val roots = LinkedHashSet<VirtualFile>()
    for (module in moduleManager.modules) {
      for (entry in ModuleRootManager.getInstance(module).contentEntries) {
        roots.addAll(entry.excludeFolderFiles)
      }
    }
    roots.toList()
  }
}

/**
 * Resolves an excluded root intersected with an optional search root.
 */
private fun resolveExcludedSearchRoot(searchRoot: VirtualFile?, excludedRoot: VirtualFile): VirtualFile? {
  if (searchRoot == null) return excludedRoot
  return when {
    VfsUtilCore.isAncestor(excludedRoot, searchRoot, false) -> searchRoot
    VfsUtilCore.isAncestor(searchRoot, excludedRoot, false) -> excludedRoot
    else -> null
  }
}

/**
 * Extracts the non-glob directory prefix from a pattern for scope narrowing.
 */
private fun extractDirectoryPrefix(pattern: String): String? {
  val globIndex = indexOfGlobChar(pattern)
  val prefix = if (globIndex < 0) pattern else pattern.substring(0, globIndex)
  val trimmed = prefix.trimEnd('/')
  if (trimmed.isEmpty()) return null
  if (globIndex < 0) {
    val slashIndex = trimmed.lastIndexOf('/')
    return if (slashIndex < 0) null else trimmed.substring(0, slashIndex).ifEmpty { null }
  }
  return trimmed
}

/**
 * Resolves an optional root directory to reduce traversal cost.
 */
private fun resolveSearchRoot(
  project: Project,
  pathScope: PathScope?,
  globPattern: String,
): VirtualFile? {
  val candidates = listOfNotNull(
    pathScope?.commonDirectory,
    extractDirectoryPrefix(globPattern)?.let { Path.of(it) },
  )
  if (candidates.isEmpty()) return null
  for (candidate in candidates) {
    val resolved = project.resolveInProject(candidate.pathString)
    if (!resolved.isDirectory()) continue
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved)
    if (virtualFile != null) return virtualFile
  }
  return null
}

/**
 * Resolves a directory filter for Find-in-Project from the common prefix.
 */
private fun resolveDirectoryFilter(project: Project, pathScope: PathScope?): Path? {
  val commonDirectory = pathScope?.commonDirectory ?: return null
  val resolved = project.resolveInProject(commonDirectory.pathString)
  return resolved.takeIf { it.isDirectory() }
}

/**
 * Checks whether a file path is in the derived path scope.
 */
private fun matchesPathScope(pathScope: PathScope?, projectDir: Path, filePath: String): Boolean {
  if (pathScope == null) return true
  val relativePath = toRelativePath(projectDir, filePath) ?: return false
  return pathScope.matches(relativePath)
}

/**
 * Converts a string path to a project-relative path if possible.
 */
private fun toRelativePath(projectDir: Path, filePath: String): Path? {
  if (filePath.isBlank()) return null
  val nioPath = runCatching { Path.of(filePath) }.getOrNull() ?: return null
  if (nioPath.isAbsolute) {
    return if (nioPath.startsWith(projectDir)) projectDir.relativize(nioPath) else null
  }
  return nioPath
}

/**
 * Builds Search Everywhere filter state mirroring text search options.
 */
private fun buildFilterState(): SeFilterState {
  val map = mutableMapOf<String, List<String>>()
  map[SeTextFilterKeys.TEXT_FILTER] = listOf("true")
  map[SeTextFilterKeys.MATCH_CASE] = listOf(true.toString())
  map[SeTextFilterKeys.WORDS] = listOf(false.toString())
  map[SeTextFilterKeys.REGEX] = listOf(false.toString())
  map[SeEverywhereFilter.KEY_ALL_TAB] = listOf(false.toString())
  map[SeEverywhereFilter.KEY_IS_EVERYWHERE] = listOf(false.toString())
  return SeFilterState.Data(map)
}

/**
 * Maps Search Everywhere items to [SearchItem]s, extracting file path and snippet.
 */
private suspend fun mapSearchEverywhereItem(
  projectDir: Path,
  backendService: SeBackendService,
  session: SeSession,
  providerCache: MutableMap<SeProviderId, SeItemsProvider?>,
  itemData: SeItemData,
  isAllTab: Boolean,
  directoryFilterPath: Path?,
  directoryFilterFile: VirtualFile?,
  fileDocumentManager: FileDocumentManager,
  includeDetails: Boolean,
): SearchItem? {
  if (itemData.isCommand) return null
  val providerId = itemData.providerId

  val item = itemData.fetchItemIfExists()
  val provider = resolveProvider(backendService, session, providerCache, providerId, isAllTab)
  val rawObject = (item as? SeLegacyItem)?.rawObject ?: item?.rawObject
  val unwrappedObject = unwrapLegacyObject(rawObject)

  var virtualFile: VirtualFile? = null
  var filePath: String? = null
  var lineText: String? = null
  var startLine: Int? = null
  var startColumn: Int? = null
  var endLine: Int? = null
  var endColumn: Int? = null
  var startOffset: Int? = null
  var endOffset: Int? = null
  var psiElement: PsiElement? = null

  fun applySnippet(snippet: UsageSnippet) {
    virtualFile = snippet.file
    filePath = snippet.filePath
    lineText = snippet.lineText
    startLine = snippet.startLine
    startColumn = snippet.startColumn
    endLine = snippet.endLine
    endColumn = snippet.endColumn
    startOffset = snippet.startOffset
    endOffset = snippet.endOffset
  }

  if (rawObject is SearchEverywhereItem) {
    if (includeDetails) {
      val snippet = buildUsageSnippet(projectDir, fileDocumentManager, rawObject.usage)
      if (snippet != null) {
        applySnippet(snippet)
      }
    }
    else {
      val usageFile = readAction { rawObject.usage.file }
      if (usageFile != null) {
        virtualFile = usageFile
        filePath = projectDir.relativizeIfPossible(usageFile)
      }
    }
  }

  if (virtualFile == null) {
    if (item != null) {
      virtualFile = provider?.getVirtualFileForItem(item)
    }
  }

  if (virtualFile == null && unwrappedObject is VirtualFile) {
    virtualFile = unwrappedObject
  }

  if (virtualFile == null && unwrappedObject is PsiFileSystemItem) {
    virtualFile = unwrappedObject.virtualFile
  }

  if (virtualFile == null && (item != null || unwrappedObject is PsiElement)) {
    val psiResult = readAction {
      val providerPsi = if (item != null) provider?.getPsiElementForItem(item) else null
      val fallbackPsi = (unwrappedObject as? PsiElement) ?: (rawObject as? PsiElement)
      val resolvedPsi = providerPsi ?: fallbackPsi
      val psiFile = resolvedPsi?.containingFile?.virtualFile
      resolvedPsi to psiFile
    }
    psiElement = psiResult.first
    if (psiResult.second != null) {
      virtualFile = psiResult.second
    }
  }

  if (includeDetails && startLine == null && psiElement != null) {
    val snippet = buildPsiSnippet(projectDir, fileDocumentManager, psiElement)
    if (snippet != null) {
      applySnippet(snippet)
    }
  }

  val resolvedVirtualFile = virtualFile
  if (resolvedVirtualFile != null && filePath == null) {
    filePath = projectDir.relativizeIfPossible(resolvedVirtualFile)
  }

  if (filePath == null && providerId.value == SeProviderIdUtils.FILES_ID) {
    filePath = itemData.presentation.text.takeIf { it.isNotBlank() }
  }

  if (directoryFilterPath != null) {
    val filterFile = virtualFile ?: return null
    val inScope = if (directoryFilterFile != null) {
      VfsUtilCore.isAncestor(directoryFilterFile, filterFile, false)
    }
    else {
      val filePathNio = filterFile.toNioPathOrNull() ?: return null
      filePathNio.startsWith(directoryFilterPath)
    }
    if (!inScope) return null
  }

  val resolvedPath = filePath?.takeIf { it.isNotBlank() } ?: return null
  val resolvedText = if (includeDetails) lineText else null
  val resolvedStartLine = if (includeDetails) startLine else null
  val resolvedStartColumn = if (includeDetails) startColumn else null
  val resolvedEndLine = if (includeDetails) endLine else null
  val resolvedEndColumn = if (includeDetails) endColumn else null
  val resolvedStartOffset = if (includeDetails) startOffset else null
  val resolvedEndOffset = if (includeDetails) endOffset else null
  return SearchItem(
    filePath = resolvedPath,
    startLine = resolvedStartLine,
    startColumn = resolvedStartColumn,
    endLine = resolvedEndLine,
    endColumn = resolvedEndColumn,
    startOffset = resolvedStartOffset,
    endOffset = resolvedEndOffset,
    lineText = resolvedText,
  )
}

/**
 * Resolves and caches Search Everywhere providers by id.
 */
private fun resolveProvider(
  backendService: SeBackendService,
  session: SeSession,
  providerCache: MutableMap<SeProviderId, SeItemsProvider?>,
  providerId: SeProviderId,
  isAllTab: Boolean,
): SeItemsProvider? {
  if (providerCache.containsKey(providerId)) return providerCache[providerId]
  val provider = backendService.tryGetProvider(providerId, isAllTab, session)
  providerCache[providerId] = provider
  return provider
}

/**
 * Unwraps legacy Search Everywhere payloads that are encoded as pairs.
 */
private fun unwrapLegacyObject(rawObject: Any?): Any? {
  return when (rawObject) {
    is Pair<*, *> -> rawObject.first
    else -> rawObject
  }
}

/**
 * Validates and clamps the result limit.
 */
private fun normalizeLimit(limit: Int): Int {
  if (limit <= 0) mcpFail("limit must be > 0")
  return limit.coerceAtMost(MAX_RESULTS_UPPER_BOUND)
}

/**
 * Builds a snippet from a usage adapter.
 */
private suspend fun buildUsageSnippet(
  projectDir: Path,
  fileDocumentManager: FileDocumentManager,
  usage: UsageInfo2UsageAdapter,
): UsageSnippet? {
  return readAction {
    val file = usage.file ?: return@readAction null
    val textRange = usage.navigationRange ?: return@readAction null
    buildSnippet(projectDir, fileDocumentManager, file, textRange)
  }
}

private data class UsageSnippet(
  @JvmField val file: VirtualFile,
  @JvmField val filePath: String,
  @JvmField val lineText: String,
  @JvmField val startLine: Int,
  @JvmField val startColumn: Int,
  @JvmField val endLine: Int,
  @JvmField val endColumn: Int,
  @JvmField val startOffset: Int,
  @JvmField val endOffset: Int,
)

private data class SearchSnippet(
  @JvmField val lineText: String,
  @JvmField val startLine: Int,
  @JvmField val startColumn: Int,
  @JvmField val endLine: Int,
  @JvmField val endColumn: Int,
  @JvmField val startOffset: Int,
  @JvmField val endOffset: Int,
)

@Serializable
internal data class SearchItem(
  /*
   * Search results are always serialized as SearchItem objects, so all search_* tools
   * share one stable, predictable schema. This keeps client parsing simple and
   * allows adding optional fields later without breaking existing consumers.
   * For search_file we only populate filePath; other fields remain null
   * because there is no text match to report for pure glob-based file search.
   * Match coordinates use 1-based line/column and 0-based offsets; end is exclusive.
   */
  @JvmField val filePath: String,
  @JvmField val startLine: Int? = null,
  @JvmField val startColumn: Int? = null,
  @JvmField val endLine: Int? = null,
  @JvmField val endColumn: Int? = null,
  @JvmField val startOffset: Int? = null,
  @JvmField val endOffset: Int? = null,
  @JvmField val lineText: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SearchResult(
  @JvmField @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS) val items: List<SearchItem> = emptyList(),
  @JvmField @EncodeDefault(mode = EncodeDefault.Mode.NEVER) val more: Boolean = false,
)

/**
 * Builds a snippet from a PSI element's navigation range.
 */
private suspend fun buildPsiSnippet(
  projectDir: Path,
  fileDocumentManager: FileDocumentManager,
  element: PsiElement,
): UsageSnippet? {
  return readAction {
    val navigationElement = element.navigationElement ?: element
    val file = navigationElement.containingFile?.virtualFile ?: return@readAction null
    val textRange = navigationElement.textRange
    buildSnippet(projectDir, fileDocumentManager, file, textRange)
  }
}

/**
 * Builds a single-line snippet for a text range within a file.
 */
private fun buildSnippet(
  projectDir: Path,
  fileDocumentManager: FileDocumentManager,
  file: VirtualFile,
  textRange: Segment,
): UsageSnippet? {
  val document = fileDocumentManager.getDocument(file) ?: return null
  val snippet = buildSearchSnippet(document, textRange, MAX_USAGE_TEXT_CHARS)
  return UsageSnippet(
    file = file,
    filePath = projectDir.relativizeIfPossible(file),
    lineText = snippet.lineText,
    startLine = snippet.startLine,
    startColumn = snippet.startColumn,
    endLine = snippet.endLine,
    endColumn = snippet.endColumn,
    startOffset = snippet.startOffset,
    endOffset = snippet.endOffset,
  )
}

private fun buildSearchSnippet(document: Document, textRange: Segment, @Suppress("SameParameterValue") maxTextChars: Int): SearchSnippet {
  val startOffset = textRange.startOffset
  val endOffset = textRange.endOffset
  val startLineNumber = document.getLineNumber(startOffset)
  val startLineStartOffset = document.getLineStartOffset(startLineNumber)
  val endLineNumber = document.getLineNumber(endOffset)
  val endLineStartOffset = document.getLineStartOffset(endLineNumber)
  val endLineEndOffset = document.getLineEndOffset(endLineNumber)
  val textBeforeOccurrence = document.getText(TextRange(startLineStartOffset, startOffset)).take(maxTextChars)
  val textInner = document.getText(TextRange(startOffset, endOffset)).take(maxTextChars)
  val textAfterOccurrence = document.getText(TextRange(endOffset, endLineEndOffset)).take(maxTextChars)
  return SearchSnippet(
    lineText = "$textBeforeOccurrence||$textInner||$textAfterOccurrence",
    startLine = startLineNumber + 1,
    startColumn = startOffset - startLineStartOffset + 1,
    endLine = endLineNumber + 1,
    endColumn = endOffset - endLineStartOffset + 1,
    startOffset = startOffset,
    endOffset = endOffset,
  )
}
