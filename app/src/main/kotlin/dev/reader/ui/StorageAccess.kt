package dev.reader.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast

/**
 * Supernote keeps user books in /Document (and library roots more generally); reading them in
 * place is the whole point, so all-files access is the only workable permission on Android 11.
 * Shared by [LibraryActivity] (now the app's entry point, so it is the first to need this) and
 * [ReaderActivity] (which still needs it when launched standalone, without the grid having run
 * first) rather than duplicated — both activities need the identical Settings deep-link and
 * fallback chain below.
 */
fun Activity.hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/**
 * This is the one path every first-time user takes, and the one path that could not be verified
 * on hardware: Supernote ships a heavily customized Android 11 build where stripped-down Settings
 * screens are common, and the per-package all-files screen
 * (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`) is exactly the kind of narrow, deep-linked
 * screen a customized ROM tends to drop, throwing [ActivityNotFoundException]. Fall back to the
 * all-apps list screen (`ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, still API 30), and if even
 * that isn't present, tell the user where to grant access by hand instead of crashing.
 */
fun Activity.requestAllFilesAccess() {
    Toast.makeText(
        this,
        "Grant all-files access so Reader can open books in your Document folder.",
        Toast.LENGTH_LONG,
    ).show()
    try {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Couldn't open Settings automatically. Please grant Reader " +
                    "\"All files access\" from Settings > Apps > Special access.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
