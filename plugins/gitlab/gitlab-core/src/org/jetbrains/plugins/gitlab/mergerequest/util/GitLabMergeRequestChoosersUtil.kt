// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.util

import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.PopupConfig
import com.intellij.collaboration.ui.codereview.list.search.ShowDirection
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.ui.util.popup.PopupItemPresentation
import com.intellij.collaboration.util.IncrementallyComputedValue
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO

internal object GitLabMergeRequestChoosersUtil {
  suspend fun chooseUser(
    point: RelativePoint,
    users: StateFlow<IncrementallyComputedValue<List<GitLabUserDTO>>>,
    avatarIconsProvider: IconsProvider<GitLabUserDTO>,
    showDirection: ShowDirection = ShowDirection.BELOW,
  ): GitLabUserDTO? =
    ChooserPopupUtil.showChooserPopupWithIncrementalLoading(
      point,
      users,
      presenter = { reviewer ->
        PopupItemPresentation.Simple(
          reviewer.username,
          avatarIconsProvider.getIcon(reviewer, Avatar.Sizes.BASE),
          reviewer.name,
        )
      },
      PopupConfig.DEFAULT.copy(showDirection = showDirection)
    )

  suspend fun chooseUsers(
    point: RelativePoint,
    choseUsers: List<GitLabUserDTO>,
    users: StateFlow<IncrementallyComputedValue<List<GitLabUserDTO>>>,
    avatarIconsProvider: IconsProvider<GitLabUserDTO>,
    showDirection: ShowDirection = ShowDirection.BELOW,
  ): List<GitLabUserDTO> =
    ChooserPopupUtil.showMultipleChooserPopupWithIncrementalLoading(
      point,
      choseUsers,
      users,
      presenter = { reviewer ->
        PopupItemPresentation.Simple(
          reviewer.username,
          avatarIconsProvider.getIcon(reviewer, Avatar.Sizes.BASE),
          reviewer.name,
        )
      },
      PopupConfig.DEFAULT.copy(showDirection = showDirection)
    )
}