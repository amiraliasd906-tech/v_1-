package com.divarsmartsearch.app.presentation.screens.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.divarsmartsearch.app.domain.model.Listing
import com.divarsmartsearch.app.domain.usecase.AddBlockedNumberUseCase
import com.divarsmartsearch.app.domain.usecase.ObserveVisibleListingsUseCase
import com.divarsmartsearch.app.domain.usecase.MarkListingSeenUseCase
import com.divarsmartsearch.app.domain.usecase.RejectListingUseCase
import com.divarsmartsearch.app.domain.usecase.SaveListingUseCase
import com.divarsmartsearch.app.util.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResultsUiState(
    val isLoading: Boolean = true,
    val listings: List<Listing> = emptyList(),
    val error: String? = null,
    val blockNumberMessage: String? = null,
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val observeVisibleListingsUseCase: ObserveVisibleListingsUseCase,
    private val markListingSeenUseCase: MarkListingSeenUseCase,
    private val saveListingUseCase: SaveListingUseCase,
    private val rejectListingUseCase: RejectListingUseCase,
    private val addBlockedNumberUseCase: AddBlockedNumberUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    /**
     * Subscribes to the live listings Flow for the lifetime of this
     * ViewModel, instead of fetching once. The background scan service
     * inserts new listings on its own schedule, with nobody looking at the
     * Results screen at the time — a one-shot fetch on init only ever
     * reflects whatever existed at that exact instant, so anything the
     * scanner adds afterwards silently never showed up.
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            observeVisibleListingsUseCase()
                .catch { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
                .onEach { listings -> _uiState.update { it.copy(isLoading = false, listings = listings) } }
                .collect()
        }
    }

    fun onOpened(listingId: Int) {
        viewModelScope.launch { markListingSeenUseCase(listingId) }
    }

    fun onSave(listingId: Int) {
        viewModelScope.launch {
            saveListingUseCase(listingId)
            removeFromList(listingId)
        }
    }

    fun onReject(listingId: Int) {
        viewModelScope.launch {
            rejectListingUseCase(listingId)
            removeFromList(listingId)
        }
    }

    fun onBlockPhoneNumber(phoneNumber: String) {
        viewModelScope.launch {
            when (val result = addBlockedNumberUseCase(phoneNumber, null)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(blockNumberMessage = "شماره $phoneNumber مسدود شد") }
                    // Listings from this number will be hidden on next refresh.
                }
                is AppResult.Error -> _uiState.update { it.copy(blockNumberMessage = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun clearBlockNumberMessage() {
        _uiState.update { it.copy(blockNumberMessage = null) }
    }

    private fun removeFromList(listingId: Int) {
        _uiState.update { state -> state.copy(listings = state.listings.filterNot { it.id == listingId }) }
    }
}
