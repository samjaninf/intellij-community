// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.chat

import com.intellij.testFramework.LightVirtualFile

internal class CodexChatVirtualFile(
  val projectPath: String,
  val threadId: String,
  val threadTitle: String,
  val subAgentId: String?,
) : LightVirtualFile(resolveFileName(threadTitle)) {
  init {
    fileType = CodexChatFileType
    isWritable = false
  }

  fun matches(threadId: String, subAgentId: String?): Boolean {
    return this.threadId == threadId && this.subAgentId == subAgentId
  }
}

private fun resolveFileName(threadTitle: String): String {
  return threadTitle.takeIf { it.isNotBlank() } ?: CodexChatBundle.message("chat.filetype.name")
}
