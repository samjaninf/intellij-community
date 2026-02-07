// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.create

import com.intellij.collaboration.async.mapState
import com.intellij.collaboration.ui.LabeledListComponentsFactory
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.list.search.ShowDirection
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO
import org.jetbrains.plugins.gitlab.mergerequest.ui.create.model.GitLabMergeRequestCreateViewModel
import org.jetbrains.plugins.gitlab.mergerequest.util.GitLabMergeRequestChoosersUtil
import org.jetbrains.plugins.gitlab.util.GitLabBundle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

internal object GitLabMergeRequestCreateReviewersComponentFactory {
  fun createReviewersListPanelHandle(
    vm: GitLabMergeRequestCreateViewModel,
  ): Pair<JComponent, JComponent> {
    val label = LabeledListComponentsFactory.createLabelPanel(
      vm.reviewers.mapState { it.isEmpty() },
      GitLabBundle.message("merge.request.create.no.reviewers"),
      GitLabBundle.message("merge.request.create.reviewers")
    )

    val list = LabeledListComponentsFactory.createListPanel(
      vm.reviewers,
      { comp, _ -> chooseReviewers(comp, vm, vm.avatarIconProvider) },
      { UserLabel(it, vm.avatarIconProvider) }
    )

    return label to list
  }

  private suspend fun chooseReviewers(
    parentComponent: JComponent,
    vm: GitLabMergeRequestCreateViewModel,
    avatarIconsProvider: IconsProvider<GitLabUserDTO>,
  ) {
    val point = RelativePoint.getNorthEastOf(parentComponent)
    val allowsMultiple = vm.allowsMultipleReviewers.value
    val currentReviewers = vm.reviewers.value

    val newList = if (allowsMultiple) {
      GitLabMergeRequestChoosersUtil.chooseUsers(point, currentReviewers, vm.projectMembers, avatarIconsProvider, ShowDirection.ABOVE)
    }
    else {
      val reviewer =
        GitLabMergeRequestChoosersUtil.chooseUser(point, vm.projectMembers, avatarIconsProvider, ShowDirection.ABOVE)
      listOfNotNull(reviewer)
    }
    vm.setReviewers(newList)
  }
}

@Suppress("FunctionName")
private fun UserLabel(user: GitLabUserDTO, avatarIconsProvider: IconsProvider<GitLabUserDTO>) =
  JLabel(user.name, avatarIconsProvider.getIcon(user, Avatar.Sizes.BASE), SwingConstants.LEFT).apply {
    border = JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP / 2)
  }
