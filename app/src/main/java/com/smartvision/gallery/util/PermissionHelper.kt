package com.smartvision.gallery.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Media permission checks for the gallery. No MANAGE_EXTERNAL_STORAGE — that is
 * a Play-policy risk and poor UX for a gallery; the photo-picking media
 * permissions are the correct scope.
 *
 * Android 13+ models partial authorization: "select some photos" leaves
 * READ_MEDIA_IMAGES/VIDEO denied but READ_MEDIA_VISUAL_USER_SELECTED granted.
 * That is a valid, usable state (the app can read the selected subset) and must
 * not be treated as denied — the old `.all{}` check on {IMAGES, VIDEO} killed
 * this path entirely.
 */
object PermissionHelper {

    /** True if the app can read at least some media. Partial (USER_SELECTED)
     *  counts as granted so the gallery opens instead of bouncing to a guide. */
    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val images = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
            val video = granted(context, Manifest.permission.READ_MEDIA_VIDEO)
            val partial = granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            // Full (images & video) OR partial subset selection.
            (images && video) || partial
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Permissions to request at once. API33+ asks all three so the user's
     *  "select some photos" choice (USER_SELECTED=true, IMAGES=false) is not
     *  flagged as a denial by an .all{} check downstream. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Full first-launch request set: media (+partial subset) + ACCESS_MEDIA_LOCATION
     *  (EXIF GPS for geo-refill) + POST_NOTIFICATIONS (foreground scan service).
     *  One RequestMultiplePermissions call; Android groups them and shows the
     *  dialogs in sequence. Notification/location denial must NOT block the
     *  gallery, so [hasStoragePermission] and the rationale/permanent-denial
     *  checks keep using [requiredPermissions] (media only). */
    fun firstLaunchPermissions(): Array<String> = buildList {
        addAll(requiredPermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** True if we should show a rationale dialog before re-requesting. */
    fun shouldShowRationale(activity: Activity, perms: Array<String> = requiredPermissions()): Boolean =
        perms.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    /**
     * Permanently denied = not granted AND no rationale should-be-shown (the
     * user ticked "don't ask again" or never asked). The only escape is the
     * system settings page; detect this so the UI offers "前往设置" not a
     * dead re-request button.
     */
    fun isPermanentlyDenied(activity: Activity, perms: Array<String> = requiredPermissions()): Boolean =
        perms.any { perm ->
            !granted(activity, perm) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true

    /** ACCESS_MEDIA_LOCATION is not requested up front; UI shows a hint when a
     *  location feature is used without it (Apple-Photos-style quiet badge). */
    fun hasMediaLocationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true
        else granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun granted(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
