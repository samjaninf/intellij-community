// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import org.jetbrains.annotations.ApiStatus

private val GROUP = EventLogGroup("python.type.evaluation", 1)
private val DURATION = EventFields.DurationMs
private val JB_TYPE_ENGINE_TIME = GROUP.registerEvent("jb.type.engine.time", DURATION)
private val HYBRID_TYPE_ENGINE_TIME = GROUP.registerEvent("hybrid.type.engine.time", DURATION)

@ApiStatus.Internal
class PyTypeEvaluationStatisticsServiceImpl : PyTypeEvaluationStatisticsService {
  override fun logJBTypeEngineTime(durationMs: Long) {
    JB_TYPE_ENGINE_TIME.log(durationMs)
  }

  override fun logHybridTypeEngineTime(durationMs: Long) {
    HYBRID_TYPE_ENGINE_TIME.log(durationMs)
  }
}

/**
 * FUS collector for Python type evaluation statistics.
 *
 * This collector is registered separately from [PyTypeEvaluationStatisticsServiceImpl]
 * to avoid dual registration as both a service and an extension.
 */
@ApiStatus.Internal
class PyTypeEvaluationCollector : CounterUsagesCollector() {
  override fun getGroup(): EventLogGroup = GROUP
}
