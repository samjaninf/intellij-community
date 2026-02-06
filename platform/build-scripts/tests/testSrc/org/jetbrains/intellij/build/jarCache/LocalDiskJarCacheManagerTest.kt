// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.jarCache

import com.dynatrace.hash4j.hashing.HashStream64
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.intellij.build.InMemoryContentSource
import org.jetbrains.intellij.build.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal class LocalDiskJarCacheManagerTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `cache deduplicates payload and refreshes metadata last access time`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)

      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      val firstResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val entryPaths = findSingleEntryPaths(cacheDir)
      val metadataFile = entryPaths.metadataFile
      val staleAccessTime = System.currentTimeMillis() - 10.days.inWholeMilliseconds
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(staleAccessTime))

      val secondResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )
      val secondAccessTime = Files.getLastModifiedTime(metadataFile).toMillis()

      assertThat(firstResult).isEqualTo(secondResult)
      assertThat(produceCalls.get()).isEqualTo(1)
      assertThat(secondAccessTime).isGreaterThan(staleAccessTime)
    }
  }

  @Test
  fun `cache hit does not refresh metadata last access time within touch interval`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)

      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      val recentAccessTime = System.currentTimeMillis() - 1_000
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(recentAccessTime))
      val expectedAccessTime = Files.getLastModifiedTime(metadataFile).toMillis()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val actualAccessTime = Files.getLastModifiedTime(metadataFile).toMillis()
      assertThat(actualAccessTime).isEqualTo(expectedAccessTime)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `cache hit refreshes metadata last access time after touch interval`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)

      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      val staleAccessTime = System.currentTimeMillis() - 1.days.inWholeMilliseconds
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(staleAccessTime))
      val expectedOldAccessTime = Files.getLastModifiedTime(metadataFile).toMillis()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val actualAccessTime = Files.getLastModifiedTime(metadataFile).toMillis()
      assertThat(actualAccessTime).isGreaterThan(expectedOldAccessTime)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `legacy cache format is purged on startup while unrelated files are preserved`() {
    val cacheDir = tempDir.resolve("cache")
    Files.createDirectories(cacheDir)

    val legacyJar = cacheDir.resolve("legacy-16-foo-bar.jar")
    val legacyMetadata = cacheDir.resolve("legacy-16-foo-bar.m")
    val legacyCleanupMarker = cacheDir.resolve(".last.cleanup.marker")
    val oldVersionDir = cacheDir.resolve("v16")
    val unrelatedFile = cacheDir.resolve("keep.txt")
    val nonLegacyVersionDir = cacheDir.resolve("vtest")
    val unrelatedDir = cacheDir.resolve("custom")

    Files.writeString(legacyJar, "old jar")
    Files.writeString(legacyMetadata, "old metadata")
    Files.writeString(legacyCleanupMarker, "old cleanup")
    Files.createDirectories(oldVersionDir)
    Files.writeString(oldVersionDir.resolve("old.txt"), "old")
    Files.writeString(unrelatedFile, "keep")
    Files.createDirectories(nonLegacyVersionDir)
    Files.createDirectories(unrelatedDir)

    createManager(cacheDir = cacheDir, maxAccessTimeAge = 3.days)

    assertThat(Files.exists(legacyJar)).isFalse()
    assertThat(Files.exists(legacyMetadata)).isFalse()
    assertThat(Files.exists(legacyCleanupMarker)).isFalse()
    assertThat(Files.exists(oldVersionDir)).isFalse()
    assertThat(Files.exists(unrelatedFile)).isTrue()
    assertThat(Files.exists(nonLegacyVersionDir)).isTrue()
    assertThat(Files.exists(unrelatedDir)).isTrue()
  }

  @Test
  fun `cleanup marks and then removes stale entries`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 1.days)

      val builder = TestSourceBuilder(produceCalls = AtomicInteger(), useCacheAsTargetFile = true)
      val sources = createSources()
      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val versionDir = findVersionDir(cacheDir)
      val entryPaths = findSingleEntryPaths(cacheDir)
      val metadataFile = entryPaths.metadataFile
      val markFile = entryPaths.markFile
      val cleanupMarkerFile = versionDir.resolve(".last.cleanup.marker")
      val lockFile = getStripedLockFile(versionDir)

      assertThat(Files.exists(lockFile)).isTrue()

      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(System.currentTimeMillis() - 10.days.inWholeMilliseconds))

      manager.cleanup()

      assertThat(Files.exists(entryPaths.metadataFile)).isTrue()
      assertThat(Files.exists(markFile)).isTrue()

      Files.setLastModifiedTime(cleanupMarkerFile, FileTime.fromMillis(System.currentTimeMillis() - 2.days.inWholeMilliseconds))
      manager.cleanup()

      assertThat(Files.exists(entryPaths.metadataFile)).isFalse()
      assertThat(Files.exists(entryPaths.payloadFile)).isFalse()
      assertThat(Files.exists(lockFile)).isTrue()
    }
  }

  @Test
  fun `cleanup removes entry files when entry metadata is missing`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 1.days)

      val builder = TestSourceBuilder(produceCalls = AtomicInteger(), useCacheAsTargetFile = true)
      val sources = createSources()
      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val versionDir = findVersionDir(cacheDir)
      val entryPaths = findSingleEntryPaths(cacheDir)
      val metadataFile = entryPaths.metadataFile
      val lockFile = getStripedLockFile(versionDir)
      assertThat(Files.exists(lockFile)).isTrue()

      Files.delete(metadataFile)
      manager.cleanup()

      assertThat(Files.exists(entryPaths.payloadFile)).isFalse()
      assertThat(Files.exists(lockFile)).isTrue()
    }
  }

  @Test
  fun `concurrent compute for same key produces payload once`() {
    runBlocking {
      val manager = createManager(cacheDir = tempDir.resolve("cache"), maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true, produceDelayMs = 50)
      val sources = createSources()

      val results = coroutineScope {
        List(8) { index ->
          async(Dispatchers.Default) {
            manager.computeIfAbsent(
              sources = sources,
              targetFile = tempDir.resolve("out/$index/first.jar"),
              nativeFiles = null,
              span = Span.getInvalid(),
              producer = builder,
            )
          }
        }.awaitAll()
      }

      assertThat(results.distinct()).hasSize(1)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `manager with scope reuses cached payload`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = LocalDiskJarCacheManager(
        cacheDir = cacheDir,
        productionClassOutDir = tempDir.resolve("classes/production"),
        maxAccessTimeAge = 30.days,
        scope = this,
      )
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      val firstResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )
      val secondResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(secondResult).isEqualTo(firstResult)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `different target file names produce separate cache entries`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )
      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/second.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val entries = listEntryPaths(cacheDir)
      assertThat(produceCalls.get()).isEqualTo(2)
      assertThat(entries).hasSize(2)
      assertThat(entries.map { it.payloadFile.fileName.toString() }).anySatisfy { assertThat(it).contains("__first.jar") }
      assertThat(entries.map { it.payloadFile.fileName.toString() }).anySatisfy { assertThat(it).contains("__second.jar") }
    }
  }

  @Test
  fun `manager can be recreated for existing cache directory`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val firstManager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      val firstResult = firstManager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val secondManager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val secondResult = secondManager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(secondResult).isEqualTo(firstResult)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `corrupted metadata triggers cache rebuild`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      Files.write(metadataFile, byteArrayOf(1, 2, 3, 4))

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(2)
      val metadataBytes = Files.readAllBytes(metadataFile)
      assertThat(metadataBytes.size).isGreaterThanOrEqualTo(12)
      val metadataBuffer = ByteBuffer.wrap(metadataBytes)
      val rewrittenMagic = metadataBuffer.int
      val rewrittenSchema = metadataBuffer.int
      assertThat(rewrittenMagic).isNotEqualTo(0)
      assertThat(rewrittenSchema).isGreaterThan(0)
    }
  }

  @Test
  fun `metadata schema mismatch triggers cache rebuild`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      val metadataBytes = Files.readAllBytes(metadataFile)
      val originalMetadata = ByteBuffer.wrap(metadataBytes)
      val originalMagic = originalMetadata.int
      val originalSchema = originalMetadata.int
      val mismatchedSchema = if (originalSchema == Int.MAX_VALUE) originalSchema - 1 else originalSchema + 1
      originalMetadata.putInt(Int.SIZE_BYTES, mismatchedSchema)
      Files.write(metadataFile, metadataBytes)

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(2)
      val rewrittenMetadata = ByteBuffer.wrap(Files.readAllBytes(metadataFile))
      assertThat(rewrittenMetadata.int).isEqualTo(originalMagic)
      assertThat(rewrittenMetadata.int).isEqualTo(originalSchema)
    }
  }

  @Test
  fun `malformed native blob size triggers cache rebuild`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      val metadataBuffer = ByteBuffer.wrap(Files.readAllBytes(metadataFile))
      metadataBuffer.int
      metadataBuffer.int
      metadataBuffer.int
      metadataBuffer.int
      metadataBuffer.long
      metadataBuffer.int
      val nativeBlobSizeOffset = metadataBuffer.position()
      metadataBuffer.putInt(nativeBlobSizeOffset, 1)
      val metadataBytes = metadataBuffer.array()
      Files.write(metadataFile, metadataBytes)

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(2)
    }
  }

  @Test
  fun `metadata stores native blob length field`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      val metadataBuffer = ByteBuffer.wrap(Files.readAllBytes(metadataFile))
      val magic = metadataBuffer.int
      val schema = metadataBuffer.int
      assertThat(magic).isNotEqualTo(0)
      assertThat(schema).isGreaterThan(0)
      assertThat(metadataBuffer.int).isEqualTo(1)

      val sourceSize = metadataBuffer.int
      metadataBuffer.long
      val nativeFileCount = metadataBuffer.int
      val nativeBlobSize = metadataBuffer.int

      assertThat(sourceSize).isGreaterThan(0)
      assertThat(nativeFileCount).isEqualTo(0)
      assertThat(nativeBlobSize).isEqualTo(0)
      assertThat(metadataBuffer.hasRemaining()).isFalse()
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `missing payload triggers cache rebuild`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val payloadFile = findSingleEntryPaths(cacheDir).payloadFile
      Files.delete(payloadFile)

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(2)
      assertThat(Files.exists(payloadFile)).isTrue()
    }
  }

  @Test
  fun `missing metadata triggers cache rebuild`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val metadataFile = findSingleEntryPaths(cacheDir).metadataFile
      Files.delete(metadataFile)

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(2)
      assertThat(Files.exists(metadataFile)).isTrue()
    }
  }

  @Test
  fun `cleanup keeps reaccessed stale entry and clears mark`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 1.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val versionDir = findVersionDir(cacheDir)
      val cleanupMarkerFile = versionDir.resolve(".last.cleanup.marker")
      val entryPaths = findSingleEntryPaths(cacheDir)
      val metadataFile = entryPaths.metadataFile
      val markFile = entryPaths.markFile

      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(System.currentTimeMillis() - 10.days.inWholeMilliseconds))
      manager.cleanup()
      assertThat(Files.exists(markFile)).isTrue()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out2/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      Files.setLastModifiedTime(cleanupMarkerFile, FileTime.fromMillis(System.currentTimeMillis() - 2.days.inWholeMilliseconds))
      manager.cleanup()

      assertThat(Files.exists(entryPaths.payloadFile)).isTrue()
      assertThat(Files.exists(markFile)).isFalse()
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `cleanup cadence prevents immediate second-pass deletion`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 1.days)
      val builder = TestSourceBuilder(produceCalls = AtomicInteger(), useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val entryPaths = findSingleEntryPaths(cacheDir)
      val metadataFile = entryPaths.metadataFile
      val markFile = entryPaths.markFile
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(System.currentTimeMillis() - 10.days.inWholeMilliseconds))

      manager.cleanup()
      assertThat(Files.exists(markFile)).isTrue()

      manager.cleanup()
      assertThat(Files.exists(entryPaths.payloadFile)).isTrue()
      assertThat(Files.exists(markFile)).isTrue()
    }
  }

  @Test
  fun `cleanup skips lock acquisition for fresh unmarked entries`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 1.days)
      val versionDir = findVersionDir(cacheDir)
      val key = "ab-key"
      val entryPaths = buildEntryPathsFromStem(
        shardDir = versionDir.resolve("entries").resolve("ab"),
        entryStem = "$key${entryNameSeparatorForTests}first.jar",
      )
      val metadataFile = entryPaths.metadataFile
      val markFile = entryPaths.markFile
      val lockFile = getStripedLockFile(versionDir)

      Files.createDirectories(entryPaths.payloadFile.parent)
      Files.writeString(entryPaths.payloadFile, "payload")
      Files.write(metadataFile, byteArrayOf(1))
      Files.setLastModifiedTime(metadataFile, FileTime.fromMillis(System.currentTimeMillis()))

      manager.cleanup()

      assertThat(Files.exists(lockFile)).isFalse()
      assertThat(Files.exists(entryPaths.payloadFile)).isTrue()
      assertThat(Files.exists(markFile)).isFalse()
    }
  }

  @Test
  fun `cache hit copies payload to target when cache is not target`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = false)
      val sources = createSources()

      val firstTarget = tempDir.resolve("out/first.jar")
      val secondTarget = tempDir.resolve("out2/first.jar")
      val firstResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = firstTarget,
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )
      val secondResult = manager.computeIfAbsent(
        sources = sources,
        targetFile = secondTarget,
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val payloadFile = findSingleEntryPaths(cacheDir).payloadFile
      assertThat(firstResult).isEqualTo(firstTarget)
      assertThat(secondResult).isEqualTo(secondTarget)
      assertThat(Files.readString(firstTarget)).isEqualTo("payload")
      assertThat(Files.readString(secondTarget)).isEqualTo("payload")
      assertThat(Files.readString(payloadFile)).isEqualTo("payload")
      assertThat(Files.exists(payloadFile)).isTrue()
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `cache hit replaces existing target when cache is not target`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = false)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val existingTarget = tempDir.resolve("out2/first.jar")
      Files.createDirectories(existingTarget.parent)
      Files.writeString(existingTarget, "stale")

      val result = manager.computeIfAbsent(
        sources = sources,
        targetFile = existingTarget,
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val payloadFile = findSingleEntryPaths(cacheDir).payloadFile
      assertThat(result).isEqualTo(existingTarget)
      assertThat(Files.readString(existingTarget)).isEqualTo("payload")
      assertThat(Files.readString(payloadFile)).isEqualTo("payload")
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  @Test
  fun `cache file name includes target file name`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      val payloadFile = manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/first.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      assertThat(produceCalls.get()).isEqualTo(1)
      assertThat(Files.exists(payloadFile)).isTrue()
      assertThat(payloadFile.fileName.toString()).contains("__first.jar")
    }
  }

  @Test
  fun `cache entry and sidecar names fit common filesystem file name limit`() {
    runBlocking {
      val cacheDir = tempDir.resolve("cache")
      val manager = createManager(cacheDir = cacheDir, maxAccessTimeAge = 30.days)
      val produceCalls = AtomicInteger()
      val builder = TestSourceBuilder(produceCalls = produceCalls, useCacheAsTargetFile = true)
      val sources = createSources()

      manager.computeIfAbsent(
        sources = sources,
        targetFile = tempDir.resolve("out/${"a".repeat(2000)}.jar"),
        nativeFiles = null,
        span = Span.getInvalid(),
        producer = builder,
      )

      val entryPaths = findSingleEntryPaths(cacheDir)
      assertThat(entryPaths.payloadFile.fileName.toString().length).isLessThanOrEqualTo(255)
      assertThat(entryPaths.metadataFile.fileName.toString().length).isLessThanOrEqualTo(255)
      assertThat(entryPaths.markFile.fileName.toString().length).isLessThanOrEqualTo(255)
      assertThat(produceCalls.get()).isEqualTo(1)
    }
  }

  private fun createManager(cacheDir: Path, maxAccessTimeAge: Duration): LocalDiskJarCacheManager {
    return LocalDiskJarCacheManager(
      cacheDir = cacheDir,
      productionClassOutDir = tempDir.resolve("classes/production"),
      maxAccessTimeAge = maxAccessTimeAge,
    )
  }

  private fun createSources(): List<Source> {
    return listOf(InMemoryContentSource("a.txt", "hello".encodeToByteArray()))
  }

  private data class TestEntryPaths(
    @JvmField val entryStem: String,
    @JvmField val payloadFile: Path,
    @JvmField val metadataFile: Path,
    @JvmField val markFile: Path,
  )

  private val metadataFileSuffixForTests = ".meta"
  private val markedForCleanupFileSuffixForTests = ".mark"
  private val entryNameSeparatorForTests = "__"

  private fun findSingleEntryPaths(cacheDir: Path): TestEntryPaths {
    return listEntryPaths(cacheDir).single()
  }

  private fun listEntryPaths(cacheDir: Path): List<TestEntryPaths> {
    val versionDir = findVersionDir(cacheDir)
    val entriesDir = versionDir.resolve("entries")
    if (Files.notExists(entriesDir)) {
      return emptyList()
    }

    val result = mutableListOf<TestEntryPaths>()
    for (shardDir in listDirectories(entriesDir)) {
      Files.newDirectoryStream(shardDir).use { files ->
        for (file in files) {
          if (!Files.isRegularFile(file)) {
            continue
          }

          val fileName = file.fileName.toString()
          if (fileName.endsWith(metadataFileSuffixForTests) || fileName.endsWith(markedForCleanupFileSuffixForTests)) {
            continue
          }

          if (parseKeyFromEntryStem(fileName) == null) {
            continue
          }

          result.add(buildEntryPathsFromStem(shardDir, fileName))
        }
      }
    }
    return result.sortedBy { it.entryStem }
  }

  private fun buildEntryPathsFromStem(shardDir: Path, entryStem: String): TestEntryPaths {
    return TestEntryPaths(
      entryStem = entryStem,
      payloadFile = shardDir.resolve(entryStem),
      metadataFile = shardDir.resolve(entryStem + metadataFileSuffixForTests),
      markFile = shardDir.resolve(entryStem + markedForCleanupFileSuffixForTests),
    )
  }

  private fun parseKeyFromEntryStem(entryStem: String): String? {
    val separatorIndex = entryStem.indexOf(entryNameSeparatorForTests)
    if (separatorIndex <= 0) {
      return null
    }
    return entryStem.substring(0, separatorIndex)
  }

  private fun getStripedLockFile(versionDir: Path): Path {
    return versionDir.resolve("striped-lock-slots.lck")
  }

  private fun findVersionDir(cacheDir: Path): Path {
    return listDirectories(cacheDir).single { it.fileName.toString().matches(Regex("v\\d+")) }
  }

  private fun listDirectories(parent: Path): List<Path> {
    if (Files.notExists(parent)) {
      return emptyList()
    }

    return Files.newDirectoryStream(parent).use { stream ->
      stream.filterTo(mutableListOf()) { Files.isDirectory(it) }
    }
  }

  private class TestSourceBuilder(
    private val produceCalls: AtomicInteger,
    override val useCacheAsTargetFile: Boolean,
    private val produceDelayMs: Long = 0,
  ) : SourceBuilder {
    override fun updateDigest(digest: HashStream64) {
      digest.putString("test")
    }

    override suspend fun produce(targetFile: Path) {
      produceCalls.incrementAndGet()
      if (produceDelayMs > 0) {
        delay(produceDelayMs)
      }
      Files.createDirectories(targetFile.parent)
      Files.writeString(targetFile, "payload")
    }

    override fun consumeInfo(source: Source, size: Int, hash: Long) {
    }
  }
}
