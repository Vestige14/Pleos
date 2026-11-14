package app.memoryvault.platform

import org.junit.Assert.*
import org.junit.Test

class PhotoPermissionsTest {
    // Conflating the location grant with library access breaks redacted-photo handling.
    @Test fun android14DistinguishesFullPartialAndDeniedIndependentlyFromGps() {
        assertEquals(PhotoPermissions(PhotoAccess.FULL, true),
            PhotoPermissionPolicy.inspect(34, setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.ACCESS_MEDIA_LOCATION")))
        assertEquals(PhotoPermissions(PhotoAccess.PARTIAL, false),
            PhotoPermissionPolicy.inspect(34, setOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")))
        assertEquals(PhotoPermissions(PhotoAccess.DENIED, true),
            PhotoPermissionPolicy.inspect(34, setOf("android.permission.ACCESS_MEDIA_LOCATION")))
        assertEquals(PhotoAccess.FULL, PhotoPermissionPolicy.inspect(34, setOf(
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")).access)
    }

    @Test fun olderAndroidUsesItsActualReadPermission() {
        assertEquals(PhotoAccess.FULL, PhotoPermissionPolicy.inspect(32, setOf("android.permission.READ_EXTERNAL_STORAGE")).access)
        assertEquals(PhotoAccess.DENIED, PhotoPermissionPolicy.inspect(33, setOf("android.permission.READ_EXTERNAL_STORAGE")).access)
        assertEquals(PhotoAccess.FULL, PhotoPermissionPolicy.inspect(33, setOf("android.permission.READ_MEDIA_IMAGES")).access)
        assertEquals(PhotoAccess.DENIED, PhotoPermissionPolicy.inspect(33, setOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")).access)
    }

    @Test fun reselectionRequestsThePlatformReadAndLocationPermissionsTogether() {
        assertEquals(setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.ACCESS_MEDIA_LOCATION"), PhotoPermissionPolicy.requestPermissions(34).toSet())
        assertEquals(setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.ACCESS_MEDIA_LOCATION"),
            PhotoPermissionPolicy.requestPermissions(33).toSet())
        assertEquals(setOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.ACCESS_MEDIA_LOCATION"),
            PhotoPermissionPolicy.requestPermissions(29).toSet())
    }
}
