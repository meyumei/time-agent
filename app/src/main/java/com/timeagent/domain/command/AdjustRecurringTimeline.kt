package com.timeagent.domain.command

import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.recurring.RuleChangePreview

data class AdjustRecurringTimeline(
    override val requestId: String,
    val plan: ScheduleBlockEntity,
    val instanceId: String,
    val expectedPlanVersion: Long,
    val expectedInstanceVersion: Long,
    val expectedTaskVersion: Long,
    val expectedRuleVersion: Long,
    val includingFuture: Boolean,
    val preview: RuleChangePreview?,
    override val origin: Origin = Origin.UI,
) : Command
