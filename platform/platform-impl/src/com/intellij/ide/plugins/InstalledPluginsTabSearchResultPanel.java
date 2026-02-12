// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.plugins.marketplace.CheckErrorsResult;
import com.intellij.ide.plugins.marketplace.statistics.PluginManagerUsageCollector;
import com.intellij.ide.plugins.newui.ListPluginComponent;
import com.intellij.ide.plugins.newui.MyPluginModel;
import com.intellij.ide.plugins.newui.PluginModelAsyncOperationsExecutor;
import com.intellij.ide.plugins.newui.PluginModelFacade;
import com.intellij.ide.plugins.newui.PluginUiModel;
import com.intellij.ide.plugins.newui.PluginUiModelKt;
import com.intellij.ide.plugins.newui.PluginsGroup;
import com.intellij.ide.plugins.newui.PluginsGroupComponent;
import com.intellij.ide.plugins.newui.PluginsGroupComponentWithProgress;
import com.intellij.ide.plugins.newui.SearchQueryParser;
import com.intellij.ide.plugins.newui.SearchResultPanel;
import com.intellij.ide.plugins.newui.SearchUpDownPopupController;
import com.intellij.ide.plugins.newui.UiPluginManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.containers.ContainerUtil;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.intellij.ide.plugins.PluginManagerConfigurablePanel.applyUpdates;
import static com.intellij.ide.plugins.PluginManagerConfigurablePanel.containsQuery;

@ApiStatus.Internal
class InstalledPluginsTabSearchResultPanel extends SearchResultPanel {
  private final @NotNull Consumer<? super PluginsGroupComponent> mySelectionListener;
  private final @Nullable Consumer<String> mySearchInMarketplaceTabHandler;
  private final @NotNull PluginModelFacade myPluginModelFacade;
  private final @NotNull DefaultActionGroup mySearchActionGroup;
  private final @NotNull Supplier<PluginsGroupComponentWithProgress> myInstalledPanelSupplier;

  InstalledPluginsTabSearchResultPanel(@NotNull CoroutineScope coroutineScope,
                                       SearchUpDownPopupController installedController,
                                       PluginsGroupComponentWithProgress panel,
                                       @NotNull DefaultActionGroup searchActionGroup,
                                       @NotNull Supplier<PluginsGroupComponentWithProgress> installedPanelSupplier,
                                       @NotNull Consumer<? super PluginsGroupComponent> selectionListener,
                                       @Nullable Consumer<String> searchInMarketplaceTabHandler,
                                       @NotNull PluginModelFacade pluginModelFacade) {
    super(coroutineScope, installedController, panel, false);
    mySearchActionGroup = searchActionGroup;
    myInstalledPanelSupplier = installedPanelSupplier;
    mySelectionListener = selectionListener;
    mySearchInMarketplaceTabHandler = searchInMarketplaceTabHandler;
    myPluginModelFacade = pluginModelFacade;
  }

  @Override
  protected void setupEmptyText() {
    myPanel.getEmptyText().setText(IdeBundle.message("plugins.configurable.nothing.found"));
    var query = getQuery();
    if (query.contains("/downloaded") || query.contains("/userInstalled") ||
        query.contains("/outdated") ||
        query.contains("/enabled") || query.contains("/disabled") ||
        query.contains("/invalid") ||
        query.contains("/bundled") || query.contains("/updatedBundled")) {
      return;
    }
    if (mySearchInMarketplaceTabHandler != null) {
      myPanel.getEmptyText().appendSecondaryText(IdeBundle.message("plugins.configurable.search.in.marketplace"),
                                                 SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                                                 e -> mySearchInMarketplaceTabHandler.accept(query));
    }
  }

  @Override
  public void setQuery(@NotNull String query) {
    super.setQuery(query);
    SearchQueryParser.Installed parser = new SearchQueryParser.Installed(query);
    for (AnAction action : mySearchActionGroup.getChildren(ActionManager.getInstance())) {
      ((InstalledPluginsTab.InstalledSearchOptionAction)action).setState(parser);
    }
  }

  @Override
  protected void handleQuery(@NotNull String query, @NotNull PluginsGroup result, AtomicBoolean runQuery) {
    int searchIndex = PluginManagerUsageCollector.updateAndGetSearchIndex();
    myPluginModelFacade.getModel().setInvalidFixCallback(null);
    SearchQueryParser.Installed parser = new SearchQueryParser.Installed(query);
    List<PluginUiModel> descriptors = myPluginModelFacade.getModel().getInstalledDescriptors();

    if (!parser.vendors.isEmpty()) {
      for (Iterator<PluginUiModel> I = descriptors.iterator(); I.hasNext(); ) {
        if (!MyPluginModel.isVendor(I.next(), parser.vendors)) {
          I.remove();
        }
      }
    }
    if (!parser.tags.isEmpty()) {
      String sessionId = myPluginModelFacade.getModel().getSessionId();

      for (Iterator<PluginUiModel> I = descriptors.iterator(); I.hasNext(); ) {
        if (!ContainerUtil.intersects(PluginUiModelKt.calculateTags(I.next(), sessionId), parser.tags)) {
          I.remove();
        }
      }
    }
    for (Iterator<PluginUiModel> I = descriptors.iterator(); I.hasNext(); ) {
      PluginUiModel descriptor = I.next();
      if (parser.attributes) {
        if (parser.enabled &&
            (!myPluginModelFacade.isEnabled(descriptor) || !myPluginModelFacade.getErrors(descriptor).isEmpty())) {
          I.remove();
          continue;
        }
        if (parser.disabled &&
            (myPluginModelFacade.isEnabled(descriptor) || !myPluginModelFacade.getErrors(descriptor).isEmpty())) {
          I.remove();
          continue;
        }
        boolean isBundledOrBundledUpdate = descriptor.isBundled() || descriptor.isBundledUpdate();
        if (parser.bundled && !isBundledOrBundledUpdate) {
          I.remove();
          continue;
        }
        if (parser.updatedBundled && !descriptor.isBundledUpdate()) {
          I.remove();
          continue;
        }
        if (parser.userInstalled && isBundledOrBundledUpdate) {
          I.remove();
          continue;
        }
        if (parser.invalid && myPluginModelFacade.getErrors(descriptor).isEmpty()) {
          I.remove();
          continue;
        }
        if (parser.needUpdate && !UiPluginManager.getInstance().isNeedUpdate(descriptor.getPluginId())) {
          I.remove();
          continue;
        }
      }
      if (parser.searchQuery != null && !containsQuery(descriptor, parser.searchQuery)) {
        I.remove();
      }
    }

    result.addModels(descriptors);
    Map<PluginId, CheckErrorsResult> errors = UiPluginManager.getInstance()
      .loadErrors(myPluginModelFacade.getModel().mySessionId.toString(),
                  ContainerUtil.map(descriptors, PluginUiModel::getPluginId));
    result.getPreloadedModel().setErrors(MyPluginModel.getErrors(errors));
    result.getPreloadedModel().setPluginInstallationStates(UiPluginManager.getInstance().getInstallationStatesSync());
    PluginManagerUsageCollector.performInstalledTabSearch(
      ProjectUtil.getActiveProject(), parser, result.getModels(), searchIndex, null);

    if (!result.getModels().isEmpty()) {
      if (parser.invalid) {
        myPluginModelFacade.getModel().setInvalidFixCallback(() -> {
          PluginsGroup group = getGroup();
          if (group.ui == null) {
            myPluginModelFacade.getModel().setInvalidFixCallback(null);
            return;
          }

          PluginsGroupComponent resultPanel = getPanel();

          for (PluginUiModel descriptor : new ArrayList<>(group.getModels())) {
            if (myPluginModelFacade.getErrors(descriptor).isEmpty()) {
              resultPanel.removeFromGroup(group, descriptor);
            }
          }

          group.titleWithCount();
          fullRepaint();

          if (group.getModels().isEmpty()) {
            myPluginModelFacade.getModel().setInvalidFixCallback(null);
            removeGroup();
          }
        });
      }
      else if (parser.needUpdate) {
        result.mainAction = new PluginManagerConfigurablePanel.LinkLabelButton<>(IdeBundle.message("plugin.manager.update.all"), null, (__, ___) -> {
          result.mainAction.setEnabled(false);

          for (ListPluginComponent plugin : result.ui.plugins) {
            plugin.updatePlugin();
          }
        });
      }
      PluginModelAsyncOperationsExecutor.INSTANCE.loadUpdates(getCoroutineScope(), updates -> {
        if (!ContainerUtil.isEmpty(updates)) {
          myPostFillGroupCallback = () -> {
            //noinspection unchecked
            applyUpdates(myPanel, (Collection<PluginUiModel>)updates);
            mySelectionListener.accept(myInstalledPanelSupplier.get());
            mySelectionListener.accept(getPanel());
          };
        }
        return null;
      });
    }
    updatePanel(runQuery);
  }
}
