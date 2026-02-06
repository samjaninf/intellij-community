// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.util

import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.ui.util.popup.PopupItemPresentation
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO

internal object GitLabMergeRequestReviewersUtil {
  suspend fun selectReviewer(
    point: RelativePoint,
    potentialReviewers: Flow<Result<List<GitLabUserDTO>>>,
    avatarIconsProvider: IconsProvider<GitLabUserDTO>,
  ): GitLabUserDTO? {
    return ChooserPopupUtil.showAsyncChooserPopup(
      point,
      potentialReviewers,
      presenter = { reviewer ->
        PopupItemPresentation.Simple(
          reviewer.username,
          avatarIconsProvider.getIcon(reviewer, Avatar.Sizes.BASE),
          reviewer.name,
        )
      }
    )
  }

  suspend fun selectReviewers(
    point: RelativePoint,
    originalReviewers: List<GitLabUserDTO>,
    potentialReviewers: Flow<Result<List<GitLabUserDTO>>>,
    avatarIconsProvider: IconsProvider<GitLabUserDTO>,
  ): List<GitLabUserDTO> {
    val potentialReviewersBatch = flow {
      val batch = potentialReviewers.first()
      emit(batch)
    }
    return ChooserPopupUtil.showAsyncMultipleChooserPopup(
      point,
      originalReviewers,
      potentialReviewersBatch,
      presenter = { reviewer ->
        PopupItemPresentation.Simple(
          reviewer.username,
          avatarIconsProvider.getIcon(reviewer, Avatar.Sizes.BASE),
          reviewer.name,
        )
      }
    )
  }
}