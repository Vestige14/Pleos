package app.memoryvault.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LinkOrigin { AI, USER }
enum class NoteLinkStatus { SUGGESTED, CONFIRMED, REJECTED }
enum class AiLinkJobState { WAITING, RUNNING, RETRY_WAIT, REVIEW_REQUIRED, DONE, BLOCKED, SUPERSEDED }

@Serializable
data class VisitCandidate(val id: String, val placeName: String, val arrivedAt: Long, val leftAt: Long)

object CandidateSnapshot {
    fun encode(candidates: List<VisitCandidate>): String = Json.encodeToString(
        candidates.sortedWith(compareBy(VisitCandidate::arrivedAt, VisitCandidate::id)),
    )

    fun decode(json: String): List<VisitCandidate> = Json.decodeFromString(json)

    fun contextHash(candidates: List<VisitCandidate>): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(candidates).toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
