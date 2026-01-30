package com.intellij.xdebugger.impl.ui

import com.intellij.platform.debugger.impl.shared.SessionTabComponentProviderShared
import com.intellij.platform.debugger.impl.shared.XDebuggerMonolithAccessPoint
import com.intellij.xdebugger.XDebugProcess
import org.jetbrains.annotations.ApiStatus
import javax.swing.JComponent

@ApiStatus.Internal
@ApiStatus.Experimental
interface XDebugSessionTabCustomizer {
  fun getBottomLocalsComponentProvider(): SessionTabComponentProviderShared? = null

  fun allowFramesViewCustomization(): Boolean = false

  fun getDefaultFramesViewKey(): String? = null

  fun forceShowNewDebuggerUi(): Boolean = false
}

@ApiStatus.Obsolete
interface SessionTabComponentProvider {
  fun createBottomLocalsComponent(): JComponent
}

fun XDebugProcess.allowFramesViewCustomization(): Boolean {
  return (this as? XDebugSessionTabCustomizer)?.allowFramesViewCustomization() ?: false
}

/**
 * Use XDebugProcess.getSessionTabCustomer().getBottomLocalsComponentProvider()
 * If you need to find a session proxy, use XDebugManagerProxy, XDebuggerMonolithAccessPoint
 */
@ApiStatus.Obsolete
fun XDebugProcess.getBottomLocalsComponentProvider(): SessionTabComponentProvider? {
  val newProvider = (this as? XDebugSessionTabCustomizer)?.getBottomLocalsComponentProvider() ?: return null
  return object: SessionTabComponentProvider {
    override fun createBottomLocalsComponent(): JComponent {
      val session = this@getBottomLocalsComponentProvider.session
      val proxy = XDebuggerMonolithAccessPoint.find { it.asProxy(session) } ?: error("Can't find the session proxy")
      return newProvider.createBottomLocalsComponent(proxy)
    }
  }
}

@ApiStatus.Internal
fun XDebugProcess.useSplitterView(): Boolean = getSessionTabCustomer()?.getBottomLocalsComponentProvider() != null


fun XDebugProcess.forceShowNewDebuggerUi(): Boolean {
  return (this as? XDebugSessionTabCustomizer)?.forceShowNewDebuggerUi() ?: false
}

@ApiStatus.Internal
fun XDebugProcess.getDefaultFramesViewKey(): String? {
  return (this as? XDebugSessionTabCustomizer)?.getDefaultFramesViewKey()
}

@ApiStatus.Internal
fun XDebugProcess.getSessionTabCustomer(): XDebugSessionTabCustomizer? {
  return this as? XDebugSessionTabCustomizer
}