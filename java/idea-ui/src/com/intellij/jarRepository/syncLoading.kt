// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.jarRepository

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.impl.libraries.LibraryTableImplUtil
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.util.registry.Registry
import kotlinx.coroutines.*
import org.jetbrains.concurrency.await
import org.jetbrains.idea.maven.utils.library.RepositoryUtils
import java.util.concurrent.TimeUnit

fun loadDependenciesSync(project: Project) {
  runBlocking(JarRepositoryManager.DOWNLOADER_EXECUTOR.asCoroutineDispatcher()) {
    val libs = collectLibrariesToSync(project)
    if (libs.isEmpty()) {
      return@runBlocking
    }

    loadDependenciesSyncImpl(project, libs)
  }
}

internal suspend fun loadDependenciesSyncImpl(project: Project, libs: Set<Library>) {
  val timeout = Registry.intValue("load.maven.dependencies.timeout", 120).toLong()
  try {
    withTimeout(TimeUnit.MINUTES.toMillis(timeout)) {
      submitLoadJobs(project = project, libs = libs, scope = this)
    }
  }
  catch (e: TimeoutCancellationException) {
    logger<JarRepositoryManager>().error("Cant resolve maven dependencies within $timeout minutes")
  }
}

private fun submitLoadJobs(project: Project, libs: Collection<Library>, scope: CoroutineScope) {
  for (library in libs) {
    scope.launch {
      if (LibraryTableImplUtil.isValidLibrary(library)) {
        try {
          RepositoryUtils.reloadDependencies(project, library as LibraryEx).await()
        }
        catch (e: Throwable) {
          thisLogger().error(e)
        }
      }
    }
  }
}
