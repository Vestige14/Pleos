package app.memoryvault.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context

/** NaviHelper 3.0.5 / navi-data 3.0.4. No credentials, payload logs or route changes. */
internal class AndroidPleosNavigationSdk(context: Context) : NavigationSdk {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var helper: NaviHelper? = null
    @Volatile private var listener: NavigationSdk.Listener? = null

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            listener?.onDestinationInfo(NavigationPlace(
                destinationInfo.name, destinationInfo.address,
                destinationInfo.latitude, destinationInfo.longitude,
            ))
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            listener?.onRecentDestinationInfo(recentDestinationInfoList
                .take(NavigationRetention.MAX_RECENT).map {
                    NavigationPlace(it.name, it.address, it.latitude, it.longitude)
                })
        }

        override fun onDestinationChanged() { listener?.onDestinationChanged() }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            // The official arrival DTO has name/address/duration/distance, no coordinates or timestamp.
            // Do not borrow a cached destination's coordinates or derive time from travel duration.
            listener?.onDestinationArrived(NavigationPlace(
                destinationArrivedInfo.name, destinationArrivedInfo.address, null, null,
            ))
        }

        override fun onRouteCancelled() { listener?.onRouteCancelled() }
        override fun onError(errorCode: NaviErrorCode) { listener?.onUnavailable() }
        override fun onNavigationStatus(status: NavigationStatus) {
            if (status == NavigationStatus.INACTIVE) listener?.onUnavailable()
        }
    }

    override fun connect(listener: NavigationSdk.Listener) {
        val created = synchronized(lock) {
            check(helper == null)
            this.listener = listener
            NaviHelper(appContext).also { helper = it }
        }
        created.initialize(
            onConnected = {
                try {
                    val active = synchronized(lock) {
                        if (helper !== created) false
                        else { created.addListener(events); true }
                    }
                    if (active) this.listener?.onConnected()
                } catch (_: Exception) {
                    this.listener?.onUnavailable()
                }
            },
            onFailed = { this.listener?.onUnavailable() },
        )
    }

    override fun getDestinationInfo() {
        synchronized(lock) { helper ?: error("Navigation SDK is closed") }.getDestinationInfo()
    }

    override fun getRecentDestinationInfo() {
        synchronized(lock) { helper ?: error("Navigation SDK is closed") }.getRecentDestinationInfo()
    }

    override fun close() {
        val active = synchronized(lock) {
            listener = null
            helper.also { helper = null }
        } ?: return
        try { active.removeListener(events) }
        finally { active.release() }
    }
}
