// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection;

import com.intellij.codeInsight.ExpressionUtil;
import com.intellij.codeInsight.Nullability;
import com.intellij.codeInsight.TypeNullability;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.java.JavaBundle;
import com.intellij.java.codeserver.core.JavaPsiSwitchUtil;
import com.intellij.java.syntax.parser.JavaKeywords;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiCaseLabelElement;
import com.intellij.psi.PsiCaseLabelElementList;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDeconstructionPattern;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiKeyword;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPattern;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchLabelStatementBase;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MatchExceptionInspection extends AbstractBaseJavaLocalInspectionTool {

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

    return new JavaElementVisitor() {
      @Override
      public void visitSwitchExpression(@NotNull PsiSwitchExpression expression) {
        super.visitSwitchExpression(expression);
        checkSwitchBlock(expression);
      }

      @Override
      public void visitSwitchStatement(@NotNull PsiSwitchStatement statement) {
        super.visitSwitchStatement(statement);
        checkSwitchBlock(statement);
      }

      private void checkSwitchBlock(@NotNull PsiSwitchBlock switchBlock) {
        //fast exit
        if (JavaPsiSwitchUtil.findDefaultElement(switchBlock) != null) {
          return;
        }

        PsiPattern pattern = findPatternCanProduceMatchException(switchBlock, Set.of(), true);
        if (pattern == null) {
          return;
        }

        QuickFixFactory quickFixFactory = QuickFixFactory.getInstance();
        holder.problem(pattern, JavaBundle.message("inspection.match.exception.problems.message"))
          .fix(Objects.requireNonNull(quickFixFactory.createAddSwitchDefaultFix(switchBlock, null).asModCommandAction()))
          .register();
      }
    };
  }

  /**
   * Identifies a pattern in the branches of a given {@code PsiSwitchBlock} that could potentially
   * produce a match exception during execution.
   *
   * @param switchBlock            the {@link PsiSwitchBlock} to analyze, representing a switch statement or
   *                               switch expression in Java code. It must not be null.
   * @param skipDominatingElements a set of elements that should be skipped when searching for a pattern.
   * @param necessaryNullable      flag indicating whether the pattern must be certainly nullable.
   * @return a {@link PsiPattern} that could potentially cause a match exception, or {@code null}
   * if no such pattern is found.
   */
  public static @Nullable PsiPattern findPatternCanProduceMatchException(@NotNull PsiSwitchBlock switchBlock,
                                                                         @NotNull Set<@NotNull PsiElement> skipDominatingElements,
                                                                         boolean necessaryNullable) {
    List<PsiElement> branches = JavaPsiSwitchUtil.getSwitchBranches(switchBlock);
    for (PsiElement branch : branches) {
      if (!(branch instanceof PsiDeconstructionPattern psiDeconstructionPattern)) continue;
      PsiPattern deconstructionComponent = findDeconstructionComponentCanProduceMatchException(switchBlock,
                                                                                               psiDeconstructionPattern,
                                                                                               psiDeconstructionPattern,
                                                                                               skipDominatingElements,
                                                                                               necessaryNullable);
      if (deconstructionComponent != null) return deconstructionComponent;
    }
    return null;
  }

  private static @Nullable PsiPattern findDeconstructionComponentCanProduceMatchException(
    @NotNull PsiSwitchBlock switchBlock,
    @NotNull PsiDeconstructionPattern psiDeconstructionPattern,
    @NotNull PsiDeconstructionPattern topLevelDeconstructionPattern,
    @NotNull Set<@NotNull PsiElement> skipDominatingElements,
    boolean necessaryNullable) {
    PsiTypeElement typeElement = psiDeconstructionPattern.getTypeElement();
    PsiType recordType = typeElement.getType();
    PsiClass recordClass = PsiUtil.resolveClassInClassTypeOnly(recordType);
    if (recordClass == null || !recordClass.isRecord()) {
      return null;
    }
    PsiRecordComponent[] recordComponents = recordClass.getRecordComponents();
    @NotNull PsiPattern @NotNull [] deconstructionComponents =
      psiDeconstructionPattern.getDeconstructionList().getDeconstructionComponents();
    if (deconstructionComponents.length != recordComponents.length) {
      return null;
    }

    for (int i = 0; i < recordComponents.length; i++) {
      PsiPattern deconstructionComponent = deconstructionComponents[i];
      if (deconstructionComponent instanceof PsiDeconstructionPattern nestedDeconstructionPattern) {
        PsiPattern canProduceMatchException =
          findDeconstructionComponentCanProduceMatchException(switchBlock,
                                                              nestedDeconstructionPattern,
                                                              topLevelDeconstructionPattern,
                                                              skipDominatingElements, necessaryNullable);
        if (canProduceMatchException != null) return canProduceMatchException;
      }
      PsiRecordComponent component = recordComponents[i];
      PsiType componentType = component.getType();
      TypeNullability nullability = componentType.getNullability();
      PsiExpression expression = switchBlock.getExpression();
      if (expression == null) return null;
      if (necessaryNullable && nullability.nullability() != Nullability.NULLABLE) continue;
      if (!necessaryNullable && nullability.nullability() == Nullability.NOT_NULL) continue;
      PsiClass componentClass = PsiUtil.resolveClassInClassTypeOnly(componentType);
      if (componentClass == null) continue;
      if (deconstructionComponent instanceof PsiDeconstructionPattern ||
          componentClass.hasModifierProperty(PsiModifier.SEALED)) {
        if (!hasDominated(switchBlock, topLevelDeconstructionPattern, deconstructionComponent, componentClass, skipDominatingElements)) {
          return deconstructionComponent;
        }
      }
    }
    return null;
  }

  private static boolean hasDominated(@NotNull PsiSwitchBlock block,
                                      @NotNull PsiDeconstructionPattern pattern,
                                      @NotNull PsiPattern deconstructionComponent,
                                      @NotNull PsiClass sealedClass,
                                      @NotNull Set<@NotNull PsiElement> skipDominatingElements) {
    String text = pattern.getText();
    TextRange textRange = pattern.getTextRange();
    TextRange componentTextRange = deconstructionComponent.getTextRange();
    TextRange toChange = componentTextRange.shiftLeft(textRange.getStartOffset());
    String newPatternTe = StringUtil.replaceSubstring(text, toChange, sealedClass.getQualifiedName() + " someVariable");
    PsiPattern newPattern = createPatternFromText(newPatternTe, block);
    if (newPattern == null) return true;
    List<PsiElement> branches = JavaPsiSwitchUtil.getSwitchBranches(block);
    PsiExpression expression = block.getExpression();
    if (expression == null) return true;
    PsiType selectorType = expression.getType();
    if (selectorType == null) return true;
    for (PsiElement branch : branches) {
      if (skipDominatingElements.contains(branch) ||
          //case null, default
          (isNullOrDefault(branch) &&
           ContainerUtil.exists(skipDominatingElements, e -> JavaPsiSwitchUtil.isInCaseNullDefaultLabel(e)))) {
        continue;
      }
      boolean dominated = JavaPsiSwitchUtil.isDominated(newPattern, branch, selectorType);
      if (dominated) return true;
    }
    return false;
  }

  private static boolean isNullOrDefault(@NotNull PsiElement branch) {
    return ExpressionUtil.isNullLiteral(branch) ||
           (branch instanceof PsiKeyword && JavaKeywords.DEFAULT.equals(branch.getText()));
  }


  private static @Nullable PsiPattern createPatternFromText(@NotNull String patternText, @NotNull PsiElement context) {
    PsiElementFactory factory = PsiElementFactory.getInstance(context.getProject());
    String labelText = "case " + patternText + "->{}";
    PsiStatement statement;
    try {
      statement = factory.createStatementFromText(labelText, context);
    }
    catch (IncorrectOperationException e) {
      return null;
    }
    PsiSwitchLabelStatementBase label = ObjectUtils.tryCast(statement, PsiSwitchLabelStatementBase.class);
    if (label == null) return null;
    PsiCaseLabelElementList list = label.getCaseLabelElementList();
    if (list == null) return null;
    PsiCaseLabelElement element = list.getElements()[0];
    if (!(element instanceof PsiPattern pattern)) return null;
    return pattern;
  }
}
