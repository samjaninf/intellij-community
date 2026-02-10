// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.remote;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;

/**
 * This service provides functionality for SSH remote interpreters. It is
 * expected to be implemented in Python SSH Interpreter plugin using the
 * extension point {@link #EP_NAME}.
 */
public interface PythonSshInterpreterManager {
  Logger LOG = Logger.getInstance(PythonSshInterpreterManager.class);

  ExtensionPointName<PythonSshInterpreterManager> EP_NAME = ExtensionPointName.create("Pythonid.sshInterpreterManager");

  final class Factory {
    public static @Nullable PythonSshInterpreterManager getInstance() {
      var extensions = EP_NAME.getExtensionList();
      return switch (extensions.size()) {
        case 0 -> {
          LOG.debug(MessageFormat.format("Extension for ''{0}'' extension point is absent", EP_NAME.getName()));
          yield null;
        }
        case 1 -> extensions.get(0);
        default -> {
          LOG.error(MessageFormat.format("Several extensions registered for ''{0}'' extension point", EP_NAME.getName()));
          yield  null;
        }
      };
    }
  }
}
