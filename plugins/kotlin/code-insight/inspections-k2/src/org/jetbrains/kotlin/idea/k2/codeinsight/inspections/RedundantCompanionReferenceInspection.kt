// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.codeinsight.inspections

import com.intellij.codeInspection.CleanupLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.idea.base.analysis.api.utils.canBeRedundantCompanionReference
import org.jetbrains.kotlin.idea.base.analysis.api.utils.deleteReferenceFromQualifiedExpression
import org.jetbrains.kotlin.idea.base.analysis.api.utils.isRedundantCompanionReference
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.asUnit
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinApplicableInspectionBase
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtVisitor
import org.jetbrains.kotlin.psi.KtVisitorVoid

class RedundantCompanionReferenceInspection : KotlinApplicableInspectionBase.Simple<KtReferenceExpression, Unit>(), CleanupLocalInspectionTool {
    override fun getProblemDescription(
        element: KtReferenceExpression,
        context: Unit
    ): @InspectionMessage String = KotlinBundle.message("redundant.companion.reference")

    override fun createQuickFix(
        element: KtReferenceExpression,
        context: Unit
    ): KotlinModCommandQuickFix<KtReferenceExpression> = RemoveRedundantCompanionReferenceFix()

    override fun KaSession.prepareContext(element: KtReferenceExpression): Unit? {
        return element.isRedundantCompanionReference().asUnit
    }

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): KtVisitor<*, *> = object : KtVisitorVoid() {

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            visitTargetElement(expression, holder, isOnTheFly)
        }
    }

    override fun isApplicableByPsi(element: KtReferenceExpression): Boolean {
        return element.canBeRedundantCompanionReference()
    }

    private class RemoveRedundantCompanionReferenceFix : KotlinModCommandQuickFix<KtReferenceExpression>() {

        override fun getFamilyName() = KotlinBundle.message("remove.redundant.companion.reference.fix.text")

        override fun applyFix(
            project: Project,
            element: KtReferenceExpression,
            updater: ModPsiUpdater
        ) {
            element.deleteReferenceFromQualifiedExpression()
        }
    }

}