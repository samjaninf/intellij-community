// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.svn.dialogs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.vfs.VcsVirtualFile;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.NavigatableAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SpeedSearchComparator;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.NotNullFunction;
import com.intellij.util.ui.StatusText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.SvnVcs;
import org.jetbrains.idea.svn.api.Revision;
import org.jetbrains.idea.svn.api.Url;
import org.jetbrains.idea.svn.browse.DirectoryEntry;
import org.jetbrains.idea.svn.dialogs.browserCache.Expander;
import org.jetbrains.idea.svn.history.SvnFileRevision;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

/**
 * @author alex
 */
public class RepositoryBrowserComponent extends JPanel implements Disposable, UiDataProvider {

  private Tree myRepositoryTree;
  private final SvnVcs myVCS;

  public RepositoryBrowserComponent(@NotNull SvnVcs vcs) {
    myVCS = vcs;
    createComponent();
  }

  public JTree getRepositoryTree() {
    return myRepositoryTree;
  }

  public @NotNull Project getProject() {
    return myVCS.getProject();
  }

  public void setRepositoryURLs(Url[] urls, final boolean showFiles) {
    setRepositoryURLs(urls, showFiles, null, false);
  }

  public void setRepositoryURLs(Url[] urls,
                                final boolean showFiles,
                                @Nullable NotNullFunction<RepositoryBrowserComponent, Expander> defaultExpanderFactory,
                                boolean expandFirst) {
    RepositoryTreeModel model = new RepositoryTreeModel(myVCS, showFiles, this);

    if (defaultExpanderFactory != null) {
      model.setDefaultExpanderFactory(defaultExpanderFactory);
    }

    model.setRoots(urls);
    Disposer.register(this, model);
    myRepositoryTree.setModel(model);

    if (expandFirst) {
      myRepositoryTree.expandRow(0);
    }
  }

  public void setRepositoryURL(Url url,
                               boolean showFiles,
                               final NotNullFunction<RepositoryBrowserComponent, Expander> defaultExpanderFactory) {
    RepositoryTreeModel model = new RepositoryTreeModel(myVCS, showFiles, this);

    model.setDefaultExpanderFactory(defaultExpanderFactory);

    model.setSingleRoot(url);
    Disposer.register(this, model);
    myRepositoryTree.setModel(model);
    myRepositoryTree.setRootVisible(true);
    myRepositoryTree.setSelectionRow(0);
  }

  public void setRepositoryURL(Url url, boolean showFiles) {
    RepositoryTreeModel model = new RepositoryTreeModel(myVCS, showFiles, this);
    model.setSingleRoot(url);
    Disposer.register(this, model);
    myRepositoryTree.setModel(model);
    myRepositoryTree.setRootVisible(true);
    myRepositoryTree.setSelectionRow(0);
  }

  public void expandNode(final @NotNull TreeNode treeNode) {
    final TreeNode[] pathToNode = ((RepositoryTreeModel)myRepositoryTree.getModel()).getPathToRoot(treeNode);

    if ((pathToNode != null) && (pathToNode.length > 0)) {
      final TreePath treePath = new TreePath(pathToNode);
      myRepositoryTree.expandPath(treePath);
    }
  }

  public Collection<TreeNode> getExpandedSubTree(final @NotNull TreeNode treeNode) {
    final TreeNode[] pathToNode = ((RepositoryTreeModel)myRepositoryTree.getModel()).getPathToRoot(treeNode);

    final Enumeration<TreePath> expanded = myRepositoryTree.getExpandedDescendants(new TreePath(pathToNode));

    final List<TreeNode> result = new ArrayList<>();
    if (expanded != null) {
      while (expanded.hasMoreElements()) {
        final TreePath treePath = expanded.nextElement();
        result.add((TreeNode)treePath.getLastPathComponent());
      }
    }
    return result;
  }

  public boolean isExpanded(final @NotNull TreeNode treeNode) {
    final TreeNode[] pathToNode = ((RepositoryTreeModel)myRepositoryTree.getModel()).getPathToRoot(treeNode);

    return (pathToNode != null) && (pathToNode.length > 0) && myRepositoryTree.isExpanded(new TreePath(pathToNode));
  }

  public void addURL(@NotNull Url url) {
    ((RepositoryTreeModel)myRepositoryTree.getModel()).addRoot(url);
  }

  public void removeURL(@NotNull Url url) {
    ((RepositoryTreeModel)myRepositoryTree.getModel()).removeRoot(url);
  }

  public @Nullable DirectoryEntry getSelectedEntry() {
    TreePath selection = myRepositoryTree.getSelectionPath();
    if (selection == null) {
      return null;
    }
    Object element = selection.getLastPathComponent();
    if (element instanceof RepositoryTreeNode node) {
      return node.getSVNDirEntry();
    }
    return null;
  }

  public @Nullable String getSelectedURL() {
    Url selectedUrl = getSelectedSVNURL();
    return selectedUrl == null ? null : selectedUrl.toString();
  }

  public @Nullable Url getSelectedSVNURL() {
    TreePath selection = myRepositoryTree.getSelectionPath();
    if (selection == null) {
      return null;
    }
    Object element = selection.getLastPathComponent();
    if (element instanceof RepositoryTreeNode node) {
      return node.getURL();
    }
    return null;
  }

  public void addChangeListener(TreeSelectionListener l) {
    myRepositoryTree.addTreeSelectionListener(l);
  }

  public void removeChangeListener(TreeSelectionListener l) {
    myRepositoryTree.removeTreeSelectionListener(l);
  }

  public Component getPreferredFocusedComponent() {
    return myRepositoryTree;
  }

  private void createComponent() {
    setLayout(new BorderLayout());
    myRepositoryTree = new Tree();
    myRepositoryTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    myRepositoryTree.setRootVisible(false);
    myRepositoryTree.setShowsRootHandles(true);
    JScrollPane scrollPane =
      ScrollPaneFactory.createScrollPane(myRepositoryTree, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                                         ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
    add(scrollPane, BorderLayout.CENTER);
    myRepositoryTree.setCellRenderer(new SvnRepositoryTreeCellRenderer());
    TreeSpeedSearch search = TreeSpeedSearch.installOn(myRepositoryTree, false, o -> {
      Object component = o.getLastPathComponent();
      if (component instanceof RepositoryTreeNode) {
        return ((RepositoryTreeNode)component).getURL().toDecodedString();
      }
      return null;
    });
    search.setComparator(new SpeedSearchComparator(false, true));

    EditSourceOnDoubleClickHandler.install(myRepositoryTree);
  }

  public @Nullable RepositoryTreeNode getSelectedNode() {
    TreePath selection = myRepositoryTree.getSelectionPath();
    if (selection != null && selection.getLastPathComponent() instanceof RepositoryTreeNode) {
      return (RepositoryTreeNode)selection.getLastPathComponent();
    }
    return null;
  }

  public void setSelectedNode(final @NotNull TreeNode node) {
    final TreeNode[] pathNodes = ((RepositoryTreeModel)myRepositoryTree.getModel()).getPathToRoot(node);
    myRepositoryTree.setSelectionPath(new TreePath(pathNodes));
  }

  public @Nullable VirtualFile getSelectedVcsFile() {
    final RepositoryTreeNode node = getSelectedNode();
    if (node == null) return null;

    DirectoryEntry entry = node.getSVNDirEntry();
    if (entry == null || !entry.isFile()) {
      return null;
    }

    String name = entry.getName();
    FileTypeManager manager = FileTypeManager.getInstance();

    if (entry.getName().lastIndexOf('.') > 0 && !manager.getFileTypeByFileName(name).isBinary()) {
      Url url = node.getURL();
      SvnFileRevision revision =
        new SvnFileRevision(myVCS, Revision.UNDEFINED, Revision.HEAD, url, entry.getAuthor(), entry.getDate(), null, null);

      return new VcsVirtualFile(node.getSVNDirEntry().getName(), revision);
    }
    else {
      return null;
    }
  }

  @Override
  public void uiDataSnapshot(@NotNull DataSink sink) {
    Project project = myVCS.getProject();
    if (project.isDefault()) return;
    sink.set(CommonDataKeys.PROJECT, project);
    VirtualFile vcsFile = getSelectedVcsFile();
    if (vcsFile != null) {
      // do not return OpenFileDescriptor instance here as in that case SelectInAction will be enabled and its invocation (using keyboard)
      // will raise error - see IDEA-104113 - because of the following operations inside SelectInAction.actionPerformed():
      // - at first VcsVirtualFile content will be loaded which for svn results in showing progress dialog
      // - then DataContext from SelectInAction will still be accessed which results in error as current event count has already changed
      // (because of progress dialog)
      sink.set(CommonDataKeys.NAVIGATABLE, new NavigatableAdapter() {
        @Override
        public void navigate(boolean requestFocus) {
          navigate(project, vcsFile, requestFocus);
        }
      });
    }
  }

  @Override
  public void dispose() {
  }

  public void setLazyLoadingExpander(final NotNullFunction<RepositoryBrowserComponent, Expander> expanderFactory) {
    ((RepositoryTreeModel)myRepositoryTree.getModel()).setDefaultExpanderFactory(expanderFactory);
  }

  public @NotNull StatusText getStatusText() {
    return myRepositoryTree.getEmptyText();
  }
}
