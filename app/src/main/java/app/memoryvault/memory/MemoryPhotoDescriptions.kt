package app.memoryvault.memory

import androidx.room.withTransaction
import app.memoryvault.data.AppDatabase
import app.memoryvault.sync.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** A small photo-specific receipt/cache, not a second generic job framework. */
class MemoryPhotoDescriptions(private val db: AppDatabase, private val api: MemoryAssociationApi,
    private val now: () -> Long = System::currentTimeMillis, private val model: String = MemoryWire.MODEL) {
    suspend fun cached(sha256: String): String? = db.memoryAssociationDao()
        .photo("$sha256|$model|${MemoryWire.PHOTO_VERSION}")?.takeIf { it.state == "READY" }?.description

    suspend fun description(image: SanitizedMemoryPhoto, session: LinkSession, allowed: () -> Boolean): String {
        if (!allowed()) throw MemoryReviewBlocked()
        val key = "${image.sha256}|$model|${MemoryWire.PHOTO_VERSION}"
        val acquisition = db.withTransaction {
            val stored = db.memoryAssociationDao().photo(key)
            if (stored?.state == "READY") return@withTransaction stored to false
            if (stored?.state == "RUNNING") {
                if ((stored.leaseUntil ?: Long.MAX_VALUE) <= now()) {
                    val unknown = stored.copy(state = "REVIEW_REQUIRED", leaseUntil = null,
                        attemptToken = null, errorCode = LinkError.OUTCOME_UNKNOWN.name)
                    db.memoryAssociationDao().savePhoto(unknown)
                    return@withTransaction unknown to false
                }
                return@withTransaction stored to false
            }
            if (stored != null && (stored.state !in setOf("WAITING", "RETRY_WAIT") || stored.nextAttemptAt > now()))
                return@withTransaction stored to false
            val base = stored ?: MemoryPhotoDescription(key, image.sha256, model, MemoryWire.PHOTO_VERSION,
                UUID.randomUUID().toString(), "WAITING", 0, now(), null, null, null, null, null)
            if (base.attempts >= 5) return@withTransaction base.copy(errorCode = "ATTEMPTS_EXHAUSTED") to false
            if (!allowed()) throw MemoryReviewBlocked()
            val claimed = base.copy(state = "RUNNING", attempts = base.attempts + 1, nextAttemptAt = now(), leaseUntil = now() + 120_000,
                attemptToken = UUID.randomUUID().toString(), errorCode = LinkError.OUTCOME_UNKNOWN.name).also {
                db.memoryAssociationDao().savePhoto(it)
            }
            claimed to true
        }
        val (receipt, claimed) = acquisition
        if (receipt.state == "READY") return checkNotNull(receipt.description)
        if (!claimed) throw PhotoPreparationException("PHOTO_${if (receipt.state == "RUNNING") "BUSY" else receipt.errorCode ?: receipt.state}")
        val request = PhotoDescriptionRequest(receipt.requestId, image.sha256, "image/jpeg", Base64.getEncoder().encodeToString(image.bytes))
        try {
            return when (val outcome = api.describe(request, session, allowed)) {
                is MemoryApiResult.Success -> db.withTransaction {
                    val current = db.memoryAssociationDao().photo(key) ?: throw PhotoPreparationException("PHOTO_STALE")
                    if (current.attemptToken != receipt.attemptToken || current.state != "RUNNING" ||
                        (current.leaseUntil ?: 0) <= now()) throw PhotoPreparationException("PHOTO_STALE")
                    val response = outcome.value
                    // Validate even when the API is an injected implementation.
                    MemoryWire.photoResponse(request, MemoryWire.json.encodeToString(PhotoDescriptionResponse.serializer(), response))
                    db.memoryAssociationDao().savePhoto(current.copy(state = "READY", description = response.description,
                        labelsJson = MemoryWire.json.encodeToString(response.labels),
                        attemptToken = null, leaseUntil = null, errorCode = null))
                    response.description
                }
                is MemoryApiResult.Failed -> {
                    settleFailure(receipt, outcome.failure)
                    throw PhotoPreparationException("PHOTO_${outcome.failure.code.name}")
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { settleFailure(receipt, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false)) }
            throw cancelled
        } catch (error: PhotoPreparationException) { throw error }
        catch (_: Exception) {
            settleFailure(receipt, LinkFailure(502, LinkError.MALFORMED_RESPONSE, false))
            throw PhotoPreparationException("PHOTO_MALFORMED_RESPONSE")
        }
    }
    private suspend fun settleFailure(receipt: MemoryPhotoDescription, failure: LinkFailure) = db.withTransaction {
        val current = db.memoryAssociationDao().photo(receipt.cacheKey) ?: return@withTransaction
        if (current.state != "RUNNING" || current.attemptToken != receipt.attemptToken) return@withTransaction
        val retry = failure.retryable && failure.code in setOf(LinkError.NETWORK_UNAVAILABLE, LinkError.UPSTREAM_RATE_LIMIT,
            LinkError.UPSTREAM_UNAVAILABLE, LinkError.CACHE_CAPACITY, LinkError.SERVER_BUSY, LinkError.CALL_RATE_LIMIT) &&
            (failure.status == 429 || failure.status in 500..599) && current.attempts < 5
        val state = if (retry) "RETRY_WAIT" else "REVIEW_REQUIRED"
        val delay = maxOf(30_000L * (1L shl (current.attempts - 1).coerceIn(0, 4)), failure.retryAfterMillis.coerceAtLeast(0))
        db.memoryAssociationDao().savePhoto(current.copy(state = state, attemptToken = null, leaseUntil = null,
            nextAttemptAt = if (delay > Long.MAX_VALUE - now()) Long.MAX_VALUE else now() + delay, errorCode = failure.code.name))
    }
}
