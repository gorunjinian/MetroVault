package com.gorunjinian.metrovault.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds the app-wide [UserPreferencesRepository] off the main thread.
 * MainActivity keeps the splash screen up until it exists, which makes this
 * the one place guaranteed to run before any screen composes - so the
 * startup safety net lives here as well.
 */
@Suppress("PrivatePropertyName", "PropertyName")
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _UserPreferencesRepository = MutableStateFlow<UserPreferencesRepository?>(null)
    val UserPreferencesRepository: StateFlow<UserPreferencesRepository?> = _UserPreferencesRepository.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Safety net: an unprovisioned app with leftover data on disk
                // (a wipe cut short by a process kill) is wiped before the
                // repository below reads the store, so no reload is needed.
                SecureStorage(getApplication()).wipeResidueIfUnprovisioned()
                _UserPreferencesRepository.value = UserPreferencesRepository(getApplication())
            }
        }
    }
}
