package app.memoryvault.domain

import app.memoryvault.data.PhotoMeta
import app.memoryvault.data.Visit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun validCoordinates(lat: Double, lng: Double) =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    if (!validCoordinates(lat1, lng1) || !validCoordinates(lat2, lng2)) return Double.NaN
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val haversine = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2.0 * 6_371_000.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

object PhotoMatcher {
    const val RADIUS_M = 300.0
    fun match(photo: PhotoMeta, visits: List<Visit>): String? {
        val time = photo.takenAt ?: return null
        val lat = photo.lat ?: return null
        val lng = photo.lng ?: return null
        if (!validCoordinates(lat, lng)) return null
        return visits.sortedWith(compareBy(Visit::arrivedAt, Visit::id)).firstOrNull { visit ->
            time >= visit.arrivedAt && time <= visit.leftAt &&
                distanceMeters(lat, lng, visit.lat, visit.lng) <= RADIUS_M
        }?.id
    }
}
