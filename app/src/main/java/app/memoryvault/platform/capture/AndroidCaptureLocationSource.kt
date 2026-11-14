package app.memoryvault.platform.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

data class CaptureLocationSupport(val permissionGranted: Boolean, val enabledProviders: List<String>)

/** Best effort at the foreground capture instant. No subscriptions, background permission or waiting. */
class AndroidCaptureLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val manager get() = appContext.getSystemService(LocationManager::class.java)

    fun support(): CaptureLocationSupport {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return CaptureLocationSupport(granted, try { manager?.getProviders(true).orEmpty() } catch (_: Exception) { emptyList() })
    }

    operator fun invoke(now: CaptureMoment): CaptureLocation? {
        val support = support()
        if (!support.permissionGranted) return null
        val fixes = support.enabledProviders.mapNotNull { provider ->
            try {
                manager?.getLastKnownLocation(provider)?.let {
                    LocationFix(it.latitude, it.longitude, provider, it.time, it.elapsedRealtimeNanos,
                        if (it.hasAccuracy()) it.accuracy else null)
                }
            } catch (_: SecurityException) { null }
            catch (_: Exception) { null }
        }
        return FreshCaptureLocation.select(fixes, now)
    }
}
