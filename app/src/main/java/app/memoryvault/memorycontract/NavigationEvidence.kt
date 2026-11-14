package app.memoryvault.memorycontract

/** Observation time is not a historical visit time. */
enum class NavigationEvidenceKind { DESTINATION_OBSERVED, ARRIVAL_OBSERVED, UNDATED_RECENT }
enum class NavigationEvidenceStatus { NOT_READY, AVAILABLE, UNAVAILABLE }
data class NavigationEvidence(
    val id: String,
    val kind: NavigationEvidenceKind,
    val placeName: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val observedAtMillis: Long,
    val eventAtMillis: Long?,
)
interface NavigationEvidenceProvider {
    fun snapshot(): List<NavigationEvidence>
    fun status(): NavigationEvidenceStatus
}
