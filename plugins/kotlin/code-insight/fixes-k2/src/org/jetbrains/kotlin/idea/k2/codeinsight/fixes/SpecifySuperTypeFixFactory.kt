// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.containers.toMutableSmartList
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.base.analysis.api.utils.shortenReferences
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinApplicatorBasedQuickFix
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.renderer.render

object SpecifySuperTypeFixFactory {

    class TypeStringWithoutArgs(
        val longTypeRepresentation: String,
        @NlsSafe val shortTypeRepresentation: String
    )

    data class Input(
        val superTypes: List<TypeStringWithoutArgs>,
    ) : KotlinApplicatorBasedQuickFix.Input

    private class SpecifySuperTypeQuickFix(
        element: KtSuperExpression,
        input: SpecifySuperTypeFixFactory.Input,
    ) : KotlinApplicatorBasedQuickFix<KtSuperExpression, Input>(element, input) {

        override fun getFamilyName(): String = KotlinBundle.message("intention.name.specify.supertype")

        override fun invoke(
            element: KtSuperExpression,
            input: SpecifySuperTypeFixFactory.Input,
            project: Project,
            editor: Editor?,
        ) {
            editor ?: return

            when (input.superTypes.size) {
                0 -> return
                1 -> element.specifySuperType(input.superTypes.single())
                else -> JBPopupFactory.getInstance()
                    .createListPopup(createListPopupStep(element, input.superTypes))
                    .showInBestPositionFor(editor)
            }
        }
    }

    private fun KtSuperExpression.specifySuperType(superType: TypeStringWithoutArgs) {
        project.executeWriteCommand(KotlinBundle.message("name.specify.supertype.command.title")) {
            val label = this.labelQualifier?.text ?: ""
            val psiFactory = KtPsiFactory(project)
            val replaced = replace(psiFactory.createExpression("super<${superType.longTypeRepresentation}>$label")) as KtSuperExpression
            shortenReferences(replaced)
        }
    }

    private fun createListPopupStep(superExpression: KtSuperExpression, superTypes: List<TypeStringWithoutArgs>): ListPopupStep<*> {
        return object : BaseListPopupStep<TypeStringWithoutArgs>(KotlinBundle.getMessage("popup.title.choose.supertype"), superTypes) {
            override fun isAutoSelectionEnabled() = false

            override fun onChosen(selectedValue: TypeStringWithoutArgs, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    superExpression.specifySuperType(selectedValue)
                }
                return PopupStep.FINAL_CHOICE
            }

            override fun getTextFor(value: TypeStringWithoutArgs): String {
                return value.shortTypeRepresentation
            }
        }
    }

    val ambiguousSuper = KotlinQuickFixFactory.IntentionBased { diagnostic: KaFirDiagnostic.AmbiguousSuper ->
        val candidates = diagnostic.candidates.toMutableSmartList()
        // TODO: the following logic would become unnecessary if feature https://youtrack.jetbrains.com/issue/KT-49314 is accepted because
        //  the candidate would not contain those being removed here.
        candidates.removeAll { superType ->
            candidates.any { otherSuperType ->
                !superType.semanticallyEquals(otherSuperType) && otherSuperType.isSubtypeOf(superType)
            }
        }
        if (candidates.isEmpty()) return@IntentionBased emptyList()
        val superTypes = candidates.mapNotNull { superType ->
            when (superType) {
                is KaErrorType -> null
                is KaClassType ->
                    TypeStringWithoutArgs(superType.classId.asSingleFqName().render(), superType.classId.shortClassName.render())

                else -> error("Expected a class or an error type, but ${superType::class} was found")
            }
        }

        listOf(
            SpecifySuperTypeQuickFix(diagnostic.psi, Input(superTypes)),
        )
    }
}