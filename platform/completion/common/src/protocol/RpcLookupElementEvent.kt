// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.completion.common.protocol

import com.intellij.openapi.editor.impl.EditorId
import com.intellij.platform.project.ProjectId
import kotlinx.serialization.Serializable

/**
 * Represents an event about showing/hiding a completion lookup and choosing a current completion item in it.
 */
@Serializable
sealed interface RpcLookupElementEvent {
  /**
   * the current item changed in the current lookup
   */
  @Serializable
  data class CurrentItemChanged(
    val requestId: RpcCompletionRequestId,
    val arrangementId: RpcCompletionArrangementId,
    val itemId: RpcCompletionItemId?,
    val itemPattern: String,
    val prefixLength: Int,
    val additionalPrefix: String,
  ) : RpcLookupElementEvent {
    override fun toString(): String = buildToString("SelectedItem") {
      field("requestId", requestId)
      field("arrangementId", arrangementId)
      field("itemId", itemId)
      field("itemPattern", itemPattern)
      field("prefixLength", prefixLength)
      field("additionalPrefix", additionalPrefix)
    }
  }

  /**
   * the lookup is closed without completion
   */
  @Serializable
  data class Cancel(val projectId: ProjectId) : RpcLookupElementEvent {
    override fun toString(): String = buildToString("Cancel") {
      field("projectId", projectId)
    }
  }

  /**
   * the lookup is closed with completion
   */
  @Serializable
  data class ItemSelected(val projectId: ProjectId) : RpcLookupElementEvent {
    override fun toString(): String = buildToString("ItemSelected") {
      field("projectId", projectId)
    }
  }

  @Serializable
  /**
   * the lookup is shown
   */
  data class Show(val projectId: ProjectId, val editor: EditorId) : RpcLookupElementEvent {
    override fun toString(): String = buildToString("Show") {
      field("projectId", projectId)
      field("editor", editor)
    }
  }
}

