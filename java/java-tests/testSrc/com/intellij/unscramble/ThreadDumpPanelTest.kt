// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.unscramble

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import junit.framework.TestCase
import org.jetbrains.annotations.Nls
import java.util.Objects
import javax.swing.Icon


class ThreadDumpPanelTest : LightPlatformTestCase() {
  private lateinit var threadDumpPanel: ThreadDumpPanel
  private lateinit var myConsoleView: ConsoleView

  @Throws(Exception::class)
  override fun setUp() {
    super.setUp()
    val consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    myConsoleView = consoleBuilder.getConsole()
    threadDumpPanel = ThreadDumpPanel.createFromDumpItems(project, myConsoleView, DefaultActionGroup(), emptyList())
  }

  @Throws(Exception::class)
  override fun tearDown() {
    try {
      Disposer.dispose(myConsoleView)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  fun testBasicDump() {
    val tree: Tree = threadDumpPanel.tree
    val dumpItems = createBasicDump()
    UISettings.getInstance().getState().mergeEqualStackTraces = false
    threadDumpPanel.addDumpItems(dumpItems, 0, emptyList(), 0)
    TestCase.assertEquals("Should show all unmerged items", 6, tree.model.getChildCount(tree.model.root))
    // Select first item
    tree.setSelectionRow(0)
    (myConsoleView as ConsoleViewImpl).waitAllRequests()

    // Verify stack trace is printed to console
    val document = (myConsoleView as ConsoleViewImpl).editor!!.document
    val consoleText = document.text
    assertTrue("Console should contain stack trace of the 1st item MyCoroutine1", consoleText.contains("at MainKt.foo(Main.kt:161)"))

    tree.setSelectionRow(2)
    (myConsoleView as ConsoleViewImpl).waitAllRequests()

    assertTrue("Console should contain stack trace of the 3rs item Thread3", consoleText.contains("boo(Main.kt:1)"))
  }

  private fun createBasicDump(): List<MergeableDumpItem> {
    return listOf(
      TestDumpItem(
        name = "MyCoroutine1",
        stateDesc = "RUNNING on thread Thread1 [BlockingEventLoop@3e53c781]",
        stackTrace = "at MainKt.foo(Main.kt:161)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:101)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:100)\n"
      ),
      TestDumpItem(
        name = "MyCoroutine2",
        stateDesc = "RUNNING on thread Thread2 [BlockingEventLoop@3e53c781]",
        stackTrace = "at MainKt.foo(Main.kt:161)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:101)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:100)\n"
      ),
      TestDumpItem(
        name = "MyCoroutine3",
        stateDesc = "RUNNING on thread Thread3 [BlockingEventLoop@3e53c781]",
        stackTrace = "at MainKt.foo(Main.kt:161)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:101)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:100)\n"
      ),
      TestDumpItem(
        name = "Thread1",
        stateDesc = "\"Thread1\" daemon prio=5 tid=0x24 nid=NA runnable",
        stackTrace = "at MainKt.isPrime(Main.kt:14)\n" +
                     "\tat MainKt.foo1(Main.kt:32)\n" +
                     "\tat MainKt.foo3(Main.kt:21)\n" +
                     "\tat MainKt.foo4(Main.kt:25)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:104)"
      ),
      TestDumpItem(
        name = "Thread2",
        stateDesc = "\"Thread2\" daemon prio=5 tid=0x24 nid=NA runnable",
        stackTrace = "at MainKt.isPrime(Main.kt:14)\n" +
                     "\tat MainKt.foo1(Main.kt:32)\n" +
                     "\tat MainKt.foo3(Main.kt:21)\n" +
                     "\tat MainKt.foo4(Main.kt:25)\n" +
                     "\tat MainKt\$main\$1\$t1\$1\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:104)"
      ),
      TestDumpItem(
        name = "Thread3",
        stateDesc = "\"Thread3\" daemon prio=5 tid=0x24 nid=NA runnable",
        stackTrace = "at MainKt.foo2(_Collections.kt:1915)\n" +
                     "\tat MainKt\$main\$1\$t2\$1\$1\$1\$1\$1\$1.invokeSuspend(Main.kt:128)\n" +
                     "\tat MainKt\$main\$1\$t2\$1\$1\$1\$1\$1\$1.invoke(Main.kt:-1)\n" +
                     "\tat MainKt\$main\$1\$t2\$1\$1\$1\$1\$1\$1.invoke(Main.kt:-1)\n" +
                     "\tat MainKt.boo(Main.kt:167)\n" +
                     "\tat MainKt.access\$boo(Main.kt:1)"
      ),
    )
  }
}

private class TestDumpItem(
  override val name: String,
  override val stateDesc: String,
  override val stackTrace: @NlsSafe String,
): MergeableDumpItem {
  override val interestLevel: Int
    get() = stackTrace.count { it == '\n' }
  override val icon: Icon
    get() = AllIcons.Debugger.ThreadRunning
  override val iconToolTip: @Nls String?
    get() = null
  override val attributes: SimpleTextAttributes
    get() = DumpItem.RUNNING_ATTRIBUTES
  override val isDeadLocked: Boolean
    get() = false
  override val awaitingDumpItems: Set<DumpItem>
    get() = emptySet()

  override val mergeableToken: MergeableToken get() = TestMergeableToken()

  private inner class TestMergeableToken : MergeableToken {
    private val comparableStackTrace: String =
      stackTrace.substringAfter("\n").replace("<0x\\d+>\\s".toRegex(), "<merged>")

    override val item: TestDumpItem get() = this@TestDumpItem

    override fun equals(other: Any?): Boolean {
      if (other !is TestMergeableToken) return false
      if (this.comparableStackTrace != other.comparableStackTrace) return false
      return true
    }

    override fun hashCode(): Int {
      return Objects.hash(
        comparableStackTrace
      )
    }
  }
}