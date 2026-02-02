// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.whatsNew.collectors

import com.intellij.internal.statistic.collectors.fus.actions.persistence.ActionRuleValidator
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.eventLog.validator.ValidationResultType
import com.intellij.internal.statistic.eventLog.validator.rules.EventContext
import com.intellij.internal.statistic.eventLog.validator.rules.impl.CustomValidationRule
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.project.Project

internal object WhatsNewCounterUsageCollector : CounterUsagesCollector() {
  private val eventLogGroup: EventLogGroup = EventLogGroup("whatsnew", 5, description = "What's New usage statistics")

  private val visionActionId = EventFields.String("vision_action_id", listOf("whatsnew.vision.zoom", "whatsnew.vision.gif"))

  private val startPageId = EventFields.StringValidatedByCustomRule("start_page_id", WhatsNewMultipageUrlValidationRule::class.java)
  private val opened =
    eventLogGroup.registerEvent("tab_opened", startPageId, EventFields.Enum(("type"), OpenedType::class.java), "What's New tab was opened")
  private val duration = EventFields.Long("duration_seconds")
  private val closed = eventLogGroup.registerEvent("tab_closed", startPageId, duration, "What's New tab was closed")
  private val actionId = EventFields.StringValidatedByCustomRule("action_id", ActionRuleValidator::class.java)
  private val perform = eventLogGroup.registerEvent("action_performed", actionId, "An action was performed in the What's New tab")
  private val failed = eventLogGroup.registerEvent("action_failed",
                                                   actionId,
                                                   EventFields.Enum(("type"), ActionFailedReason::class.java),
                                                   "An action failed in the What's New tab")
  private val visionAction =
    eventLogGroup.registerEvent("vision_action_performed", visionActionId, "A vision-related action was performed in the What's New tab")
  private val oldUrl = EventFields.StringValidatedByCustomRule("old_url", WhatsNewMultipageUrlValidationRule::class.java)
  private val newUrl = EventFields.StringValidatedByCustomRule("new_url", WhatsNewMultipageUrlValidationRule::class.java)
  private val urlChanged = eventLogGroup.registerEvent("url_changed", oldUrl, newUrl, "What's New url has changed")

  fun openedPerformed(project: Project?, startPageId: String?, byClient: Boolean) {
    opened.log(project, startPageId ?: "Default", if (byClient) OpenedType.ByClient else OpenedType.Auto)
  }

  fun closedPerformed(project: Project?, startPageId: String?, seconds: Long) {
    closed.log(project, startPageId ?: "Default", seconds)
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

  fun urlChanged(project: Project?, oldUrl: String?, newUrl: String) {
    urlChanged.log(project, oldUrl, newUrl)
  }

  override fun getGroup(): EventLogGroup {
    return eventLogGroup
  }
}

internal enum class OpenedType { Auto, ByClient }

@Suppress("EnumEntryName")
internal enum class ActionFailedReason { Not_Allowed, Not_Found }


internal class WhatsNewMultipageUrlValidationRule : CustomValidationRule() {
  override fun getRuleId(): String = "whats_new_url_validation_rule"

  override fun doValidate(data: String, context: EventContext): ValidationResultType {
    return ValidationResultType.ACCEPTED
  }
}