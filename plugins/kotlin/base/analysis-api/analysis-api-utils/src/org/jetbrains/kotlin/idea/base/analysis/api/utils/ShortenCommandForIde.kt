// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.analysis.api.utils

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.components.ShortenCommand

/**
 * An IDE-specific version of [ShortenCommand] with a possibility to extend and add aditional properties.
 */
@OptIn(KaImplementationDetail::class)
@ApiStatus.Internal
interface ShortenCommandForIde : ShortenCommand

@OptIn(KaImplementationDetail::class)
internal class ShortenCommandForIdeImpl(command: ShortenCommand) : ShortenCommandForIde, ShortenCommand by command

internal fun ShortenCommand.toIdeCommand(): ShortenCommandForIde = ShortenCommandForIdeImpl(this)
