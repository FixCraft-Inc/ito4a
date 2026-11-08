package com.fixcraft.ito

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.ito.data.InvalidPreferences
import com.fixcraft.ito.data.PreferencesStore
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.SavedService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

sealed interface PreferencesState {
    data object Loading : PreferencesState
    data class Ready(val preferences: Preferences) : PreferencesState
    data object Unreadable : PreferencesState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val services = (application as ItoApplication).services
    val connections = (application as ItoApplication).connections
    private val store = PreferencesStore(application)
    private val mutableState = MutableStateFlow<PreferencesState>(PreferencesState.Loading)
    private val mutableNotice = MutableStateFlow<Int?>(null)
    private var reader: Job? = null
    val state = mutableState.asStateFlow()
    val notice = mutableNotice.asStateFlow()

    init { retryRead(); services.reload() }

    fun retryRead() {
        reader?.cancel()
        reader = viewModelScope.launch {
            mutableState.value = PreferencesState.Loading
            try {
                store.preferences.collect { mutableState.value = PreferencesState.Ready(it) }
            } catch (_: IOException) {
                mutableState.value = PreferencesState.Unreadable
            } catch (_: InvalidPreferences) {
                mutableState.value = PreferencesState.Unreadable
            }
        }
    }

    // Routing and audio preferences stay in the stored schema with their
    // defaults. Settings expose them again when direct calls and lower-data
    // audio exist, so nothing here can change them today.

    fun setPresentation(value: Presentation) = update { it.copy(presentation = value) }

    fun addService(service: SavedService) = update { current ->
        when {
            current.services.any { it.origin == service.origin } -> {
                mutableNotice.value = R.string.duplicate_service
                current
            }
            current.services.size >= Preferences.MAX_SERVICES -> {
                mutableNotice.value = R.string.service_limit
                current
            }
            else -> current.copy(services = current.services + service)
        }
    }

    fun removeService(id: UUID) = update { current ->
        current.copy(services = current.services.filterNot { it.id == id })
    }

    fun dismissNotice() { mutableNotice.value = null }

    private fun update(change: (Preferences) -> Preferences) {
        viewModelScope.launch {
            try {
                store.update(change)
            } catch (_: IOException) {
                mutableNotice.value = R.string.save_error
            } catch (_: InvalidPreferences) {
                mutableState.value = PreferencesState.Unreadable
            }
        }
    }
}
