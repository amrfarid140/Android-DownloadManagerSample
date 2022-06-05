package me.amryousef.bgtest

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QueueableDownloadManager.initialise(this)
    }
}