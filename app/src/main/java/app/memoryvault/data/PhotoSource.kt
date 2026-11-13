package app.memoryvault.data

data class PhotoMeta(val uri: String, val takenAt: Long?, val lat: Double?, val lng: Double?)

fun interface PhotoSource {
    suspend fun scan(): List<PhotoMeta>
}
