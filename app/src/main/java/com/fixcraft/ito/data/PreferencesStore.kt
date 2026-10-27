package com.fixcraft.ito.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fixcraft.ito.domain.Preferences
import kotlinx.coroutines.flow.map

private val Context.itoPreferences by preferencesDataStore(name = "preferences")

class PreferencesStore(context: Context) {
    private val store = context.applicationContext.itoPreferences
    private val snapshot = stringPreferencesKey("snapshot")

    val preferences = store.data.map { PreferencesCodec.decode(it[snapshot]) }

    suspend fun update(change: (Preferences) -> Preferences) {
        store.edit { values ->
            // Decode inside the transaction. Corrupt state must never become a fresh default on write.
            values[snapshot] = PreferencesCodec.encode(change(PreferencesCodec.decode(values[snapshot])))
        }
    }
}
