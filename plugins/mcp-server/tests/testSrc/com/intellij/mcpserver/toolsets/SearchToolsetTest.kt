@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.mcpserver.McpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.SearchToolset
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.junit5.fixture.pathInProjectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.junit5.fixture.virtualFileFixture
import io.kotest.common.runBlocking
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class SearchToolsetTest : McpToolsetTestBase() {
  private val json = Json { ignoreUnknownKeys = true }

  private val searchFile by sourceRootFixture.virtualFileFixture(
    "se_unique_search_file_7c2f.txt",
    "Search Everywhere file content"
  )

  private val fileMaskTxt by sourceRootFixture.virtualFileFixture(
    "se_mask_match_4d9a.txt",
    "Search Everywhere file mask content"
  )

  private val fileMaskJava by sourceRootFixture.virtualFileFixture(
    "se_mask_match_4d9a.java",
    "Search Everywhere file mask content"
  )

  private val subdir1Fixture = moduleFixture.sourceRootFixture(pathFixture = projectFixture.pathInProjectFixture(Path("subdir1")))
  private val subdir2Fixture = moduleFixture.sourceRootFixture(pathFixture = projectFixture.pathInProjectFixture(Path("subdir2")))
  private val scopedFileInSubdir1 by subdir1Fixture.virtualFileFixture(
    "se_scoped_file_3e7a.txt",
    "Scoped file content"
  )
  private val scopedJavaFileInSubdir1 by subdir1Fixture.virtualFileFixture(
    "se_scoped_java_3e7a.java",
    "Scoped file content"
  )
  private val scopedFileInSubdir2 by subdir2Fixture.virtualFileFixture(
    "se_scoped_file_3e7a.txt",
    "Scoped file content"
  )

  private val caseSensitiveFile by sourceRootFixture.virtualFileFixture(
    "se_case_sensitive_1a2b.txt",
    "SE_CASE_TOKEN"
  )

  private val wholeWordExactFile by sourceRootFixture.virtualFileFixture(
    "se_whole_word_exact_9f1c.txt",
    "SE_WHOLE_WORD_TOKEN"
  )

  private val wholeWordPartialFile by sourceRootFixture.virtualFileFixture(
    "se_whole_word_partial_9f1c.txt",
    "SE_WHOLE_WORD_TOKENX"
  )

  private val regexFile by sourceRootFixture.virtualFileFixture(
    "se_regex_5aa1.txt",
    "SE_REGEX_TOKEN_1234"
  )

  private val maxResultsFile1 by sourceRootFixture.virtualFileFixture(
    "se_max_results_8b1c_1.txt",
    "Max results content"
  )

  private val maxResultsFile2 by sourceRootFixture.virtualFileFixture(
    "se_max_results_8b1c_2.txt",
    "Max results content"
  )

  private fun parseResult(text: String?): JsonObject {
    val payload = text ?: error("Tool call result should include text content")
    return json.parseToJsonElement(payload).jsonObject
  }

  private fun JsonObject.resultEntries(): List<JsonArray> =
    this["items"]?.jsonArray?.map { it.jsonArray } ?: emptyList()

  private fun JsonObject.entryFilePaths(): List<String> =
    resultEntries().mapNotNull { entry -> entry.getOrNull(0)?.jsonPrimitive?.content }

  private fun JsonObject.entriesCount(): Int = resultEntries().size

  @Test
  fun search_files_provider_returns_file_path() = runBlocking {
    DumbService.getInstance(project).waitForSmartMode()
    val fileName = searchFile.name
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(fileName))
        put("providers", JsonArray(listOf(JsonPrimitive("files"))))
        put("resultFormat", JsonPrimitive("files"))
      }
    ) { actualResult ->
      val result = parseResult(actualResult.textContent.text)
      assertThat(result.entryFilePaths()).anyMatch { it.contains(fileName) }
    }
  }

  @Test
  fun search_files_provider_respects_directory_scope() = runBlocking {
    DumbService.getInstance(project).waitForSmartMode()
    val fileName = scopedFileInSubdir1.name
    val otherFileName = scopedFileInSubdir2.name
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(fileName))
        put("providers", JsonArray(listOf(JsonPrimitive("files"))))
        put("resultFormat", JsonPrimitive("files"))
        put("searchRoot", JsonPrimitive("subdir1"))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains("subdir1") }
      assertThat(filePaths).noneMatch { it.contains("subdir2") }
      assertThat(otherFileName).isEqualTo(fileName)
    }
  }

  @Test
  fun search_text_provider_respects_file_mask() = runBlocking {
    val query = "Search Everywhere file mask content"
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("filePattern", JsonPrimitive("*.txt"))
        put("resultFormat", JsonPrimitive("entries"))
        put("includeNonProjectItems", JsonPrimitive(true))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(fileMaskTxt.name) }
      assertThat(filePaths).noneMatch { it.contains(fileMaskJava.name) }
    }
  }

  @Test
  fun search_text_provider_respects_directory_and_file_mask() = runBlocking {
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive("Scoped file content"))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("filePattern", JsonPrimitive("*.txt"))
        put("searchRoot", JsonPrimitive("subdir1"))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(scopedFileInSubdir1.name) }
      assertThat(filePaths).noneMatch { it.contains(scopedJavaFileInSubdir1.name) }
      assertThat(filePaths).noneMatch { it.contains("subdir2") }
    }
  }

  @Test
  fun search_text_provider_respects_case_sensitive() = runBlocking {
    val query = "se_case_token"
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("caseSensitive", JsonPrimitive(true))
      }
    ) { actualResult ->
      val result = parseResult(actualResult.textContent.text)
      assertThat(result.entriesCount()).isZero()
    }

    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("caseSensitive", JsonPrimitive(false))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(caseSensitiveFile.name) }
    }
  }

  @Test
  fun search_text_provider_respects_whole_words_only() = runBlocking {
    val query = "SE_WHOLE_WORD_TOKEN"
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("wholeWordsOnly", JsonPrimitive(true))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(wholeWordExactFile.name) }
      assertThat(filePaths).noneMatch { it.contains(wholeWordPartialFile.name) }
    }

    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("wholeWordsOnly", JsonPrimitive(false))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(wholeWordExactFile.name) }
      assertThat(filePaths).anyMatch { it.contains(wholeWordPartialFile.name) }
    }
  }

  @Test
  fun search_text_provider_supports_regex_query() = runBlocking {
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive("SE_REGEX_TOKEN_\\d{4}"))
        put("querySyntax", JsonPrimitive("regex"))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("resultFormat", JsonPrimitive("entries"))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains(regexFile.name) }
    }
  }

  @Test
  fun search_text_provider_respects_directory_scope() = runBlocking {
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive("Scoped file content"))
        put("querySyntax", JsonPrimitive("regex"))
        put("providers", JsonArray(listOf(JsonPrimitive("text"))))
        put("searchMode", JsonPrimitive("lexical"))
        put("searchRoot", JsonPrimitive("subdir1"))
      }
    ) { actualResult ->
      val filePaths = parseResult(actualResult.textContent.text).entryFilePaths()
      assertThat(filePaths).anyMatch { it.contains("subdir1") }
      assertThat(filePaths).noneMatch { it.contains("subdir2") }
    }
  }

  @Test
  fun search_hybrid_mode_respects_max_results() = runBlocking {
    DumbService.getInstance(project).waitForSmartMode()
    val query = "se_max_results_8b1c"
    testMcpTool(
      SearchToolset::search.name,
      buildJsonObject {
        put("query", JsonPrimitive(query))
        put("providers", JsonArray(listOf(JsonPrimitive("files"))))
        put("searchMode", JsonPrimitive("hybrid"))
        put("maxResults", JsonPrimitive(1))
        put("timeoutMs", JsonPrimitive(5_000))
      }
    ) { actualResult ->
      val result = parseResult(actualResult.textContent.text)
      val entryFilePaths = result.entryFilePaths()
      val expectedNames = setOf(maxResultsFile1.name, maxResultsFile2.name)
      assertThat(result.entriesCount()).isEqualTo(1)
      assertThat(entryFilePaths).anyMatch { path -> expectedNames.any { path.contains(it) } }
    }
  }
}
