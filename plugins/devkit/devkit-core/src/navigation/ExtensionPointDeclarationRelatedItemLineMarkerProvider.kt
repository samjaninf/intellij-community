// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.KeyedExtensionCollector
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.annotations.NonNls
import org.jetbrains.idea.devkit.dom.index.ExtensionPointIndex
import org.jetbrains.idea.devkit.util.ExtensionPointCandidate
import org.jetbrains.idea.devkit.util.PluginRelatedLocatorsUtils
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.sourcePsiElement

/**
 * Provides gutter icon for EP code declaration to matching `<extensionPoint>` in `plugin.xml`.
 */
internal class ExtensionPointDeclarationRelatedItemLineMarkerProvider : DevkitRelatedLineMarkerProviderBase() {
  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>?>) {
    val uElement = getUParentForIdentifier(element)
    if (uElement is UField) {
      if (!isExtensionPointNameDeclarationField(uElement)) return
      process(resolveEpFqn(uElement), uElement, element.getProject(), result)
    } else if (uElement is UCallExpression) {
      if (!isExtensionPointNameDeclarationViaSuperCall(uElement)) return

      val uDeclaration = checkNotNull(uElement.getParentOfType(UDeclaration::class.java)) { uElement.asSourceString() }
      process(resolveEpFqn(uElement), uDeclaration, element.getProject(), result)
    }
  }

  private fun process(
    @NonNls epFqn: @NonNls String?,
    uDeclaration: UDeclaration,
    project: Project,
    result: MutableCollection<in RelatedItemLineMarkerInfo<*>?>
  ) {
    if (epFqn == null) return

    val point = ExtensionPointIndex.findExtensionPoint(project, PluginRelatedLocatorsUtils.getCandidatesScope(project), epFqn)
    if (point == null) return

    val identifier = uDeclaration.uastAnchor.sourcePsiElement
    if (identifier == null) return

    val candidate = ExtensionPointCandidate(SmartPointerManager.createPointer(point.getXmlTag()), epFqn)
    val info = LineMarkerInfoHelper.createExtensionPointLineMarkerInfo(mutableListOf<ExtensionPointCandidate?>(candidate), identifier)
    result.add(info)
  }

  private fun isExtensionPointNameDeclarationViaSuperCall(uCallExpression: UCallExpression): Boolean {
    if (uCallExpression.valueArgumentCount != 1) return false
    if (uCallExpression.kind !== UastCallKind.CONSTRUCTOR_CALL) {
      if (uCallExpression.kind !== UastCallKind.METHOD_CALL && uCallExpression.methodName != "super") {
        return false
      }
    }

    // Kotlin EP_NAME field with CTOR call -> handled by UField branch
    if (uCallExpression.getParentOfType(UField::class.java) != null) {
      return false
    }

    val resolvedMethod = uCallExpression.resolve()
    if (resolvedMethod == null) return false
    if (!resolvedMethod.isConstructor()) return false
    return InheritanceUtil.isInheritor(resolvedMethod.getContainingClass(), KeyedExtensionCollector::class.java.name)
  }


  @NonNls
  private fun resolveEpFqn(uCallExpression: UCallExpression): @NonNls String? {
    val uParameter = uCallExpression.getArgumentForParameter(0)
    if (uParameter == null) return null
    return uParameter.evaluateString()
  }

  @NonNls
  private fun resolveEpFqn(uField: UField): @NonNls String? {
    val initializer = uField.uastInitializer

    var epNameExpression: UExpression? = null
    if (initializer is UCallExpression) {
      epNameExpression = initializer.getArgumentForParameter(0)
    } else if (initializer is UQualifiedReferenceExpression) {
      val selector = initializer.selector

      if (selector !is UCallExpression) return null
      epNameExpression = selector.getArgumentForParameter(0)
    }
    if (epNameExpression == null) return null

    if (epNameExpression is UInjectionHost) {
      return epNameExpression.evaluateToString()
    }
    // constants
    return epNameExpression.evaluateString()
  }

  private fun isExtensionPointNameDeclarationField(uField: UField): Boolean {
    if (!uField.isFinal) {
      return false
    }

    val initializer = uField.uastInitializer
    if (initializer !is UCallExpression && initializer !is UQualifiedReferenceExpression) {
      return false
    }

    val fieldClass = PsiTypesUtil.getPsiClass(uField.getType())
    if (fieldClass == null) {
      return false
    }

    val qualifiedClassName = fieldClass.getQualifiedName()
    return ExtensionPointName::class.java.name == qualifiedClassName ||
      ProjectExtensionPointName::class.java.name == qualifiedClassName ||
      InheritanceUtil.isInheritor(fieldClass, false, KeyedExtensionCollector::class.java.name)
  }
}
