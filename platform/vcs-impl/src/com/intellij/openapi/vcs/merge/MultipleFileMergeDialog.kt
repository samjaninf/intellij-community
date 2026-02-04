// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.merge

import com.intellij.CommonBundle
import com.intellij.configurationStore.StoreReloadManager
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.InvalidDiffRequestException
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.MergeUtil
import com.intellij.diff.statistics.MergeAction
import com.intellij.diff.statistics.MergeStatisticsCollector
import com.intellij.diff.util.DiffUtil
import com.intellij.ide.DataManager
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.command.WriteCommandAction.writeCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diff.DiffBundle
import com.intellij.openapi.editor.ReadOnlyModificationException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsContexts.ColumnName
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileTooBigException
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.TableUtil
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.treeStructure.treetable.DefaultTreeTableExpander
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.containers.Convertor
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.initOnShow
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode

open class MultipleFileMergeDialog(
  private val project: Project?,
  files: List<VirtualFile>,
  private val mergeProvider: MergeProvider,
  private val mergeDialogCustomizer: MergeDialogCustomizer
) : DialogWrapper(project) {
  private val unresolvedFiles = files.toMutableList()
  private val _processedFiles = mutableListOf<VirtualFile>()
  private val mergeSession = (mergeProvider as? MergeProvider2)?.createMergeSession(files)
  val processedFiles: List<VirtualFile> get() = _processedFiles
  private val tableModel = ListTreeTableModelOnColumns(DefaultMutableTreeNode(),
                                                       CustomColumns.createColumns(mergeDialogCustomizer, mergeSession))
  private lateinit var table: TreeTable

  private var groupByDirectory: Boolean = false
    get() = when {
      project != null -> VcsConfiguration.getInstance(project).GROUP_MULTIFILE_MERGE_BY_DIRECTORY
      else -> field
    }
    set(value) = when {
      project != null -> VcsConfiguration.getInstance(project).GROUP_MULTIFILE_MERGE_BY_DIRECTORY = value
      else -> field = value
    }

  init {
    project?.blockReloadingProjectOnExternalChanges()
    title = mergeDialogCustomizer.getMultipleFileDialogTitle()
    @Suppress("LeakingThis")
    init()

    updateTree(SetDefaultTreeStateStrategy())
  }

  override fun createCenterPanel(): JComponent {
    lateinit var acceptYoursButton: JButton
    lateinit var acceptTheirsButton: JButton
    lateinit var mergeButton: JButton

    table = MergeConflictsTreeTable(tableModel).apply {
      val virtualFileRenderer = object : ChangesBrowserNodeRenderer(project, { !groupByDirectory }, false) {
        override fun calcFocusedState() = UIUtil.isAncestor(this@MultipleFileMergeDialog.peer.window,
                                                            IdeFocusManager.getInstance(project).focusOwner)
      }.apply {
        font = UIUtil.getListFont()
      }

      setTreeCellRenderer(virtualFileRenderer)
      rowHeight = virtualFileRenderer.preferredSize.height
      preferredScrollableViewportSize = JBUI.size(600, 300)

      object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean {
          if (EditSourceOnDoubleClickHandler.isToggleEvent(tree, event)) return false
          showMergeDialog(selectedFiles)
          return true
        }
      }.installOn(this)
    }.also { table ->
      TableSpeedSearch.installOn(table, Convertor { (it as? VirtualFile)?.name })

      DataManager.registerDataProvider(table) { dataId ->
        when {
          PlatformDataKeys.TREE_EXPANDER.`is`(dataId) -> DefaultTreeTableExpander(table)
          else -> null
        }
      }
    }

    val panel = panel {
      row {
        label(VcsBundle.message("merge.loading.merge.details")).applyToComponent {
          initOnShow("MultipleFileMergeDialog - Load Label") {
            @Suppress("HardCodedStringLiteral") // withContext loses the nls annotation
            val title = withContext(Dispatchers.Default) {
              mergeDialogCustomizer.getMultipleFileMergeDescription(unresolvedFiles)
            }
            text = title
          }
        }
      }

      row {
        scrollCell(table)
          .align(Align.FILL)
          .resizableColumn()

        panel {
          row {
            acceptYoursButton = button(VcsBundle.message("multiple.file.merge.accept.yours")) {
              acceptForResolution(MergeSession.Resolution.AcceptedYours, table.selectedFiles)
            }.align(AlignX.FILL)
              .component
          }
          row {
            acceptTheirsButton = button(VcsBundle.message("multiple.file.merge.accept.theirs")) {
              acceptForResolution(MergeSession.Resolution.AcceptedTheirs, table.selectedFiles)
            }.align(AlignX.FILL)
              .component
          }
          row {
            val mergeAction = object : AbstractAction(VcsBundle.message("multiple.file.merge.merge")) {
              override fun actionPerformed(e: ActionEvent) {
                showMergeDialog(table.selectedFiles)
              }
            }
            mergeAction.putValue(DEFAULT_ACTION, true)
            mergeButton = createJButtonForAction(mergeAction)
            cell(mergeButton)
              .align(AlignX.FILL)
          }
        }.align(AlignY.TOP)
      }.resizableRow()

      if (project != null) {
        row {
          checkBox(VcsBundle.message("multiple.file.merge.group.by.directory.checkbox"))
            .selected(groupByDirectory)
            .applyToComponent {
              addChangeListener { toggleGroupByDirectory(isSelected) }
            }
        }
      }
    }

    fun updateButtonState() {
      val selectedFiles = table.selectedFiles
      val haveSelection = selectedFiles.any()
      val haveUnmergeableFiles = selectedFiles.any { mergeSession?.canMerge(it) == false }
      val haveUnacceptableFiles =
        selectedFiles.any { mergeSession != null && mergeSession !is MergeSessionEx && !mergeSession.canMerge(it) }

      acceptYoursButton.isEnabled = haveSelection && !haveUnacceptableFiles
      acceptTheirsButton.isEnabled = haveSelection && !haveUnacceptableFiles
      mergeButton.isEnabled = haveSelection && !haveUnmergeableFiles
    }

    table.tree.selectionModel.addTreeSelectionListener { updateButtonState() }

    return panel
  }

  private fun toggleGroupByDirectory(state: Boolean) {
    if (groupByDirectory == state) return
    groupByDirectory = state
    updateTree(OnGroupingChangeTreeStateStrategy())
  }

  private fun <State> updateTree(treeStateStrategy: TreeTableStateStrategy<State>) {
    val factory = when {
      project != null && groupByDirectory -> ChangesGroupingSupport.findFactory(ChangesGroupingSupport.DIRECTORY_GROUPING)
                                             ?: NoneChangesGroupingFactory
      else -> NoneChangesGroupingFactory
    }
    val model = TreeModelBuilder.buildFromVirtualFiles(project, factory, unresolvedFiles)

    val savedState = treeStateStrategy.saveState(table)
    tableModel.setRoot(model.root as TreeNode)
    treeStateStrategy.restoreState(table, savedState)

    (table.model as? AbstractTableModel)?.fireTableDataChanged()

    TableUtil.scrollSelectionToVisible(table)
  }

  override fun createActions(): Array<Action> {
    cancelAction.putValue(Action.NAME, CommonBundle.getCloseButtonText())
    return arrayOf(cancelAction)
  }

  override fun dispose() {
    project?.unblockReloadingProjectOnExternalChanges()
    super.dispose()
  }

  @NonNls
  override fun getDimensionServiceKey(): String = "MultipleFileMergeDialog"

  @JvmSuppressWildcards
  protected open fun beforeResolve(files: Collection<VirtualFile>): Boolean {
    return true
  }

  private fun acceptForResolution(resolution: MergeSession.Resolution, files: List<VirtualFile>) {
    val (binaryFiles, textFiles) = files.partition { mergeProvider.isBinary(it) }
    acceptRevision(resolution, binaryFiles)
    acceptRevision(resolution, textFiles)
  }

  private fun acceptRevision(resolution: MergeSession.Resolution, files: List<VirtualFile>) {
    assert(resolution.yoursOrTheirs())

    val side = if (resolution == MergeSession.Resolution.AcceptedYours) MergeAction.LEFT else MergeAction.RIGHT
    MergeStatisticsCollector.logButtonClickOnTable(project, side)

    FileDocumentManager.getInstance().saveAllDocuments()

    runWithModalProgressBlocking(ModalTaskOwner.component(contentPanel),
                                 VcsBundle.message("multiple.file.merge.dialog.progress.title.resolving.conflicts")) {
      if (!beforeResolve(files)) {
        return@runWithModalProgressBlocking
      }

      runCatching {
        if (mergeSession is MergeSessionEx) {
          mergeSession.acceptFilesRevisions(files, resolution)
          for (file in files) {
            checkMarkModifiedProject(project, file)
          }

          markFilesProcessed(files, resolution)
        }
        else {
          for (file in files) {
            val data = mergeProvider.loadRevisions(file)
            withContext(Dispatchers.UiWithModelAccess) {
              resolveFileViaContent(file, resolution, data)
            }
            checkMarkModifiedProject(project, file)
            markFileProcessed(file, resolution)
          }
        }
      }.getOrHandleException {
        withContext(Dispatchers.UiWithModelAccess) {
          Messages.showErrorDialog(contentPanel, VcsBundle.message(
            "multiple.file.merge.dialog.message.error.saving.merged.data",
            it.message))
        }
      }
    }

    updateModelFromFiles()
  }

  @RequiresEdt
  private fun resolveFileViaContent(file: VirtualFile, resolution: MergeSession.Resolution, data: MergeData) {
    if (!DiffUtil.makeWritable(project, file)) {
      throw IOException(UIBundle.message("file.is.read.only.message.text", file.presentableUrl))
    }

    val isCurrent = resolution == MergeSession.Resolution.AcceptedYours
    val message = if (isCurrent) VcsBundle.message("multiple.file.merge.dialog.command.name.accept.yours")
    else VcsBundle.message("multiple.file.merge.dialog.command.name.accept.theirs")

    writeCommandAction(project).withName(message).run<Exception> {
      if (isCurrent) {
        file.setBinaryContent(data.CURRENT)
      }
      else {
        file.setBinaryContent(data.LAST)
      }
    }
  }

  private fun markFilesProcessed(files: List<VirtualFile>, resolution: MergeSession.Resolution) {
    unresolvedFiles.removeAll(files)
    if (mergeSession is MergeSessionEx) {
      mergeSession.conflictResolvedForFiles(files, resolution)
    }
    else if (mergeSession != null) {
      files.forEach {
        mergeSession.conflictResolvedForFile(it, resolution)
      }
    }
    else {
      files.forEach {
        mergeProvider.conflictResolvedForFile(it)
      }
    }
    _processedFiles.addAll(files)

    if (project != null) VcsDirtyScopeManager.getInstance(project).filesDirty(files, emptyList())
  }

  private fun markFileProcessed(file: VirtualFile, resolution: MergeSession.Resolution) {
    markFilesProcessed(listOf(file), resolution)
  }

  private fun updateModelFromFiles() {
    if (unresolvedFiles.isEmpty()) {
      doCancelAction()
    }
    else {
      updateTree(OnModelChangeTreeStateStrategy())
      table.requestFocusInWindow()
    }
  }

  @RequiresBlockingContext
  @RequiresEdt
  private fun showMergeDialog(files: List<VirtualFile>) {
    val requestFactory = DiffRequestFactory.getInstance()
    if (files.isEmpty()) return
    if (!beforeResolve(files)) {
      return
    }

    runForFilesWithErrorHandling(files) { file ->
      val filePath = VcsUtil.getFilePath(file)

      val conflictData = ProgressManager.getInstance().runProcessWithProgressSynchronously(ThrowableComputable<ConflictData, VcsException> {
        val mergeData = mergeProvider.loadRevisions(file)

        val title = tryCompute { mergeDialogCustomizer.getMergeWindowTitle(file) }

        val conflictTitles = listOf(
          tryCompute { mergeDialogCustomizer.getLeftPanelTitle(file) },
          tryCompute { mergeDialogCustomizer.getCenterPanelTitle(file) },
          tryCompute { mergeDialogCustomizer.getRightPanelTitle(file, mergeData.LAST_REVISION_NUMBER) }
        )

        val titleCustomizer = tryCompute { mergeDialogCustomizer.getTitleCustomizerList(filePath) }
                              ?: MergeDialogCustomizer.DEFAULT_CUSTOMIZER_LIST

        ConflictData(mergeData, title, conflictTitles, titleCustomizer)
      }, VcsBundle.message("multiple.file.merge.dialog.progress.title.loading.revisions"), true, project)

      val mergeData = conflictData.mergeData
      val byteContents = listOf(mergeData.CURRENT, mergeData.ORIGINAL, mergeData.LAST)
      val contentTitles = conflictData.contentTitles
      val title = conflictData.title

      val callback = { result: MergeResult ->
        saveDocument(file)
        checkMarkModifiedProject(project, file)

        if (result != MergeResult.CANCEL) {
          ProgressManager.getInstance()
            .runProcessWithProgressSynchronously({ markFileProcessed(file, getSessionResolution(result)) },
                                                 VcsBundle.message("multiple.file.merge.dialog.progress.title.resolving.conflicts"), true,
                                                 project, contentPanel)
        }
      }


      val request = if (mergeProvider.isBinary(file)) { // respect MIME-types in svn
        requestFactory.createBinaryMergeRequest(project, file, byteContents, title, contentTitles, callback)
      }
      else {
        requestFactory.createMergeRequest(project, file, byteContents, mergeData.CONFLICT_TYPE, title, contentTitles, callback)
      }

      MergeUtils.putRevisionInfos(request, mergeData)
      conflictData.contentTitleCustomizers.run {
        DiffUtil.addTitleCustomizers(request, listOf(leftTitleCustomizer, centerTitleCustomizer, rightTitleCustomizer))
      }
      DiffManager.getInstance().showMerge(project, request)
    }
    updateModelFromFiles()
  }

  private fun runForFilesWithErrorHandling(files: List<VirtualFile>, handler: (VirtualFile) -> Unit) {
    try {
      files.forEach(handler)
    }
    catch (ex: VcsException) {
      Messages.showErrorDialog(contentPanel, VcsBundle.message("multiple.file.merge.dialog.error.loading.revisions.to.merge", ex.message))
    }
    catch (e: InvalidDiffRequestException) {
      when (e.cause) {
        is FileTooBigException -> {
          Messages.showErrorDialog(contentPanel,
                                   VcsBundle.message("multiple.file.merge.dialog.message.file.too.big.to.be.loaded"),
                                   VcsBundle.message("multiple.file.merge.dialog.title.can.t.show.merge.dialog"))
        }

        is ReadOnlyModificationException -> {
          Messages.showErrorDialog(contentPanel,
                                   DiffBundle.message("error.cant.resolve.conflicts.in.a.read.only.file"),
                                   VcsBundle.message("multiple.file.merge.dialog.title.can.t.show.merge.dialog"))
        }
        else -> {
          LOG.error(e)
          Messages.showErrorDialog(contentPanel, e.message, VcsBundle.message("multiple.file.merge.dialog.title.can.t.show.merge.dialog"))
        }
      }
    }
  }

  override fun getPreferredFocusedComponent(): JComponent? = table
}

private fun <T> tryCompute(task: () -> T): T? {
  try {
    return task()
  }
  catch (e: ProcessCanceledException) {
    throw e
  }
  catch (e: VcsException) {
    LOG.warn(e)
  }
  catch (e: Exception) {
    LOG.error(e)
  }
  return null
}

private val LOG = Logger.getInstance(MultipleFileMergeDialog::class.java)

private fun Project?.blockReloadingProjectOnExternalChanges() {
  this ?: return
  StoreReloadManager.getInstance(this).blockReloadingProjectOnExternalChanges()
}

private fun Project?.unblockReloadingProjectOnExternalChanges() {
  this ?: return
  StoreReloadManager.getInstance(this).unblockReloadingProjectOnExternalChanges()
}

private fun checkMarkModifiedProject(project: Project?, file: VirtualFile) {
  MergeUtil.reportProjectFileChangeIfNeeded(project, file)
}

private fun saveDocument(file: VirtualFile) {
  val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
  application.runWriteAction { FileDocumentManager.getInstance().saveDocument(document) }
}

private fun MergeSession.Resolution.yoursOrTheirs(): Boolean = when (this) {
  MergeSession.Resolution.AcceptedYours, MergeSession.Resolution.AcceptedTheirs -> true
  MergeSession.Resolution.Merged -> false
}

private fun getSessionResolution(result: MergeResult): MergeSession.Resolution = when (result) {
  MergeResult.LEFT -> MergeSession.Resolution.AcceptedYours
  MergeResult.RIGHT -> MergeSession.Resolution.AcceptedTheirs
  MergeResult.RESOLVED -> MergeSession.Resolution.Merged
  MergeResult.CANCEL -> throw IllegalArgumentException(result.name)
}

private val TreeTable.selectedFiles: List<VirtualFile>
  get() = VcsTreeModelData.selected(tree).userObjects(VirtualFile::class.java)

private object CustomColumns {
  fun createColumns(customizer: MergeDialogCustomizer, session: MergeSession?): Array<ColumnInfo<*, *>> {
    val columns = ArrayList<ColumnInfo<*, *>>()
    columns.add(object : ColumnInfo<DefaultMutableTreeNode, Any>(VcsBundle.message("multiple.file.merge.column.name")) {
      override fun valueOf(node: DefaultMutableTreeNode) = node.userObject
      override fun getColumnClass(): Class<*> = TreeTableModel::class.java
    })

    val mergeInfoColumns = session?.mergeInfoColumns
    if (mergeInfoColumns != null) {
      var customColumnNames = customizer.getColumnNames()
      if (customColumnNames != null && customColumnNames.size != mergeInfoColumns.size) {
        LOG.error("Custom column names ($customColumnNames) don't match default columns ($mergeInfoColumns)")
        customColumnNames = null
      }
      mergeInfoColumns.mapIndexedTo(columns) { index, columnInfo ->
        ColumnInfoAdapter(columnInfo, customColumnNames?.get(index) ?: columnInfo.name)
      }
    }
    return columns.toTypedArray()
  }


  private class ColumnInfoAdapter(
    private val base: ColumnInfo<Any, Any>,
    private val columnName: @ColumnName String,
  ) : ColumnInfo<DefaultMutableTreeNode, Any>(columnName) {
    override fun valueOf(node: DefaultMutableTreeNode) = (node.userObject as? VirtualFile)?.let { base.valueOf(it) }
    override fun getMaxStringValue() = base.maxStringValue
    override fun getAdditionalWidth() = base.additionalWidth
    override fun getTooltipText() = base.tooltipText ?: columnName
  }
}

/**
 * See [ChangesTree.TreeStateStrategy] that cannot be applied to [TreeTable]
 */
private interface TreeTableStateStrategy<T> {
  fun saveState(table: TreeTable): T

  fun restoreState(table: TreeTable, state: T)
}

private class SetDefaultTreeStateStrategy : TreeTableStateStrategy<Any?> {
  override fun saveState(table: TreeTable): Any? = null

  override fun restoreState(table: TreeTable, state: Any?) {
    TreeUtil.expandAll(table.tree)
    TreeUtil.promiseSelectFirstLeaf(table.tree)
  }
}

private class OnGroupingChangeTreeStateStrategy : TreeTableStateStrategy<OnGroupingChangeTreeStateStrategy.SelectionState> {
  override fun saveState(table: TreeTable): SelectionState {
    val selectedFiles = table.selectedFiles
    return SelectionState(selectedFiles)
  }

  override fun restoreState(table: TreeTable, state: SelectionState) {
    TreeUtil.expandAll(table.tree)

    val newRoot = table.tree.model.root as DefaultMutableTreeNode
    val treePaths = state.selectedFiles
      .mapNotNull { file -> TreeUtil.findNodeWithObject(newRoot, file) }
      .map { node -> TreeUtil.getPath(newRoot, node) }
    TreeUtil.selectPaths(table.tree, treePaths)

    if (table.tree.selectionCount == 0) {
      TreeUtil.promiseSelectFirstLeaf(table.tree)
    }
  }

  class SelectionState(val selectedFiles: List<VirtualFile>)
}

private class OnModelChangeTreeStateStrategy : TreeTableStateStrategy<OnModelChangeTreeStateStrategy.SelectionState> {
  override fun saveState(table: TreeTable): SelectionState {
    val treeState = TreeState.createOn(table.tree, true, true)
    val firstSelectedIndex = table.selectionModel.minSelectionIndex
    return SelectionState(treeState, firstSelectedIndex)
  }

  override fun restoreState(table: TreeTable, state: SelectionState) {
    state.treeState.applyTo(table.tree)

    if (table.tree.selectionCount == 0) {
      val toSelect = state.firstSelectedIndex.coerceAtMost(table.rowCount - 1)
      table.selectionModel.setSelectionInterval(toSelect, toSelect)
    }
  }

  class SelectionState(val treeState: TreeState, val firstSelectedIndex: Int)
}

private data class ConflictData(
  val mergeData: MergeData,
  val title: @NlsContexts.DialogTitle String?,
  val contentTitles: List<@NlsContexts.Label String?>,
  val contentTitleCustomizers: MergeDialogCustomizer.DiffEditorTitleCustomizerList,
)