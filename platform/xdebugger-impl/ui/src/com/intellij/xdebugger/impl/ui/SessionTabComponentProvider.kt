package com.intellij.xdebugger.impl.ui

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.debugger.impl.shared.proxy.XDebugSessionProxy
import org.jetbrains.annotations.ApiStatus
import javax.swing.JComponent


interface SessionTabComponentProvider {
  @ApiStatus.Obsolete
  fun createBottomLocalsComponent(): JComponent;

  @ApiStatus.Internal
  fun createBottomLocalsComponent(session: XDebugSessionProxy): JComponent

  @ApiStatus.Internal
  companion object {
    private val EP_NAME = ExtensionPointName<SessionTabComponentProvider>("com.intellij.xdebugger.debuggerTabCustomizer")
    fun getInstanceSafe(): SessionTabComponentProvider? = EP_NAME.extensionList.singleOrNull()
    fun getInstance(): SessionTabComponentProvider = checkNotNull(getInstanceSafe())
  }
}