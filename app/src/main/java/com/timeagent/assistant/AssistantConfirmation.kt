package com.timeagent.assistant

import com.timeagent.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface AssistantWriter {
    suspend fun write(mutation: AssistantMutation, allowFuture: Boolean)
    suspend fun commit(preview: AssistantPreview, allowFuture: Boolean) = write(preview.mutation, allowFuture)
}

/** One pending action, one explicit confirmation, one atomic repository call. */
class AssistantConfirmation(private val writer: AssistantWriter) {
    private var pending: AssistantPreview? = null
    private val completed = mutableSetOf<String>()
    private val lock = Mutex()
    fun stage(preview: AssistantPreview) { pending = preview }
    fun cancel() { pending = null }

    suspend fun confirm(previewId: String, allowFuture: Boolean = false): CommitResult = lock.withLock {
        if (previewId in completed) return@withLock CommitResult.AlreadySaved
        val item = pending?.takeIf { it.id == previewId } ?: return@withLock CommitResult.Rejected("预览已取消或已过期，请重新解析。", true)
        if (item.needsFutureApproval && !allowFuture) return@withLock CommitResult.Rejected("请单独确认这段实际记录确实包含未来时间，或取消并改建计划。")
        try {
            writer.commit(item, allowFuture)
            completed += previewId
            if (pending?.id == previewId) pending = null
            CommitResult.Saved()
        } catch (e: CancellationException) { throw e }
        catch (_: StaleChange) {
            pending = null
            CommitResult.Rejected("数据已变化，旧预览没有保存。请重新解析并核对最新内容。", true)
        } catch (e: IllegalArgumentException) {
            CommitResult.Rejected(e.message ?: "参数无效，尚未保存；输入仍保留。")
        } catch (_: TimerAlreadyRunning) {
            pending = null
            CommitResult.Rejected("已有另一个计时启动，本次未开始；请先处理当前计时。", true)
        } catch (_: Exception) {
            CommitResult.Rejected("保存失败，输入和预览仍保留，请稍后重试。")
        }
    }
}

class RepositoryAssistantWriter(private val repository: TimeRepository) : AssistantWriter {
    override suspend fun commit(preview: AssistantPreview, allowFuture: Boolean) {
        if (preview.mutation is AssistantMutation.Batch) repository.assistantTransaction(preview.id) { write(preview.mutation, allowFuture) }
        else write(preview.mutation, allowFuture)
    }
    override suspend fun write(mutation: AssistantMutation, allowFuture: Boolean) {
        when (mutation) {
            is AssistantMutation.Batch -> mutation.mutations.forEach { write(it, allowFuture) }
            is AssistantMutation.SaveIdea -> repository.saveIdea(mutation.idea, mutation.expectedVersion)
            is AssistantMutation.SaveReminder -> repository.saveReminder(mutation.reminder, mutation.expectedVersion)
            is AssistantMutation.SaveTask -> repository.saveTask(mutation.task, mutation.expectedVersion)
            is AssistantMutation.SavePlan -> repository.savePlan(mutation.plan, mutation.expectedVersion, expectedTaskVersion = mutation.expectedTaskVersion, expectedConflicts = mutation.expectedConflicts)
            is AssistantMutation.SaveRecord -> repository.saveRecord(mutation.record, allowFuture, mutation.expectedVersion, mutation.expectedTaskVersion)
            is AssistantMutation.SaveEvent -> repository.saveAssistantEvent(mutation.task, mutation.plan, mutation.expectedConflicts)
            is AssistantMutation.DeleteRecord -> repository.deleteRecord(mutation.id, mutation.expectedVersion)
            is AssistantMutation.SaveCourse -> repository.saveOccurrence(mutation.occurrence, mutation.expectedVersion, mutation.expectedConflicts)
            is AssistantMutation.StartTimer -> repository.startTimer(mutation.taskId, requestId = mutation.requestId, expectedTaskVersion = mutation.expectedTaskVersion)
            is AssistantMutation.StopTimer -> repository.stopTimer(mutation.recordId, expectedVersion = mutation.expectedVersion)
            is AssistantMutation.CancelSampleCourses -> repository.cancelSampleCourses(mutation.courses, mutation.occurrences, mutation.requestId)
        }
    }
}
