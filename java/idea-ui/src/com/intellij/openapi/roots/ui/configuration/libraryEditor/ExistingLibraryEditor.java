// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.ui.configuration.libraryEditor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectModelExternalSource;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;

public class ExistingLibraryEditor extends LibraryEditorBase implements Disposable {
  private final LibraryEx myLibrary;
  private final LibraryEditorListener myListener;
  private String myLibraryName;
  private LibraryProperties myLibraryProperties;
  private LibraryProperties myDetectedLibraryProperties;
  private LibraryEx.ModifiableModelEx myModel;
  private LibraryType<?> myDetectedType;
  private boolean myDetectedTypeComputed;

  public ExistingLibraryEditor(@NotNull Library library, @Nullable LibraryEditorListener listener) {
    myLibrary = (LibraryEx)library;
    myListener = listener;
  }

  public Library getLibrary() {
    return myLibrary;
  }

  @Override
  public @NlsSafe String getName() {
    if (myLibraryName != null) {
      return myLibraryName;
    }
    return myLibrary.getName();
  }

  @Override
  public LibraryType<?> getType() {
    final LibraryKind kind = myLibrary.getKind();
    if (kind != null) {
      return LibraryType.findByKind(kind);
    }
    return detectType();
  }

  @Override
  public @Nullable ProjectModelExternalSource getExternalSource() {
    return myLibrary.getExternalSource();
  }

  @Override
  public void setType(@NotNull LibraryType<?> type) {
    getModel().setKind(type.getKind());
  }

  private LibraryType detectType() {
    if (!myDetectedTypeComputed) {
      LibraryTable libraryTable = myLibrary.getTable();
      if (libraryTable == null || libraryTable.isEditable()) {
        final Pair<LibraryType<?>,LibraryProperties<?>> pair = LibraryDetectionManager.getInstance().detectType(Arrays.asList(getFiles(OrderRootType.CLASSES)));
        if (pair != null) {
          myDetectedType = pair.getFirst();
          myDetectedLibraryProperties = pair.getSecond();
        }
      }
      myDetectedTypeComputed = true;
    }
    return myDetectedType;
  }

  @Override
  public LibraryProperties getProperties() {
    final LibraryType type = getType();
    if (type == null) return null;

    if (myDetectedType != null) {
      return myDetectedLibraryProperties;
    }

    if (myLibraryProperties == null) {
      myLibraryProperties = type.getKind().createDefaultProperties();
      //noinspection unchecked
      myLibraryProperties.loadState(getOriginalProperties().getState());
    }
    return myLibraryProperties;
  }

  @Override
  public void setProperties(LibraryProperties properties) {
    myLibraryProperties = properties;
  }

  private LibraryProperties getOriginalProperties() {
    return myLibrary.getProperties();
  }

  @Override
  public void dispose() {
    if (myModel != null) {
      // dispose if wasn't committed
      Disposer.dispose(myModel);
    }
  }

  @Override
  public String @NotNull [] getUrls(@NotNull OrderRootType rootType) {
    if (myModel != null) {
      return myModel.getUrls(rootType);
    }
    return myLibrary.getUrls(rootType);
  }

  @Override
  public VirtualFile @NotNull [] getFiles(@NotNull OrderRootType rootType) {
    if (myModel != null) {
      return myModel.getFiles(rootType);
    }
    return myLibrary.getFiles(rootType);
  }

  @Override
  public String @NotNull [] getExcludedRootUrls() {
    if (myModel != null) {
      return myModel.getExcludedRootUrls();
    }
    return myLibrary.getExcludedRootUrls();
  }

  @Override
  public void setName(String name) {
    String oldName = getModel().getName();
    myLibraryName = name;
    getModel().setName(name);
    if (myListener != null) {
      myListener.libraryRenamed(myLibrary, oldName, name);
    }
  }

  @Override
  public void addRoot(@NotNull VirtualFile file, @NotNull OrderRootType rootType) {
    getModel().addRoot(file, rootType);
  }

  @Override
  public void addRoot(@NotNull String url, @NotNull OrderRootType rootType) {
    getModel().addRoot(url, rootType);
  }

  @Override
  public void addExcludedRoot(@NotNull String url) {
    getModel().addExcludedRoot(url);
  }

  @Override
  public void addJarDirectory(@NotNull VirtualFile file, boolean recursive, @NotNull OrderRootType rootType) {
    getModel().addJarDirectory(file, recursive, rootType);
  }

  @Override
  public void addJarDirectory(@NotNull String url, boolean recursive, @NotNull OrderRootType rootType) {
    getModel().addJarDirectory(url, recursive, rootType);
  }

  @Override
  public void removeRoot(@NotNull String url, @NotNull OrderRootType rootType) {
    getModel().removeRoot(url, rootType);
  }

  @Override
  public void removeExcludedRoot(@NotNull String url) {
    getModel().removeExcludedRoot(url);
  }

  public void commit() {
    if (myModel != null) {
      if (myLibraryProperties != null) {
        myModel.setProperties(myLibraryProperties);
      }
      myModel.commit();
      myModel = null;
      myLibraryName = null;
      myLibraryProperties = null;
    }
  }

  public LibraryEx.ModifiableModelEx getModel() {
    if (myModel == null) {
      myModel = myLibrary.getModifiableModel();
    }
    return myModel;
  }

  @Override
  public boolean hasChanges() {
    if (myModel != null && myModel.isChanged()) {
      return true;
    }
    return myLibraryProperties != null && !myLibraryProperties.equals(getOriginalProperties());
  }

  @Override
  public boolean isJarDirectory(@NotNull String url, @NotNull OrderRootType rootType) {
    if (myModel != null) {
      return myModel.isJarDirectory(url, rootType);
    }
    return myLibrary.isJarDirectory(url, rootType);
  }

  @Override
  public boolean isValid(final @NotNull String url, final @NotNull OrderRootType orderRootType) {
    if (myModel != null) {
      return myModel.isValid(url, orderRootType);
    }
    return myLibrary.isValid(url, orderRootType);
  }

  @Override
  public Collection<OrderRootType> getOrderRootTypes() {
    return Arrays.asList(OrderRootType.getAllTypes());
  }
}
