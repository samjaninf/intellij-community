// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.performancePlugin.commands

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.playback.PlaybackContext
import com.intellij.openapi.ui.playback.commands.AbstractCommand
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.diagnostic.dump.IndexContentDiagnosticDumper
import com.intellij.util.indexing.diagnostic.dump.paths.IndexedFilePath
import com.intellij.util.indexing.diagnostic.dump.paths.hasPresentablePathMatching
import com.jetbrains.performancePlugin.PerformanceTestingBundle
import com.jetbrains.performancePlugin.utils.ActionCallbackProfilerStopper
import com.jetbrains.performancePlugin.utils.errors.ErrorCollector
import com.jetbrains.performancePlugin.utils.errors.ToDirectoryWritingErrorCollector
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.toPromise
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText


class CompareProjectFiles(text: String, line: Int) : AbstractCommand(text, line) {
  companion object {
    const val PREFIX = CMD_PREFIX + "compareProjectFiles"
    private val LOG = logger<CompareProjectFiles>()

    @JvmStatic
    fun main(args: Array<String>) {
      val expectedPath = Paths.get(args[0])
      val actualPath = Paths.get(args[1])
      compareProjectFiles(expectedPath, actualPath, actualPath.parent, ProgressIndicatorBase())
    }

    private fun compareProjectFiles(
      expectedDirectory: Path,
      actualDirectory: Path,
      failureDiagnosticDirectory: Path,
      indicator: ProgressIndicator,
    ) {
      val expectedContentDiagnostic = IndexContentDiagnosticDumper.readFrom(StoreIndices.getFileForDiagnostic(expectedDirectory))
      val actualContentDiagnostic = IndexContentDiagnosticDumper.readFrom(StoreIndices.getFileForDiagnostic(actualDirectory))

      val expectedStableIteratorNames = expectedContentDiagnostic.projectIndexedFileProviderDebugNameToFileIds.keys.filter { name ->
        meaningfulDebugNames.any { name.startsWith(it) }
      }
      val actualStableIteratorNames = actualContentDiagnostic.projectIndexedFileProviderDebugNameToFileIds.keys.filter { name ->
        meaningfulDebugNames.any { name.startsWith(it) }
      }

      val errorCollectors = arrayListOf<ErrorCollector>()

      val setOfIteratorsErrorCollector = ToDirectoryWritingErrorCollector("set-of-iterators", failureDiagnosticDirectory.resolve("errors-for-set-of-iterators"), 100)
      errorCollectors.add(setOfIteratorsErrorCollector)
      setOfIteratorsErrorCollector.runCatchingError { compareSetsOfIndexableIterators(expectedStableIteratorNames, actualStableIteratorNames) }


      val expectedFileIdToFile = expectedContentDiagnostic.allIndexedFilePaths.associateBy { it.originalFileSystemId }
      val actualFileIdToFile = actualContentDiagnostic.allIndexedFilePaths.associateBy { it.originalFileSystemId }

      val expectedFileIdToIterName = expectedContentDiagnostic.projectIndexedFileProviderDebugNameToFileIds.flatMap { (name, ids) -> ids.map { it to name } }.toMap()
      val actualFileIdToIterName = actualContentDiagnostic.projectIndexedFileProviderDebugNameToFileIds.flatMap { (name, ids) -> ids.map { it to name } }.toMap()

      val allFiles = "all-files"
      val allFilesErrorCollector = ToDirectoryWritingErrorCollector(allFiles, failureDiagnosticDirectory.resolve("errors-for-${FileUtil.sanitizeFileName(allFiles)}"), 100)
      errorCollectors.add(allFilesErrorCollector)

      indicator.text = PerformanceTestingBundle.message("comparing.project.files.for.0", allFiles)
      compareSetsOfFiles(expectedFileIdToFile.keys, expectedFileIdToFile, expectedFileIdToIterName, actualFileIdToFile.keys, actualFileIdToFile, actualFileIdToIterName, allFilesErrorCollector)

      if (errorCollectors.any { it.numberOfErrors > 0 }) {
        throw RuntimeException("Some errors during files comparison have been collected. See details in $failureDiagnosticDirectory")
      }

      LOG.info("Success. Project files are equal")
    }

    // There may be iterators with non-presentable and unstable debug names, like "com.intellij.javaee.ExternalResourcesRootsProvider@eeabcf4"
    // For such iterators we compare files as a whole set.
    private val meaningfulDebugNames = listOf("Module", "Library", "SDK", "JDK", "Synthetic library", "Go SDK module")

    private fun compareSetsOfFiles(
      expectedIds: Iterable<Int>,
      expectedFileIdToFile: Map<Int, IndexedFilePath>,
      expectedFileIdToIterName: Map<Int, String>,
      actualIds: Iterable<Int>,
      actualFileIdToFile: Map<Int, IndexedFilePath>,
      actualFileIdToIterName: Map<Int, String>,
      errorCollector: ToDirectoryWritingErrorCollector,
    ) {
      val expectedFiles = expectedIds.mapNotNull { expectedFileIdToFile[it] }.associateBy { it.portableFilePath }.filterKeys { file -> ignoredFilesPatterns.none { file.hasPresentablePathMatching(it) } }

      val actualFiles = actualIds.mapNotNull { actualFileIdToFile[it] }.associateBy { it.portableFilePath }.filterKeys { file -> ignoredFilesPatterns.none { file.hasPresentablePathMatching(it) } }

      val missingFilePaths = expectedFiles.keys - actualFiles.keys
      val redundantFilePaths = actualFiles.keys - expectedFiles.keys
      if (missingFilePaths.isNotEmpty() || redundantFilePaths.isNotEmpty()) {
        errorCollector.addError(RuntimeException(buildString {
          appendLine("The sets of indexed files do not match")
          appendLine("  Missing file paths:")
          missingFilePaths.forEach { appendLine("    ${it.presentablePath} ; first-run iterator: ${expectedFiles[it]?.originalFileSystemId?.let { expectedFileIdToIterName[it] }}") }
          appendLine("  Redundant file paths:")
          redundantFilePaths.forEach { appendLine("    ${it.presentablePath} ; second-run iterator: ${actualFiles[it]?.originalFileSystemId?.let { actualFileIdToIterName[it] }}") }
        }))
      }

      if (errorCollector.numberOfErrors > 0) return

      for (filePath in expectedFiles.keys) {
        val expectedIndexedFile = expectedFiles.getValue(filePath)
        val actualIndexedFile = actualFiles.getValue(filePath) // Ignore "originalFileSystemId" field. It is expected that original VFS IDs mismatch between IDE restarts.
        val expectedData = expectedIndexedFile.copy(originalFileSystemId = 0, originalFileUrl = "")
        val actualData = actualIndexedFile.copy(originalFileSystemId = 0, originalFileUrl = "")
        expectedData
        if (expectedData != actualData) {
          errorCollector.addError(RuntimeException(buildString {
            appendLine("Indexed file ${filePath.presentablePath} data mismatch")

            appendLine("  Expected:")
            append(expectedIndexedFile.toString().lineSequence().joinToString("\n") { "    $it" })
            appendLine("iterator: ${expectedFileIdToIterName[expectedIndexedFile.originalFileSystemId]}")

            appendLine("  Actual:")
            append(actualIndexedFile.toString().lineSequence().joinToString("\n") { "    $it" })
            appendLine("iterator: ${actualFileIdToIterName[actualIndexedFile.originalFileSystemId]}")
          }))
        }
        if (errorCollector.numberOfErrors > 100) break
      }
    }

    private fun compareSetsOfIndexableIterators(
      expectedIteratorNames: List<String>,
      actualIteratorNames: List<String>,
    ) {
      val missingIteratorNames = expectedIteratorNames - actualIteratorNames
      val redundantIteratorNames = actualIteratorNames - expectedIteratorNames
      if (missingIteratorNames.isNotEmpty() || redundantIteratorNames.isNotEmpty()) {
        if (missingIteratorNames.size == redundantIteratorNames.size) {
          // The same .jar file may be in different libraries.
          // It means it may be indexed by different iterators in different IDE launches, and we record only iterator that indexed it.

          // We want to check if we indexed all jars from missing list in redundant list

          // first check that only library iterators are missing and redundant
          if (missingIteratorNames.all { it.contains("Library") } && redundantIteratorNames.all { it.contains("Library") }) {
            // parse all .jar names. This expects LibraryIndexableFilesIteratorImpl.getDebugName format
            val missingJars = missingIteratorNames.map { it.substringAfter("(").substringBefore(")") }.sorted()
            val redundantJars = redundantIteratorNames.map { it.substringAfter("(").substringBefore(")") }.sorted()

            if (missingJars == redundantJars) {
              return
            }
          }
        }

        error(buildString {
          appendLine("The sets of indexable file iterators do not match")
          appendLine("  Missing iterators: [" + missingIteratorNames.joinToString() + "]")
          appendLine("  Redundant iterators: [" + redundantIteratorNames.joinToString() + "]")
        })
      }
    }

    private val ignoredFilesPatterns: List<String> by lazy {
      val listPath = System.getProperty("compare.project.files.list.of.files.to.ignore.from.comparison") ?: return@lazy emptyList<String>()
      val patterns = Paths.get(listPath).readText().lines().map { it.trim() }.filterNot { it.isEmpty() }
      LOG.info("The following files will be ignored from project files comparison:\n" + patterns.joinToString(separator = "\n"))
      patterns
    }
  }

  override fun _execute(context: PlaybackContext): Promise<Any?> {
    val actionCallback = ActionCallbackProfilerStopper()

    val input = text.substring(PREFIX.length).trim()

    val index = input.split(" ")
    val property = System.getProperty("dump.project.files.directory")
    val expectedDirectory = if (property != null) Paths.get(property)
    else {
      actionCallback.reject("dump.project.files.directory property must be specified")
      return actionCallback.toPromise()
    }
    val actualDirectory = Paths.get(index[0])

    val failureDiagnosticDirectory = getFailureDiagnosticDirectory()

    val project = context.project
    DumbService.getInstance(project).smartInvokeLater {
      object : Task.Modal(project, PerformanceTestingBundle.message("comparing.project.files"), false) {
        override fun run(indicator: ProgressIndicator) {
          try {
            (FileBasedIndex.getInstance() as FileBasedIndexImpl).flushIndexes()
            compareProjectFiles(expectedDirectory, actualDirectory, failureDiagnosticDirectory, indicator)
            actionCallback.setDone()
          }
          catch (e: Throwable) {
            LOG.error(e)
            actionCallback.reject(e.message)
          }
        }
      }.queue()
    }
    return actionCallback.toPromise()
  }

  private fun getFailureDiagnosticDirectory(): Path {
    val property = System.getProperty("compare.project.files.command.failure.diagnostic.directory")
    if (property != null) {
      return Paths.get(property)
    }
    return FileUtil.createTempDirectory("compare-project-files", "failure").toPath()
  }
}
