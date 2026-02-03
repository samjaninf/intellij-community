package com.intellij.agent.workbench.chat

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class CodexChatEditorServiceTest : FileEditorManagerTestCase() {
  override fun setUp() {
    super.setUp()
    FileEditorProvider.EP_FILE_EDITOR_PROVIDER.point.registerExtension(TestChatFileEditorProvider(), testRootDisposable)
  }

  fun testReuseEditorForThread() {
    val service = project.service<CodexChatEditorService>()

    runInEdtAndWait {
      service.openChat("/work/project-a", "thread-1", "Fix auth bug", null)
      service.openChat("/work/project-a", "thread-1", "Fix auth bug", null)
    }

    val files = openedChatFiles()
    assertThat(files).hasSize(1)
  }

  fun testSeparateTabsForSubAgents() {
    val service = project.service<CodexChatEditorService>()

    runInEdtAndWait {
      service.openChat("/work/project-a", "thread-1", "Fix auth bug", "alpha")
      service.openChat("/work/project-a", "thread-1", "Fix auth bug", "beta")
    }

    val files = openedChatFiles()
    assertThat(files).hasSize(2)
  }

  fun testTabTitleUsesThreadTitle() {
    val service = project.service<CodexChatEditorService>()
    val title = "Investigate crash"

    runInEdtAndWait {
      service.openChat("/work/project-a", "thread-2", title, null)
    }

    val file = openedChatFiles().single()
    assertThat(file.name).isEqualTo(title)
  }

  fun testResumeCommandDisablesUpdateCheck() {
    assertThat(buildCodexResumeShellCommand("thread-1")).containsExactly(
      "codex",
      "-c",
      "check_for_update_on_startup=false",
      "resume",
      "thread-1",
    )
  }

  private fun openedChatFiles(): List<CodexChatVirtualFile> {
    return FileEditorManager.getInstance(project).openFiles.filterIsInstance<CodexChatVirtualFile>()
  }
}

private class TestChatFileEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file is CodexChatVirtualFile
  }

  override fun acceptRequiresReadAction(): Boolean = false

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    return TestChatFileEditor(file)
  }

  override fun getEditorTypeId(): String = "agent.workbench-chat-editor-test"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}

private class TestChatFileEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val component = JPanel()

  override fun getComponent(): JComponent = component

  override fun getPreferredFocusedComponent(): JComponent = component

  override fun getName(): String = "CodexChatTestEditor"

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun getFile(): VirtualFile = file

  override fun dispose() = Unit
}
