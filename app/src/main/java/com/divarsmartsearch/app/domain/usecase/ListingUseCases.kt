package com.divarsmartsearch.app.domain.usecase

import com.divarsmartsearch.app.domain.model.HistoryTab
import com.divarsmartsearch.app.domain.model.Listing
import com.divarsmartsearch.app.domain.model.SellerReport
import com.divarsmartsearch.app.domain.repository.ListingRepository
import com.divarsmartsearch.app.util.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetVisibleListingsUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(searchId: Int? = null): AppResult<List<Listing>> =
        repository.getVisibleListings(searchId)
}

/** Live results feed: emits again automatically whenever the background scanner adds/updates a listing. */
class ObserveVisibleListingsUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    operator fun invoke(searchId: Int? = null): Flow<List<Listing>> =
        repository.observeVisibleListings(searchId)
}

class GetListingHistoryUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(tab: HistoryTab): AppResult<List<Listing>> =
        repository.getHistory(tab)
}

class MarkListingSeenUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(listingId: Int): AppResult<Unit> = repository.markSeen(listingId)
}

class SaveListingUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(listingId: Int): AppResult<Unit> = repository.saveListing(listingId)
}

class RejectListingUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(listingId: Int): AppResult<Unit> = repository.rejectListing(listingId)
}

class GetSellerReportUseCase @Inject constructor(
    private val repository: ListingRepository
) {
    suspend operator fun invoke(phoneNumber: String): AppResult<SellerReport> =
        repository.getSellerReport(phoneNumber)
}
