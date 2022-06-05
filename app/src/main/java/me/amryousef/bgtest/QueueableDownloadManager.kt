@file:OptIn(InternalSerializationApi::class)

package me.amryousef.bgtest

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import androidx.core.content.getSystemService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@Serializable
enum class DownloadState {
    Queued,
    Started,
    Errored,
    Finished
}

@Serializable
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val storageLocation: String
)

@Serializable
data class DownloadProgress(
    val downloadedBytes: Int,
    val totalBytes: Int
)

@Serializable
data class DownloadRequestWrapper(
    val id: Int?,
    val request: DownloadRequest,
    val state: DownloadState,
) {
    var progress: DownloadProgress? = null
}

interface DownloadManagerListener {
    suspend fun onRequestFailed(request: DownloadRequest, error: Exception)
    suspend fun onRequestStarted(request: DownloadRequest)
    suspend fun onRequestFinished(request: DownloadRequest)
    suspend fun onDownloadProgress(request: DownloadRequest, progress: DownloadProgress)
}

fun tickerFlow(periodInMillis: Long) = flow {
    while (true) {
        emit(Unit)
        delay(periodInMillis)
    }
}

class QueueableDownloadManager(
    private val application: Application
) : DownloadManagerListener, CoroutineScope {
    private val downloadManager = application.getSystemService<DownloadManager>()!!
    private val requestsQueue =
        PersistableListWrapper(
            application,
            "download_queue",
            ListSerializer(DownloadRequestWrapper::class.serializer())
        )
    private val listeners = mutableListOf<DownloadManagerListener>()

    init {
        tickerFlow(TimeUnit.SECONDS.toMillis(1)).onEach {
            val requests = requestsQueue.reader { it.filter { item -> item.state == DownloadState.Started } }
            val items =
                requests
                    .mapNotNull { it.id?.toLong() }
                    .toLongArray()
            val cursor = downloadManager.query(
                DownloadManager.Query().apply {
                    setFilterById(*items)
                }
            )
            if (cursor.moveToFirst()) {
                do {
                    val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val totalBytesIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloadedBytesIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    if (idIndex != -1 && statusIndex != -1 && totalBytesIndex != -1 && downloadedBytesIndex != -1) {
                        val id = cursor.getLong(idIndex)
                        val status = when (cursor.getInt(statusIndex)) {
                            DownloadManager.STATUS_FAILED -> DownloadState.Errored
                            DownloadManager.STATUS_RUNNING -> DownloadState.Started
                            else -> DownloadState.Started
                        }
                        val totalBytes = cursor.getLong(totalBytesIndex)
                        val downloadedBytes = cursor.getLong(downloadedBytesIndex)
                        val progress = DownloadProgress(
                            downloadedBytes = downloadedBytes.toInt(),
                            totalBytes = totalBytes.toInt()
                        )
                        val request = requests.firstOrNull { item -> item.id == id.toInt() }
                        if (status == DownloadState.Started && request != null) {
                            launch { onDownloadProgress(request.request, progress) }
                        }
                        if (status == DownloadState.Errored && request != null) {
                            launch { onRequestFailed(request.request, IllegalStateException()) }
                        }
                    }
                } while (cursor.moveToNext())
            }
        }.launchIn(this)
    }

    fun addListener(listener: DownloadManagerListener) {
        listeners.add(listener)
    }

    suspend fun getQueue() = requestsQueue.reader { it.toList() }

    suspend fun enqueue(requests: List<DownloadRequest>) {
        coroutineScope {
            val query = DownloadManager.Query().apply {
                setFilterByStatus(DownloadManager.STATUS_RUNNING.or(DownloadManager.STATUS_PENDING))
            }
            if (downloadManager.query(query).count != downloadBatchSize) {
                requests.chunked(downloadBatchSize).apply {
                    firstOrNull()
                        ?.map { request ->
                            request to downloadManager.enqueue(
                                DownloadManager.Request(Uri.parse(request.url)).apply {
                                    setAllowedOverRoaming(false)
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                                    setDestinationInExternalFilesDir(
                                        application,
                                        "downloads",
                                        request.storageLocation
                                    )
                                }
                            )
                        }
                        ?.map {
                            async {
                                requestsQueue.writer { list ->
                                    list.add(
                                        DownloadRequestWrapper(
                                            it.second.toInt(),
                                            it.first,
                                            DownloadState.Started
                                        )
                                    )
                                }
                                listeners.forEach { lo -> lo.onRequestStarted(it.first) }
                            }
                        }?.awaitAll()
                    requestsQueue.writer { list ->
                        subList(1, size).map { toEnqueue ->
                            list.addAll(
                                toEnqueue.map {
                                    DownloadRequestWrapper(
                                        null,
                                        it,
                                        DownloadState.Queued
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                requestsQueue.writer {
                    it.addAll(
                        requests.map { request ->
                            DownloadRequestWrapper(
                                null,
                                request,
                                DownloadState.Queued
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        lateinit var instance: QueueableDownloadManager
            private set

        var downloadBatchSize = 20

        fun initialise(application: Application) {
            instance = QueueableDownloadManager(application)
        }
    }

    override suspend fun onRequestFailed(request: DownloadRequest, error: Exception) {
        requestsQueue.writer { list ->
            list.replaceAll { wrapper ->
                if (wrapper.request == request) {
                    wrapper.copy(state = DownloadState.Errored)
                } else {
                    wrapper
                }
            }
        }
        refillQueue()
        listeners.forEach { it.onRequestFailed(request, error) }
    }

    override suspend fun onRequestStarted(request: DownloadRequest) {
        requestsQueue.writer { list ->
            list.replaceAll { wrapper ->
                if (wrapper.request == request) {
                    wrapper.copy(state = DownloadState.Started)
                } else {
                    wrapper
                }
            }
        }
        listeners.forEach { it.onRequestStarted(request) }
    }

    override suspend fun onRequestFinished(request: DownloadRequest) {
        requestsQueue.writer { list ->
            list.replaceAll { wrapper ->
                if (wrapper.request == request) {
                    wrapper.copy(state = DownloadState.Finished)
                } else {
                    wrapper
                }
            }
        }
        refillQueue()
        listeners.forEach { it.onRequestFinished(request) }
    }

    override suspend fun onDownloadProgress(request: DownloadRequest, progress: DownloadProgress) {
        requestsQueue.writer { list ->
            list.replaceAll { wrapper ->
                if (wrapper.request == request) {
                    val copy = wrapper.copy()
                    copy.progress = progress
                    copy
                } else {
                    wrapper
                }
            }
        }
        listeners.forEach { it.onDownloadProgress(request, progress) }
    }

    private suspend fun refillQueue() {
        val query = DownloadManager.Query().apply {
            setFilterByStatus(DownloadManager.STATUS_RUNNING.or(DownloadManager.STATUS_PENDING))
        }
        if (downloadManager.query(query).count == 0) {
            val nextItems = requestsQueue
                .reader { it.filter { item -> item.state == DownloadState.Queued } }
                .take(downloadBatchSize)
            val startedItems = nextItems.map { request ->
                val id = downloadManager.enqueue(
                    DownloadManager.Request(Uri.parse(request.request.url)).apply {
                        setAllowedOverRoaming(false)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        setDestinationInExternalFilesDir(
                            application,
                            "downloads",
                            request.request.storageLocation
                        )
                    }
                )
                request.copy(id = id.toInt())
            }
            requestsQueue.writer { list ->
                list.replaceAll { wrapper ->
                    val started =
                        startedItems.firstOrNull { it.request.fileName == wrapper.request.fileName }
                    started ?: wrapper
                }
            }
            startedItems.forEach { request ->
                listeners.forEach { it.onRequestStarted(request.request) }
            }
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO
}