// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins.newui

import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.PluginsGroupType
import com.intellij.ide.plugins.newui.PluginLogo.endBatchMode
import com.intellij.ide.plugins.newui.PluginLogo.startBatchMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.EDT
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.accessibility.AccessibleAnnouncerUtil
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.ScrollPaneConstants

@ApiStatus.Internal
abstract class SearchResultPanel(
  @JvmField val controller: SearchPopupController,
  @JvmField val myPanel: PluginsGroupComponentWithProgress,
  isMarketplace: Boolean,
) {
  private var myVerticalScrollBar: JScrollBar? = null
  var group: PluginsGroup
    private set
  private var myQuery = ""
  private var myRunQuery: AtomicBoolean? = null
  private val isMarketplace: Boolean
  private var isLoading = false
  private var myAnnounceSearchResultsAlarm: SingleAlarm? = null

  @JvmField protected var myPostFillGroupCallback: Runnable? = null

  init {
    myPanel.getAccessibleContext().setAccessibleName(IdeBundle.message("title.search.results"))
    this.isMarketplace = isMarketplace
    this.group = PluginsGroup(
      IdeBundle.message("title.search.results"),
      if (isMarketplace) PluginsGroupType.SEARCH else PluginsGroupType.SEARCH_INSTALLED
    )

    setEmptyText("")

    loading(false)
  }

  val panel: PluginsGroupComponent
    get() = myPanel

  fun createScrollPane(): JComponent {
    val pane = JBScrollPane(myPanel)
    pane.setBorder(JBUI.Borders.empty())
    myVerticalScrollBar = pane.getVerticalScrollBar()
    return pane
  }

  fun createVScrollPane(): JComponent {
    val pane = createScrollPane() as JBScrollPane
    pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
    pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    return pane
  }

  protected open fun setEmptyText(query: String) {
    myPanel.getEmptyText().setText(IdeBundle.message("empty.text.nothing.found"))
  }

  val isQueryEmpty: Boolean
    get() = myQuery.isEmpty()

  fun setEmptyQuery() {
    myQuery = ""
  }

  var query: String
    get() = StringUtil.defaultIfEmpty(myQuery, "")
    set(query) {
      assert(EDT.isCurrentThreadEdt())

      setEmptyText(query)

      if (query == myQuery) {
        return
      }

      if (myRunQuery != null) {
        myRunQuery!!.set(false)
        myRunQuery = null
        loading(false)
      }

      removeGroup()
      myQuery = query

      if (!this.isQueryEmpty) {
        handleQuery(query)
      }
    }

  private fun handleQuery(query: String) {
    loading(true)

    myRunQuery = AtomicBoolean(true)
    val runQuery = myRunQuery!!
    val group = this.group

    ApplicationManager.getApplication().executeOnPooledThread(Runnable {
      handleQuery(query, group, runQuery)
    })
  }

  protected fun updatePanel(runQuery: AtomicBoolean) {
    ApplicationManager.getApplication().invokeLater(Runnable {
      assert(EDT.isCurrentThreadEdt())
      if (!runQuery.get()) {
        return@Runnable
      }
      myRunQuery = null

      loading(false)

      if (!group.getDescriptors().isEmpty()) {
        group.titleWithCount()
        try {
          startBatchMode()
          myPanel.addLazyGroup(this.group, myVerticalScrollBar!!, 100, Runnable { this.fullRepaint() })
        }
        finally {
          endBatchMode()
        }
      }

      announceSearchResultsWithDelay()
      myPanel.initialSelection(false)
      runPostFillGroupCallback()
      fullRepaint()
    }, ModalityState.any())
  }

  protected abstract fun handleQuery(query: String, result: PluginsGroup, runQuery: AtomicBoolean?)

  private fun runPostFillGroupCallback() {
    if (myPostFillGroupCallback != null) {
      myPostFillGroupCallback!!.run()
      myPostFillGroupCallback = null
    }
  }

  private fun loading(start: Boolean) {
    val panel = myPanel
    if (start) {
      isLoading = true
      panel.showLoadingIcon()
    }
    else {
      isLoading = false
      panel.hideLoadingIcon()
    }
  }

  fun dispose() {
    myPanel.dispose()
    if (myAnnounceSearchResultsAlarm != null) {
      Disposer.dispose(myAnnounceSearchResultsAlarm!!)
    }
  }

  fun removeGroup() {
    if (group.ui != null) {
      myPanel.removeGroup(this.group)
      fullRepaint()
    }
    this.group = PluginsGroup(
      IdeBundle.message("title.search.results"),
      if (isMarketplace) PluginsGroupType.SEARCH else PluginsGroupType.SEARCH_INSTALLED
    )
  }

  fun fullRepaint() {
    myPanel.doLayout()
    myPanel.revalidate()
    myPanel.repaint()
  }

  private fun announceSearchResultsWithDelay() {
    if (AccessibleAnnouncerUtil.isAnnouncingAvailable()) {
      if (myAnnounceSearchResultsAlarm == null) {
        myAnnounceSearchResultsAlarm =
          SingleAlarm(
            Runnable { this.announceSearchResults() },
            250,
            null,
            Alarm.ThreadToUse.SWING_THREAD,
            ModalityState.stateForComponent(myPanel)
          )
      }

      myAnnounceSearchResultsAlarm!!.cancelAndRequest()
    }
  }

  private fun announceSearchResults() {
    if (myPanel.isShowing() && !isLoading) {
      val pluginsTabName = IdeBundle.message(if (isMarketplace) "plugin.manager.tab.marketplace" else "plugin.manager.tab.installed")
      val message = IdeBundle.message(
        "plugins.configurable.search.result.0.plugins.found.in.1",
        group.getDescriptors().size, pluginsTabName
      )
      AccessibleAnnouncerUtil.announce(myPanel, message, false)
    }
  }
}