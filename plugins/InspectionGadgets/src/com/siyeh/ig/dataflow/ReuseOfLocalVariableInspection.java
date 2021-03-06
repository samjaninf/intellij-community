/*
 * Copyright 2003-2018 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.dataflow;

import com.intellij.codeInsight.BlockUtils;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.HighlightUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReuseOfLocalVariableInspection extends BaseInspection {
  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new ReuseOfLocalVariableFix();
  }

  @Override
  protected boolean buildQuickFixesOnlyForOnTheFlyErrors() {
    return true;
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message(
      "reuse.of.local.variable.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "reuse.of.local.variable.problem.descriptor");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ReuseOfLocalVariableVisitor();
  }

  private static class ReuseOfLocalVariableFix extends InspectionGadgetsFix {

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("reuse.of.local.variable.split.quickfix");
    }

    @Override
    public void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)descriptor.getPsiElement();
      final PsiLocalVariable variable = (PsiLocalVariable)referenceExpression.resolve();
      if (variable == null) return;
      final PsiAssignmentExpression assignment = (PsiAssignmentExpression)PsiUtil.skipParenthesizedExprUp(referenceExpression.getParent());
      if (assignment == null) return;
      PsiExpressionStatement assignmentStatement = (PsiExpressionStatement)assignment.getParent();
      final PsiExpression lExpression = PsiUtil.skipParenthesizedExprDown(assignment.getLExpression());
      if (lExpression == null) return;
      final String originalVariableName = lExpression.getText();
      final PsiType type = variable.getType();
      final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
      final PsiCodeBlock variableBlock = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
      final String newVariableName = codeStyleManager.suggestUniqueVariableName(originalVariableName, variableBlock, false);
      final SearchScope scope = new LocalSearchScope(assignmentStatement.getParent());
      final Query<PsiReference> query = ReferencesSearch.search(variable, scope, false);
      final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
      final PsiElementFactory factory = psiFacade.getElementFactory();
      List<PsiReferenceExpression> collectedReferences = new ArrayList<>();
      for (PsiReference reference : query) {
        final PsiElement referenceElement = reference.getElement();
        final TextRange textRange = assignmentStatement.getTextRange();
        if (referenceElement.getTextOffset() <= textRange.getEndOffset()) {
          continue;
        }
        final PsiExpression newExpression = factory.createExpressionFromText(newVariableName, referenceElement);
        final PsiReferenceExpression replacementExpression = (PsiReferenceExpression)referenceElement.replace(newExpression);
        collectedReferences.add(replacementExpression);
      }
      CommentTracker commentTracker = new CommentTracker();
      final PsiExpression rhs = assignment.getRExpression();
      final String rhsText = rhs == null ? "" : commentTracker.text(rhs);
      @NonNls final String newStatementText = type.getCanonicalText() + ' ' + newVariableName + " =  " + rhsText + ';';

      final PsiDeclarationStatement declarationStatement = (PsiDeclarationStatement)BlockUtils.addAfter(assignmentStatement, factory.createStatementFromText(newStatementText, assignmentStatement));
      assignmentStatement = PsiTreeUtil.getPrevSiblingOfType(declarationStatement, PsiExpressionStatement.class);
      commentTracker.deleteAndRestoreComments(Objects.requireNonNull(assignmentStatement));
      final PsiElement[] elements = declarationStatement.getDeclaredElements();
      final PsiLocalVariable newVariable = (PsiLocalVariable)elements[0];
      final PsiElement context = declarationStatement.getParent();
      HighlightUtils.showRenameTemplate(context, newVariable, collectedReferences.toArray(new PsiReferenceExpression[0]));
    }
  }

  private static class ReuseOfLocalVariableVisitor extends BaseInspectionVisitor {

    @Override
    public void visitAssignmentExpression(
      @NotNull PsiAssignmentExpression assignment) {
      super.visitAssignmentExpression(assignment);
      final PsiElement assignmentParent = assignment.getParent();
      if (!(assignmentParent instanceof PsiExpressionStatement)) {
        return;
      }
      final PsiExpression lhs = PsiUtil.skipParenthesizedExprDown(assignment.getLExpression());
      if (!(lhs instanceof PsiReferenceExpression)) {
        return;
      }
      final PsiReferenceExpression reference =
        (PsiReferenceExpression)lhs;
      final PsiElement referent = reference.resolve();
      if (!(referent instanceof PsiLocalVariable)) {
        return;
      }
      final PsiVariable variable = (PsiVariable)referent;

      //TODO: this is safe, but can be weakened
      if (variable.getInitializer() == null) {
        return;
      }
      final IElementType tokenType = assignment.getOperationTokenType();
      if (!JavaTokenType.EQ.equals(tokenType)) {
        return;
      }
      final PsiExpression rhs = assignment.getRExpression();
      if (VariableAccessUtils.variableIsUsed(variable, rhs)) {
        return;
      }
      final PsiCodeBlock variableBlock =
        PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
      if (variableBlock == null) {
        return;
      }

      if (loopExistsBetween(assignment, variableBlock)) {
        return;
      }
      if (tryExistsBetween(assignment, variableBlock)) {
        // this could be weakened, slightly, if it could be verified
        // that a variable is used in only one branch of a try statement
        return;
      }
      final PsiElement assignmentBlock = assignmentParent.getParent();
      if (assignmentBlock == null) {
        return;
      }
      if (variableBlock.equals(assignmentBlock)) {
        registerError(lhs);
      }
      final PsiStatement[] statements = variableBlock.getStatements();
      final PsiElement containingStatement =
        getChildWhichContainsElement(variableBlock, assignment);
      int statementPosition = -1;
      for (int i = 0; i < statements.length; i++) {
        if (statements[i].equals(containingStatement)) {
          statementPosition = i;
          break;
        }
      }
      if (statementPosition == -1) {
        return;
      }
      for (int i = statementPosition + 1; i < statements.length; i++) {
        if (VariableAccessUtils.variableIsUsed(variable, statements[i])) {
          return;
        }
      }
      registerError(lhs);
    }

    private static boolean loopExistsBetween(PsiAssignmentExpression assignment, PsiCodeBlock block) {
      PsiElement elementToTest = assignment;
      while (elementToTest != null) {
        if (elementToTest.equals(block)) {
          return false;
        }
        if (elementToTest instanceof PsiLoopStatement) {
          return true;
        }
        elementToTest = elementToTest.getParent();
      }
      return false;
    }

    private static boolean tryExistsBetween(PsiAssignmentExpression assignment, PsiCodeBlock block) {
      PsiElement elementToTest = assignment;
      while (elementToTest != null) {
        if (elementToTest.equals(block)) {
          return false;
        }
        if (elementToTest instanceof PsiTryStatement) {
          return true;
        }
        elementToTest = elementToTest.getParent();
      }
      return false;
    }

    /**
     * @noinspection AssignmentToMethodParameter
     */
    @Nullable
    public static PsiElement getChildWhichContainsElement(
      @NotNull PsiCodeBlock ancestor,
      @NotNull PsiElement descendant) {
      PsiElement element = descendant;
      while (!element.equals(ancestor)) {
        descendant = element;
        element = descendant.getParent();
        if (element == null) {
          return null;
        }
      }
      return descendant;
    }
  }
}