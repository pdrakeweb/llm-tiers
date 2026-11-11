package io.github.pdrakeweb.llmtiers.modelmanager

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Android's system download service. */
internal class SystemDownloads(context: Context) : Downloads {
    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, destination: File, allowMetered: Boolean, title: String): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(url))
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(false)
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
    )

    override fun query(id: Long): DownloadSnapshot? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.Pending
                DownloadManager.STATUS_RUNNING -> DownloadStatus.Running
                DownloadManager.STATUS_PAUSED -> DownloadStatus.Paused
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Successful
                else -> DownloadStatus.Failed
            }
            DownloadSnapshot(
                status = status,
                bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    override fun remove(id: Long) {
        manager.remove(id)
    }
}

internal class PreferenceKeyValues(context: Context) : KeyValues {
    private val preferences = context.getSharedPreferences("llm-tiers-models", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    // commit(), not apply(): the download id must be on disk before the process can be killed.
    override fun put(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}
