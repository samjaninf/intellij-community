// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.analysis.api.utils

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.descendantsOfType
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenOptionsForIde
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

context(_: KaSession)
internal fun collectPossibleCompanionReferenceShortenings(
    file: KtFile,
    selection: TextRange,
    shortenOptions: ShortenOptionsForIde,
): List<KtReferenceExpression> {
    if (!shortenOptions.removeExplicitCompanionReferences) return emptyList()

    return file.descendantsOfType<KtReferenceExpression>()
        .filter { it.canBeRedundantCompanionReference() }
        .filter { it.textRange.intersects(selection) }
        .filter { it.isRedundantCompanionReference() }
        .toList()
}

context(_: KaSession)
internal fun collectPossibleCompanionReferenceShorteningsInElement(
    element: KtElement,
    shortenOptions: ShortenOptionsForIde,
): List<KtReferenceExpression> {
    if (!shortenOptions.removeExplicitCompanionReferences) return emptyList()

    return element.descendantsOfType<KtReferenceExpression>()
        .filter { it.canBeRedundantCompanionReference() }
        .filter { it.isRedundantCompanionReference() }
        .toList()
}

@ApiStatus.Internal
fun KtReferenceExpression.canBeRedundantCompanionReference(): Boolean {
    val element = this

    val parent = element.parent as? KtDotQualifiedExpression ?: return false
    if (parent.getStrictParentOfType<KtImportDirective>() != null) return false
    val grandParent = parent.parent as? KtElement
    val selectorExpression = parent.selectorExpression
    if (element == selectorExpression && grandParent !is KtDotQualifiedExpression) return false
    return element == selectorExpression || element.text != (selectorExpression as? KtNameReferenceExpression)?.text
}

context(_: KaSession)
@ApiStatus.Internal
fun KtReferenceExpression.isRedundantCompanionReference(): Boolean {
    val parent = this.parent as? KtDotQualifiedExpression ?: return false

    val referenceName = this.text

    val symbol = this.mainReference.resolveToSymbol()
    val objectDeclaration =
        if (symbol is KaNamedClassSymbol && symbol.classKind == KaClassKind.COMPANION_OBJECT) {
            // Try to get the PSI for the companion object
            symbol.psi as? KtObjectDeclaration
        } else {
            null
        } ?: return false

    if (referenceName != objectDeclaration.name) return false

    val grandParent = parent.parent as? KtElement
    val selectorExpression = parent.selectorExpression

    val (oldTargetExpression, simplifiedText) = if (grandParent is KtDotQualifiedExpression && this == selectorExpression) {
        grandParent.selectorExpression to (parent.receiverExpression.text + "." + grandParent.selectorExpression?.text)
    } else {
        parent.selectorExpression to parent.selectorExpression!!.text
    }

    val oldTarget = oldTargetExpression?.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol?.symbol?.psi ?: return false
    val fragment = KtPsiFactory(this.project).createExpressionCodeFragment(
        simplifiedText,
        this
    )
    val q = fragment.getContentElement() ?: return false
    return oldTarget == analyze(q) {
        val partiallyAppliedSymbol = q.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
        partiallyAppliedSymbol?.symbol?.psi
    }
}

@ApiStatus.Internal
fun KtReferenceExpression.deleteReferenceFromQualifiedExpression() {
    val parent = this.parent as? KtDotQualifiedExpression ?: return
    val selector = parent.selectorExpression ?: return
    val receiver = parent.receiverExpression
    if (this == receiver) parent.replace(selector) else parent.replace(receiver)
}
