// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.add

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.Nls
import java.awt.Component
import javax.swing.Icon

/**
 * Represents the view for adding new Python SDK. It is used in
 * [PyAddSdkDialog].
 *
 * If you need to be notified when this view is closed, implement [com.intellij.openapi.Disposable]
 */
interface PyAddSdkView {
  val panelName: String
    @Nls(capitalization = Nls.Capitalization.Title) get

  val icon: Icon

  /**
   * Returns the created sdk after closing [PyAddSdkDialog]. The method may
   * return `null` if the dialog was closed or cancelled or if the creation
   * failed.
   *
   * The creation of the sdk may occur either in this method or in the
   * [complete] method a while back.
   */
  fun getOrCreateSdk(): Sdk?

  fun onSelected(): Unit = Unit

  /**
   * The [component] *might* return the new [Component] after [next] or
   * [previous].
   */
  val component: Component

  /**
   * Completes SDK creation.
   *
   * The method is called by [PyAddSdkDialog] when *OK* or *Finish* button is
   * pressed.
   *
   * The method may attempt to create the SDK and throw an [Exception] if some
   * error during the creation is occurred. The created SDK could be later
   * obtained by [getOrCreateSdk] method.
   *
   * If the method throws an [Exception] the error message is shown to the user
   * and [PyAddSdkDialog] is not closed.
   *
   * @throws Exception if SDK creation failed for some reason
   */
  fun complete(): Unit = Unit

  /**
   * Returns the list of validation errors. The returned list is empty if there
   * are no errors found.
   *
   * @see com.intellij.openapi.ui.DialogWrapper.doValidateAll
   */
  @RequiresEdt
  fun validateAll(): List<ValidationInfo>
}