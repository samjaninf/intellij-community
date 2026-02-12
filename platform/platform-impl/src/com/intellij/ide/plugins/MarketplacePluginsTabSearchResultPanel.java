// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins;

import com.intellij.icons.AllIcons;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.plugins.marketplace.PluginSearchResult;
import com.intellij.ide.plugins.marketplace.ranking.MarketplaceLocalRanker;
import com.intellij.ide.plugins.marketplace.statistics.PluginManagerUsageCollector;
import com.intellij.ide.plugins.newui.LinkComponent;
import com.intellij.ide.plugins.newui.PluginModelAsyncOperationsExecutor;
import com.intellij.ide.plugins.newui.PluginUiModel;
import com.intellij.ide.plugins.newui.PluginsGroup;
import com.intellij.ide.plugins.newui.PluginsGroupComponent;
import com.intellij.ide.plugins.newui.PluginsGroupComponentWithProgress;
import com.intellij.ide.plugins.newui.PluginsViewCustomizer;
import com.intellij.ide.plugins.newui.PluginsViewCustomizerKt;
import com.intellij.ide.plugins.newui.SearchQueryParser;
import com.intellij.ide.plugins.newui.SearchResultPanel;
import com.intellij.ide.plugins.newui.SearchUpDownPopupController;
import com.intellij.ide.plugins.newui.UiPluginManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiserStartupActivityKt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.StatusText;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.Icon;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.intellij.ide.plugins.PluginManagerConfigurablePanel.applyUpdates;
import static com.intellij.ide.plugins.PluginManagerConfigurablePanel.showRightBottomPopup;

@ApiStatus.Internal
class MarketplacePluginsTabSearchResultPanel extends SearchResultPanel {
  private static final Logger LOG = Logger.getInstance(MarketplacePluginsTabSearchResultPanel.class);

  private final Project myProject;
  private final @NotNull Consumer<? super PluginsGroupComponent> mySelectionListener;
  private final @NotNull DefaultActionGroup myMarketplaceSortByGroup;
  private final @NotNull LinkComponent myMarketplaceSortByAction;
  private final @NotNull Supplier<PluginsGroupComponentWithProgress> myMarketplacePanelSupplier;

  MarketplacePluginsTabSearchResultPanel(CoroutineScope coroutineScope,
                                         SearchUpDownPopupController marketplaceController,
                                         PluginsGroupComponentWithProgress panel,
                                         Project project,
                                         @NotNull Consumer<? super PluginsGroupComponent> selectionListener,
                                         @NotNull DefaultActionGroup marketplaceSortByGroup,
                                         @NotNull Supplier<PluginsGroupComponentWithProgress> marketplacePanelSupplier) {
    super(coroutineScope, marketplaceController, panel, true);
    myProject = project;
    mySelectionListener = selectionListener;
    myMarketplaceSortByGroup = marketplaceSortByGroup;
    myMarketplacePanelSupplier = marketplacePanelSupplier;
    myMarketplaceSortByAction = createSortByAction();
  }

  private @NotNull LinkComponent createSortByAction() {
    LinkComponent sortByAction = new LinkComponent() {
      @Override
      protected boolean isInClickableArea(Point pt) {
        return true;
      }

      @Override
      public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
          accessibleContext = new AccessibleLinkComponent();
        }
        return accessibleContext;
      }

      protected class AccessibleLinkComponent extends AccessibleLinkLabel {
        @Override
        public AccessibleRole getAccessibleRole() {
          return AccessibleRole.COMBO_BOX;
        }
      }
    };

    sortByAction.setIcon(new Icon() {
      @Override
      public void paintIcon(Component c, Graphics g, int x, int y) {
        getIcon().paintIcon(c, g, x, y + 1);
      }

      @Override
      public int getIconWidth() {
        return getIcon().getIconWidth();
      }

      @Override
      public int getIconHeight() {
        return getIcon().getIconHeight();
      }

      private static @NotNull Icon getIcon() {
        return AllIcons.General.ButtonDropTriangle;
      }
    }); // TODO: icon
    sortByAction.setPaintUnderline(false);
    sortByAction.setIconTextGap(JBUIScale.scale(4));
    sortByAction.setHorizontalTextPosition(SwingConstants.LEFT);
    sortByAction.setForeground(PluginsGroupComponent.SECTION_HEADER_FOREGROUND);

    //noinspection unchecked
    sortByAction.setListener(
      (component, __) -> showRightBottomPopup(component.getParent().getParent(), IdeBundle.message("plugins.configurable.sort.by"),
                                              myMarketplaceSortByGroup), null);

    DumbAwareAction.create(event -> sortByAction.doClick())
      .registerCustomShortcutSet(KeyEvent.VK_DOWN, 0, sortByAction);
    return sortByAction;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected void handleQuery(@NotNull String query, @NotNull PluginsGroup result, @NotNull AtomicBoolean runQuery) {
    int searchIndex = PluginManagerUsageCollector.updateAndGetSearchIndex();

    SearchQueryParser.Marketplace parser = new SearchQueryParser.Marketplace(query);

    if (parser.internal) {
      try {
        PluginsViewCustomizer.PluginsGroupDescriptor groupDescriptor =
          PluginsViewCustomizerKt.getPluginsViewCustomizer().getInternalPluginsGroupDescriptor();
        if (groupDescriptor != null) {
          if (parser.searchQuery == null) {
            result.addDescriptors(groupDescriptor.getPlugins());
          }
          else {
            for (IdeaPluginDescriptor pluginDescriptor : groupDescriptor.getPlugins()) {
              if (StringUtil.containsIgnoreCase(pluginDescriptor.getName(), parser.searchQuery)) {
                result.addDescriptor(pluginDescriptor);
              }
            }
          }
          result.removeDuplicates();
          result.sortByName();
          return;
        }
      }
      catch (Exception e) {
        LOG.error("Error while loading internal plugins group", e);
      }
    }

    PluginModelAsyncOperationsExecutor.INSTANCE.getCustomRepositoriesPluginMap(getCoroutineScope(), map -> {
      Map<String, List<PluginUiModel>> customRepositoriesMap = (Map<String, List<PluginUiModel>>)map;
      if (parser.suggested && myProject != null) {
        List<@NotNull PluginUiModel> plugins =
          PluginsAdvertiserStartupActivityKt.findSuggestedPlugins(myProject, customRepositoriesMap);
        result.addModels(plugins);
        updateSearchPanel(result, runQuery, plugins);
      }
      else if (!parser.repositories.isEmpty()) {
        for (String repository : parser.repositories) {
          List<PluginUiModel> descriptors = customRepositoriesMap.get(repository);
          if (descriptors == null) {
            continue;
          }
          if (parser.searchQuery == null) {
            result.addModels(descriptors);
          }
          else {
            for (PluginUiModel descriptor : descriptors) {
              if (StringUtil.containsIgnoreCase(descriptor.getName(), parser.searchQuery)) {
                result.addModel(descriptor);
              }
            }
          }
        }
        result.removeDuplicates();
        result.sortByName();
        updateSearchPanel(result, runQuery, result.getModels());
      }
      else {
        PluginModelAsyncOperationsExecutor.INSTANCE
          .performMarketplaceSearch(getCoroutineScope(),
                                    parser.getUrlQuery(),
                                    !result.getModels().isEmpty(),
                                    (searchResult, updates) -> {
                                      applySearchResult(result, searchResult, (List<PluginUiModel>)updates, customRepositoriesMap,
                                                        parser, searchIndex);
                                      updatePanel(runQuery);
                                      return null;
                                    });
      }
      return null;
    });
  }

  private void updateSearchPanel(@NonNull PluginsGroup result, AtomicBoolean runQuery, List<@NotNull PluginUiModel> plugins) {
    Set<PluginId> ids = plugins.stream().map(it -> it.getPluginId()).collect(Collectors.toSet());
    result.getPreloadedModel().setInstalledPlugins(UiPluginManager.getInstance().findInstalledPluginsSync(ids));
    result.getPreloadedModel().setPluginInstallationStates(UiPluginManager.getInstance().getInstallationStatesSync());
    updatePanel(runQuery);
  }

  private void applySearchResult(@NotNull PluginsGroup result,
                                 PluginSearchResult searchResult,
                                 List<PluginUiModel> updates,
                                 Map<String, List<PluginUiModel>> customRepositoriesMap,
                                 SearchQueryParser.Marketplace parser,
                                 int searchIndex) {
    if (searchResult.getError() != null) {
      ApplicationManager.getApplication().invokeLater(
        () -> myPanel.getEmptyText()
          .setText(IdeBundle.message("plugins.configurable.search.result.not.loaded"))
          .appendSecondaryText(
            IdeBundle.message("plugins.configurable.check.internet"),
            StatusText.DEFAULT_ATTRIBUTES, null), ModalityState.any()
      );
    }
    // compare plugin versions between marketplace & custom repositories
    List<PluginUiModel> customPlugins = ContainerUtil.flatten(customRepositoriesMap.values());
    Collection<PluginUiModel> plugins =
      RepositoryHelper.mergePluginModelsFromRepositories(searchResult.getPlugins(),
                                                         customPlugins,
                                                         false);
    result.addModels(0, new ArrayList<>(plugins));

    if (parser.searchQuery != null) {
      List<PluginUiModel> descriptors = ContainerUtil.filter(customPlugins,
                                                             descriptor -> StringUtil.containsIgnoreCase(
                                                               descriptor.getName(),
                                                               parser.searchQuery));
      result.addModels(0, descriptors);
    }

    result.removeDuplicates();

    Map<PluginUiModel, Double> pluginToScore = null;
    final var localRanker = MarketplaceLocalRanker.getInstanceIfEnabled();
    if (localRanker != null) {
      pluginToScore = localRanker.rankPlugins(parser, result.getModels());
    }

    if (!result.getModels().isEmpty()) {
      String title = IdeBundle.message("plugin.manager.action.label.sort.by.1");

      for (AnAction action : myMarketplaceSortByGroup.getChildren(ActionManager.getInstance())) {
        MarketplacePluginsTab.MarketplaceSortByAction sortByAction = (MarketplacePluginsTab.MarketplaceSortByAction)action;
        sortByAction.setState(parser);
        if (sortByAction.myState) {
          title = IdeBundle.message("plugin.manager.action.label.sort.by",
                                    sortByAction.myOption.getPresentableNameSupplier().get());
        }
      }

      myMarketplaceSortByAction.setText(title);
      result.addSecondaryAction(myMarketplaceSortByAction);

      if (!ContainerUtil.isEmpty(updates)) {
        myPostFillGroupCallback = () -> {
          applyUpdates(myPanel, updates);
          mySelectionListener.accept(myMarketplacePanelSupplier.get());
          mySelectionListener.accept(getPanel());
        };
      }
    }
    Set<PluginId> ids = result.getModels().stream().map(it -> it.getPluginId()).collect(Collectors.toSet());
    result.getPreloadedModel().setInstalledPlugins(UiPluginManager.getInstance().findInstalledPluginsSync(ids));
    result.getPreloadedModel().setPluginInstallationStates(UiPluginManager.getInstance().getInstallationStatesSync());
    PluginManagerUsageCollector.INSTANCE.performMarketplaceSearch(ProjectUtil.getActiveProject(), parser, result.getModels(),
                                                                  searchIndex, pluginToScore);
  }
}