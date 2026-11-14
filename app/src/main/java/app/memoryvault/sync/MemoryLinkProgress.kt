package app.memoryvault.sync

import app.memoryvault.data.AiLinkJob
import app.memoryvault.domain.AiLinkJobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NoteLinkProgress(val state: AiLinkJobState, val error: LinkError?)
data class MemoryLinkProgress(val available: Boolean = false, val notes: Map<Long, NoteLinkProgress> = emptyMap()) {
    val waiting: Int get() = notes.values.count { it.state in setOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.RUNNING) }
    val suggested: Int get() = notes.values.count { it.state == AiLinkJobState.DONE }
    val review: Int get() = notes.values.count { it.state in setOf(AiLinkJobState.REVIEW_REQUIRED, AiLinkJobState.SUPERSEDED) }
    val blocked: Int get() = notes.values.count { it.state == AiLinkJobState.BLOCKED }
    companion object {
        val unavailable = MutableStateFlow(MemoryLinkProgress()).asStateFlow()
        fun fromJobs(jobs: List<AiLinkJob>): MemoryLinkProgress {
            val latest = jobs.groupBy { it.noteId }.mapValues { (_, versions) ->
                val job = versions.maxWith(compareBy(AiLinkJob::noteRevision, AiLinkJob::userRevision))
                NoteLinkProgress(job.state, job.lastErrorCode?.let { code -> LinkError.entries.firstOrNull { it.name == code } })
            }
            return MemoryLinkProgress(true, latest)
        }
    }
}
