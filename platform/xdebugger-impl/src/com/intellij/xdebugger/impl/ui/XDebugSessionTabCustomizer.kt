package com.intellij.xdebugger.impl.ui

import com.intellij.platform.debugger.impl.shared.SessionTabComponentProvider
import com.intellij.xdebugger.XDebugProcess
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
@ApiStatus.Experimental
interface XDebugSessionTabCustomizer {
  fun getBottomLocalsComponentProvider(): SessionTabComponentProvider? = null

  fun allowFramesViewCustomization(): Boolean = false

  fun getDefaultFramesViewKey(): String? = null

  fun forceShowNewDebuggerUi(): Boolean = false
}

fun XDebugProcess.allowFramesViewCustomization(): Boolean {
  return (this as? XDebugSessionTabCustomizer)?.allowFramesViewCustomization() ?: false
}

fun XDebugProcess.getBottomLocalsComponentProvider(): SessionTabComponentProvider? {
  return (this as? XDebugSessionTabCustomizer)?.getBottomLocalsComponentProvider()
}

@ApiStatus.Internal
fun XDebugProcess.useSplitterView(): Boolean = getBottomLocalsComponentProvider() != null


fun XDebugProcess.forceShowNewDebuggerUi(): Boolean {
  return (this as? XDebugSessionTabCustomizer)?.forceShowNewDebuggerUi() ?: false
}

@ApiStatus.Internal
fun XDebugProcess.getDefaultFramesViewKey(): String? {
  return (this as? XDebugSessionTabCustomizer)?.getDefaultFramesViewKey()
}