package com.timeagent.data

import com.timeagent.domain.command.ConvertInbox
import com.timeagent.domain.command.InboxTarget
import com.timeagent.domain.command.LedgerMutation
import com.timeagent.ledger.LedgerRepository

internal suspend fun TimeRepository.convertInboxRaw(command: ConvertInbox) {
    val idea = requireNotNull(database.dao().idea(command.ideaId)?.takeIf {
        it.deletedAt == null && it.status == "UNSCHEDULED"
    }) { "想法已分拣或已删除，请重新打开收件箱" }
    if (idea.version != command.expectedVersion) throw StaleChange()
    when (val target = command.target) {
        is InboxTarget.Task -> {
            require(database.dao().task(target.item.id) == null) { "任务已存在" }
            saveTask(target.item)
        }
        is InboxTarget.Schedule -> {
            require(database.dao().plan(target.item.id) == null) { "日程已存在" }
            savePlan(target.item, requestId = "${command.requestId}:schedule")
        }
        is InboxTarget.Ledger -> LedgerRepository(database, commandClock, this).applyMutation(
            LedgerMutation.SaveTransaction(target.item, null),
        )
        is InboxTarget.Memory -> {
            require(database.dao().memory(target.item.id) == null) { "规则已存在" }
            saveMemoryRaw(target.item.copy(sourceType = "USER_INPUT", sourceId = idea.id))
        }
    }
    saveIdea(idea.copy(status = "ARCHIVED"), idea.version)
}
