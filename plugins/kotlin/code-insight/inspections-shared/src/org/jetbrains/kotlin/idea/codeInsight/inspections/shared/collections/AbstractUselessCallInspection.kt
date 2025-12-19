// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight.inspections.shared.collections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.codeinsight.utils.EmptinessCheckFunctionUtils
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitor
import org.jetbrains.kotlin.psi.KtVisitorVoid


abstract class AbstractUselessCallInspection : AbstractKotlinInspection() {
    protected abstract val conversions: List<ConversionWithFix>

    context(_: KaSession)
    private fun QualifiedExpressionVisitor.suggestConversionIfNeeded(
        expression: KtQualifiedExpression,
        calleeExpression: KtExpression,
        conversion: ConversionWithFix
    ) {
        val descriptor = with(conversion) {
            holder.manager.createConversionProblemDescriptor(expression, calleeExpression, isOnTheFly) ?: return
        }

        holder.registerProblem(descriptor)
    }

    inner class QualifiedExpressionVisitor internal constructor(val holder: ProblemsHolder, val isOnTheFly: Boolean) : KtVisitorVoid() {
        override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
            super.visitQualifiedExpression(expression)
            val selector = expression.selectorExpression as? KtCallExpression ?: return
            val calleeExpression = selector.calleeExpression ?: return
            if (calleeExpression.text !in conversions.map { it.callableId.callableName.asString() }) return

            analyze(calleeExpression) {
                val resolvedCall = calleeExpression.resolveToCall()?.singleFunctionCallOrNull() ?: return
                val callableId = resolvedCall.symbol.callableId ?: return
                val conversion = conversions.firstOrNull { it.callableId == callableId } ?: return
                suggestConversionIfNeeded(expression, calleeExpression, conversion)
            }
        }
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = QualifiedExpressionVisitor(holder, isOnTheFly)

    protected fun KtExpression.isUsingLabelInScope(labelName: String): Boolean {
        var usingLabel = false
        accept(object : KtTreeVisitor<Unit>() {
            override fun visitExpressionWithLabel(expression: KtExpressionWithLabel, data: Unit?): Void? {
                if (expression.getLabelName() == labelName) {
                    usingLabel = true
                }
                return super.visitExpressionWithLabel(expression, data)
            }
        })
        return usingLabel
    }

    protected interface ConversionWithFix {
        val callableId: CallableId

        context(_: KaSession)
        fun InspectionManager.createConversionProblemDescriptor(
            expression: KtQualifiedExpression,
            calleeExpression: KtExpression,
            isOnTheFly: Boolean,
        ): ProblemDescriptor?
    }

    protected companion object {

        fun topLevelCallableId(packagePath: String, functionName: String): CallableId {
            return CallableId(FqName.topLevel(Name.identifier(packagePath)), Name.identifier(functionName))
        }

        fun KtQualifiedExpression.invertSelectorFunction(): KtQualifiedExpression? {
            return EmptinessCheckFunctionUtils.invertFunctionCall(this) as? KtQualifiedExpression
        }
    }
}