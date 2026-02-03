// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
@Suppress("unused")
class CodexChatEditorService(private val project: Project) {
  fun openChat(
    projectPath: String,
    threadId: String,
    threadTitle: String,
    subAgentId: String?,
  ) {
    val manager = FileEditorManager.getInstance(project)
    val existing = findExistingChat(manager.openFiles, threadId, subAgentId)
    val file = existing ?: CodexChatVirtualFile(
      projectPath = projectPath,
      threadId = threadId,
      threadTitle = threadTitle,
      subAgentId = subAgentId,
    )
    manager.openFile(file, true)
  }

  private fun findExistingChat(openFiles: Array<VirtualFile>, threadId: String, subAgentId: String?): CodexChatVirtualFile? {
    return openFiles
      .filterIsInstance<CodexChatVirtualFile>()
      .firstOrNull { it.matches(threadId, subAgentId) }
  }
}
