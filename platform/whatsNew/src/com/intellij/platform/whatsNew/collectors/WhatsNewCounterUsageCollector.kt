// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.whatsNew.collectors

import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionRuleValidator
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.project.Project

internal object WhatsNewCounterUsageCollector : CounterUsagesCollector() {
  private val eventLogGroup: EventLogGroup = EventLogGroup("whatsnew", 4, description = "What's New usage statistics")

  private val visionActionId = EventFields.String("vision_action_id", listOf("whatsnew.vision.zoom", "whatsnew.vision.gif"))

  private val opened = eventLogGroup.registerEvent("tab_opened", EventFields.Enum(("type"), OpenedType::class.java), "What's New tab was opened")
  private val duration = EventFields.Long("duration_seconds")
  private val closed = eventLogGroup.registerEvent("tab_closed", duration, "What's New tab was closed")
  private val actionId = EventFields.StringValidatedByCustomRule("action_id", ActionRuleValidator::class.java)
  private val perform = eventLogGroup.registerEvent("action_performed", actionId, "An action was performed in the What's New tab")
  private val failed = eventLogGroup.registerEvent("action_failed",
                                                   actionId,
                                                   EventFields.Enum(("type"), ActionFailedReason::class.java),
                                                   "An action failed in the What's New tab")
  private val visionAction =
    eventLogGroup.registerEvent("vision_action_performed", visionActionId, "A vision-related action was performed in the What's New tab")

  fun openedPerformed(project: Project?, byClient: Boolean) {
    opened.log(project, if (byClient) OpenedType.ByClient else OpenedType.Auto)
  }

  fun closedPerformed(project: Project?, seconds: Long) {
    closed.log(project, seconds)
  }

  fun actionPerformed(project: Project?, id: String) {
    perform.log(project, id)
  }

  fun actionNotAllowed(project: Project?, id: String) {
    failed.log(project, id, ActionFailedReason.Not_Allowed)
  }

  fun actionNotFound(project: Project?, id: String) {
    failed.log(project, id, ActionFailedReason.Not_Found)
  }

  fun visionActionPerformed(project: Project?, id: String) {
    visionAction.log(project, id)
  }

  override fun getGroup(): EventLogGroup {
    return eventLogGroup
  }
}

internal enum class OpenedType { Auto, ByClient }

@Suppress("EnumEntryName")
internal enum class ActionFailedReason { Not_Allowed, Not_Found }