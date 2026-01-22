// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.PythonUiService;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.search.PySuperMethodsSearch;
import com.jetbrains.python.psi.types.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PyMethodOverridingInspection extends PyInspection {

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                 boolean isOnTheFly,
                                                 @NotNull LocalInspectionToolSession session) {
    return new Visitor(holder, PyInspectionVisitor.getContext(session));
  }

  public static class Visitor extends PyInspectionVisitor {
    public Visitor(@Nullable ProblemsHolder holder, @NotNull TypeEvalContext context) {
      super(holder, context);
    }

    @Override
    public void visitPyFunction(final @NotNull PyFunction function) {
      final PyClass containingClass = function.getContainingClass();
      if (containingClass == null || skipFunctionValidation(function)) return;

      for (PsiElement psiElement : PySuperMethodsSearch.search(function, myTypeEvalContext).findAll()) {
        if (psiElement instanceof PyFunction baseMethod) {
          validateOverriddenFunction(function, baseMethod, containingClass);
        }
      }
    }

    private boolean skipFunctionValidation(@NotNull PyFunction function) {
      return PyUtil.isConstructorLikeMethod(function) ||
             PyKnownDecoratorUtil.hasUnknownOrChangingSignatureDecorator(function, myTypeEvalContext) ||
             ContainerUtil.exists(PyInspectionExtension.EP_NAME.getExtensionList(),
                                  e -> e.ignoreMethodParameters(function, myTypeEvalContext));
    }

    private void validateOverriddenFunction(@NotNull PyFunction function,
                                            @NotNull PyFunction baseMethod,
                                            @NotNull PyClass containingClass) {
      final PyClass baseClass = baseMethod.getContainingClass();
      final String methodSignature = containingClass.getName() + "." + function.getName() + "()";
      final String baseClassName = baseClass != null ? baseClass.getName() : "";

      PyCallableParameterListTypeImpl baseMethodInputSignature =
        new PyCallableParameterListTypeImpl(baseMethod.getParameters(myTypeEvalContext));
      PyCallableParameterListTypeImpl functionInputSignature =
        new PyCallableParameterListTypeImpl(function.getParameters(myTypeEvalContext));

      if (!PyTypeChecker.match(baseMethodInputSignature, functionInputSignature, myTypeEvalContext)) {
        final String msg = PyPsiBundle.message("INSP.signature.mismatch", methodSignature, baseClassName);

        registerProblem(function.getParameterList(), msg,
                        PythonUiService.getInstance().createPyChangeSignatureQuickFixForMismatchingMethods(function, baseMethod));
      }

      final PyAnnotation annotation = function.getAnnotation();
      final PyExpression returnExpression = annotation != null ? annotation.getValue() : null;
      if (returnExpression == null) return;

      final PyType baseMethodReturnType = myTypeEvalContext.getReturnType(baseMethod);
      final PyType overriddenReturnType = myTypeEvalContext.getReturnType(function);

      final boolean isReturnTypeCompatible = baseMethodReturnType instanceof PyNeverType ||
                                             PyTypeChecker.match(baseMethodReturnType, overriddenReturnType, myTypeEvalContext);

      if (!isReturnTypeCompatible) {
        final String msg = PyPsiBundle.message("INSP.overridden.method.return.type.mismatch", methodSignature, baseClassName);
        registerProblem(returnExpression, msg);
      }
    }
  }
}
