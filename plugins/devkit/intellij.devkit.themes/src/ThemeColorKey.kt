// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.themes

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageHandler
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.json.JsonLanguage
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.model.Pointer
import com.intellij.model.SingleTargetReference
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.model.search.LeafOccurrence
import com.intellij.model.search.LeafOccurrenceMapper
import com.intellij.model.search.SearchContext
import com.intellij.model.search.SearchRequest
import com.intellij.model.search.SearchService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.ElementManipulators
import com.intellij.psi.ElementManipulators.getValueTextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.walkUp
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.util.Query

private const val COLORS_BLOCK_KEY = "colors"

internal class ThemeColorKey(
  val colorKey: String,
  val file: PsiFile?,
  val rangeInFile: TextRange?,
) : Symbol, RenameTarget, SearchTarget, NavigationTarget, DocumentationTarget {
  override fun createPointer(): Pointer<ThemeColorKey> = Pointer.hardPointer(this)
  override fun computePresentation(): TargetPresentation = presentation()
  override fun navigationRequest(): NavigationRequest? {
    if (file == null || rangeInFile == null) return null

    return NavigationRequest.sourceNavigationRequest(file, rangeInFile)
  }

  override val usageHandler: UsageHandler
    get() = UsageHandler.createEmptyUsageHandler(colorKey)
  override val targetName: String
    get() = colorKey
  override val maximalSearchScope: SearchScope?
    get() = null

  override fun presentation(): TargetPresentation {
    return TargetPresentation.builder(DevKitThemesBundle.message("theme.color.key", colorKey)).presentation()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ThemeColorKey

    return colorKey == other.colorKey
  }

  override fun hashCode(): Int = colorKey.hashCode()

  override fun toString(): String {
    return "ThemeColorKey('$colorKey')"
  }

  override fun computeDocumentationHint(): @NlsContexts.HintText String {
    return DevKitThemesBundle.message("theme.color.key", colorKey)
  }
}

internal class ThemeColorKeyDeclaration(private val element: JsonProperty, private val symbol: ThemeColorKey) : PsiSymbolDeclaration {
  override fun getDeclaringElement(): PsiElement = element
  override fun getRangeInDeclaringElement(): TextRange {
    val originalRange = element.nameElement.textRangeInParent
    if (originalRange.length < 2) return originalRange

    return originalRange.shiftRight(1).grown(-2) // drop quotes
  }
  override fun getSymbol(): Symbol = symbol
}

internal class ThemeColorKeyReference(
  private val hostElement: JsonStringLiteral,
  val isSoft: Boolean = false,
) : PsiSymbolReference, SingleTargetReference() {

  override fun getElement(): PsiElement = hostElement
  override fun getRangeInElement(): TextRange = getValueTextRange(hostElement)

  override fun resolveSingleTarget(): Symbol? {
    val containingFile = getElement().getContainingFile()
    if (containingFile !is JsonFile) return null

    val colorName = ElementManipulators.getValueText(hostElement)
    if (colorName.isBlank()) return null

    val namedColors = ThemeJsonUtil.getNamedColorsMap(containingFile)
    val color = namedColors[colorName]
    if (color == null) return null

    val targetColorKeyElement = color.declaration.retrieve()
    if (targetColorKeyElement == null) return null

    return ThemeColorKey(colorName, targetColorKeyElement.containingFile, targetColorKeyElement.textRange)
  }
}

internal class ThemeColorKeyDeclarationProvider : PsiSymbolDeclarationProvider {
  override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
    val fileName = element.containingFile?.name ?: return emptyList()
    if (!ThemeJsonUtil.isThemeFilename(fileName)) return emptyList()

    if (element !is JsonProperty) return emptyList()
    val grandParent = element.getParent() ?: return emptyList()

    val greatGrandParent = grandParent.getParent()
    if (greatGrandParent is JsonProperty && greatGrandParent.getName() == COLORS_BLOCK_KEY) {
      return listOf(ThemeColorKeyDeclaration(element, ThemeColorKey(element.name, element.containingFile, element.textRange)))
    }

    return emptyList()
  }
}

internal class ThemeColorKeyReferenceProvider : PsiSymbolReferenceProvider {
  private val COLOR_N_PATTERN: Regex = Regex("Color\\d+")

  override fun getReferences(element: PsiExternalReferenceHost, hints: PsiSymbolReferenceHints): Collection<PsiSymbolReference> {
    val fileName = element.containingFile?.name ?: return emptyList()
    if (!ThemeJsonUtil.isThemeFilename(fileName)) return emptyList()

    if (element !is JsonStringLiteral) return emptyList()
    val parent = element.getParent()

    if (parent !is JsonProperty) return emptyList()
    val name = parent.getName()

    if (parent.getValue() === element) { // inside value of property
      if (ThemeColorAnnotator.isColorCode(element.getValue())) return emptyList()

      val isSoft = isSoftReferenceRequired(name)
      if (isKeyInteresting(name) || isSoft) {
        return listOf(ThemeColorKeyReference(element, isSoft))
      }

      val grandParent: PsiElement? = parent.getParent()
      if (grandParent != null) {
        val greatGrandParent = grandParent.getParent()
        if (greatGrandParent is JsonProperty) {
          val parentName = greatGrandParent.getName()
          if (COLOR_N_PATTERN.matches(parentName)
              || isKeyInteresting(parentName)
              || COLORS_BLOCK_KEY == parentName) {
            return listOf(ThemeColorKeyReference(element))
          }
        }
      }
    }

    return emptyList()
  }

  private fun isSoftReferenceRequired(keyName: String): Boolean {
    return keyName.endsWith("Border")
  }

  private fun isKeyInteresting(name: String): Boolean {
    return name.endsWith("Foreground")
           || name.endsWith("Background")
           || name.endsWith("Color")
           || name.endsWith(".foreground")
           || name.endsWith(".background")
           || name.endsWith("color")
           || "foreground" == name
           || "background" == name
           || COLOR_N_PATTERN.matches(name)
  }

  override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> {
    if (target is ThemeColorKey) {
      return listOf(SearchRequest.of(target.colorKey))
    }
    return emptyList()
  }
}

internal class ThemeColorKeySearcher : UsageSearcher {
  override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? {
    val symbol = parameters.target as? ThemeColorKey ?: return null
    return SearchService.getInstance()
      .searchWord(parameters.project, symbol.colorKey)
      .inContexts(SearchContext.inCode(), SearchContext.inStrings())
      .inScope(parameters.searchScope)
      .caseSensitive(true)
      .inFilesWithLanguageOfKind(JsonLanguage.INSTANCE)
      .buildQuery(LeafOccurrenceMapper.withPointer(symbol.createPointer(), ::findReferencesToSymbol))
  }

  private fun findReferencesToSymbol(symbol: Symbol, leafOccurrence: LeafOccurrence): Collection<PsiUsage> {
    val symbolReferenceService = PsiSymbolReferenceService.getService()
    for ((element, offsetInElement) in walkUp(leafOccurrence.start, leafOccurrence.offsetInStart, leafOccurrence.scope)) {
      if (element !is PsiExternalReferenceHost) continue

      val foundReferences = symbolReferenceService.getReferences(element, PsiSymbolReferenceHints.offsetHint(offsetInElement))
        .asSequence()
        .filterIsInstance<ThemeColorKeyReference>()
        .filter { it.rangeInElement.containsOffset(offsetInElement) }
        .filter { ref -> ref.resolvesTo(symbol) }
        .map { ThemeColorKeyUsage(it.element.containingFile, it.absoluteRange, false) }
        .toList()

      if (foundReferences.isNotEmpty()) return foundReferences
    }
    return emptyList()
  }
}

private class ThemeColorKeyUsage(
  override val file: PsiFile,
  override val range: TextRange,
  override val declaration: Boolean,
) : PsiUsage {
  override fun createPointer(): Pointer<ThemeColorKeyUsage> {
    val declaration = declaration
    return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
      ThemeColorKeyUsage(restoredFile, restoredRange, declaration)
    }
  }
}