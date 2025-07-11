// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.uast.test.kotlin

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.junit.Test

class KotlinUastIdentifiersTest : AbstractKotlinIdentifiersTest() {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K1

    @Test
    fun testLocalDeclarations() = doTest("LocalDeclarations")

    @Test
    fun testComments() = doTest("Comments")

    @Test
    fun testConstructors() = doTest("Constructors")

    @Test
    fun testSimpleAnnotated() = doTest("SimpleAnnotated")

    @Test
    fun testAnonymous() = doTest("Anonymous")

    @Test
    fun testLambdas() = doTest("Lambdas")

    @Test
    fun testSuperCalls() = doTest("SuperCalls")

    @Test
    fun testPropertyInitializer() = doTest("PropertyInitializer")

    @Test
    fun testEnumValuesConstructors() = doTest("EnumValuesConstructors")

    @Test
    fun testNonTrivialIdentifiers() = doTest("NonTrivialIdentifiers")

    @Test
    fun testBrokenDataClass() = doTest("BrokenDataClass")

    @Test
    fun testBrokenGeneric() = doTest("BrokenGeneric")

}