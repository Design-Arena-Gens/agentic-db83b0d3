package com.kitoko.packer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kitoko_completed")

class CompletedOrdersStore(private val context: Context) {
    private val key: Preferences.Key<Set<String>> = preferencesKey("completed_orders")

    val completedOrders: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[key] ?: emptySet()
    }

    suspend fun markCompleted(orderId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = current + orderId
        }
    }
}
