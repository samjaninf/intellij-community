// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal

import com.intellij.ide.util.RunOnceUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.ExperimentalUI.Companion.isNewUI
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.terminal.block.ui.updateFrontendSettingsAndSync

/**
 * Internal logic related to the migration of Experimental 2024 Terminal users to the Reworked Terminal.
 */
@ApiStatus.Internal
object ExperimentalTerminalMigration {
  private const val EXP_ENGINE_OPTION_VISIBLE_REGISTRY = "terminal.new.ui.option.visible"

  fun migrateTerminalEngineOnce(options: TerminalOptionsProvider) {
    RunOnceUtil.runOnceForApp("TerminalOptionsProvider.TerminalEngineMigration.2026.1") {
      updateFrontendSettingsAndSync(options.coroutineScope) {
        if (options.terminalEngine == TerminalEngine.NEW_TERMINAL) {
          options.terminalEngine = TerminalEngine.REWORKED
          // Ensure that the Experimental Terminal option is still visible
          Registry.get(EXP_ENGINE_OPTION_VISIBLE_REGISTRY).setValue(true)
        }
      }
    }
  }

  /**
   * Whether the Experimental 2024 terminal engine option should be visible to user. In the settings, menus, and other places.
   */
  fun isExpTerminalOptionVisible(): Boolean {
    TerminalOptionsProvider.instance // Ensure that all setting migrations are performed
    return isNewUI() && Registry.`is`(EXP_ENGINE_OPTION_VISIBLE_REGISTRY, false)
  }
}