// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.shared.folding

import com.intellij.compose.ide.plugin.shared.COMPOSE_MODIFIER_FQN
import com.intellij.compose.ide.plugin.shared.callReturnTypeFqName
import com.intellij.compose.ide.plugin.shared.isAndroidFile
import com.intellij.compose.ide.plugin.shared.isComposableFunction
import com.intellij.compose.ide.plugin.shared.isComposableGetter
import com.intellij.compose.ide.plugin.shared.isComposableLambda
import com.intellij.compose.ide.plugin.shared.isComposableType
import com.intellij.compose.ide.plugin.shared.isComposeEnabledForElementModule
import com.intellij.compose.ide.plugin.shared.isStandardLambda
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitor

/**
 * Adds a folding region for a Modifier chain longer or equal than two.
 *
 * Based on: [com.android.tools.compose.ComposeFoldingBuilder]
 */
@ApiStatus.Internal
abstract class ComposeFoldingBuilder : CustomFoldingBuilder() {
  override fun buildLanguageFoldRegions(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement,
    document: Document,
    quick: Boolean,
  ) {
    if (root !is KtFile || DumbService.isDumb(root.project)) return

    // Do not run on Android modules - this is covered with the Android plugin
    if (isAndroidFile(root)) return

    // Do not run on modules that do not have Compose enabled
    if (!isComposeEnabledForElementModule(root)) return

    root.accept(ComposeFoldingVisitor(descriptors), false)
  }

  private fun KtElement.isModifierChainLongerThanTwo(): Boolean {
    if (this !is KtDotQualifiedExpression) return false
    if (this.receiverExpression !is KtDotQualifiedExpression) return false

    return this.callReturnTypeFqName() == COMPOSE_MODIFIER_FQN
  }

  /** For Modifier.adjust().adjust() -> Modifier.(...) */
  override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String {
    return node.text.substringBefore(".").trim() + ".(...)"
  }

  override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false

  private inner class ComposeFoldingVisitor(
    private val descriptors: MutableList<FoldingDescriptor>
  ) : KtTreeVisitor<Boolean>() {

    override fun visitNamedFunction(function: KtNamedFunction, insideComposable: Boolean): Void?{
      val isComposable = function.isComposableFunction() || function.isComposableType()
      return super.visitNamedFunction(function, isComposable)
    }

    override fun visitProperty(property: KtProperty, insideComposable: Boolean): Void? {
      val isComposable = insideComposable || property.isComposableGetter() || property.isComposableType()
      return super.visitProperty(property, isComposable)
    }

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression, insideComposable: Boolean): Void? {
      val isComposable = when {
        lambdaExpression.isComposableLambda() -> true
        lambdaExpression.isStandardLambda() -> false
        else -> insideComposable
      }
      return super.visitLambdaExpression(lambdaExpression, isComposable)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression, insideComposable: Boolean): Void? {
      if (insideComposable && expression.parent !is KtDotQualifiedExpression && expression.isModifierChainLongerThanTwo()) {
        descriptors.add(FoldingDescriptor(expression.node, expression.node.textRange))
      }
      return super.visitDotQualifiedExpression(expression, insideComposable)
    }
  }
}