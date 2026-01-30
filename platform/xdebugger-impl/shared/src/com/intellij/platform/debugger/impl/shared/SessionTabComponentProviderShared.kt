// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.shared

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.debugger.impl.shared.proxy.XDebugSessionProxy
import org.jetbrains.annotations.ApiStatus
import javax.swing.JComponent

@ApiStatus.Internal
interface SessionTabComponentProviderShared {
  fun createBottomLocalsComponent(session: XDebugSessionProxy): JComponent

  companion object {
    private val EP_NAME = ExtensionPointName<SessionTabComponentProviderShared>("com.intellij.xdebugger.debuggerTabCustomizer")
    fun getInstanceSafe(): SessionTabComponentProviderShared? = EP_NAME.extensionList.singleOrNull()
    fun getInstance(): SessionTabComponentProviderShared = checkNotNull(getInstanceSafe())
  }
}