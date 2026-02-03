// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.chat

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

const val CODEX_CHAT_BUNDLE: @NonNls String = "messages.CodexChatBundle"

internal object CodexChatBundle {
  private val BUNDLE = DynamicBundle(CodexChatBundle::class.java, CODEX_CHAT_BUNDLE)

  fun message(key: @PropertyKey(resourceBundle = CODEX_CHAT_BUNDLE) String, vararg params: Any): @Nls String {
    return BUNDLE.getMessage(key, *params)
  }
}
