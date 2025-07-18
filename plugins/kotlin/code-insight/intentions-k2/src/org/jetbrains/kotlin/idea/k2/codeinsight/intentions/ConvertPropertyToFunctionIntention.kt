// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.intentions

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.modcommand.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil.htmlEmphasize
import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.CollectAffectedCallablesUtils.getAffectedCallables
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ConvertFunctionToPropertyAndViceVersaUtils.add
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ConvertFunctionToPropertyAndViceVersaUtils.addConflictIfCantRefactor
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ConvertFunctionToPropertyAndViceVersaUtils.findReferencesToElement
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ConvertFunctionToPropertyAndViceVersaUtils.reportDeclarationConflict
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.idea.util.hasJvmFieldAnnotation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.psi.psiUtil.siblings

private data class ElementContext(
    val callables: Collection<PsiElement>,
    val refsToRename: Collection<PsiReference>,
    val kotlinRefsToReplaceWithCall: Collection<KtSimpleNameExpression>,
    val javaRefsToReplaceWithCall: Collection<PsiReferenceExpression>,
    val conflicts: Map<PsiElement, ModShowConflicts.Conflict>,
    val newName: String,
)

class ConvertPropertyToFunctionIntention : PsiBasedModCommandAction<KtProperty>(null, KtProperty::class.java) {

    override fun getFamilyName(): @IntentionFamilyName String =
        KotlinBundle.message("convert.property.to.function")

    override fun getPresentation(context: ActionContext, element: KtProperty): Presentation {
        return Presentation.of(familyName).withPriority(PriorityAction.Priority.LOW)
    }

    override fun isElementApplicable(
        element: KtProperty,
        context: ActionContext,
    ): Boolean = isApplicableByPsi(element, context) && isApplicableByAnalyze(element)

    override fun perform(
        context: ActionContext,
        element: KtProperty,
    ): ModCommand {
        val elementContext = analyze(element) {
            prepareContext(element)
        } ?: return ModCommand.nop()
        return ModCommand
            .showConflicts(elementContext.conflicts)
            .andThen(ModCommand.psiUpdate(element) { e, updater -> convertPropertyToFunction(e.project, elementContext, updater) })
    }

    private fun isApplicableByPsi(
        element: KtProperty,
        context: ActionContext,
    ): Boolean {
        val identifier = element.nameIdentifier ?: return false
        if (!identifier.textRange.containsOffset(context.offset)) return false
        return element.delegate == null
                && !element.isVar
                && !element.isLocal
                && (element.initializer == null || element.getter == null)
                && !element.hasJvmFieldAnnotation()
                && !element.hasModifier(KtTokens.CONST_KEYWORD)
    }

    private fun isApplicableByAnalyze(element: KtProperty): Boolean =
        analyze(element) { prepareContext(element, true) } != null
}

private fun convertPropertyToFunction(
    project: Project,
    elementContext: ElementContext,
    updater: ModPsiUpdater,
) {
    val writableElementContext = getWritable(elementContext, updater)
    val (callables, refsToRename, kotlinRefsToReplaceWithCall, javaRefsToReplaceWithCall, _, newName) = writableElementContext

    val kotlinPsiFactory = KtPsiFactory(project)
    val javaPsiFactory = PsiElementFactory.getInstance(project)
    val newKotlinCallExpr = kotlinPsiFactory.createExpression("$newName()")

    kotlinRefsToReplaceWithCall.forEach { it.replace(newKotlinCallExpr) }
    refsToRename.forEach { it.handleElementRename(newName) }
    javaRefsToReplaceWithCall.forEach {
        val getterRef = it.handleElementRename(newName)
        getterRef.replace(javaPsiFactory.createExpressionFromText("${getterRef.text}()", null))
    }
    callables.forEach {
        when (it) {
            is KtProperty -> convertProperty(it, newName, kotlinPsiFactory)
            is PsiMethod -> it.name = newName
        }
    }
}

private fun getWritable(
    elementContext: ElementContext,
    updater: ModPsiUpdater,
): ElementContext {
    val (callables, refsToRename, kotlinRefsToReplaceWithCall, javaRefsToReplaceWithCall, _, newName) = elementContext
    return ElementContext(
        callables = callables.map(updater::getWritable),
        refsToRename = refsToRename
            .map(PsiReference::getElement)
            .map(updater::getWritable)
            .mapNotNull(PsiElement::getReference),
        kotlinRefsToReplaceWithCall = kotlinRefsToReplaceWithCall.map(updater::getWritable),
        javaRefsToReplaceWithCall = javaRefsToReplaceWithCall.map(updater::getWritable),
        conflicts = emptyMap(),
        newName = newName,
    )
}

private fun convertProperty(
    originalProperty: KtProperty,
    newName: String,
    psiFactory: KtPsiFactory,
) {
    val property = originalProperty.copy() as KtProperty
    val getter = property.getter

    val sampleFunction = psiFactory.createFunction("fun foo() {\n\n}")

    property.valOrVarKeyword.replace(sampleFunction.funKeyword!!)
    property.addAfter(psiFactory.createParameterList("()"), property.nameIdentifier)
    if (property.initializer == null) {
        if (getter != null) {
            val dropGetterTo = (getter.equalsToken ?: getter.bodyExpression)?.siblings(forward = false, withItself = false)
                ?.firstOrNull { it !is PsiWhiteSpace }
            getter.deleteChildRange(getter.firstChild, dropGetterTo)

            val dropPropertyFrom = getter.siblings(forward = false, withItself = false).first { it !is PsiWhiteSpace }.nextSibling
            property.deleteChildRange(dropPropertyFrom, getter.prevSibling)

            val typeReference = property.typeReference
            if (typeReference != null) {
                property.addAfter(psiFactory.createWhiteSpace(), typeReference)
            }
        }
    }
    property.setName(newName)
    property.annotationEntries.forEach {
        if (it.useSiteTarget != null) {
            it.replace(psiFactory.createAnnotationEntry("@${it.shortName}${it.valueArgumentList?.text ?: ""}"))
        }
    }

    originalProperty.replace(psiFactory.createFunction(property.text))
}

private fun KaSession.prepareContext(
    element: KtProperty,
    applicabilityCheck: Boolean = false
): ElementContext? {
    val callableSymbol: KaCallableSymbol = element.symbol
    val propertyName = callableSymbol.name?.asString() ?: return null
    val newName = JvmAbi.getterName(callableSymbol.name?.asString() ?: return null)
    val nameChanged = propertyName != newName
    val conflicts = mutableMapOf<PsiElement, ModShowConflicts.Conflict>()
    val callables = getAffectedCallables(callableSymbol)
    val kotlinRefsToReplaceWithCall = mutableListOf<KtSimpleNameExpression>()
    val refsToRename = mutableListOf<PsiReference>()
    val javaRefsToReplaceWithCall = mutableListOf<PsiReferenceExpression>()

    if (!applicabilityCheck) {
        for (callable in callables) {
            if (callable !is PsiNamedElement) continue

            addConflictIfCantRefactor(callable, conflicts)

            if (callable is KtParameter) {
                conflicts.add(
                    callable,
                    if (callable.hasActualModifier()) KotlinBundle.message("property.has.an.actual.declaration.in.the.class.constructor")
                    else KotlinBundle.message("property.overloaded.in.child.class.constructor")
                )
            }

            // TODO: KTIJ-34287 Investigate why some tests fails without canBeAnalyzed check
            // org.jetbrains.kotlin.idea.k2.codeinsight.fixes.HighLevelQuickFixMultiModuleTestGenerated.Other
            if (callable is KtProperty && callable.canBeAnalysed()) {
                callable.containingKtFile
                    .scopeContext(callable)
                    .compositeScope()
                    .callables { it == callableSymbol.name }
                    .filterIsInstance<KaNamedFunctionSymbol>()
                    .find {
                        val receiverType = it.receiverType ?: return@find false
                        (callableSymbol.containingSymbol as? KaClassifierSymbol)?.defaultType?.semanticallyEquals(receiverType)
                            ?: return@find false
                    }?.let { reportDeclarationConflict(conflicts, it.psi!!) { s -> KotlinBundle.message("0.already.exists", s) } }
            } else if (callable is PsiMethod) {
                callable.checkDeclarationConflict(propertyName, conflicts, callables)
            }

            val usages = findReferencesToElement(callable) ?: continue

            for (usage in usages) {
                if (usage is KtReference) {
                    if (usage is KtSimpleNameReference) {
                        val expression = usage.expression
                        analyze(expression) {
                            if (expression.resolveToCall() != null && expression.getStrictParentOfType<KtCallableReferenceExpression>() == null) {
                                kotlinRefsToReplaceWithCall.add(expression)
                            } else if (nameChanged) {
                                refsToRename.add(usage)
                            }
                        }
                    } else {
                        val refElement = usage.element
                        conflicts.add(
                            refElement,
                            KotlinBundle.message(
                                "unrecognized.reference.will.be.skipped.0", htmlEmphasize(refElement.text)
                            )
                        )
                    }
                    continue
                }

                val refElement = usage.element

                if (refElement.text.endsWith(newName)) continue

                if (usage is PsiJavaReference) {
                    if (usage.resolve() is PsiField && usage is PsiReferenceExpression) {
                        javaRefsToReplaceWithCall.add(usage)
                    }
                    continue
                }

                conflicts.add(
                    refElement,
                    KotlinBundle.message(
                        "can.t.replace.foreign.reference.with.call.expression.0",
                        htmlEmphasize(refElement.text)
                    )
                )
            }
        }
    }

    return ElementContext(
        callables,
        refsToRename,
        kotlinRefsToReplaceWithCall,
        javaRefsToReplaceWithCall,
        conflicts,
        newName,
    )
}

private fun PsiMethod.checkDeclarationConflict(
    name: String,
    conflicts: MutableMap<PsiElement, ModShowConflicts.Conflict>,
    callables: Collection<PsiElement>,
) {
    containingClass?.findMethodsByName(name, true)
        // as is necessary here: see KT-10386
        ?.firstOrNull { it.parameterList.parametersCount == 0 && !callables.contains(it.namedUnwrappedElement as PsiElement?) }
        ?.let { reportDeclarationConflict(conflicts, it) { s -> KotlinBundle.message("0.already.exists", s) } }
}
