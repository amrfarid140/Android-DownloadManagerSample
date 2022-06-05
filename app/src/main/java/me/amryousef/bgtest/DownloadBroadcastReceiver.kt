package me.amryousef.bgtest

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking


class DownloadBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        runBlocking(Dispatchers.IO) {
            val queue = QueueableDownloadManager.instance.getQueue()
            val downloadId = p1?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != null && downloadId != -1L) {
                val request = queue.firstOrNull { wrapper ->
                    wrapper.id != null && wrapper.id == downloadId.toInt()
                }
                if (request != null) {
                    QueueableDownloadManager.instance.onRequestFinished(request.request)
                }
            }
        }
        Log.v("AMR", p1.toString())
    }
}