package com.divarsmartsearch.app.presentation.screens.permanentfilters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divarsmartsearch.app.domain.model.BlockedPhoneNumber
import com.divarsmartsearch.app.domain.model.KeywordFilter
import com.divarsmartsearch.app.domain.usecase.AddBlockedNumberUseCase
import com.divarsmartsearch.app.domain.usecase.AddKeywordFilterUseCase
import com.divarsmartsearch.app.domain.usecase.GetBlockedNumbersUseCase
import com.divarsmartsearch.app.domain.usecase.GetKeywordFiltersUseCase
import com.divarsmartsearch.app.domain.usecase.RemoveBlockedNumberUseCase
import com.divarsmartsearch.app.domain.usecase.RemoveKeywordFilterUseCase
import com.divarsmartsearch.app.domain.usecase.SetKeywordFilterEnabledUseCase
import com.divarsmartsearch.app.util.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermanentFiltersUiState(
    val isLoading: Boolean = true,
    val numbers: List<BlockedPhoneNumber> = emptyList(),
    val newPhoneNumber: String = "",
    val newPhoneNote: String = "",
    val addError: String? = null,
    val error: String? = null,

    // Keyword filters — each row is its own independent, toggleable filter.
    val isLoadingKeywordFilters: Boolean = true,
    val keywordFilters: List<KeywordFilter> = emptyList(),
    val newFilterLabel: String = "",
    val newFilterType: String = "exclude", // "exclude" | "owner_signal"
    val addFilterError: String? = null,
)

@HiltViewModel
class PermanentFiltersViewModel @Inject constructor(
    private val getBlockedNumbersUseCase: GetBlockedNumbersUseCase,
    private val addBlockedNumberUseCase: AddBlockedNumberUseCase,
    private val removeBlockedNumberUseCase: RemoveBlockedNumberUseCase,
    private val getKeywordFiltersUseCase: GetKeywordFiltersUseCase,
    private val addKeywordFilterUseCase: AddKeywordFilterUseCase,
    private val removeKeywordFilterUseCase: RemoveKeywordFilterUseCase,
    private val setKeywordFilterEnabledUseCase: SetKeywordFilterEnabledUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermanentFiltersUiState())
    val uiState: StateFlow<PermanentFiltersUiState> = _uiState.asStateFlow()

    init {
        load()
        loadKeywordFilters()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = getBlockedNumbersUseCase()) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, numbers = result.data) }
                is AppResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun updateNewPhoneNumber(value: String) = _uiState.update { it.copy(newPhoneNumber = value, addError = null) }
    fun updateNewPhoneNote(value: String) = _uiState.update { it.copy(newPhoneNote = value) }

    /**
     * Called after the user picks a contact from the system Contact
     * Picker (see PermanentFiltersScreen) instead of typing a number by
     * hand. Adds it straight to the blocklist, using the contact's name
     * as the note so it's still recognizable in the list afterward.
     */
    fun addNumberFromContact(phoneNumber: String, contactName: String?) {
        viewModelScope.launch {
            when (val result = addBlockedNumberUseCase(phoneNumber, contactName)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(numbers = it.numbers + result.data, addError = null)
                }
                is AppResult.Error -> _uiState.update { it.copy(addError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun addNumber() {
        val state = _uiState.value
        viewModelScope.launch {
            when (val result = addBlockedNumberUseCase(state.newPhoneNumber, state.newPhoneNote.ifBlank { null })) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        numbers = it.numbers + result.data,
                        newPhoneNumber = "",
                        newPhoneNote = "",
                        addError = null,
                    )
                }
                is AppResult.Error -> _uiState.update { it.copy(addError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun removeNumber(id: Int) {
        viewModelScope.launch {
            when (removeBlockedNumberUseCase(id)) {
                is AppResult.Success -> _uiState.update { it.copy(numbers = it.numbers.filterNot { n -> n.id == id }) }
                else -> Unit
            }
        }
    }

    fun loadKeywordFilters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingKeywordFilters = true) }
            when (val result = getKeywordFiltersUseCase()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoadingKeywordFilters = false, keywordFilters = result.data)
                }
                is AppResult.Error -> _uiState.update { it.copy(isLoadingKeywordFilters = false) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun updateNewFilterLabel(value: String) = _uiState.update { it.copy(newFilterLabel = value, addFilterError = null) }

    /** Toggling is optimistic: the switch flips immediately, then persists. */
    fun toggleKeywordFilter(filter: KeywordFilter, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                keywordFilters = state.keywordFilters.map { if (it.id == filter.id) it.copy(isEnabled = enabled) else it }
            )
        }
        viewModelScope.launch { setKeywordFilterEnabledUseCase(filter.id, enabled) }
    }

    fun updateNewFilterType(type: String) = _uiState.update { it.copy(newFilterType = type) }

    fun addKeywordFilter() {
        val label = _uiState.value.newFilterLabel.trim()
        val filterType = _uiState.value.newFilterType
        if (label.isBlank()) {
            _uiState.update { it.copy(addFilterError = "کلمه نمی‌تواند خالی باشد") }
            return
        }
        viewModelScope.launch {
            val category = if (filterType == "owner_signal") "owner" else "custom"
            when (val result = addKeywordFilterUseCase(label, label, category, filterType)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        keywordFilters = it.keywordFilters + result.data,
                        newFilterLabel = "",
                        addFilterError = null,
                    )
                }
                is AppResult.Error -> _uiState.update { it.copy(addFilterError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun removeKeywordFilter(id: Int) {
        viewModelScope.launch {
            when (removeKeywordFilterUseCase(id)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(keywordFilters = it.keywordFilters.filterNot { f -> f.id == id })
                }
                else -> Unit
            }
        }
    }
}
