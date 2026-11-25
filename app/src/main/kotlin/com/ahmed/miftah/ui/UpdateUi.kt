package com.ahmed.miftah.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ahmed.miftah.R
import com.ahmed.miftah.core.ReleaseVersion
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared UI bits for the in-app update check (auto prompt + manual Settings row). */
object UpdateUi {

    fun installedVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Non-intrusive prompt: a single dialog with the new version name and a Download action. */
    fun showAvailableDialog(context: Context, release: ReleaseVersion) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setMessage(context.getString(R.string.update_available_msg, release.versionName))
            .setPositiveButton(R.string.update_download) { _, _ -> openDownload(context, release.apkUrl) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun openDownload(context: Context, apkUrl: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
    }
}
