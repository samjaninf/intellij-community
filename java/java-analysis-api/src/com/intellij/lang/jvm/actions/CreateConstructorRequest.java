// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang.jvm.actions;

public interface CreateConstructorRequest extends CreateExecutableRequest {

  /**
   * @return should start live template after a new field was created.
   */
  default boolean isStartTemplate() {
    return true;
  }
}
