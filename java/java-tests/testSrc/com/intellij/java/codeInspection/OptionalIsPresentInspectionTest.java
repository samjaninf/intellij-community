// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInspection;

import com.intellij.JavaTestUtil;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.OptionalIsPresentInspection;
import com.intellij.testFramework.LightProjectDescriptor;
import com.siyeh.ig.LightJavaInspectionTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OptionalIsPresentInspectionTest extends LightJavaInspectionTestCase {
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath() + "/inspection/optionalIsPresent/";
  }

  @Nullable
  @Override
  protected InspectionProfileEntry getInspection() {
    return new OptionalIsPresentInspection();
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return JAVA_11_ANNOTATED;
  }


  public void testAssignment() { doTest(); }

  public void testConsumer() { doTest(); }

  public void testConsumerIndirect() { doTest(); }

  public void testConsumerIndirectAmbiguousOverload() { doTest(); }

  public void testConsumerIndirectComment() { doTest(); }

  public void testConsumerIndirectFieldInaccessible() { doTest(); }

  public void testConsumerIndirectTooComplex_1() { doTest(); }

  public void testConsumerIndirectTooComplex_2() { doTest(); }

  public void testConsumerIndirectTypeMismatch() { doTest(); }

  public void testOptional() { doTest(); }
}
