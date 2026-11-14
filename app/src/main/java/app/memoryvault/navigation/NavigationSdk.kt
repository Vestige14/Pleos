package app.memoryvault.navigation

/** Only SDK-supplied destination fields; these are never the device's capture location. */
internal data class NavigationPlace(
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/** Read-only seam for the validator. Deliberately exposes no route/screen mutation APIs. */
internal interface NavigationSdk : AutoCloseable {
    fun connect(listener: Listener)
    fun getDestinationInfo()
    fun getRecentDestinationInfo()

    interface Listener {
        fun onConnected()
        fun onUnavailable()
        fun onDestinationInfo(place: NavigationPlace?)
        fun onRecentDestinationInfo(places: List<NavigationPlace>)
        fun onDestinationChanged()
        fun onDestinationArrived(place: NavigationPlace)
        fun onRouteCancelled()
    }
}
