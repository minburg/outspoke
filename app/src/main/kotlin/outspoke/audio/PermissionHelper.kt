package dev.brgr.outspoke.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.brgr.outspoke.audio.PermissionHelper.hasRecordPermission

/**
 * Utility for checking and directing the user to grant [Manifest.permission.RECORD_AUDIO].
 *
 * The IME itself **cannot** present a permission dialog - only an Activity can. Any call
 * to [hasRecordPermission] that returns `false` should be surfaced to the user as a
 * [dev.brgr.outspoke.ui.keyboard.KeyboardUiState.Error] with a link that opens the
 * companion app's permission screen.
 */
object PermissionHelper {

    /**
     * Returns `true` if [Manifest.permission.RECORD_AUDIO] has been granted for [context].
     */
    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED


    /**
     * Returns an [Intent] that deep-links to [dev.brgr.outspoke.settings.SettingsActivity]
     * so the user can grant [Manifest.permission.RECORD_AUDIO] from within an Activity
     * context.  The `SettingsActivity` intent-filter handles `outspoke://settings/{_}` URIs
     * and the NavHost routes `permissions` to the HomeScreen which requests the permission.
     */
    fun requestPermissionIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "outspoke://settings/permissions".toUri())
}

