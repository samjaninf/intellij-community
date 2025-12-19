// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight.inspections.shared.collections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.isDefinitelyNotNull
import org.jetbrains.kotlin.idea.base.psi.copied
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.utils.callExpression
import org.jetbrains.kotlin.idea.codeinsights.impl.base.asQuickFix
import org.jetbrains.kotlin.idea.quickfix.ReplaceWithDotCallFix
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

// TODO: This class is currently only registered for K2 due to bugs in the
//  K1 implementation of the analysis API.
//  Once it is fixed, it should be used for both K1 and K2.
//  See: KT-65376
class UselessCallOnNotNullInspection : AbstractUselessCallInspection() {
    override val conversions: List<ConversionWithFix> = listOf(
        UselessOrEmptyConversionWithFix(topLevelCallableId("kotlin.collections", "orEmpty"), Conversion.Delete),
        UselessOrEmptyConversionWithFix(topLevelCallableId("kotlin.sequences", "orEmpty"), Conversion.Delete),
        UselessOrEmptyConversionWithFix(topLevelCallableId("kotlin.text", "orEmpty"), Conversion.Delete),

        UselessIsNullOrEmptyConversionWithFix(topLevelCallableId("kotlin.text", "isNullOrEmpty"), Conversion.Replace("isEmpty")),
        UselessIsNullOrEmptyConversionWithFix(topLevelCallableId("kotlin.text", "isNullOrBlank"), Conversion.Replace("isBlank")),
        UselessIsNullOrEmptyConversionWithFix(topLevelCallableId("kotlin.collections", "isNullOrEmpty"), Conversion.Replace("isEmpty"))
    )

    private inner class UselessOrEmptyConversionWithFix(
        override val callableId: CallableId,
        val conversion: Conversion,
    ) : ConversionWithFix {
        context(_: KaSession)
        override fun InspectionManager.createConversionProblemDescriptor(
            expression: KtQualifiedExpression,
            calleeExpression: KtExpression,
            isOnTheFly: Boolean
        ): ProblemDescriptor? {
            val newName = (conversion as? Conversion.Replace)?.replacementName

            val safeExpression = expression as? KtSafeQualifiedExpression
            val notNullType = expression.receiverExpression.isDefinitelyNotNull
            val defaultRange =
                TextRange(expression.operationTokenNode.startOffset, calleeExpression.endOffset).shiftRight(-expression.startOffset)
            return if (newName != null && (notNullType || safeExpression != null)) {
                val fixes = listOfNotNull(
                    createRenameUselessCallFix(expression, newName),
                    safeExpression?.let { LocalQuickFix.from(ReplaceWithDotCallFix(safeExpression)) }
                )
                createProblemDescriptor(
                    expression,
                    defaultRange,
                    KotlinBundle.message("call.on.not.null.type.may.be.reduced"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                    *fixes.toTypedArray()
                )
            } else if (notNullType) {
                createProblemDescriptor(
                    expression,
                    defaultRange,
                    KotlinBundle.message("redundant.call.on.not.null.type"),
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    isOnTheFly,
                    RemoveUselessCallFix()
                )
            } else if (safeExpression != null) {
                createProblemDescriptor(
                    safeExpression.operationTokenNode.psi,
                    KotlinBundle.message("this.call.is.redundant.with"),
                    ReplaceWithDotCallFix(safeExpression).asQuickFix(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                )
            } else {
                null
            }
        }
    }

    private inner class UselessIsNullOrEmptyConversionWithFix(
        override val callableId: CallableId,
        val conversion: Conversion,
    ) : ConversionWithFix {
        context(_: KaSession)
        override fun InspectionManager.createConversionProblemDescriptor(
            expression: KtQualifiedExpression,
            calleeExpression: KtExpression,
            isOnTheFly: Boolean
        ): ProblemDescriptor? {
            val newName = (conversion as? Conversion.Replace)?.replacementName

            val safeExpression = expression as? KtSafeQualifiedExpression
            val notNullType = expression.receiverExpression.isDefinitelyNotNull
            val defaultRange =
                TextRange(expression.operationTokenNode.startOffset, calleeExpression.endOffset).shiftRight(-expression.startOffset)
            return if (newName != null && (notNullType || safeExpression != null)) {
                val fixes = listOfNotNull(
                    createRenameUselessCallFix(expression, newName),
                    safeExpression?.let { LocalQuickFix.from(ReplaceWithDotCallFix(safeExpression)) }
                )
                createProblemDescriptor(
                    expression,
                    defaultRange,
                    KotlinBundle.message("call.on.not.null.type.may.be.reduced"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                    *fixes.toTypedArray()
                )
            } else if (notNullType) {
                createProblemDescriptor(
                    expression,
                    defaultRange,
                    KotlinBundle.message("redundant.call.on.not.null.type"),
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    isOnTheFly,
                    RemoveUselessCallFix()
                )
            } else if (safeExpression != null) {
                createProblemDescriptor(
                    safeExpression.operationTokenNode.psi,
                    KotlinBundle.message("this.call.is.redundant.with"),
                    ReplaceWithDotCallFix(safeExpression).asQuickFix(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                )
            } else {
                null
            }
        }
    }

    context(_: KaSession)
    override fun InspectionManager.createConversionProblemDescriptor(
        expression: KtQualifiedExpression,
        calleeExpression: KtExpression,
        conversion: Conversion,
        isOnTheFly: Boolean
    ): ProblemDescriptor? {
        val newName = (conversion as? Conversion.Replace)?.replacementName

        val safeExpression = expression as? KtSafeQualifiedExpression
        val notNullType = expression.receiverExpression.isDefinitelyNotNull
        val defaultRange =
            TextRange(expression.operationTokenNode.startOffset, calleeExpression.endOffset).shiftRight(-expression.startOffset)
        return if (newName != null && (notNullType || safeExpression != null)) {
            val fixes = listOfNotNull(
                createRenameUselessCallFix(expression, newName),
                safeExpression?.let { LocalQuickFix.from(ReplaceWithDotCallFix(safeExpression)) }
            )
            createProblemDescriptor(
                expression,
                defaultRange,
                KotlinBundle.message("call.on.not.null.type.may.be.reduced"),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                *fixes.toTypedArray()
            )
        } else if (notNullType) {
            createProblemDescriptor(
                expression,
                defaultRange,
                KotlinBundle.message("redundant.call.on.not.null.type"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                isOnTheFly,
                RemoveUselessCallFix()
            )
        } else if (safeExpression != null) {
            createProblemDescriptor(
                safeExpression.operationTokenNode.psi,
                KotlinBundle.message("this.call.is.redundant.with"),
                ReplaceWithDotCallFix(safeExpression).asQuickFix(),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
            )
        } else {
            null
        }
    }

    context(_: KaSession)
    private fun createRenameUselessCallFix(
        expression: KtQualifiedExpression,
        newFunctionName: String
    ): RenameUselessCallFix? {
        if (expression.isUsingLabelInScope(newFunctionName)) {
            return null
        }
        val nonInvertedFix = RenameUselessCallFix(newFunctionName, invert = false)
        if (expression.parent.safeAs<KtPrefixExpression>()?.operationToken != KtTokens.EXCL) {
            return nonInvertedFix
        }
        val copiedExpression = expression.copied().apply {
            callExpression?.calleeExpression?.replace(KtPsiFactory(expression.project).createExpression(newFunctionName))
        }
        val codeFragment = KtPsiFactory(expression.project).createExpressionCodeFragment(copiedExpression.text, expression)
        val contentElement = codeFragment.getContentElement() as? KtQualifiedExpression ?: return nonInvertedFix
        // After changing to the inverted name, we make sure that if the function is inverted, we are calling the correct function.
        // (Relevant if, for example, a different List.isEmpty() is already defined in the same scope, we do not want to use it)
        val invertedName = contentElement.invertSelectorFunction()?.callExpression?.calleeExpression?.text
        return if (invertedName != null && !expression.isUsingLabelInScope(invertedName)) {
            RenameUselessCallFix(invertedName, invert = true)
        } else {
            nonInvertedFix
        }
    }
}