// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.impl.customization

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.BuildNumber
import com.intellij.platform.ide.customization.ExternalProductResourceUrls
import com.intellij.util.Url
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
@Suppress("DEPRECATION")
class LegacyExternalProductResourceUrls : ExternalProductResourceUrls {
  init {
    thisLogger().warn("""
      |The default implementation LegacyExternalProductResourceUrls is deprecated and will be removed in the future;
      |Provide your own implementation of ExternalProductResourceUrls using data which is currently stored in 
      |<idea.platform.prefix>ApplicationInfo.xml file and register it as the service override.
      |IDEs developed by JetBrains should use BaseJetBrainsExternalProductResourceUrls as a superclass for their implementation.
      |""".trimMargin())
  }

  override val updateMetadataUrl: Url? = null
  override fun computePatchUrl(from: BuildNumber, to: BuildNumber): Url? = null
}
