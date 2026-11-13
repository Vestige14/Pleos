package app.memoryvault.data

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SeedDay(val date: String, val demoNow: Long, val visits: List<Visit>)

object SeedLoader {
    fun parse(json: String): SeedDay {
        val input = Json.decodeFromString<SeedInput>(json)
        val date = LocalDate.parse(input.date).toString()
        val visits = input.visits.map {
            val arrived = OffsetDateTime.parse(it.arrivedAt).toInstant().toEpochMilli()
            val left = OffsetDateTime.parse(it.leftAt).toInstant().toEpochMilli()
            require(left >= arrived) { "Visit departure precedes arrival" }
            Visit(it.id, date, it.placeName, it.lat, it.lng, arrived, left)
        }.sortedWith(compareBy(Visit::arrivedAt, Visit::id))
        return SeedDay(date, OffsetDateTime.parse(input.demoNow).toInstant().toEpochMilli(), visits)
    }
}

@Serializable
private data class SeedInput(val date: String, val demoNow: String, val visits: List<VisitInput>)

@Serializable
private data class VisitInput(
    val id: String,
    val placeName: String,
    val lat: Double,
    val lng: Double,
    val arrivedAt: String,
    val leftAt: String,
)
