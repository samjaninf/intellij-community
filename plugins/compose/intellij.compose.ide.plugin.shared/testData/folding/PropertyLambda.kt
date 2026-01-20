package com.example

import <fold text='...'>androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier</fold>

val annotatedLambda = @Composable <fold text='{...}'>{
  val foldable = <fold text='Modifier.(...)'>Modifier
    .adjust()
    .adjust()</fold>
}</fold>

val paramLambda = @Composable <fold text='{...}'>{ count: Int ->
  val foldable = <fold text='Modifier.(...)'>Modifier
    .adjust()
    .adjust()</fold>
}</fold>

val explicitUnit: @Composable () -> Unit = <fold text='{...}'>{
  val foldable = <fold text='Modifier.(...)'>Modifier
    .adjust()
    .adjust()</fold>
}</fold>

val modifierType: @Composable Modifier.() -> Modifier = <fold text='{...}'>{
  <fold text='this.(...)'>this
    .adjust()
    .adjust()</fold>
}</fold>