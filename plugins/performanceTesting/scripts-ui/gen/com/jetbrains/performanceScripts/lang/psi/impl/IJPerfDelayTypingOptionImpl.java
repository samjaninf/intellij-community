// This is a generated file. Not intended for manual editing.
package com.jetbrains.performanceScripts.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.jetbrains.performanceScripts.lang.psi.IJPerfElementTypes.*;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.jetbrains.performanceScripts.lang.psi.*;

public class IJPerfDelayTypingOptionImpl extends ASTWrapperPsiElement implements IJPerfDelayTypingOption {

  public IJPerfDelayTypingOptionImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull IJPerfVisitor visitor) {
    visitor.visitDelayTypingOption(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof IJPerfVisitor) accept((IJPerfVisitor)visitor);
    else super.accept(visitor);
  }

}
