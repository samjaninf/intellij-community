// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal

import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.AppConsoleFontOptions
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions
import com.intellij.openapi.editor.colors.impl.AppFontOptions
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl

@State(
  name = "TerminalFontOptions",
  storages = [Storage("terminal-font.xml")],
)
internal class TerminalFontOptions : AppFontOptions<PersistentTerminalFontPreferences>() {
  companion object {
    @JvmStatic fun getInstance(): TerminalFontOptions = service<TerminalFontOptions>()
  }

  fun getTerminalFontSettings(): TerminalFontSettings {
    val preferences = fontPreferences
    return TerminalFontSettings(
      fontFamily = preferences.fontFamily,
      fontSize = preferences.getSize2D(preferences.fontFamily),
      lineSpacing = preferences.lineSpacing,
      columnSpacing = state.COLUMN_SPACING,
    )
  }

  fun setTerminalFontSettings(preferences: TerminalFontSettings) {
    val newPreferences = FontPreferencesImpl()
    // start with the console preferences as the default
    AppConsoleFontOptions.getInstance().fontPreferences.copyTo(newPreferences)
    // then overwrite the subset that the terminal settings provide
    newPreferences.clearFonts()
    newPreferences.addFontFamily(preferences.fontFamily)
    newPreferences.setFontSize(preferences.fontFamily, preferences.fontSize)
    newPreferences.lineSpacing = preferences.lineSpacing
    // then apply the settings that aren't a part of FontPreferences
    state.COLUMN_SPACING = preferences.columnSpacing
    // apply the FontPreferences part, the last line because it invokes incModificationCount()
    update(newPreferences)
  }

  override fun createFontState(fontPreferences: FontPreferences): PersistentTerminalFontPreferences =
    PersistentTerminalFontPreferences(fontPreferences)

  override fun noStateLoaded() {
    // the state is mostly inherited from the console settings
    val defaultState = PersistentTerminalFontPreferences(AppConsoleFontOptions.getInstance().fontPreferences)
    // but it can be changed here later, for example, if we want to change the default line spacing
    loadState(defaultState)
  }
}

// All this weirdness with similar nondescript names like TerminalFontOptions and TerminalFontSettings
// comes from the way the AppFontOptions API is designed.
// We need an implementation of FontPreferences to use in update() and createFontState(),
// but its API is not very extendable and is not very easy to use in the settings GUI.
// Other settings resolve this by using AppFontOptionsPanel, which has a handy getFontPreferences() method,
// but it's rather heavyweight and comes with the color schema and whatnot.
// So we need here a lightweight "mutable preferences" class to use in the settings GUI,
// and we need a state to be persisted, and we need a lot of boilerplate code to convert these things back and forth.
// An alternative would be to drop AppFontOptions completely, but then we wouldn't be able to easily reuse its logic
// and easily grab the defaults from AppConsoleFontOptions.

// To reduce possible confusion, the name TerminalFontSettings was chosen here to make it different
// from FontPreferences and AppFontOptions and PersistentFontPreferences.

internal data class TerminalFontSettings(
  var fontFamily: String,
  var fontSize: Float,
  var lineSpacing: Float,
  var columnSpacing: Float,
)

internal class PersistentTerminalFontPreferences: AppEditorFontOptions.PersistentFontPreferences {
  @Suppress("unused") // for serialization
  constructor(): super()
  constructor(fontPreferences: FontPreferences): super(fontPreferences)

  var COLUMN_SPACING: Float = 1.0f
}
