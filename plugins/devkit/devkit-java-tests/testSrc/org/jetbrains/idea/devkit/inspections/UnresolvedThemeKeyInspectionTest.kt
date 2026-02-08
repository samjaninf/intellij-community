// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UnresolvedThemeKeyInspectionTest : BasePlatformTestCase() {

  fun testRegularKeys() {
    myFixture.configureByText("test.theme.json", """
      {
        "name": "Test Theme",
        "ui": {
          "ActionButton": {
            "focusedBorderColor": "#000000",
            <warning descr="Unresolved key 'ActionButton.unknownKey'">"unknownKey"</warning>: "#FFFFFF"
          }
        }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testColorNGradientKeys() {
    myFixture.configureByText("test.theme.json", """
      {
        "name": "Test Theme",
        "ui": {
          "ProjectGradients": {
            "Group1": {
              "DiagonalGradient": {
                "Color1": "#000000",
                <warning descr="Unresolved key 'ProjectGradients.Group1.DiagonalGradient.UnknownColor1'">"UnknownColor1"</warning>: "#ffffff"
              },
              "UnknownGradient": {
                <warning descr="Unresolved key 'ProjectGradients.Group1.UnknownGradient.Color1'">"Color1"</warning>: "#000000"
              }
            }
          }
        }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testFractionNGradientKeys() {
    myFixture.configureByText("test.theme.json", """
      {
        "name": "Test Theme",
        "ui": {
          "ProjectGradients": {
            "Group2": {
              "DiagonalGradient": {
                "Fraction1": 0.5,
                <warning descr="Unresolved key 'ProjectGradients.Group2.DiagonalGradient.UnknownFraction1'">"UnknownFraction1"</warning>: 0.1
              },
              "UnknownGradient": {
                <warning descr="Unresolved key 'ProjectGradients.Group2.UnknownGradient.Fraction1'">"Fraction1"</warning>: 0.5
              }
            }
          }
        }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }
}