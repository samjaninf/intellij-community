// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.impl;

import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.codeInsight.hints.presentation.PresentationRenderer;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.actions.JavaDebuggerActionsCollector;
import com.intellij.debugger.impl.attach.JavaAttachDebuggerProvider;
import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaDebuggerConsoleFilterProvider implements ConsoleFilterProvider {
  static final Pattern PATTERN = Pattern.compile("Listening for transport (\\S+) at address: (\\S+)");

  @Override
  public Filter @NotNull [] getDefaultFilters(@NotNull Project project) {
    return new Filter[]{new JavaDebuggerAttachFilter(project)};
  }

  public static Matcher getConnectionMatcher(String line) {
    if (line.contains("Listening for transport")) {
      Matcher matcher = PATTERN.matcher(line);
      if (matcher.find()) {
        return matcher;
      }
    }
    return null;
  }

  private static class JavaDebuggerAttachFilter implements Filter {
    @NotNull Project myProject;

    private JavaDebuggerAttachFilter(@NotNull Project project) {
      this.myProject = project;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
      Matcher matcher = getConnectionMatcher(line);
      if (matcher == null) {
        return null;
      }
      String transport = matcher.group(1);
      String address = matcher.group(2);
      int start = entireLength - line.length();

      if (Registry.is("debugger.auto.attach.from.any.console") && !isDebuggerAttached(transport, address, myProject)) {
        ApplicationManager.getApplication().invokeLater(
          () -> JavaAttachDebuggerProvider.attach(transport, address, null, myProject),
          ModalityState.any());
      }

      // to trick the code unwrapping single results in com.intellij.execution.filters.CompositeFilter#createFinalResult
      return new Result(Arrays.asList(
        new AttachInlayResult(start + matcher.start(), start + matcher.end(), transport, address),
        new ResultItem(0, 0, null)));
    }
  }

  private static boolean isDebuggerAttached(String transport, String address, Project project) {
    return DebuggerManagerEx.getInstanceEx(project).getSessions()
      .stream()
      .map(s -> s.getDebugEnvironment().getRemoteConnection())
      .anyMatch(c -> address.equals(c.getApplicationAddress()) && "dt_shmem".equals(transport) != c.isUseSockets());
  }

  private static class AttachInlayResult extends Filter.ResultItem implements InlayProvider {
    private final String myTransport;
    private final String myAddress;

    AttachInlayResult(int highlightStartOffset, int highlightEndOffset, String transport, String address) {
      super(highlightStartOffset, highlightEndOffset, null);
      myTransport = transport;
      myAddress = address;
    }

    @Override
    public EditorCustomElementRenderer createInlayRenderer(Editor editor) {
      JavaDebuggerActionsCollector.attachFromConsoleInlayShown.log();
      PresentationFactory factory = new PresentationFactory(editor);
      InlayPresentation presentation = factory.referenceOnHover(
        factory.roundWithBackground(factory.smallText("Attach debugger")),
        (event, point) -> {
          JavaDebuggerActionsCollector.attachFromConsoleInlay.log();
          JavaAttachDebuggerProvider.attach(myTransport, myAddress, null, editor.getProject());
        });
      return new PresentationRenderer(presentation);
    }
  }
}
