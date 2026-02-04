/*
 * Copyright 2003-2025 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ipp.shift;

import com.intellij.codeInspection.CommonQuickFixBundle;
import com.intellij.codeInspection.dataFlow.CommonDataflow;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.openapi.project.DumbAware;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.IntentionPowerPackBundle;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import com.siyeh.ipp.base.MCIntention;
import com.siyeh.ipp.base.PsiElementPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ReplaceShiftWithMultiplyIntention extends MCIntention implements DumbAware {

  @Override
  public @NotNull String getFamilyName() {
    return IntentionPowerPackBundle.message("replace.shift.with.multiply.intention.family.name");
  }

  @Override
  protected @NotNull String getTextForElement(@NotNull PsiElement element) {
    if (element instanceof PsiAssignmentExpression exp) {
      final PsiJavaToken sign = exp.getOperationSign();
      final IElementType tokenType = sign.getTokenType();
      final String assignString = JavaTokenType.GTGTEQ.equals(tokenType) ?
                                  (isSafelyDivisible(exp.getLExpression()) ? "/=" : "Math.floorDiv") : "*=";
      return CommonQuickFixBundle.message("fix.replace.x.with.y", sign.getText(), assignString);
    } else {
      final PsiBinaryExpression exp = (PsiBinaryExpression) element;
      final PsiJavaToken sign = exp.getOperationSign();
      final IElementType tokenType = sign.getTokenType();
      final String operatorString = tokenType.equals(JavaTokenType.GTGT) ?
                                    (isSafelyDivisible(exp.getLOperand()) ? "/" : "Math.floorDiv") : "*";
      return CommonQuickFixBundle.message("fix.replace.x.with.y", sign.getText(), operatorString);
    }
  }

  @Override
  public @NotNull PsiElementPredicate getElementPredicate() {
    return new ShiftByLiteralPredicate() {
      @Override
      public boolean satisfiedBy(PsiElement element) {
        if (element instanceof PsiAssignmentExpression expr
            && expr.getOperationTokenType().equals(JavaTokenType.GTGTEQ)
            && PsiUtil.getLanguageLevel(element).isLessThan(LanguageLevel.JDK_1_8)
            && !isSafelyDivisible(expr.getLExpression())
        ) {
          return false;
        }
        if (element instanceof PsiBinaryExpression expr
            && expr.getOperationTokenType().equals(JavaTokenType.GTGT)
            && PsiUtil.getLanguageLevel(element).isLessThan(LanguageLevel.JDK_1_8)
            && !isSafelyDivisible(expr.getLOperand())
        ) {
          return false;
        }
        return super.satisfiedBy(element);
      }
    };
  }

  @Override
  public void invoke(@NotNull PsiElement element) {
    if (element instanceof PsiBinaryExpression expr) {
      replaceShiftWithMultiplyOrDivide(expr);
    }
    else if (element instanceof PsiAssignmentExpression expr) {
      replaceShiftAssignWithMultiplyOrDivideAssign(expr);
    }
  }

  private static boolean isSafelyDivisible(@NotNull PsiExpression lhsExpr) {
    LongRangeSet range = CommonDataflow.getExpressionRange(lhsExpr);
    return range != null && range.min() >= 0;
  }

  private static void replaceShiftAssignWithMultiplyOrDivideAssign(PsiAssignmentExpression exp) {
    final PsiExpression lhsExpr = exp.getLExpression();
    final PsiExpression rhsExpr = PsiUtil.skipParenthesizedExprDown(exp.getRExpression());
    if (!(rhsExpr instanceof PsiLiteralExpression rhsLiteral)) return;
    CommentTracker commentTracker = new CommentTracker();
    final String lhsText = commentTracker.text(lhsExpr, ParenthesesUtils.MULTIPLICATIVE_PRECEDENCE);
    final String rhsText = rhsReplacement(rhsLiteral, lhsExpr.getType());
    String expString;
    if (exp.getOperationTokenType().equals(JavaTokenType.GTGTEQ)) {
      if (isSafelyDivisible(lhsExpr)) {
        expString = lhsText + "/=" + rhsText;
      } else {
        expString = lhsText + "=" + "Math.floorDiv(" + lhsText + ", " + rhsText + ")";
      }
    } else {
      expString = lhsText + "*=" + rhsText;
    }
    PsiReplacementUtil.replaceExpression(exp, expString, commentTracker);
  }

  private static void replaceShiftWithMultiplyOrDivide(PsiBinaryExpression expression) {
    final PsiExpression lhsExpr = expression.getLOperand();
    final PsiExpression rhsExpr = PsiUtil.skipParenthesizedExprDown(expression.getROperand());
    if (!(rhsExpr instanceof PsiLiteralExpression rhsLiteral)) return;
    CommentTracker commentTracker = new CommentTracker();
    final String lhsText = commentTracker.text(lhsExpr, ParenthesesUtils.MULTIPLICATIVE_PRECEDENCE);
    final String rhsText = rhsReplacement(rhsLiteral, lhsExpr.getType());
    String expString;
    if (expression.getOperationTokenType().equals(JavaTokenType.GTGT)) {
      if (isSafelyDivisible(lhsExpr)) {
        expString = lhsText + "/" + rhsText;
      } else {
        expString = "Math.floorDiv(" + lhsText + ", " + rhsText + ")";
      }
    } else {
      expString = lhsText + "*" + rhsText;
    }
    if (expression.getParent() instanceof PsiExpression parent && !(parent instanceof PsiParenthesizedExpression) &&
        ParenthesesUtils.getPrecedence(parent) < ParenthesesUtils.MULTIPLICATIVE_PRECEDENCE) {
      expString = '(' + expString + ')';
    }

    PsiReplacementUtil.replaceExpression(expression, expString, commentTracker);
  }

  private static String rhsReplacement(@NotNull PsiLiteralExpression rhs, @Nullable PsiType type) {
    final Number value = (Number)rhs.getValue();
    assert value != null;
     if (PsiTypes.longType().equals(type)) {
       return Long.toString(1L << value.intValue()) + 'L';
     } else {
       return Integer.toString(1 << value.intValue());
     }
  }
}