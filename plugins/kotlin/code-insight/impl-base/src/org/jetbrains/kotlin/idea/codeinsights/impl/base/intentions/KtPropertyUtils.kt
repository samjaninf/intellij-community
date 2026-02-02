// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions

import org.jetbrains.kotlin.psi.KtBackingField
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.allChildren

object KtPropertyUtils {
    fun hasExplicitBackingField(element: KtProperty): Boolean = element.allChildren.find { it is KtBackingField } != null
}