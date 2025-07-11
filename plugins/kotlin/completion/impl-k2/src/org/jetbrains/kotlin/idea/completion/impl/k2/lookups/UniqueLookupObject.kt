// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.completion.lookups

import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.completion.api.serialization.SerializableLookupObject

/**
 * This is a temporary hack to prevent clash of the lookup elements with same names.
 */
@Serializable
@ApiStatus.Internal
class UniqueLookupObject : SerializableLookupObject