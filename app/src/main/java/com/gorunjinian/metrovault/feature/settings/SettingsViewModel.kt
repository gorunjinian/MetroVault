package com.gorunjinian.metrovault.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("PrivatePropertyName", "PropertyName")
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _UserPreferencesRepository = MutableStateFlow<UserPreferencesRepository?>(null)
    val UserPreferencesRepository: StateFlow<UserPreferencesRepository?> = _UserPreferencesRepository.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _UserPreferencesRepository.value = UserPreferencesRepository(getApplication())
            }
        }
    }
}