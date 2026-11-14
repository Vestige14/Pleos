package app.memoryvault.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class PhotoAccess { FULL, PARTIAL, DENIED }
data class PhotoPermissions(val access: PhotoAccess, val location: Boolean)

object PhotoPermissionPolicy {
    private const val LEGACY = "android.permission.READ_EXTERNAL_STORAGE"
    private const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private const val LOCATION = "android.permission.ACCESS_MEDIA_LOCATION"

    fun inspect(sdk: Int, granted: Set<String>): PhotoPermissions {
        val access = when {
            sdk >= 33 && IMAGES in granted -> PhotoAccess.FULL
            sdk >= 34 && SELECTED in granted -> PhotoAccess.PARTIAL
            sdk <= 32 && LEGACY in granted -> PhotoAccess.FULL
            else -> PhotoAccess.DENIED
        }
        return PhotoPermissions(access, LOCATION in granted)
    }

    fun requestPermissions(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf(IMAGES, SELECTED, LOCATION)
        sdk >= 33 -> listOf(IMAGES, LOCATION)
        else -> listOf(LEGACY, LOCATION)
    }
}

fun readPhotoPermissions(context: Context): PhotoPermissions = PhotoPermissionPolicy.inspect(
    Build.VERSION.SDK_INT,
    PhotoPermissionPolicy.requestPermissions(Build.VERSION.SDK_INT).filter {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }.toSet(),
)
