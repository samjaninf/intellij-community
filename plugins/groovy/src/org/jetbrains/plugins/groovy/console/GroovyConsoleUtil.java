// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.console;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.util.ModuleChooserUtil;

import static org.jetbrains.plugins.groovy.bundled.BundledGroovy.getBundledGroovyVersion;
import static org.jetbrains.plugins.groovy.console.GroovyConsoleUtilKt.getApplicableModules;
import static org.jetbrains.plugins.groovy.console.GroovyConsoleUtilKt.sdkVersionIfHasNeededDependenciesToRunConsole;

public final class GroovyConsoleUtil {

  public static @NotNull @Nls String getDisplayGroovyVersion(@NotNull Module module) {
    final String sdkVersion = sdkVersionIfHasNeededDependenciesToRunConsole(module);
    return sdkVersion == null ? GroovyBundle.message("groovy.version.bundled.0", getBundledGroovyVersion())
                              : GroovyBundle.message("groovy.version.0", sdkVersion);
  }

  public static void selectModuleAndRun(Project project, Consumer<Module> consumer) {
    ModuleChooserUtil.selectModule(project, getApplicableModules(project), GroovyConsoleUtil::getDisplayGroovyVersion, consumer);
  }

  public static @NotNull @Nls String getTitle(@NotNull Module module) {
    return GroovyBundle.message("module.name.0.and.groovy.version.1", module.getName(), getDisplayGroovyVersion(module));
  }
}
