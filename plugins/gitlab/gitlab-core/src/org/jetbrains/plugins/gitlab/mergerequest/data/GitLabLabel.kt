// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.data

class GitLabLabel(
  val title: String,
  val colorHex: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is GitLabLabel) return false

    if (title != other.title) return false

    return true
  }

  override fun hashCode(): Int {
    return title.hashCode()
  }
}