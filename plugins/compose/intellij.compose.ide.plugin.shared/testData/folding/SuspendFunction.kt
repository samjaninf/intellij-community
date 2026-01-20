package com.example

import <fold text='...'>androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier</fold>

suspend fun awaitApplication(content: @Composable () -> Unit) {}

suspend fun main() = <fold text='{...}'>awaitApplication <fold text='{...}'>{
  val m = <fold text='Modifier.(...)'>Modifier
    .adjust()
    .adjust()</fold>
}</fold></fold>

suspend fun anotherSuspendMain() = <fold text='{...}'>awaitApplication <fold text='{...}'>{
  @Composable
  fun NestedComposable() <fold text='{...}'>{
    val inner = <fold text='Modifier.(...)'>Modifier
      .adjust()
      .adjust()
      .adjust()</fold>
  }</fold>

  val outer = <fold text='Modifier.(...)'>Modifier
    .adjust()
    .adjust()</fold>
}</fold></fold>

suspend fun withNestedAwait() <fold text='{...}'>{
  awaitApplication <fold text='{...}'>{
    val m = <fold text='Modifier.(...)'>Modifier
      .adjust()
      .adjust()</fold>
  }</fold>
}</fold>

suspend fun noFoldOutsideComposable() <fold text='{...}'>{
  val notFolded = Modifier
    .adjust()
    .adjust()

  awaitApplication <fold text='{...}'>{
    val folded = <fold text='Modifier.(...)'>Modifier
      .adjust()
      .adjust()</fold>
  }</fold>
}</fold>

suspend fun regularSuspendLambda(block: suspend () -> Unit) {}

suspend fun noFoldInSuspendLambda() <fold text='{...}'>{
  regularSuspendLambda <fold text='{...}'>{
    val notFolded = Modifier
      .adjust()
      .adjust()
  }</fold>
}</fold>

suspend fun mixedParams(
  composableContent: @Composable () -> Unit,
  suspendBlock: suspend () -> Unit
) {}

suspend fun mixedParamsTest() <fold text='{...}'>{
  mixedParams<fold text='(...)'>(
    composableContent = <fold text='{...}'>{
      val folded = <fold text='Modifier.(...)'>Modifier
        .adjust()
        .adjust()</fold>
    }</fold>,
    suspendBlock = <fold text='{...}'>{
      val notFolded = Modifier
        .adjust()
        .adjust()
    }</fold>
  )</fold>
}</fold>