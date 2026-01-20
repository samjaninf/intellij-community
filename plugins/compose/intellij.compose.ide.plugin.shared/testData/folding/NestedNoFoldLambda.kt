package com.example

import <fold text='...'>androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier</fold>

@Composable
fun ComposableFunction() <fold text='{...}'>{
  val annotatedLambda = <fold text='{...}'>{
     Modifier
       .adjust()
       .adjust()
   }</fold>

  val explicitLambda: () -> Unit = <fold text='{...}'>{
    val m = Modifier
      .padding(8.dp)
      .fillMaxWidth()
  }</fold>
}</fold>