// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins.newui;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.PluginsGroupType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.Alarm;
import com.intellij.util.SingleAlarm;
import com.intellij.util.ui.EDT;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.accessibility.AccessibleAnnouncerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.ScrollPaneConstants;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
public abstract class SearchResultPanel {
  public final @NotNull SearchPopupController controller;

  protected final @NotNull PluginsGroupComponentWithProgress myPanel;
  private JScrollBar myVerticalScrollBar;
  private PluginsGroup myGroup;
  private @NotNull String myQuery = "";
  private AtomicBoolean myRunQuery;
  private boolean isMarketplace;
  private boolean isLoading;
  private SingleAlarm myAnnounceSearchResultsAlarm;

  protected Runnable myPostFillGroupCallback;

  public SearchResultPanel(@NotNull SearchPopupController controller,
                           @NotNull PluginsGroupComponentWithProgress panel,
                           boolean isMarketplace) {
    this.controller = controller;
    myPanel = panel;
    myPanel.getAccessibleContext().setAccessibleName(IdeBundle.message("title.search.results"));
    this.isMarketplace = isMarketplace;
    myGroup = new PluginsGroup(IdeBundle.message("title.search.results"),
                               isMarketplace ? PluginsGroupType.SEARCH : PluginsGroupType.SEARCH_INSTALLED);

    setEmptyText("");

    loading(false);
  }

  public @NotNull PluginsGroupComponent getPanel() {
    return myPanel;
  }

  public @NotNull PluginsGroup getGroup() {
    return myGroup;
  }

  public @NotNull JComponent createScrollPane() {
    JBScrollPane pane = new JBScrollPane(myPanel);
    pane.setBorder(JBUI.Borders.empty());
    myVerticalScrollBar = pane.getVerticalScrollBar();
    return pane;
  }

  public @NotNull JComponent createVScrollPane() {
    JBScrollPane pane = (JBScrollPane)createScrollPane();
    pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
    pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    return pane;
  }

  protected void setEmptyText(@NotNull String query) {
    myPanel.getEmptyText().setText(IdeBundle.message("empty.text.nothing.found"));
  }

  public boolean isQueryEmpty() {
    return myQuery.isEmpty();
  }

  public void setEmptyQuery() {
    myQuery = "";
  }

  public @NotNull String getQuery() {
    return StringUtil.defaultIfEmpty(myQuery, "");
  }

  public void setQuery(@NotNull String query) {
    assert EDT.isCurrentThreadEdt();

    setEmptyText(query);

    if (query.equals(myQuery)) {
      return;
    }

    if (myRunQuery != null) {
      myRunQuery.set(false);
      myRunQuery = null;
      loading(false);
    }

    removeGroup();
    myQuery = query;

    if (!isQueryEmpty()) {
      handleQuery(query);
    }
  }

  private void handleQuery(@NotNull String query) {
    loading(true);

    AtomicBoolean runQuery = myRunQuery = new AtomicBoolean(true);
    PluginsGroup group = myGroup;

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      handleQuery(query, group, runQuery);
    });
  }

  protected void updatePanel(AtomicBoolean runQuery) {
    ApplicationManager.getApplication().invokeLater(() -> {
      assert EDT.isCurrentThreadEdt();

      if (!runQuery.get()) {
        return;
      }
      myRunQuery = null;

      loading(false);

      if (!myGroup.getDescriptors().isEmpty()) {
        myGroup.titleWithCount();
        try {
          PluginLogo.startBatchMode();
          myPanel.addLazyGroup(myGroup, myVerticalScrollBar, 100, this::fullRepaint);
        }
        finally {
          PluginLogo.endBatchMode();
        }
      }

      announceSearchResultsWithDelay();
      myPanel.initialSelection(false);
      runPostFillGroupCallback();
      fullRepaint();
    }, ModalityState.any());
  }

  protected abstract void handleQuery(@NotNull String query, @NotNull PluginsGroup result, @Nullable AtomicBoolean runQuery);

  private void runPostFillGroupCallback() {
    if (myPostFillGroupCallback != null) {
      myPostFillGroupCallback.run();
      myPostFillGroupCallback = null;
    }
  }

  private void loading(boolean start) {
    PluginsGroupComponentWithProgress panel = myPanel;
    if (start) {
      isLoading = true;
      panel.showLoadingIcon();
    }
    else {
      isLoading = false;
      panel.hideLoadingIcon();
    }
  }

  public void dispose() {
    myPanel.dispose();
    if (myAnnounceSearchResultsAlarm != null) {
      Disposer.dispose(myAnnounceSearchResultsAlarm);
    }
  }

  public void removeGroup() {
    if (myGroup.ui != null) {
      myPanel.removeGroup(myGroup);
      fullRepaint();
    }
    myGroup = new PluginsGroup(IdeBundle.message("title.search.results"),
                               isMarketplace ? PluginsGroupType.SEARCH : PluginsGroupType.SEARCH_INSTALLED);
  }

  public void fullRepaint() {
    myPanel.doLayout();
    myPanel.revalidate();
    myPanel.repaint();
  }

  private void announceSearchResultsWithDelay() {
    if (AccessibleAnnouncerUtil.isAnnouncingAvailable()) {
      if (myAnnounceSearchResultsAlarm == null) {
        myAnnounceSearchResultsAlarm =
          new SingleAlarm(this::announceSearchResults, 250, null, Alarm.ThreadToUse.SWING_THREAD, ModalityState.stateForComponent(myPanel));
      }

      myAnnounceSearchResultsAlarm.cancelAndRequest();
    }
  }

  private void announceSearchResults() {
    if (myPanel.isShowing() && !isLoading) {
      String pluginsTabName = IdeBundle.message(isMarketplace ? "plugin.manager.tab.marketplace" : "plugin.manager.tab.installed");
      String message = IdeBundle.message("plugins.configurable.search.result.0.plugins.found.in.1",
                                         myGroup.getDescriptors().size(), pluginsTabName);
      AccessibleAnnouncerUtil.announce(myPanel, message, false);
    }
  }
}