@file:Suppress("FunctionName", "unused")

package com.intellij.mcpserver.toolsets.general

import com.intellij.find.impl.FindInProjectUtil
import com.intellij.find.impl.SearchEverywhereItem
import com.intellij.ide.rpc.DataContextId
import com.intellij.ide.rpc.rpcId
import com.intellij.mcpserver.*
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.toolsets.Constants.MAX_USAGE_TEXT_CHARS
import com.intellij.mcpserver.util.buildUsageSnippetText
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.Segment
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.searchEverywhere.*
import com.intellij.platform.searchEverywhere.backend.impl.SeBackendService
import com.intellij.platform.searchEverywhere.providers.SeEverywhereFilter
import com.intellij.platform.searchEverywhere.providers.SeTextFilterKeys
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.usages.UsageInfo2UsageAdapter
import fleet.kernel.change
import fleet.kernel.rebase.shared
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.milliseconds

private enum class SearchQueryType {
  TEXT, REGEX
}

private enum class SearchOutput {
  ENTRIES, FILES
}

private enum class SearchMode {
  HYBRID, SEMANTIC, LEXICAL
}

private const val MAX_RESULTS_UPPER_BOUND = 5000

internal class SearchToolset : McpToolset {
  @McpTool
  @McpDescription("""
        |Use this tool first for codebase lookup.
        |Prefer semantic search for classes/methods/fields/files: pass identifier fragments, not natural language.
        |For file-content matches, switch to searchMode=lexical and providers=["text"] (optionally querySyntax="regex").
        |Searches across Search Everywhere providers (classes, symbols, files, text, actions, etc.).
        |Defaults to semantic search; "semantic" means symbol-oriented lookup (classes, methods, fields, files).
        |
        |Text matches include `||` markers, e.g. `some text ||substring|| text`.
        |Results are returned under `items` as objects {filePath, lineNumber?, lineText?}. `more` indicates truncation.
        |Example (semantic classes/symbols): query="SeBackendService", providers=["classes","symbols"]
        |Example (semantic member): query="SearchToolset.search"
        |Example (lexical text): query="closeSession", searchMode="lexical", providers=["text"]
    """)
  suspend fun search(
    @McpDescription("Search query text. For semantic mode, use class/method/field/file names or identifier fragments (avoid full sentences).") query: String,
    @McpDescription("Search mode: semantic, lexical, or hybrid. Defaults to semantic; use semantic for classes/symbols/files, lexical for exact text/regex.") searchMode: String? = null,
    @McpDescription("Optional list of Search Everywhere providers to use (e.g. classes, symbols, files, text, actions). For semantic lookup, prefer classes/symbols/files.") providers: List<String>? = null,
    @McpDescription("Directory to search in, relative to project root. If not specified, searches in the entire project.") searchRoot: String? = null,
    @McpDescription("File pattern to search for. If not specified, searches for all files. Example: `*.java`") filePattern: String? = null,
    @McpDescription("Whether to search for the text in a case-sensitive manner (text provider only)") caseSensitive: Boolean = true,
    @McpDescription("Whether to match whole words only (text provider only)") wholeWordsOnly: Boolean = false,
    @McpDescription("Query syntax: text or regex. Defaults to text (text provider only).") querySyntax: String? = null,
    @McpDescription("Whether to include non-project items if supported by the provider.") includeNonProjectItems: Boolean = false,
    @McpDescription("Maximum number of entries to return.") maxResults: Int = 1000,
    @McpDescription("Output format: entries (full) or files (compact; filePath only). Defaults to entries.") resultFormat: String? = null,
    @McpDescription(Constants.TIMEOUT_MILLISECONDS_DESCRIPTION) timeoutMs: Int = Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE,
  ): SearchResult {
    if (query.isBlank()) mcpFail("Search query is empty")
    val parsedQueryType = parseQuerySyntax(querySyntax)
    val parsedOutput = parseResultFormat(resultFormat)
    val parsedMode = parseSearchMode(searchMode)
    val effectiveMaxResults = normalizeMaxResults(maxResults)

    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.searching.files.for.text", query))

    val project = currentCoroutineContext().project
    val projectDir = project.projectDirectory
    val directoryFilter = searchRoot?.let {
      project.resolveInProject(it).also { path ->
        if (!path.isDirectory()) mcpFail("The specified path '$searchRoot' is not a directory.")
      }
    }
    val directoryFilterFile = directoryFilter?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
    val filePatternCondition = createFilePatternCondition(filePattern)

    val session = SeSessionEntity.createSession()
    try {
      val dataContextId = SimpleDataContext.getProjectContext(project).rpcId()
      val backendService = project.serviceAsync<SeBackendService>()
      val fileDocumentManager = serviceAsync<FileDocumentManager>()
      val providerIds = resolveProviderIds(providers, backendService, session, dataContextId)
      if (providerIds.isEmpty()) {
        return SearchResult()
      }

      val filterState = buildFilterState(
        filePattern = filePattern,
        caseSensitive = caseSensitive,
        wholeWordsOnly = wholeWordsOnly,
        isRegex = parsedQueryType == SearchQueryType.REGEX,
        isEverywhere = includeNonProjectItems,
        directoryPath = directoryFilter?.toString(),
      )
      val params = SeParams(query, filterState)

      val requestedCountChannel = Channel<Int>(capacity = 1)
      requestedCountChannel.trySend(effectiveMaxResults)
      requestedCountChannel.close()

      val providerCache = HashMap<SeProviderId, SeItemsProvider?>()
      val items = LinkedHashSet<SearchItem>()
      var seenCount = 0
      val includeDetails = parsedOutput == SearchOutput.ENTRIES
      val timedOut = withTimeoutOrNull(timeoutMs.milliseconds) {
        backendService.getItems(session, providerIds, false, params, dataContextId, requestedCountChannel)
          .filterIsInstance<SeTransferItem>().mapNotNull { event ->
            mapSearchEverywhereItem(
              projectDir = projectDir,
              backendService = backendService,
              session = session,
              providerCache = providerCache,
              itemData = event.itemData,
              isAllTab = false,
              mode = parsedMode,
              directoryFilterPath = directoryFilter,
              directoryFilterFile = directoryFilterFile,
              filePatternCondition = filePatternCondition,
              fileDocumentManager = fileDocumentManager,
              includeDetails = includeDetails,
            )
          }.take(effectiveMaxResults).collect { item ->
            seenCount++
            items.add(item)
          }
      } == null
      return SearchResult(
        items = items.toList(),
        more = timedOut || seenCount >= effectiveMaxResults,
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
}

private fun buildFilterState(
  filePattern: String?,
  caseSensitive: Boolean,
  wholeWordsOnly: Boolean,
  isRegex: Boolean,
  isEverywhere: Boolean,
  directoryPath: String?,
): SeFilterState {
  val map = mutableMapOf<String, List<String>>()
  map[SeTextFilterKeys.TEXT_FILTER] = listOf("true")
  filePattern?.let { map[SeTextFilterKeys.SELECTED_TYPE] = listOf(it) }
  directoryPath?.takeIf { it.isNotBlank() }?.let { map[SeTextFilterKeys.DIRECTORY_PATH] = listOf(it) }
  map[SeTextFilterKeys.MATCH_CASE] = listOf(caseSensitive.toString())
  map[SeTextFilterKeys.WORDS] = listOf(wholeWordsOnly.toString())
  map[SeTextFilterKeys.REGEX] = listOf(isRegex.toString())
  map[SeEverywhereFilter.KEY_ALL_TAB] = listOf(false.toString())
  map[SeEverywhereFilter.KEY_IS_EVERYWHERE] = listOf(isEverywhere.toString())
  return SeFilterState.Data(map)
}

private fun createFilePatternCondition(filePattern: String?): Condition<CharSequence>? {
  if (filePattern.isNullOrBlank()) return null
  return try {
    FindInProjectUtil.createFileMaskCondition(filePattern)
  }
  catch (e: PatternSyntaxException) {
    mcpFail("Invalid file pattern: $filePattern")
  }
}

private suspend fun resolveProviderIds(
  providers: List<String>?,
  backendService: SeBackendService,
  session: SeSession,
  dataContextId: DataContextId,
): List<SeProviderId> {
  if (providers.isNullOrEmpty()) {
    return getAllProviderIds(backendService, session, dataContextId)
  }

  var containsAll = false
  val normalized = providers.mapNotNull { value ->
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val providerId = parseProviderId(trimmed)
    if (providerId == null) {
      containsAll = true
      return@mapNotNull null
    }
    providerId
  }

  if (containsAll || normalized.isEmpty()) {
    return getAllProviderIds(backendService, session, dataContextId)
  }

  return normalized.distinct()
}

private suspend fun getAllProviderIds(
  backendService: SeBackendService,
  session: SeSession,
  dataContextId: DataContextId,
): List<SeProviderId> {
  val sorted = backendService.getAvailableProviderIds(session, dataContextId) ?: return emptyList()
  return (sorted.essential + sorted.nonEssential).toList()
}

private fun parseProviderId(value: String): SeProviderId? {
  return when (value.trim().lowercase()) {
    "*", "all" -> null
    "action", "actions" -> SeProviderId(SeProviderIdUtils.ACTIONS_ID)
    "class", "classes" -> SeProviderId(SeProviderIdUtils.CLASSES_ID)
    "file", "files" -> SeProviderId(SeProviderIdUtils.FILES_ID)
    "symbol", "symbols" -> SeProviderId(SeProviderIdUtils.SYMBOLS_ID)
    "text" -> SeProviderId(SeProviderIdUtils.TEXT_ID)
    "recent", "recent_files", "recentfiles" -> SeProviderId(SeProviderIdUtils.RECENT_FILES_ID)
    "run", "run_configurations", "runconfigurations" -> SeProviderId(SeProviderIdUtils.RUN_CONFIGURATIONS_ID)
    "non_indexable_files", "nonindexablefiles" -> SeProviderId(SeProviderIdUtils.NON_INDEXABLE_FILES_ID)
    else -> SeProviderId(value)
  }
}

private suspend fun mapSearchEverywhereItem(
  projectDir: Path,
  backendService: SeBackendService,
  session: SeSession,
  providerCache: MutableMap<SeProviderId, SeItemsProvider?>,
  itemData: SeItemData,
  isAllTab: Boolean,
  mode: SearchMode,
  directoryFilterPath: Path?,
  directoryFilterFile: VirtualFile?,
  filePatternCondition: Condition<CharSequence>?,
  fileDocumentManager: FileDocumentManager,
  includeDetails: Boolean,
): SearchItem? {
  if (itemData.isCommand) return null
  val providerId = itemData.providerId
  val isSemantic = itemData.additionalInfo[SeItemDataKeys.IS_SEMANTIC]?.toBoolean()
  val isTextProvider = providerId.value == SeProviderIdUtils.TEXT_ID
  when (mode) {
    SearchMode.SEMANTIC -> if (isTextProvider && isSemantic == false) return null
    SearchMode.LEXICAL -> if (isTextProvider && isSemantic == true) return null
    SearchMode.HYBRID -> Unit
  }

  val item = itemData.fetchItemIfExists()
  val provider = resolveProvider(backendService, session, providerCache, providerId, isAllTab)
  val rawObject = (item as? SeLegacyItem)?.rawObject ?: item?.rawObject
  val unwrappedObject = unwrapLegacyObject(rawObject)

  var virtualFile: VirtualFile? = null
  var filePath: String? = null
  var lineNumber: Int? = null
  var lineText: String? = null
  var psiElement: PsiElement? = null

  if (rawObject is SearchEverywhereItem) {
    if (includeDetails) {
      val snippet = buildUsageSnippet(projectDir, fileDocumentManager, rawObject.usage)
      if (snippet != null) {
        virtualFile = snippet.file
        filePath = snippet.filePath
        lineNumber = snippet.lineNumber
        lineText = snippet.lineText
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

  if (includeDetails && lineNumber == null && psiElement != null) {
    val snippet = buildPsiSnippet(projectDir, fileDocumentManager, psiElement)
    if (snippet != null) {
      virtualFile = snippet.file
      filePath = snippet.filePath
      lineNumber = snippet.lineNumber
      lineText = snippet.lineText
    }
  }

  if (virtualFile != null && filePath == null) {
    filePath = projectDir.relativizeIfPossible(virtualFile)
  }

  if (filePath == null && providerId.value == SeProviderIdUtils.FILES_ID) {
    filePath = itemData.presentation.text.takeIf { it.isNotBlank() }
  }

  if (directoryFilterPath != null) {
    if (virtualFile == null) return null
    val inScope = if (directoryFilterFile != null) {
      VfsUtilCore.isAncestor(directoryFilterFile, virtualFile, false)
    }
    else {
      val filePathNio = virtualFile.toNioPathOrNull() ?: return null
      filePathNio.startsWith(directoryFilterPath)
    }
    if (!inScope) return null
  }

  if (filePatternCondition != null) {
    val fileName = virtualFile?.name ?: filePath?.let(::extractFileName) ?: return null
    if (!filePatternCondition.value(fileName)) return null
  }

  val resolvedPath = filePath?.takeIf { it.isNotBlank() } ?: return null
  val resolvedLine = if (includeDetails) lineNumber else null
  val resolvedText = if (includeDetails && resolvedLine != null) lineText else null
  return SearchItem(filePath = resolvedPath, lineNumber = resolvedLine, lineText = resolvedText)
}

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

private fun unwrapLegacyObject(rawObject: Any?): Any? {
  return when (rawObject) {
    is Pair<*, *> -> rawObject.first
    is kotlin.Pair<*, *> -> rawObject.first
    else -> rawObject
  }
}

private fun extractFileName(path: String): String {
  val slashIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return if (slashIndex >= 0) path.substring(slashIndex + 1) else path
}

private fun normalizeMaxResults(maxResults: Int): Int {
  if (maxResults <= 0) mcpFail("maxResults must be > 0")
  return maxResults.coerceAtMost(MAX_RESULTS_UPPER_BOUND)
}


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
  @JvmField val lineNumber: Int,
  @JvmField val lineText: String,
)

@Serializable
internal data class SearchItem(
  @JvmField val filePath: String,
  @JvmField val lineNumber: Int? = null,
  @JvmField val lineText: String? = null,
)


@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SearchResult(
  @JvmField @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS) val items: List<SearchItem> = emptyList(),
  @JvmField @EncodeDefault(mode = EncodeDefault.Mode.NEVER) val more: Boolean = false,
)

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

private fun buildSnippet(
  projectDir: Path,
  fileDocumentManager: FileDocumentManager,
  file: VirtualFile,
  textRange: Segment,
): UsageSnippet? {
  val document = fileDocumentManager.getDocument(file) ?: return null
  val snippetText = document.buildUsageSnippetText(textRange, MAX_USAGE_TEXT_CHARS)
  return UsageSnippet(
    file = file,
    filePath = projectDir.relativizeIfPossible(file),
    lineNumber = snippetText.lineNumber,
    lineText = snippetText.lineText,
  )
}

private fun parseSearchMode(searchMode: String?): SearchMode {
  val normalized = searchMode?.trim()?.lowercase()
  if (normalized.isNullOrEmpty()) return SearchMode.SEMANTIC
  return when (normalized) {
    "hybrid" -> SearchMode.HYBRID
    "semantic" -> SearchMode.SEMANTIC
    "lexical" -> SearchMode.LEXICAL
    else -> mcpFail("searchMode must be one of: semantic, lexical, hybrid")
  }
}

private fun parseQuerySyntax(querySyntax: String?): SearchQueryType {
  val normalized = querySyntax?.trim()?.lowercase()
  if (normalized.isNullOrEmpty()) return SearchQueryType.TEXT
  return when (normalized) {
    "text" -> SearchQueryType.TEXT
    "regex" -> SearchQueryType.REGEX
    else -> mcpFail("querySyntax must be one of: text, regex")
  }
}

// Count output is intentionally not supported because Search Everywhere cannot provide a reliable total.
private fun parseResultFormat(resultFormat: String?): SearchOutput {
  val normalized = resultFormat?.trim()?.lowercase()
  if (normalized.isNullOrEmpty()) return SearchOutput.ENTRIES
  return when (normalized) {
    "entries" -> SearchOutput.ENTRIES
    "files" -> SearchOutput.FILES
    else -> mcpFail("resultFormat must be one of: entries, files")
  }
}
