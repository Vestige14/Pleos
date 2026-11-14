package app.memoryvault.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable enum class ExperienceTimeMode { CAPTURE_DAY, EXPLICIT_DATE, UNRESOLVED }
@Serializable data class ExperienceTimeScope(val mode: ExperienceTimeMode, val eventDate: String?)

/** Experience dates come from this note's immutable words and capturedAt, never the retry clock. */
object ExperienceTimeResolver {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val explicit = Regex("(?<![0-9])(?:(\\d{4})-(\\d{2})-(\\d{2})|(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일|(\\d{1,2})/(\\d{1,2}))(?![0-9])")
    private val vague = Regex("예전에|지난번|옛날|언젠가|이전에|오래전|지난\\s*(?:주|달|해)|작년|재작년|며칠\\s*전|몇\\s*(?:일|달|년)\\s*전")
    private val relative = Regex("그제|어제|오늘|아까")

    fun resolve(text: String, capturedAt: Long): ExperienceTimeScope {
        val captured = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDate()
        if (vague.containsMatchIn(text)) return unresolved()
        val dates = mutableSetOf<LocalDate>()
        var hasExplicitDate = false
        try {
            explicit.findAll(text).forEach { match ->
                val g = match.groupValues
                val date = if (g[1].isNotEmpty()) LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                else {
                    val month = (g[4].ifEmpty { g[6] }).toInt()
                    val day = (g[5].ifEmpty { g[7] }).toInt()
                    // Search backwards also handles February 29 when the capture year is not a leap year.
                    (captured.year downTo captured.year - 8).firstNotNullOfOrNull { year ->
                        runCatching { LocalDate.of(year, month, day) }.getOrNull()?.takeIf { it <= captured }
                    } ?: return unresolved()
                }
                if (date.year !in 1..9999 || date > captured) return unresolved()
                dates += date
                hasExplicitDate = true
            }
            relative.findAll(text).forEach { match ->
                val daysBefore = when (match.value) { "어제" -> 1L; "그제" -> 2L; else -> 0L }
                dates += captured.minusDays(daysBefore)
                if (daysBefore != 0L) hasExplicitDate = true
            }
        } catch (_: Exception) { return unresolved() }
        if (dates.size > 1) return unresolved()
        val date = dates.singleOrNull() ?: return ExperienceTimeScope(ExperienceTimeMode.CAPTURE_DAY, captured.toString())
        return ExperienceTimeScope(if (hasExplicitDate) ExperienceTimeMode.EXPLICIT_DATE else ExperienceTimeMode.CAPTURE_DAY, date.toString())
    }

    fun day(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()
    private fun unresolved() = ExperienceTimeScope(ExperienceTimeMode.UNRESOLVED, null)
}
