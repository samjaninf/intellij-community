// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.dataFlow.java.anchor;

import com.intellij.psi.PsiPattern;
import org.jetbrains.annotations.NotNull;

public class JavaSwitchDeconstructionLabelAnchor extends JavaSwitchLabelTakenAnchor {
  public JavaSwitchDeconstructionLabelAnchor(@NotNull PsiPattern labelElement) {
    super(labelElement);
  }
}
