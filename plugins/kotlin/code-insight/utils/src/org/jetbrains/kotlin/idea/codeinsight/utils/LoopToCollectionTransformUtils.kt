// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeinsight.utils

import com.intellij.openapi.project.Project
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.canPlaceAfterSimpleNameEntry

/**
 * Utility functions for transforming index-based loops to collection loops.
 */
@ApiStatus.Internal
object LoopToCollectionTransformUtils {

    /**
     * Transforms an index-based loop to a collection-based loop by:
     * 1. Replacing the loop parameter with the provided element name
     * 2. Replacing all array access expressions with direct element references
     * 3. Replacing the loop range with the collection expression
     *
     * @param project the current project
     * @param arrayAccesses array access expressions to replace with element references
     * @param loopParameter the original loop parameter
     * @param loopRange the original loop range
     * @param newLoopRange the new collection expression to iterate over
     * @param elementName the name for the new loop variable (should be pre-validated to avoid conflicts)
     */
    fun transformLoop(
        project: Project,
        arrayAccesses: List<KtArrayAccessExpression>,
        loopParameter: KtParameter,
        loopRange: KtExpression,
        newLoopRange: KtExpression,
        elementName: String
    ) {
        val factory = KtPsiFactory(project)

        val newParameter = factory.createLoopParameter(elementName)

        // Replace all array access expressions with direct element references
        arrayAccesses.forEach { arrayAccess ->
            replaceArrayAccessWithElement(factory, arrayAccess, elementName)
        }

        loopParameter.replace(newParameter)
        loopRange.replace(newLoopRange)
    }

    /**
     * Replaces an array access expression with a simple element reference.
     * When inside a string template ${...}, creates the simplified $name form directly if possible.
     */
    private fun replaceArrayAccessWithElement(factory: KtPsiFactory, arrayAccess: KtArrayAccessExpression, elementName: String) {
        val blockEntry = arrayAccess.parent as? KtBlockStringTemplateEntry
        if (blockEntry != null && canPlaceAfterSimpleNameEntry(blockEntry.nextSibling)) {
            blockEntry.replace(factory.createSimpleNameStringTemplateEntry(elementName))
        } else {
            arrayAccess.replace(factory.createExpression(elementName))
        }
    }

    /**
     * Transforms an index-based loop to a withIndex() loop by:
     * 1. Replacing the loop parameter with a destructuring declaration (indexName, elementName)
     * 2. Replacing array access expressions with direct element references
     * 3. Replacing the loop range with collection.withIndex()
     * @param project the current project
     * @param arrayAccesses array access expressions to replace with element references
     * @param loopParameter the original loop parameter (the index variable)
     * @param loopRange the original loop range
     * @param collectionExpression the collection to iterate with withIndex()
     * @param elementName the name for the new element variable
     */
    fun transformLoopWithIndex(
        project: Project,
        arrayAccesses: List<KtArrayAccessExpression>,
        loopParameter: KtParameter,
        loopRange: KtExpression,
        collectionExpression: KtExpression,
        elementName: String
    ) {
        val factory = KtPsiFactory(project)

        val indexName = loopParameter.name ?: "index"
        val newParameter = factory.createDestructuringParameter("($indexName, $elementName)")
        val newLoopRange = factory.createExpressionByPattern("$0.withIndex()", collectionExpression)

        // Replace all array access expressions with direct element references
        arrayAccesses.forEach { arrayAccess ->
            replaceArrayAccessWithElement(factory, arrayAccess, elementName)
        }

        loopParameter.replace(newParameter)
        loopRange.replace(newLoopRange)
    }
}