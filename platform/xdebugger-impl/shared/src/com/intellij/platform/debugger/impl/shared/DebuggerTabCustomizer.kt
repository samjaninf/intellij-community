// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.shared

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.debugger.impl.rpc.XDebugVariablesViewCustomBottomComponentDto
import com.intellij.platform.debugger.impl.shared.proxy.XDebugSessionProxy
import org.jetbrains.annotations.ApiStatus
import javax.swing.JComponent

// TODO: maybe move to frontend?
@ApiStatus.Internal
interface DebuggerTabCustomizer {
  fun createBottomComponentForVariablesView(
    session: XDebugSessionProxy,
    providerDto: XDebugVariablesViewCustomBottomComponentDto,
  ): JComponent

  companion object {
    private val EP_NAME = ExtensionPointName<DebuggerTabCustomizer>("com.intellij.xdebugger.debuggerTabCustomizer")
    fun getInstanceSafe(): DebuggerTabCustomizer? = EP_NAME.extensionList.singleOrNull()
    fun getInstance(): DebuggerTabCustomizer = checkNotNull(getInstanceSafe())
  }
}