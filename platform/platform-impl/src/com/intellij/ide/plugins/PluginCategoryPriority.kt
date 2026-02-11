// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import org.jetbrains.annotations.ApiStatus

/**
 * Extension point for defining priority categories in the plugin manager.
 * Priority categories are displayed first in the installed plugins list.
 */
@ApiStatus.Internal
interface PluginCategoryPriority {
  /**
   * Returns the name of the priority category.
   * This category will be sorted before other categories.
   */
  fun getPriorityCategoryName(): String
}