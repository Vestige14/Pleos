package app.memoryvault.data

import app.memoryvault.DemoConfig
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object ExifTime {
    private val formatter = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(dateTime: String?, offset: String?, zone: ZoneId = DemoConfig.DEMO_ZONE): Long? {
        if (dateTime.isNullOrBlank()) return null
        return try {
            val local = LocalDateTime.parse(dateTime.trim(), formatter)
            if (local.year < 1) return null
            if (offset.isNullOrBlank()) local.atZone(zone).toInstant().toEpochMilli()
            else local.toInstant(ZoneOffset.of(offset.trim())).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }
}
