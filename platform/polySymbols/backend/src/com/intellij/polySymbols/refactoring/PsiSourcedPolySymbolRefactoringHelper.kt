// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.refactoring

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.polySymbols.search.PsiSourcedPolySymbolReference
import com.intellij.polySymbols.search.PsiSourcedPolySymbolReference.RenameHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringHelper
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo

private class PsiSourcedPolySymbolRefactoringHelper : RefactoringHelper<List<RenameHandler>> {
  override fun prepareOperation(usages: Array<out UsageInfo>, elements: List<PsiElement>): List<RenameHandler> =
    usages.mapNotNull { (it.reference as? PsiSourcedPolySymbolReference)?.createRenameHandler() }

  override fun performOperation(project: Project, operationData: List<RenameHandler>?) {
    if (operationData.isNullOrEmpty()) return
    WriteAction.run<Throwable> {
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      RenameUtil.renameNonCodeUsages(project, operationData.mapNotNull { it.handleRename() }.toTypedArray())
    }
  }

}