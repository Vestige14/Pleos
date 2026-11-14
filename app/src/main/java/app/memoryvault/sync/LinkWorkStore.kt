package app.memoryvault.sync

import app.memoryvault.data.AiLinkJob
import app.memoryvault.domain.AiLinkJobState

enum class ApplyOutcome { APPLIED, IGNORED_STALE, REVIEW_REQUIRED }
data class LinkLease(val jobId: String, val attemptToken: String, val request: LinkRequest, val attemptsBefore: Int)
interface LinkWorkStore {
    suspend fun claim(jobId: String): LinkLease?
    suspend fun beginAttempt(lease: LinkLease): Boolean
    suspend fun getJob(jobId: String): AiLinkJob?
    suspend fun pendingJobs(): List<AiLinkJob>
    suspend fun applyResult(jobId: String, result: LinkResponse, attemptToken: String): ApplyOutcome
    suspend fun fail(jobId: String, failure: LinkFailure, attemptToken: String): AiLinkJobState?
    suspend fun cancel(jobId: String, attemptToken: String)
    suspend fun blockForConfiguration(jobId: String, code: LinkError)
    suspend fun resumeConfigurationBlocked()
}
