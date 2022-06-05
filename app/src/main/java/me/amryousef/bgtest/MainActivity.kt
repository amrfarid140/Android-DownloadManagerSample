package me.amryousef.bgtest

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import me.amryousef.bgtest.ui.theme.BGTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requests = (0..100).map { index ->
            DownloadRequest(
                url = "https://assets.pippa.io/shows/609e4b3069be6d6524986cee/1621410644716-c0101be2c3d5fd99355ce551a7e17497.mp3",
                fileName = "Item $index",
                storageLocation = "item$index"
            )
        }
        val listener = callbackFlow<List<DownloadRequestWrapper>> {
            send(QueueableDownloadManager.instance.getQueue())
            QueueableDownloadManager.instance.addListener(
                object : DownloadManagerListener {
                    override suspend fun onRequestFailed(
                        request: DownloadRequest,
                        error: Exception
                    ) {
                        send(QueueableDownloadManager.instance.getQueue())
                    }

                    override suspend fun onRequestStarted(request: DownloadRequest) {
                        send(QueueableDownloadManager.instance.getQueue())
                    }

                    override suspend fun onRequestFinished(request: DownloadRequest) {
                        send(QueueableDownloadManager.instance.getQueue())
                    }

                    override suspend fun onDownloadProgress(
                        request: DownloadRequest,
                        progress: DownloadProgress
                    ) {
                        send(QueueableDownloadManager.instance.getQueue())
                    }

                }
            )
            awaitClose { }
        }
        setContent {
            val coroutineScope = rememberCoroutineScope()
            val state by listener.collectAsState(initial = emptyList())
            BGTestTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            items(requests) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                ) {
                                    Text(text = it.fileName)
                                    Spacer(modifier = Modifier.weight(1f))
                                    state.firstOrNull { item -> item.request.fileName == it.fileName }
                                        ?.let {
                                            Text(it.state.name)
                                            if (it.progress != null) {
                                                Text(
                                                    ": (%.2f%%)".format(
                                                        (it.progress!!.downloadedBytes.toDouble() / it.progress!!.totalBytes.toDouble()) * 100
                                                    )
                                                )
                                            }
                                        } ?: Text("Queued")
                                }
                            }
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            onClick = {
                                coroutineScope.launch {
                                    QueueableDownloadManager.instance.enqueue(requests)
                                }
                            }
                        ) {
                            Text(text = "Start Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BGTestTheme {
        Greeting("Android")
    }
}