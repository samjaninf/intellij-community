// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.themes;

import com.intellij.json.psi.JsonLiteral;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonStringLiteral;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

final class ThemeJsonNamedColorPsiReferenceProvider extends PsiReferenceProvider {
  private static final Pattern COLOR_N_PATTERN = Pattern.compile("Color\\d+");

  @Override
  public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
    if (!(element instanceof JsonLiteral literal)) return PsiReference.EMPTY_ARRAY;

    if (element instanceof JsonStringLiteral
        && ThemeColorAnnotator.isColorCode(((JsonStringLiteral)element).getValue())) {
      return PsiReference.EMPTY_ARRAY;
    }

    PsiElement parent = literal.getParent();
    if (parent instanceof JsonProperty property) {
      String name = property.getName();
      if (property.getValue() == literal) { // inside value of property
        if (isKeyInteresting(name)) {
          return new PsiReference[]{new ThemeJsonNamedColorPsiReference(literal)};
        }

        PsiElement grandParent = property.getParent();
        if (grandParent != null) {
          PsiElement greatGrandParent = grandParent.getParent();
          if (greatGrandParent instanceof JsonProperty parentProperty) {
            String parentName = parentProperty.getName();
            if (COLOR_N_PATTERN.matcher(parentName).matches() || isKeyInteresting(parentName)) {
              return new PsiReference[]{new ThemeJsonNamedColorPsiReference(literal)};
            }
          }
        }
      }
    }

    return PsiReference.EMPTY_ARRAY;
  }

  private static boolean isKeyInteresting(String name) {
    return name.endsWith("Foreground")
           || name.endsWith("Background")
           || name.endsWith("Color")
           || name.endsWith(".foreground")
           || name.endsWith(".background")
           || name.endsWith("color")
           || "foreground".equals(name)
           || "background".equals(name)
           || COLOR_N_PATTERN.matcher(name).matches();
  }
}
