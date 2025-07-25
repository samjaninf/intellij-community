// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("Propagation")
@file:Internal
@file:Suppress("NAME_SHADOWING", "OPT_IN_USAGE")

package com.intellij.util.concurrency

import com.intellij.concurrency.*
import com.intellij.concurrency.client.captureClientIdInBiConsumer
import com.intellij.concurrency.client.captureClientIdInCallable
import com.intellij.concurrency.client.captureClientIdInFunction
import com.intellij.concurrency.client.captureClientIdInRunnable
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.CeProcessCanceledException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Ref
import com.intellij.util.SmartList
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.SchedulingWrapper.MyScheduledFutureTask
import com.intellij.util.containers.forEachGuaranteed
import kotlinx.coroutines.*
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import java.util.function.Function
import kotlin.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import com.intellij.openapi.util.Pair as JBPair

private val LOG = Logger.getInstance("#com.intellij.concurrency")

private object Holder {
  // we need context propagation to be configurable
  // in order to disable it in RT modules
  var propagateThreadContext: Boolean = SystemProperties.getBooleanProperty("ide.propagate.context", true)
  val checkIdeAssertion: Boolean = SystemProperties.getBooleanProperty("ide.check.context.assertion", false)
}

@TestOnly
@ApiStatus.Internal
fun runWithContextPropagationEnabled(runnable: Runnable) {
  val propagateThreadContext = Holder.propagateThreadContext
  Holder.propagateThreadContext = true
  try {
    runnable.run()
  }
  finally {
    Holder.propagateThreadContext = propagateThreadContext
  }
}

internal val isPropagateThreadContext: Boolean
  get() = Holder.propagateThreadContext

internal val isCheckContextAssertions: Boolean
  get() = Holder.checkIdeAssertion

/**
 * Tracks all IntelliJ Platform async computations launched inside
 * ```
 * withContext(BlockingJob(Job())) {
 *   application.executeOnPooledThread {} // tracked
 *   currentThreadCoroutineScope().launch {} // tracked
 * }
 * ```
 * Both `executeOnPooledThread` and `launch` will be awaited by `withContext`
 */
@Internal
class BlockingJob(val blockingJob: Job) : AbstractCoroutineContextElement(BlockingJob), IntelliJContextElement {
  companion object : CoroutineContext.Key<BlockingJob>

  /**
   * Consider the following case:
   * ```kotlin
   * blockingContextScope {
   *   installContext(Lock) {
   *     executeOnPooledThread {
   *       // lock must not leak here, it escapes `installContext`
   *     }
   *   }
   * }
   * ```
   * The problem with the snippet above is that we consider `executeOnPooledThread` to be a structured computation, hence it should retain all
   * context elements regardless of their will. However, we must not leak `Lock` here, because `executeOnPooledThread` here is not structured with respect to `installContext`.
   * Hence, we remember the context that existed at the entry to `blockingContextScope`, and leak exact those elements.
   * The map is needed because `IntelliJContextElement` may produce new instances of context elements, and we need to track them.
   */
  private val rememberedElements: MutableMap<CoroutineContext.Element, Int> = ConcurrentHashMap()

  fun rememberElement(element: CoroutineContext.Element) {
    rememberedElements.compute(element) { _, v -> if (v == null) 1 else v + 1 }
  }

  fun forgetElement(element: CoroutineContext.Element) {
    rememberedElements.compute(element) { _, v ->
      check(v != null) { "Attempt to forget element $element that is not remembered: ${rememberedElements.keys}" }
      if (v == 1) null else v - 1
    }
  }

  fun isRemembered(element: IntelliJContextElement): Boolean = rememberedElements.containsKey(element)
}

/**
 * Tracks only coroutines bound to `currentThreadCoroutineScope` inside
 * ```
 * withContext(ThreadScopeCheckpoint(Job())) {
 *   application.executeOnPooledThread { } // NOT tracked
 *   currentThreadCoroutineScope().launch { } // tracked
 * }
 * ```
 */
@Internal
class ThreadScopeCheckpoint(val context: CoroutineContext) : AbstractCoroutineContextElement(ThreadScopeCheckpoint), IntelliJContextElement {
  companion object : CoroutineContext.Key<ThreadScopeCheckpoint>

  override fun produceChildElement(parentContext: CoroutineContext, isStructured: Boolean): IntelliJContextElement? {
    return if (parentContext[BlockingJob] != null) {
      this
    }
    else {
      null
    }
  }

  override fun toString(): String {
    return "ThreadScopeCheckpoint"
  }

  fun startWaitingForChildren(): Job {
    val supervisingJob = context.job as CompletableJob
    @Suppress("RAW_SCOPE_CREATION")
    CoroutineScope(supervisingJob + Dispatchers.Default).launch {
      val thisLaunchJob = coroutineContext.job
      supervisingJob.children.forEach {
        if (it == thisLaunchJob) {
          return@forEach
        }
        it.join()
      }
      supervisingJob.complete()
    }
    return supervisingJob
  }
}

@OptIn(DelicateCoroutinesApi::class)
@Internal
data class ChildContext internal constructor(
  val context: CoroutineContext,
  val continuation: Continuation<Unit>?,
  val ijElements: List<IntelliJContextElement>,
  val additionalCleanup: AccessToken?,
) {

  val job: Job? get() = continuation?.context?.job

  fun runInChildContext(action: Runnable) {
    runInChildContext(completeOnFinish = true, action::run)
  }

  fun <T> runInChildContext(completeOnFinish: Boolean, action: () -> T): T {
    return if (continuation == null) {
      applyContextActions().use {
        action()
      }
    }
    else {
      runAsCoroutine(continuation, completeOnFinish) { applyContextActions().use { action() } }
    }
  }

  @DelicateCoroutinesApi
  fun applyContextActions(installThreadContext: Boolean = true): AccessToken {
    val alreadyAppliedElements = mutableListOf<IntelliJContextElement>()
    try {
      for (elem in ijElements) {
        elem.beforeChildStarted(context)
        alreadyAppliedElements.add(elem)
      }
    }
    catch (e: Throwable) {
      cleanupList(e, alreadyAppliedElements.reversed()) {
        it.afterChildCompleted(context)
      }
    }
    val installToken = if (installThreadContext) {
      installThreadContext(context, replace = false)
    }
    else {
      AccessToken.EMPTY_ACCESS_TOKEN
    }
    return object : AccessToken() {
      override fun finish() {
        installToken.finish()
        ijElements.reversed().forEachGuaranteed {
          it.afterChildCompleted(context)
        }
      }
    }
  }

  fun cancelAllIntelliJElements() {
    ijElements.forEachGuaranteed {
      it.childCanceled(context)
    }
  }
}

@Internal
fun createChildContext(debugName: @NonNls String) : ChildContext = doCreateChildContext(debugName, false)

@Internal
fun createChildContextWithContextJob(debugName: @NonNls String) : ChildContext = doCreateChildContext(debugName, true)

/**
 * Creates a child context without attaching a computation via coroutine to the current BlockingJob.
 *
 * This is useful when some computations should not block outer scope from finishing.
 */
@Internal
fun createChildContextIgnoreStructuredConcurrency(debugName: @NonNls String) : ChildContext {
  // probably we need to exclude some elements like PlatformActivityTrackerService.ObservationTracker
  return installThreadContext(currentThreadContext().minusKey(BlockingJob), true) {
    createChildContext(debugName)
  }
}

/**
 * Use `unconditionalCancellationPropagation` only when you are sure that the current context will always outlive a child computation.
 * This is the case with `invokeAndWait`, as it parks the thread before computation is finished,
 * but it is not the case with `invokeLater`
 */
@Internal
private fun doCreateChildContext(debugName: @NonNls String, unconditionalCancellationPropagation: Boolean): ChildContext {
  val currentThreadContext = currentThreadContext()

  val blockingJob = currentThreadContext[BlockingJob]

  val (childContext, ijElements) = gatherAppliedChildContext(currentThreadContext,
                                                             unconditionalCancellationPropagation,
                                                             blockingJob)

  val additionalCleanup = if (blockingJob != null) {
    ijElements.forEachGuaranteed {
      blockingJob.rememberElement(it)
    }
    object : AccessToken() {
      override fun finish() {
        ijElements.forEachGuaranteed {
          blockingJob.forgetElement(it)
        }
      }
    }
  }
  else {
    AccessToken.EMPTY_ACCESS_TOKEN
  }

  // Problem: a task may infinitely reschedule itself
  //   => each re-scheduling adds a child Job and completes the current Job
  //   => the current Job cannot become completed because it has a newly added child
  //   => Job chain grows indefinitely.
  //
  // How it's handled:
  // - initially, the current job is only present in the context.
  // - the current job is installed to the context by `blockingContext`.
  // - a new child job is created, it becomes current inside scheduled task.
  // - initial current job is saved as BlockingJob into child context.
  // - BlockingJob is used to attach children.
  //
  // Effectively, the chain becomes a 1-level tree,
  // as jobs of all scheduled tasks are attached to the initial current Job.

  val parentBlockingJob =
    if (unconditionalCancellationPropagation) currentThreadContext[Job]
    else blockingJob?.blockingJob
  val (cancellationContext, childContinuation) = if (parentBlockingJob != null) {
    val continuation: Continuation<Unit> = childContinuation(debugName, parentBlockingJob)
    Pair((currentThreadContext[BlockingJob] ?: EmptyCoroutineContext) + continuation.context.job, continuation)
  }
  else {
    Pair(EmptyCoroutineContext, null)
  }

  return ChildContext(childContext.minusKey(Job) + cancellationContext, childContinuation, ijElements, additionalCleanup)
}

private fun gatherAppliedChildContext(parentContext: CoroutineContext, isStructured: Boolean, blockingJob: BlockingJob?): Pair<CoroutineContext, List<IntelliJContextElement>> {
  val ijElements = SmartList<IntelliJContextElement>()
  try {
    val newContext = parentContext.fold<CoroutineContext>(EmptyCoroutineContext) { old, elem ->
      old + produceChildContextElement(parentContext, elem, isStructured, blockingJob, ijElements)
    }
    return Pair(newContext, ijElements)
  }
  catch (e: Throwable) {
    cleanupList(e, ijElements.reversed()) {
      it.childCanceled(parentContext)
    }
  }
}

private fun <T> cleanupList(original: Throwable, list: List<T>, action: (T) -> Unit): Nothing {
  try {
    list.forEachGuaranteed(action)
  }
  catch (e: Throwable) {
    original.addSuppressed(e)
  }
  throw original
}

private fun produceChildContextElement(parentContext: CoroutineContext, element: CoroutineContext.Element, isStructured: Boolean, blockingJob: BlockingJob?, ijElements: MutableList<IntelliJContextElement>): CoroutineContext {
  return when {
    element is IntelliJContextElement -> {
      val forked = element.produceChildElement(parentContext, isStructured || blockingJob?.isRemembered(element) == true)
      if (forked != null) {
        ijElements.add(forked)
        forked
      }
      else {
        EmptyCoroutineContext
      }
    }
    isStructured || blockingJob != null -> element
    else -> {
      EmptyCoroutineContext
    }
  }
}

@OptIn(DelicateCoroutinesApi::class)
private fun childContinuation(debugName: @NonNls String, parent: Job): Continuation<Unit> {
  if (parent.isCompleted) {
    LOG.warn("Attempt to create a child continuation for an already completed job", Throwable())
  }
  lateinit var continuation: Continuation<Unit>
  GlobalScope.launch(
    parent + CoroutineName("IJ Structured concurrency: $debugName") + Dispatchers.Unconfined,
    start = CoroutineStart.UNDISPATCHED,
  ) {
    suspendCancellableCoroutine {
      continuation = it
    }
  }
  return continuation
}

internal fun captureRunnableThreadContext(command: Runnable): Runnable {
  return capturePropagationContext(command)
}

internal fun <V> captureCallableThreadContext(callable: Callable<V>): Callable<V> {
  val childContext = createChildContext(callable.toString())
  var callable = captureClientIdInCallable(callable)
  callable = ContextCallable(true, childContext, callable, AtomicBoolean(false))
  return callable
}

fun isContextAwareComputation(runnable: Any): Boolean {
  return runnable is Continuation<*> || runnable is ContextAwareRunnable || runnable is ContextAwareCallable<*> || runnable is CancellationFutureTask<*>
}

/**
 * Runs [action] in a separate child coroutine of [continuation] job to prevent transition
 * from `Cancelling` to `Cancelled` state immediately after the [continuation] job is cancelled.
 *
 * Consider the following code
 * ```
 * blockingContextScope {
 *   executeOnPooledThread {
 *     doSomethingWithoutCheckingCancellationForOneHour()
 *   }
 *   throw NPE
 * }
 * ```
 * When `throw NPE` is reached, it is important to not resume `blockingContextScope` until `executeOnPooledThread` is completed.
 * This is why we reuse coroutine algorithms to ensure proper cancellation in our structured concurrency framework.
 * In the case above, the lambda in `executeOnPooledThread` needs to be executed under `runAsCoroutine`.
 *
 * ## Exception guarantees
 * This function is intended to be executed in blocking context, hence it always emits [ProcessCanceledException]
 *
 * ## Impact on thread context
 * This function is often used in combination with [ContextRunnable].
 * It is important that [runAsCoroutine] runs _on top of_ [ContextRunnable], as [async] in this function exposes the scope of [GlobalScope].
 *
 * @param completeOnFinish whether to complete [continuation] on the computation finish. Most of the time, this is the desired default behavior.
 * However, sometimes in non-linear execution scenarios (such as NonBlockingReadAction), more precise control over the completion of a job is needed.
 */
@Internal
@Throws(ProcessCanceledException::class)
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun <T> runAsCoroutine(continuation: Continuation<Unit>, completeOnFinish: Boolean, action: () -> T): T {
  // Even though catching and restoring PCE is unnecessary,
  // we still would like to have it thrown, as it indicates _where the canceled job was accessed_,
  // in addition to the original exception indicating _where the canceled job was canceled_
  val originalPCE: Ref<ProcessCanceledException> = Ref(null)
  val deferred = GlobalScope.async(
    // we need to have a job in CoroutineContext so that `Deferred` becomes its child and properly delays cancellation
    context = continuation.context,
    start = CoroutineStart.UNDISPATCHED) {
    try {
      action()
    }
    catch (e: ProcessCanceledException) {
      originalPCE.set(e)
      throw CancellationException("Masking ProcessCanceledException: ${e.message}", e)
    }
  }
  deferred.invokeOnCompletion {
    when (it) {
      null -> if (completeOnFinish) {
        continuation.resume(Unit)
      }
      // `deferred` is an integral part of `job`, so manual cancellation within `action` should lead to the cancellation of `job`
      is CancellationException ->
        // We have scheduled periodic runnables, which use `runAsCoroutine` several times on the same `Continuation`.
        // When the context `Job` gets canceled and a corresponding `SchedulingExecutorService` does not,
        // we appear in a situation where the `SchedulingExecutorService` still launches its tasks with a canceled `Continuation`
        //
        // Multiple resumption of a single continuation is disallowed; hence, we need to prevent this situation
        // by avoiding resumption in the case of a dead coroutine scope
        if (!continuation.context.job.isCompleted) {
          continuation.resumeWithException(it)
        }
      // Regular exceptions get propagated to `job` via parent-child relations between Jobs
    }
  }
  originalPCE.get()?.let { throw it }
  try {
    return deferred.getCompleted()
  } catch (ce : CancellationException) {
    throw CeProcessCanceledException(ce)
  }
}

internal fun capturePropagationContext(r: Runnable, forceUseContextJob : Boolean = false): Runnable {
  if (isContextAwareComputation(r)) {
    return r
  }
  var command = captureClientIdInRunnable(r)
  val childContext =
    if (forceUseContextJob) createChildContextWithContextJob(r.toString())
    //TODO: do we really need .toString() here? It allocates ~6 Gb during indexing
    else createChildContext(r.toString())
  command = ContextRunnable(childContext, command)
  return command
}

@ApiStatus.Internal
fun capturePropagationContext(r: Runnable, expired: Condition<*>, signalRunnable: Runnable): JBPair<Runnable, Condition<*>> {
  if (isContextAwareComputation(signalRunnable)) {
    return JBPair.create(r, expired)
  }
  var command = captureClientIdInRunnable(r)
  val childContext = createChildContext(r.toString())
  var expired = expired
  command = ContextRunnable(childContext, command)
  val cont = childContext.continuation
  val childJob = cont?.context?.job
  expired = cleanupIfExpired(expired, childContext, childJob)
  return JBPair.create(command, expired)
}

@ApiStatus.Internal
fun <T, U> captureBiConsumerThreadContext(f: BiConsumer<T, U>): BiConsumer<T, U> {
  val childContext = createChildContext(f.toString())
  var f = captureClientIdInBiConsumer(f)
  f = ContextBiConsumer(childContext, f)
  return f
}

private fun <T> cleanupIfExpired(expiredCondition: Condition<in T>, childContext: ChildContext, childJob: Job?): Condition<T> {
  return Condition { t: T ->
    val expired = expiredCondition.value(t)
    if (expired) {
      // Cancel to avoid a hanging child job which will prevent completion of the parent one.
      childJob?.cancel(null)
      childContext.cancelAllIntelliJElements()
      true
    }
    else {
      // Treat runnable as expired if its job was already cancelled.
      childJob?.isCancelled == true
    }
  }
}

internal fun <V> capturePropagationContext(c: Callable<V>): FutureTask<V> {
  if (isContextAwareComputation(c)) {
    return FutureTask(c)
  }
  val callable = captureClientIdInCallable(c)
  val childContext = createChildContext(c.toString())
  val executionTracker = AtomicBoolean(false)
  val wrappedCallable = ContextCallable(false, childContext, callable, executionTracker)
  val cont = childContext.continuation
  if (cont != null) {
    val childJob = cont.context.job
    return CancellationFutureTask(childJob, wrappedCallable, executionTracker, childContext)
  }
  else {
    return FutureTask(wrappedCallable)
  }
}

internal fun <T, R> capturePropagationContext(function: Function<T, R>): Function<T, R> {
  val childContext = createChildContext(function.toString())
  var f = captureClientIdInFunction(function)
  f = ContextFunction(childContext, f)
  return f
}

internal fun <V> capturePropagationContext(wrapper: SchedulingWrapper, c: Callable<V>, ns: Long): MyScheduledFutureTask<V> {
  if (isContextAwareComputation(c)) {
    return wrapper.MyScheduledFutureTask(c, ns)
  }
  val callable = captureClientIdInCallable(c)
  val childContext = createChildContext("$c (scheduled: $ns)")
  val cancellationTracker = AtomicBoolean(false)
  val wrappedCallable = ContextCallable(false, childContext, callable, cancellationTracker)

  val cont = childContext.continuation
  return CancellationScheduledFutureTask(wrapper, childContext, cont?.context?.job, cancellationTracker, wrappedCallable, ns)
}

internal fun capturePropagationContext(
  wrapper: SchedulingWrapper,
  runnable: Runnable,
  ns: Long,
  period: Long,
): MyScheduledFutureTask<*> {
  val childContext = createChildContext("$runnable (scheduled: $ns, period: $period)")
  val capturedRunnable1 = captureClientIdInRunnable(runnable)
  val capturedRunnable2 = Runnable {
    // no cancellation tracker here: this is a periodic runnable that is restarted
    installThreadContext(childContext.context, false) {
      childContext.applyContextActions(false).use {
        capturedRunnable1.run()
      }
    }
  }
  val cont = childContext.continuation
  val (finalCapturedRunnable, job) = if (cont != null) {
    val capturedRunnable3 = PeriodicCancellationRunnable(childContext.continuation, capturedRunnable2)
    val childJob = cont.context.job
    capturedRunnable3 to childJob
  }
  else {
    capturedRunnable2 to null
  }
  return CancellationScheduledFutureTask<Void>(wrapper, childContext, job, finalCapturedRunnable, ns, period)
}

@ApiStatus.Internal
fun contextAwareCallable(r: Runnable): Callable<*> = ContextAwareCallable {
  r.run()
}

fun Runnable.unwrapContextRunnable(): Runnable {
  return if (this is ContextRunnable) this.delegate.unwrapContextRunnable() else this
}
