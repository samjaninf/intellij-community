// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.facet

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.openapi.module.Module
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

open class KotlinFacet(
    module: Module,
    name: String,
    configuration: KotlinFacetConfiguration
) : Facet<KotlinFacetConfiguration>(KotlinFacetType.INSTANCE, module, name, configuration, null) {
    companion object {
        fun get(module: Module): KotlinFacet? {
            if (module.isDisposed) return null
            return FacetManager.getInstance(module).getFacetByType(KotlinFacetType.TYPE_ID)
        }
    }
}

// TODO consider using mergedCompilerArguments here also - it includes "additionalArguments" section from Kotlin Facets
fun KotlinCommonCompilerArgumentsHolder.Companion.getInstance(module: Module): CommonCompilerArguments =
    // When the user ticks `useProjectSettings` the facet stays, so we have to check `useProjectSettings` manually
    KotlinFacet.get(module)?.configuration?.settings?.takeUnless { it.useProjectSettings }?.compilerArguments
        ?: getInstance(module.project).settings

@ApiStatus.Internal
fun KotlinCommonCompilerArgumentsHolder.Companion.getMergedCompilerArguments(module: Module): CommonCompilerArguments =
    // When the user ticks `useProjectSettings` the facet stays, so we have to check `useProjectSettings` manually
    KotlinFacet.get(module)?.configuration?.settings?.takeUnless { it.useProjectSettings }?.mergedCompilerArguments
        ?: getInstance(module.project).settings

