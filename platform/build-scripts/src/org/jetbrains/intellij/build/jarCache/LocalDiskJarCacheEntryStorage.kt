// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.jarCache

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.intellij.build.Source
import org.jetbrains.intellij.build.SourceAndCacheStrategy
import org.jetbrains.intellij.build.ZipSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.random.Random

internal fun tryUseCacheEntry(
  key: String,
  paths: CacheEntryPaths,
  targetFile: Path,
  sources: Collection<Source>,
  items: List<SourceAndCacheStrategy>,
  nativeFiles: MutableMap<ZipSource, List<String>>?,
  span: Span,
  producer: SourceBuilder,
  deleteInvalidEntry: Boolean,
  failOnCacheIoErrors: Boolean,
): Path? {
  // Lock-free path is only safe for materializing into an external target file.
  // If the caller wants cache file as target, keep lock-protected path to avoid
  // returning a path that can be concurrently cleaned up.
  if (!failOnCacheIoErrors) {
    if (producer.useCacheAsTargetFile) {
      return null
    }

    if (Files.exists(paths.markFile)) {
      return null
    }
  }

  val savedSources = readValidCacheMetadata(
    paths = paths,
    sources = sources,
    items = items,
    span = span,
    onInvalidEntry = if (deleteInvalidEntry) {
      { deleteEntryFiles(paths) }
    }
    else {
      null
    },
  ) ?: return null

  val resolvedTarget = if (producer.useCacheAsTargetFile) {
    if (Files.notExists(paths.payloadFile)) {
      return null
    }
    paths.payloadFile
  }
  else {
    try {
      createLinkOrCopy(targetFile = targetFile, cacheFile = paths.payloadFile)
      targetFile
    }
    catch (e: IOException) {
      if (failOnCacheIoErrors) {
        throw e
      }
      span.addEvent("cache hit materialization failed, will retry under lock: $e")
      return null
    }
  }

  touchMetadataFileIfRequired(metadataFile = paths.metadataFile, span = span)

  notifyAboutMetadata(sources = savedSources, items = items, nativeFiles = nativeFiles, producer = producer)
  span.addEvent(
    "use cache",
    Attributes.of(AttributeKey.stringKey("file"), targetFile.toString(), AttributeKey.stringKey("cacheKey"), key),
  )
  return resolvedTarget
}

internal suspend fun produceAndCache(
  paths: CacheEntryPaths,
  producer: SourceBuilder,
  targetFile: Path,
  items: List<SourceAndCacheStrategy>,
  nativeFiles: MutableMap<ZipSource, List<String>>?,
  tempFilePrefix: String,
): Path = withContext(Dispatchers.IO) {
  Files.createDirectories(paths.entryShardDir)
  val tempPayload = paths.entryShardDir.resolve("${paths.payloadFile.fileName}.tmp.$tempFilePrefix-${longToString(Random.nextLong())}")
  var payloadMoved = false
  try {
    producer.produce(tempPayload)
    moveReplacing(from = tempPayload, to = paths.payloadFile)
    payloadMoved = true
  }
  finally {
    if (!payloadMoved) {
      Files.deleteIfExists(tempPayload)
    }
  }

  val sourceCacheItems = Array(items.size) { index ->
    val source = items[index]
    SourceCacheItem(
      size = source.getSize().toInt(),
      hash = source.getHash(),
      nativeFiles = (source.source as? ZipSource)?.let { nativeFiles?.get(it) } ?: emptyList(),
    )
  }

  writeSourcesToMetadata(paths = paths, sources = sourceCacheItems, tempFilePrefix = tempFilePrefix)
  notifyAboutMetadata(sources = sourceCacheItems, items = items, nativeFiles = nativeFiles, producer = producer)

  if (!producer.useCacheAsTargetFile) {
    createLinkOrCopy(targetFile = targetFile, cacheFile = paths.payloadFile)
  }

  if (producer.useCacheAsTargetFile) paths.payloadFile else targetFile
}

private fun createLinkOrCopy(targetFile: Path, cacheFile: Path) {
  if (targetFile == cacheFile) {
    return
  }

  Files.createDirectories(targetFile.parent)
  try {
    Files.deleteIfExists(targetFile)
    Files.createLink(targetFile, cacheFile)
  }
  catch (_: IOException) {
    Files.copy(cacheFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
  }
}

private fun touchMetadataFileIfRequired(metadataFile: Path, span: Span) {
  // metadata mtime is treated as last-access timestamp for cleanup.
  try {
    val now = System.currentTimeMillis()
    val lastModifiedTime = Files.getLastModifiedTime(metadataFile).toMillis()
    if (now - lastModifiedTime >= metadataTouchMinInterval.inWholeMilliseconds) {
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(now))
    }
  }
  catch (e: IOException) {
    span.addEvent("update cache metadata modification time failed: $e")
  }
}
