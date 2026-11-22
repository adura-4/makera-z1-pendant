package com.z1pendant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.z1pendant.files.FilesController
import com.z1pendant.files.ShareInbox

/**
 * Share → Z1 Pendant from another app (Files, Drive, Gmail…). Android starts a share target in the sharing
 * app's task, which would mean a second pendant with its own connection; so this invisible activity only
 * reads the file (it holds the read permission), drops it in [ShareInbox], and brings the one real
 * [MainActivity] to the front. Files then asks "Upload to the Z1?" with Upload / Cancel.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = streamOf(intent)
        if (intent?.action != Intent.ACTION_SEND || uri == null) { finish(); return }
        Thread {
            val result = runCatching { read(uri) }
            runOnUiThread {
                result.fold(
                    onSuccess = { s ->
                        if (s == null) toast("That file is too big for the Z1.")
                        else {
                            ShareInbox.file.value = s
                            startActivity(Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                    },
                    onFailure = { android.util.Log.w("ShareActivity", "couldn't read $uri", it); toast("Couldn't read that file.") })
                finish()
            }
        }.start()
    }

    /** Name and bytes, or null if it's over the upload limit (checked before reading it all in). */
    private fun read(uri: Uri): ShareInbox.Shared? {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > FilesController.MAX_UPLOAD) return null
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("no stream")
        if (bytes.size > FilesController.MAX_UPLOAD) return null
        return ShareInbox.Shared(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.nc", bytes)
    }

    private fun streamOf(i: Intent?): Uri? = when {
        i == null -> null
        Build.VERSION.SDK_INT >= 33 -> i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
