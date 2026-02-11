// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.keymap.impl

import com.intellij.ide.IdeEventQueue.Companion.getInstance
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Clock
import com.intellij.testFramework.LightPlatformTestCase
import junit.framework.TestCase
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

class ModifierKeyDoubleClickHandlerTest : LightPlatformTestCase() {
    private val myComponent: JComponent = JPanel()

    private var myCurrentTime: Long = 0
    private var myShiftShiftActionInvocationCount = 0
    private var myShiftKeyActionInvocationCount = 0
    private var myShiftShiftKeyActionInvocationCount = 0
    private var myShiftOtherKeyActionInvocationCount = 0

    @Throws(Exception::class)
    public override fun setUp() {
        super.setUp()
        Clock.setTime(0)
        ActionManager.getInstance().registerAction(MY_SHIFT_SHIFT_ACTION, createAction(Runnable { myShiftShiftActionInvocationCount++ }))
        ActionManager.getInstance().registerAction(MY_SHIFT_KEY_ACTION, createAction(Runnable { myShiftKeyActionInvocationCount++ }))
        ActionManager.getInstance()
            .registerAction(MY_SHIFT_SHIFT_KEY_ACTION, createAction(Runnable { myShiftShiftKeyActionInvocationCount++ }))
        ActionManager.getInstance()
            .registerAction(MY_SHIFT_OTHER_KEY_ACTION, createAction(Runnable { myShiftOtherKeyActionInvocationCount++ }))
        val activeKeymap = KeymapManager.getInstance().getActiveKeymap()
        activeKeymap.addShortcut(MY_SHIFT_KEY_ACTION, SHIFT_KEY_SHORTCUT)
        activeKeymap.addShortcut(MY_SHIFT_OTHER_KEY_ACTION, SHIFT_OTHER_KEY_SHORTCUT)
        ModifierKeyDoubleClickHandler.getInstance().registerAction(MY_SHIFT_SHIFT_ACTION, KeyEvent.VK_SHIFT, -1)
        ModifierKeyDoubleClickHandler.getInstance().registerAction(MY_SHIFT_SHIFT_KEY_ACTION, KeyEvent.VK_SHIFT, KeyEvent.VK_BACK_SPACE)
    }

    @Throws(Exception::class)
    public override fun tearDown() {
        try {
            ModifierKeyDoubleClickHandler.getInstance().unregisterAction(MY_SHIFT_SHIFT_KEY_ACTION)
            ModifierKeyDoubleClickHandler.getInstance().unregisterAction(MY_SHIFT_SHIFT_ACTION)
            val activeKeymap = KeymapManager.getInstance().getActiveKeymap()
            activeKeymap.removeShortcut(MY_SHIFT_OTHER_KEY_ACTION, SHIFT_OTHER_KEY_SHORTCUT)
            activeKeymap.removeShortcut(MY_SHIFT_KEY_ACTION, SHIFT_KEY_SHORTCUT)
            val actionManager = ActionManager.getInstance()
            actionManager.unregisterAction(MY_SHIFT_OTHER_KEY_ACTION)
            actionManager.unregisterAction(MY_SHIFT_SHIFT_KEY_ACTION)
            actionManager.unregisterAction(MY_SHIFT_KEY_ACTION)
            actionManager.unregisterAction(MY_SHIFT_SHIFT_ACTION)
            Clock.reset()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testShiftShiftSuccessfulCase() {
        press()
        release()
        press()
        assertInvocationCounts(0, 0, 0, 0)
        release()
        assertInvocationCounts(0, 1, 0, 0)
    }

    fun testLongSecondClick() {
        press()
        release()
        press()
        timeStep(400)
        release()
        assertInvocationCounts(0, 0, 0, 0)
    }

    fun testShiftShiftKeySuccessfulCase() {
        press()
        release()
        press()
        key()
        assertInvocationCounts(0, 0, 1, 0)
        release()
        assertInvocationCounts(0, 0, 1, 0)
    }

    fun testShiftKey() {
        press()
        key()
        assertInvocationCounts(1, 0, 0, 0)
        release()
    }

    fun testRepeatedInvocationOnKeyHold() {
        press()
        release()
        press()
        key(2)
        assertInvocationCounts(0, 0, 2, 0)
        release()
        assertInvocationCounts(0, 0, 2, 0)
    }

    fun testNoTriggeringAfterUnrelatedAction() {
        press()
        release()
        press()
        otherKey()
        key()
        release()
        assertInvocationCounts(1, 0, 0, 1)
    }

    fun testShiftShiftOtherModifierNoAction() {
        press()
        release()
        press()
        dispatchEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_MASK or InputEvent.CTRL_MASK, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED)

        dispatchEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_MASK or InputEvent.CTRL_MASK, KeyEvent.VK_BACK_SPACE, '\b')
        dispatchEvent(KeyEvent.KEY_TYPED, InputEvent.SHIFT_MASK or InputEvent.CTRL_MASK, 0, '\b')
        dispatchEvent(KeyEvent.KEY_RELEASED, InputEvent.SHIFT_MASK or InputEvent.CTRL_MASK, KeyEvent.VK_BACK_SPACE, '\b')

        dispatchEvent(KeyEvent.KEY_RELEASED, InputEvent.SHIFT_MASK, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED)
        release()
        assertInvocationCounts(0, 0, 0, 0)
    }

    fun assertInvocationCounts(shiftKeyCount: Int, shiftShiftCount: Int, shiftShiftKeyCount: Int, shiftOtherKeyCount: Int) {
        TestCase.assertEquals(shiftKeyCount, myShiftKeyActionInvocationCount)
        TestCase.assertEquals(shiftShiftCount, myShiftShiftActionInvocationCount)
        TestCase.assertEquals(shiftShiftKeyCount, myShiftShiftKeyActionInvocationCount)
        TestCase.assertEquals(shiftOtherKeyCount, myShiftOtherKeyActionInvocationCount)
    }

    private fun press() {
        dispatchEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
    }

    private fun release() {
        dispatchEvent(KeyEvent.KEY_RELEASED, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
    }

    private fun dispatchEvent(id: Int, modifiers: Int, keyCode: Int, keyChar: Char) {
        getInstance().dispatchEvent(
            KeyEvent(
                myComponent,
                id,
                Clock.getTime(),
                modifiers,
                keyCode,
                keyChar
            )
        )
    }

    private fun otherKey() {
        key(1, true)
    }

    private fun key(repeat: Int = 1, otherKey: Boolean = false) {
        for (i in 0..<repeat) {
            dispatchEvent(
                KeyEvent.KEY_PRESSED, InputEvent.SHIFT_MASK,
                if (otherKey) KeyEvent.VK_ENTER else KeyEvent.VK_BACK_SPACE,
                if (otherKey) '\n' else '\b'
            )
            dispatchEvent(
                KeyEvent.KEY_TYPED, InputEvent.SHIFT_MASK,
                0,
                if (otherKey) '\n' else '\b'
            )
        }
        dispatchEvent(
            KeyEvent.KEY_RELEASED, InputEvent.SHIFT_MASK,
            if (otherKey) KeyEvent.VK_ENTER else KeyEvent.VK_BACK_SPACE,
            if (otherKey) '\n' else '\b'
        )
    }

    private fun timeStep(step: Long) {
        Clock.setTime(step.let { myCurrentTime += it; myCurrentTime })
    }

    companion object {
        private const val MY_SHIFT_SHIFT_ACTION = "ModifierKeyDoubleClickHandlerTest.action1"
        private const val MY_SHIFT_KEY_ACTION = "ModifierKeyDoubleClickHandlerTest.action2"
        private const val MY_SHIFT_SHIFT_KEY_ACTION = "ModifierKeyDoubleClickHandlerTest.action3"
        private const val MY_SHIFT_OTHER_KEY_ACTION = "ModifierKeyDoubleClickHandlerTest.action4"

        private val SHIFT_KEY_SHORTCUT = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, InputEvent.SHIFT_MASK), null)
        private val SHIFT_OTHER_KEY_SHORTCUT = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_MASK), null)

        private fun createAction(runnable: Runnable): AnAction {
            return object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.BGT
                }

                override fun update(e: AnActionEvent) {
                    e.getPresentation().setEnabledAndVisible(true)
                }

                override fun actionPerformed(e: AnActionEvent) {
                    runnable.run()
                }
            }
        }
    }
}
