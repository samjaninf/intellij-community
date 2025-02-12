/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.siyeh.ig.abstraction;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.testFramework.LightProjectDescriptor;
import com.siyeh.ig.LightJavaInspectionTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Bas Leijdekkers
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class OptionalUsedAsFieldOrParameterTypeInspectionTest extends LightJavaInspectionTestCase {

  public void testOptionalField() {
    doTest("import java.util.Optional;" +
           "class X {" +
           "  private /*'Optional<String>' used as type for field 's'*/Optional<String>/**/ s;" +
           "}");
  }

  public void testOptionalParameter() {
    doTest("import java.util.Optional;" +
           "class X {" +
           "  void m(/*'Optional<Object>' used as type for parameter 'o'*/Optional<Object>/**/ o) {}" +
           "}");
  }

  public void testOptionalDoubleField() {
    doTest("import java.util.OptionalDouble;" +
           "class X {" +
           "  private /*'OptionalDouble' used as type for field 'd'*/OptionalDouble/**/ d;" +
           "}");
  }

  public void testNoWarnOnOptionalReturnType() {
    doTest("import java.util.Optional;" +
           "class X {" +
           "  Optional<Integer> f() {" +
           "    return null; // don't do this\n" +
           "  }" +
           "}");
  }

  public void testParameterInOverridingMethod() {
    //noinspection OptionalIsPresent
    doTest("import java.util.Optional;" +
           "import java.util.function.Function;" +
           "class X {" +
           "  " +
           "Function<Optional<Long>, Long> homebrewOrElseNull = input-> input.isPresent() ? input.get() : null;" +
           "}");
  }

  public void testForeach() {
    doTest("import java.util.Optional;" +
           "class X {" +
           "  void x(Optional<String>[] array) {" +
           "    for(Optional<String> optional : array) {" +
           "    }" +
           "  }" +
           "}");
  }

  @Nullable
  @Override
  protected InspectionProfileEntry getInspection() {
    return new OptionalUsedAsFieldOrParameterTypeInspection();
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return JAVA_8;
  }
}
