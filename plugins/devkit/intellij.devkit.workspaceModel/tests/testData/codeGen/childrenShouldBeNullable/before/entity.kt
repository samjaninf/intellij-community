package com.intellij.workspaceModel.test.api

import com.intellij.platform.workspace.storage.WorkspaceEntity

interface MainEntity : WorkspaceEntity {
  val property: String
  val isValid: Boolean
  val secondaryEntity: SecondaryEntity
}


interface SecondaryEntity : WorkspaceEntity {
  val name: String
  val version: Int

  @Parent val mainEntity: MainEntity
}
