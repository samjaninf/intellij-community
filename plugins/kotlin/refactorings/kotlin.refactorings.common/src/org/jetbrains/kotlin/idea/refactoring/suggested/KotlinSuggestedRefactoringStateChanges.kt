// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.refactoring.suggested

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringStateChanges
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport.Parameter
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport.Signature
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.children

class KotlinSuggestedRefactoringStateChanges(refactoringSupport: SuggestedRefactoringSupport) :
    SuggestedRefactoringStateChanges(refactoringSupport)
{
    override fun createInitialState(anchor: PsiElement): SuggestedRefactoringState? {
        anchor as KtDeclaration
        if (anchor.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null // currently not supported
        if (anchor.hasModifier(KtTokens.ACTUAL_KEYWORD)) return null // currently not supported
        val state = super.createInitialState(anchor) ?: return null
        if (isDuplicate(anchor, state.oldSignature)) return null
        return state
    }

    private fun isDuplicate(declaration: KtDeclaration, signature: Signature): Boolean {
        val container = declaration.parent as? KtDeclarationContainer ?: return false
        if (container !is KtFile && container !is KtClassBody) return false
        val name = declaration.name ?: return false
        return when (declaration) {
            is KtFunction -> {
                container.declarations
                    .filter { it != declaration && it.name == name }
                    .any {
                        val otherSignature = signature(it, null) ?: return@any false
                        areDuplicateSignatures(otherSignature, signature)
                    }
            }

            is KtProperty -> {
                container.declarations.any { it != declaration && it is KtProperty && it.name == name }
            }

            else -> false
        }
    }

    // we can't compare signatures by equals here because it takes into account parameter id's and they will be different in our case
    private fun areDuplicateSignatures(signature1: Signature, signature2: Signature): Boolean {
        if (signature1.name != signature2.name) return false
        if (signature1.type != signature2.type) return false
        if (signature1.parameters.size != signature2.parameters.size) return false
        return signature1.parameters.zip(signature2.parameters).all { (p1, p2) ->
            p1.type == p2.type && p1.name == p2.name
        }
    }

    override fun signature(anchor: PsiElement, prevState: SuggestedRefactoringState?): Signature? {
        anchor as KtDeclaration
        val name = anchor.name ?: return null
        if (anchor !is KtCallableDeclaration || KotlinSuggestedRefactoringSupportBase.isOnlyRenameSupported(anchor)) {
            return Signature.create(name, null, emptyList(), null)
        }

        val parameters = (anchor.modifierList?.contextReceiverList?.contextParameters().orEmpty() + anchor.valueParameters).map { it.extractParameterData() ?: return null }
        val type = anchor.typeReference?.text
        val receiverType = anchor.receiverTypeReference?.text
        val signature = Signature.create(
            name,
            type,
            parameters,
            KotlinSignatureAdditionalData(DeclarationType.fromDeclaration(anchor), receiverType)
        ) ?: return null

        return if (prevState == null) signature else matchParametersWithPrevState(signature, anchor, prevState)
    }

    override fun parameterMarkerRanges(anchor: PsiElement): List<TextRange?> {
        if (anchor !is KtCallableDeclaration) return emptyList()
        return (anchor.modifierList?.contextReceiverList?.contextParameters().orEmpty() + (anchor as? KtFunction)?.valueParameters.orEmpty()).map { it.colon?.textRange }
    }

    private fun KtParameter.extractParameterData(): Parameter? {
        val modifiers = modifierList?.node?.children()
            ?.filter { it.elementType is KtModifierKeywordToken && it.text in modifiersToInclude }
            ?.joinToString(separator = " ") { it.text } ?: ""
        return Parameter(
            Any(),
            name ?: return null,
            typeReference?.text ?: return null,
            KotlinParameterAdditionalData(defaultValue?.text/*TODO: strip comments etc*/, modifiers, isContextParameter)
        )
    }

    private val modifiersToInclude = setOf(KtTokens.VARARG_KEYWORD.value)
}