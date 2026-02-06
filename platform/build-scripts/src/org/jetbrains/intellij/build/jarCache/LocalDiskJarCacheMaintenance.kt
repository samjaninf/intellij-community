// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.jarCache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import kotlin.time.Duration

internal suspend fun cleanupLocalDiskJarCache(
  entriesDir: Path,
  lastCleanupMarkerFile: Path,
  maxAccessTimeAge: Duration,
  withCacheEntryLock: suspend (String, suspend () -> Unit) -> Unit,
) {
  withContext(Dispatchers.IO) {
    try {
      if (!isTimeForCleanup(lastCleanupMarkerFile = lastCleanupMarkerFile)) {
        return@withContext
      }

      cleanupEntries(
        entriesDir = entriesDir,
        currentTime = System.currentTimeMillis(),
        maxTimeMs = maxAccessTimeAge.inWholeMilliseconds,
        withCacheEntryLock = withCacheEntryLock,
      )
      Files.writeString(lastCleanupMarkerFile, LocalDateTime.now().toString())
    }
    catch (_: IOException) {
      // cleanup is best-effort
    }
  }
}

internal fun purgeLegacyCacheIfRequired(
  cacheDir: Path,
  versionedCacheDir: Path,
  legacyPurgeMarkerFile: Path,
) {
  if (Files.exists(legacyPurgeMarkerFile)) {
    return
  }

  Files.newDirectoryStream(cacheDir).use { stream ->
    for (entry in stream) {
      if (entry == versionedCacheDir || entry == legacyPurgeMarkerFile) {
        continue
      }

      if (Files.isDirectory(entry)) {
        if (isLegacyVersionDirectory(entry.fileName.toString())) {
          deletePathRecursively(entry)
        }
        continue
      }

      if (!Files.isRegularFile(entry)) {
        continue
      }

      if (!isLegacyFlatMetadataFile(entry.fileName.toString())) {
        continue
      }

      val jarFile = entry.resolveSibling(entry.fileName.toString().removeSuffix(legacyMetadataSuffix) + legacyJarSuffix)
      Files.deleteIfExists(entry)
      Files.deleteIfExists(jarFile)

      val metadataMarkFile = entry.resolveSibling(entry.fileName.toString() + markedForCleanupFileSuffix)
      val jarMarkFile = jarFile.resolveSibling(jarFile.fileName.toString() + markedForCleanupFileSuffix)
      Files.deleteIfExists(metadataMarkFile)
      Files.deleteIfExists(jarMarkFile)

      if (Files.exists(cacheDir.resolve(cleanupMarkerFileName))) {
        Files.deleteIfExists(cacheDir.resolve(cleanupMarkerFileName))
      }
    }
  }

  Files.writeString(legacyPurgeMarkerFile, LocalDateTime.now().toString())
}

private fun isTimeForCleanup(lastCleanupMarkerFile: Path): Boolean {
  return Files.notExists(lastCleanupMarkerFile) ||
         Files.getLastModifiedTime(lastCleanupMarkerFile).toMillis() < (System.currentTimeMillis() - cleanupEveryDuration.inWholeMilliseconds)
}

private suspend fun cleanupEntries(
  entriesDir: Path,
  currentTime: Long,
  maxTimeMs: Long,
  withCacheEntryLock: suspend (String, suspend () -> Unit) -> Unit,
) {
  withContext(Dispatchers.IO) {
    val staleThreshold = currentTime - maxTimeMs
    val shardDirs = try {
      Files.newDirectoryStream(entriesDir)
    }
    catch (_: NoSuchFileException) {
      return@withContext
    }

    shardDirs.use { stream ->
      for (shardDir in stream) {
        if (!Files.isDirectory(shardDir)) {
          continue
        }

        val entryStems = collectEntryStems(shardDir)
        for (entryStem in entryStems) {
          val key = getCacheKeyFromEntryStem(entryStem) ?: continue
          val paths = getCacheEntryPathsByStem(entryShardDir = shardDir, entryStem = entryStem)
          if (!shouldInspectEntryUnderLock(paths = paths, staleThreshold = staleThreshold)) {
            continue
          }

          withCacheEntryLock(key) {
            cleanupEntry(paths = paths, staleThreshold = staleThreshold)
          }
        }
      }
    }
  }
}

private fun collectEntryStems(shardDir: Path): Set<String> {
  val stems = LinkedHashSet<String>()
  Files.newDirectoryStream(shardDir).use { files ->
    for (file in files) {
      if (!Files.isRegularFile(file)) {
        continue
      }

      val fileName = file.fileName.toString()
      val stem = when {
        fileName.endsWith(metadataFileSuffix) -> fileName.removeSuffix(metadataFileSuffix)
        fileName.endsWith(markedForCleanupFileSuffix) -> fileName.removeSuffix(markedForCleanupFileSuffix)
        else -> fileName
      }
      if (!stem.contains(entryNameSeparator)) {
        continue
      }
      stems.add(stem)
    }
  }
  return stems
}

private fun shouldInspectEntryUnderLock(paths: CacheEntryPaths, staleThreshold: Long): Boolean {
  val metadataFile = paths.metadataFile
  if (Files.notExists(metadataFile)) {
    return true
  }

  // Fresh entries without cleanup mark don't require lock-protected mutation.
  if (Files.notExists(paths.markFile)) {
    val lastAccessTime = try {
      Files.getLastModifiedTime(metadataFile).toMillis()
    }
    catch (_: NoSuchFileException) {
      return true
    }
    catch (_: IOException) {
      return true
    }

    return lastAccessTime <= staleThreshold
  }

  return true
}

private fun cleanupEntry(paths: CacheEntryPaths, staleThreshold: Long) {
  val metadataFile = paths.metadataFile
  if (Files.notExists(metadataFile)) {
    deleteEntryFiles(paths)
    return
  }

  val lastAccessTime = try {
    Files.getLastModifiedTime(metadataFile).toMillis()
  }
  catch (_: NoSuchFileException) {
    return
  }
  catch (_: IOException) {
    return
  }

  val markFile = paths.markFile
  if (lastAccessTime > staleThreshold) {
    try {
      Files.deleteIfExists(markFile)
    }
    catch (_: IOException) {
      // cleanup is best-effort
    }
    return
  }

  if (Files.exists(markFile)) {
    deleteEntryFiles(paths)
  }
  else {
    try {
      Files.newByteChannel(markFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).close()
    }
    catch (_: IOException) {
      // cleanup is best-effort
    }
  }
}

private fun isLegacyFlatMetadataFile(fileName: String): Boolean {
  return fileName.endsWith(legacyMetadataSuffix) && legacyFlatMetadataPattern.matches(fileName)
}

private fun isLegacyVersionDirectory(fileName: String): Boolean {
  return legacyVersionDirectoryPattern.matches(fileName)
}
