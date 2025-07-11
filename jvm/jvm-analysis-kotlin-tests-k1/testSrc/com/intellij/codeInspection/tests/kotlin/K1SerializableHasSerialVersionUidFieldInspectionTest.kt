package com.intellij.codeInspection.tests.kotlin

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode

class K1SerializableHasSerialVersionUidFieldInspectionTest : KotlinSerializableHasSerialVersionUidFieldInspectionTest() {
  override val pluginMode: KotlinPluginMode get() = KotlinPluginMode.K1
  override fun getHint(): String = "Add 'const val' property 'serialVersionUID' to 'Foo'"
}