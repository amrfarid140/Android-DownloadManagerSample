package me.amryousef.bgtest

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PersistableListWrapper<T>(
    context: Context,
    private val storageKey: String,
    private val serializer: KSerializer<List<T>>
) {
    private val content = mutableListOf<T>()
    private val mutex = Mutex()
    private val sharedPreferences = context
        .getSharedPreferences(storageKey, Context.MODE_PRIVATE)

    init {
        Json.decodeFromString(serializer, sharedPreferences.getString("data", "[]") ?: "[]")
            .apply {
                content.addAll(this)
            }
    }

    suspend fun <U> reader(block: (List<T>) -> U): U {
        return mutex.withLock { block(content) }
    }

    @SuppressLint("ApplySharedPref")
    suspend fun writer(block: (MutableList<T>) -> Unit) {
        mutex.withLock {
            block(content)
            sharedPreferences.edit().putString("data", Json.encodeToString(serializer, content))
                .commit()
        }
    }
}