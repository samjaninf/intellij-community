// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.TailType;
import com.intellij.codeInsight.TailTypes;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.TailTypeDecorator;
import org.jetbrains.annotations.NotNull;

public final class OverridableSpace extends TailTypeDecorator<LookupElement> {
  private final @NotNull TailType myTail;

  private OverridableSpace(@NotNull LookupElement keyword, @NotNull TailType tail) {
    super(keyword);
    myTail = tail;
  }

  @Override
  protected @NotNull TailType computeTailType(@NotNull InsertionContext context) {
    return context.shouldAddCompletionChar() ? TailTypes.noneType() : myTail;
  }

  public static LookupElement create(@NotNull LookupElement delegate, @NotNull TailType tail) {
    return new OverridableSpace(delegate, tail);
  }
}
