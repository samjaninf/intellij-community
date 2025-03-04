// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.env.python;

import com.google.common.collect.ImmutableList;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.python.community.testFramework.testEnv.EnvTagsKt;
import com.intellij.testFramework.UsefulTestCase;
import com.jetbrains.env.PyEnvTestCase;
import org.junit.Test;

import java.nio.file.Path;
import java.util.*;

import static com.intellij.testFramework.UsefulTestCase.assertEmpty;

public class PyEnvSufficiencyTest extends PyEnvTestCase {
  private static final List<String> BASE_TAGS =
    ImmutableList.<String>builder().add("python3", "django",  "ipython",  "nose", "pytest").build();

  @Test
  public void testSufficiency() {
    if (UsefulTestCase.IS_UNDER_TEAMCITY && SETTINGS.isEnvConfiguration()) {

      Set<String> tags = new HashSet<>();
      List<String> roots = getDefaultPythonRoots();
      if (roots.size() == 0) {
        return;         // not on env agent
      }
      for (String root : roots) {
        tags.addAll(EnvTagsKt.loadEnvTags(Path.of(root)));
      }

      List<String> missing = new ArrayList<>();
      for (String tag : necessaryTags()) {
        if (!tags.contains(tag)) {
          missing.add(tag);
        }
      }


      assertEmpty("Agent is missing environments: " + StringUtil.join(missing, ", "), missing);
    }
  }

  private static List<String> necessaryTags() {
    if (SystemInfo.isWindows) {
      return Collections.emptyList();// ImmutableList.<String>builder().addAll(BASE_TAGS).add("iron").build();
    }
    else {
      return ImmutableList.<String>builder().addAll(BASE_TAGS).add("packaging").build();
    }
  }
}
