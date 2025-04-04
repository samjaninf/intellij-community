// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.svn.difftool;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool.DiffViewer;
import com.intellij.diff.FrameDiffTool.ToolbarComponents;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.ErrorDiffRequest;
import com.intellij.diff.tools.ErrorDiffTool;
import com.intellij.diff.util.DiffUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Divider;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.difftool.SvnDiffSettingsHolder.SvnDiffSettings;
import org.jetbrains.idea.svn.difftool.properties.SvnPropertiesDiffRequest;
import org.jetbrains.idea.svn.difftool.properties.SvnPropertiesDiffViewer;
import org.jetbrains.idea.svn.properties.PropertyData;
import org.jetbrains.idea.svn.properties.PropertyValue;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.*;
import java.util.List;

import static org.jetbrains.idea.svn.SvnBundle.message;

public class SvnDiffViewer implements DiffViewer {
  private static final Logger LOG = Logger.getInstance(SvnDiffViewer.class);

  public static final Key<MyPropertyContext> PROPERTY_CONTEXT_KEY = Key.create("MyPropertyContext");
  public static final Key<Boolean> FOCUSED_VIEWER_KEY = Key.create("SvnFocusedViewer");

  private final @Nullable Project myProject;

  private final @NotNull DiffContext myContext;
  private final @NotNull DiffRequest myPropertyRequest;

  private final @NotNull SvnDiffSettings mySettings;

  private final @NotNull JPanel myPanel;
  private final @NotNull Splitter mySplitter;
  private final @NotNull Wrapper myNotificationPanel;

  private final @NotNull DiffViewer myContentViewer;
  private final @NotNull DiffViewer myPropertiesViewer;

  private final @NotNull FocusListener myContentFocusListener = new MyFocusListener(false);
  private final @NotNull FocusListener myPropertiesFocusListener = new MyFocusListener(true);

  private boolean myPropertiesViewerFocused; // False - content viewer, True - properties
  private boolean myDumbContentViewer;

  public SvnDiffViewer(@NotNull DiffContext context, @NotNull DiffRequest propertyRequest, @NotNull DiffViewer wrappingViewer) {
    myProject = context.getProject();
    myContext = context;
    myPropertyRequest = propertyRequest;
    myContentViewer = wrappingViewer;

    myPropertyRequest.onAssigned(true);

    mySettings = initSettings(context);

    mySplitter = new MySplitter(message("separator.property.changes"));
    mySplitter.setProportion(mySettings.getSplitterProportion());
    mySplitter.setFirstComponent(myContentViewer.getComponent());

    myNotificationPanel = new Wrapper();

    MyPropertyContext propertyContext = initPropertyContext(context);
    myPropertiesViewer = createPropertiesViewer(propertyRequest, propertyContext);

    myPanel = new SvnContentPanel();
    myPanel.add(mySplitter, BorderLayout.CENTER);
    myPanel.add(myNotificationPanel, BorderLayout.SOUTH);

    updatePropertiesPanel();
  }

  private static @NotNull DiffViewer createPropertiesViewer(@NotNull DiffRequest propertyRequest, @NotNull MyPropertyContext propertyContext) {
    if (propertyRequest instanceof SvnPropertiesDiffRequest) {
      return SvnPropertiesDiffViewer.create(propertyContext, ((SvnPropertiesDiffRequest)propertyRequest), true);
    }
    else {
      return ErrorDiffTool.INSTANCE.createComponent(propertyContext, propertyRequest);
    }
  }

  @Override
  public @NotNull ToolbarComponents init() {
    installListeners();

    processContextHints();

    ToolbarComponents properties = myPropertiesViewer.init();
    ToolbarComponents components = new ToolbarComponents();
    components.toolbarActions = createToolbar(properties.toolbarActions);
    return components;
  }

  @Override
  public void dispose() {
    destroyListeners();

    updateContextHints();

    Disposer.dispose(myPropertiesViewer);

    myPropertyRequest.onAssigned(false);
  }

  private void processContextHints() {
    if (myContext.getUserData(FOCUSED_VIEWER_KEY) == Boolean.TRUE) myPropertiesViewerFocused = true;
    myDumbContentViewer = myContentViewer.getPreferredFocusedComponent() == null;
  }

  private void updateContextHints() {
    if (!myDumbContentViewer && !mySettings.isHideProperties()) myContext.putUserData(FOCUSED_VIEWER_KEY, myPropertiesViewerFocused);
    mySettings.setSplitterProportion(mySplitter.getProportion());
  }

  //
  // Diff
  //

  private @Nullable JComponent createNotification() {
    if (myPropertyRequest instanceof ErrorDiffRequest) {
      return createNotification(((ErrorDiffRequest)myPropertyRequest).getMessage(), EditorNotificationPanel.Status.Error);
    }

    List<DiffContent> contents = ((SvnPropertiesDiffRequest)myPropertyRequest).getContents();

    Map<String, PropertyValue> before = getProperties(contents.get(0));
    Map<String, PropertyValue> after = getProperties(contents.get(1));

    if (before.isEmpty() && after.isEmpty()) return null;

    if (!before.keySet().equals(after.keySet())) {
      return createNotification(message("label.svn.properties.changed"), EditorNotificationPanel.Status.Info);
    }

    for (String key : before.keySet()) {
      if (!Comparing.equal(before.get(key), after.get(key))) {
        return createNotification(message("label.svn.properties.changed"), EditorNotificationPanel.Status.Info);
      }
    }

    return null;
  }

  private static @NotNull Map<String, PropertyValue> getProperties(@NotNull DiffContent content) {
    if (content instanceof EmptyContent) return Collections.emptyMap();

    List<PropertyData> properties = ((SvnPropertiesDiffRequest.PropertyContent)content).getProperties();

    Map<String, PropertyValue> map = new HashMap<>();

    for (PropertyData data : properties) {
      if (map.containsKey(data.getName())) LOG.warn("Duplicated property: " + data.getName());
      map.put(data.getName(), data.getValue());
    }

    return map;
  }

  private static @NotNull JPanel createNotification(@NlsContexts.Label @NotNull String text, @NotNull EditorNotificationPanel.Status status) {
    return new EditorNotificationPanel(status).text(text);
  }

  //
  // Misc
  //

  private void updatePropertiesPanel() {
    DiffUtil.runPreservingFocus(myContext, () -> {
      if (!mySettings.isHideProperties()) {
        mySplitter.setSecondComponent(myPropertiesViewer.getComponent());
        myNotificationPanel.setContent(null);
      }
      else {
        mySplitter.setSecondComponent(null);
        myNotificationPanel.setContent(createNotification());
      }
    });
  }

  private @NotNull List<AnAction> createToolbar(@Nullable List<AnAction> propertiesActions) {
    List<AnAction> result = new ArrayList<>();

    if (propertiesActions != null) result.addAll(propertiesActions);

    result.add(new ToggleHidePropertiesAction());

    return result;
  }

  private static @NotNull SvnDiffSettings initSettings(@NotNull DiffContext context) {
    SvnDiffSettings settings = context.getUserData(SvnDiffSettings.KEY);
    if (settings == null) {
      settings = SvnDiffSettings.getSettings();
      context.putUserData(SvnDiffSettings.KEY, settings);
    }
    return settings;
  }

  private @NotNull MyPropertyContext initPropertyContext(@NotNull DiffContext context) {
    MyPropertyContext propertyContext = context.getUserData(PROPERTY_CONTEXT_KEY);
    if (propertyContext == null) {
      propertyContext = new MyPropertyContext();
      context.putUserData(PROPERTY_CONTEXT_KEY, propertyContext);
    }
    return propertyContext;
  }

  private void installListeners() {
    myContentViewer.getComponent().addFocusListener(myContentFocusListener);
    myPropertiesViewer.getComponent().addFocusListener(myPropertiesFocusListener);
  }

  private void destroyListeners() {
    myContentViewer.getComponent().removeFocusListener(myContentFocusListener);
    myPropertiesViewer.getComponent().removeFocusListener(myPropertiesFocusListener);
  }

  //
  // Getters
  //

  @Override
  public @NotNull JComponent getComponent() {
    return myPanel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    if (myPropertiesViewerFocused) {
      JComponent component = getPropertiesPreferredFocusedComponent();
      if (component != null) return component;
      return myContentViewer.getPreferredFocusedComponent();
    }
    else {
      JComponent component = myContentViewer.getPreferredFocusedComponent();
      if (component != null) return component;
      return getPropertiesPreferredFocusedComponent();
    }
  }

  private @Nullable JComponent getPropertiesPreferredFocusedComponent() {
    if (mySettings.isHideProperties()) return null;
    return myPropertiesViewer.getPreferredFocusedComponent();
  }

  //
  // Actions
  //

  private class ToggleHidePropertiesAction extends ToggleAction implements DumbAware {
    ToggleHidePropertiesAction() {
      ActionUtil.copyFrom(this, "Subversion.TogglePropertiesDiff");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return !mySettings.isHideProperties();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      mySettings.setHideProperties(!state);
      updatePropertiesPanel();
    }
  }

  //
  // Helpers
  //

  private class SvnContentPanel extends JPanel implements UiDataProvider {
    private SvnContentPanel() {
      super(new BorderLayout());
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
      if (myPropertiesViewerFocused) {
        DataSink.uiDataSnapshot(sink, myContentViewer);
        DataSink.uiDataSnapshot(sink, myPropertiesViewer);
      }
      else {
        DataSink.uiDataSnapshot(sink, myPropertiesViewer);
        DataSink.uiDataSnapshot(sink, myContentViewer);
      }
    }
  }

  private class MyPropertyContext extends DiffContext {
    @Override
    public @Nullable Project getProject() {
      return myContext.getProject();
    }

    @Override
    public boolean isWindowFocused() {
      return myContext.isWindowFocused();
    }

    @Override
    public boolean isFocusedInWindow() {
      return DiffUtil.isFocusedComponentInWindow(myPropertiesViewer.getComponent());
    }

    @Override
    public void requestFocusInWindow() {
      DiffUtil.requestFocusInWindow(myPropertiesViewer.getPreferredFocusedComponent());
    }
  }

  private class MyFocusListener extends FocusAdapter {
    private final boolean myValue;

    MyFocusListener(boolean value) {
      myValue = value;
    }

    @Override
    public void focusGained(FocusEvent e) {
      myPropertiesViewerFocused = myValue;
    }
  }

  private static class MySplitter extends Splitter {
    private final @NlsContexts.Separator @NotNull String myLabelText;

    MySplitter(@NlsContexts.Separator @NotNull String text) {
      super(true);
      myLabelText = text;
    }

    @Override
    protected Divider createDivider() {
      return new DividerImpl() {
        @Override
        public void setOrientation(boolean isVerticalSplit) {
          if (!isVertical()) LOG.warn("unsupported state: splitter should be vertical");

          removeAll();

          setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));

          JLabel label = new JLabel(myLabelText);
          label.setFont(UIUtil.getOptionPaneMessageFont());
          label.setForeground(UIUtil.getLabelForeground());
          add(label, new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.CENTER, GridBagConstraints.NONE, JBUI.insets(2), 0, 0));
          setDividerWidth(label.getPreferredSize().height + JBUIScale.scale(4));

          revalidate();
          repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
          super.paintComponent(g);
          g.setColor(JBColor.border());
          LinePainter2D.paint((Graphics2D)g, 0, 0, getWidth(), 0);
        }
      };
    }
  }
}
