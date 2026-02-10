// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.core.script.k2.modules

import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.workspace.storage.SymbolicEntityId
import com.intellij.platform.workspace.storage.WorkspaceEntityWithSymbolicId

interface ScriptCompilationConfigurationEntity : WorkspaceEntityWithSymbolicId {
    val bytes: ByteArray

    override val symbolicId: ScriptCompilationConfigurationEntityId
        get() = ScriptCompilationConfigurationEntityId(bytes)
}

data class ScriptCompilationConfigurationEntityId(private val bytes: ByteArray) : SymbolicEntityId<ScriptCompilationConfigurationEntity> {
    private val hash = bytes.contentHashCode()

    override val presentableName: @NlsSafe String =
        "ByteString[size=${bytes.size}, hash=$hash]"

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        other is ScriptCompilationConfigurationEntityId && bytes.contentEquals(other.bytes)
}