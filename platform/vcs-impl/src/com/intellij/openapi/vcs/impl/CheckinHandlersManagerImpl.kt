// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.impl

import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.checkin.BaseCheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory

class CheckinHandlersManagerImpl : CheckinHandlersManager() {
  override fun getRegisteredCheckinHandlerFactories(vcses: Array<AbstractVcs>): List<BaseCheckinHandlerFactory> {
    val vcsesKeys = vcses.mapTo(HashSet()) { it.keyInstanceMethod }
    return ArrayList<BaseCheckinHandlerFactory>().also { builder ->
      VcsCheckinHandlerFactory.EP_NAME.extensionList.filterTo(builder) { it.key in vcsesKeys }
      builder.addAll(CheckinHandlerFactory.EP_NAME.extensionList)
    }
  }
}
