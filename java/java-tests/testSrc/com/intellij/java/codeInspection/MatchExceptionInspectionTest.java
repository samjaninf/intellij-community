// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInspection;

import com.intellij.JavaTestUtil;
import com.intellij.codeInspection.MatchExceptionInspection;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

public final class MatchExceptionInspectionTest extends LightJavaCodeInsightFixtureTestCase {
  @Override
  protected String getBasePath() {
    return JavaTestUtil.getRelativeJavaTestDataPath() + "/inspection/matchException";
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return JAVA_21;
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myFixture.enableInspections(new MatchExceptionInspection());
  }

  private void doTest() {
    myFixture.testHighlighting(getTestName(false) + ".java");
  }

  public void testMatchExceptionNestedDeconstruction() { doTest(); }

  public void testMatchExceptionSealedClass() { doTest(); }

  public void testMatchExceptionDoubleNestedDeconstruction() { doTest(); }

  public void testNoMatchExceptionMostNestedDeconstruction() { doTest(); }

  public void testMatchExceptionNestedSealedClass() { doTest(); }

  public void testNoMatchExceptionNestedDeconstructionWithDefault() { doTest(); }

  public void testNoMatchExceptionSealedClassWithNullDefault() { doTest(); }

  public void testNoMatchExceptionDoubleNestedDeconstructionWithDominated() { doTest(); }

  public void testNoMatchExceptionSealedClassWithDominated() { doTest(); }
}
