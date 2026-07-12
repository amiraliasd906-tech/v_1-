package com.divarsmartsearch.app.domain.repository

import com.divarsmartsearch.app.domain.model.HistoryTab
import com.divarsmartsearch.app.domain.model.Listing
import com.divarsmartsearch.app.domain.model.SellerReport
import com.divarsmartsearch.app.util.AppResult
import kotlinx.coroutines.flow.Flow

interface ListingRepository {
    suspend fun getVisibleListings(searchId: Int? = null): AppResult<List<Listing>>

    /**
     * Live view of the visible listings, straight from the database. The
     * background scanner inserts new listings independently of any screen
     * being open, so the Results screen must observe this Flow (not just
     * fetch once) or newly ingested listings never appear until the
     * ViewModel happens to be recreated.
     */
    fun observeVisibleListings(searchId: Int? = null): Flow<List<Listing>>
    suspend fun getHistory(tab: HistoryTab): AppResult<List<Listing>>
    suspend fun markSeen(listingId: Int): AppResult<Unit>
    suspend fun saveListing(listingId: Int): AppResult<Unit>
    suspend fun rejectListing(listingId: Int): AppResult<Unit>

    /** Every stored listing (any search) that shares [phoneNumber], aggregated for display. */
    suspend fun getSellerReport(phoneNumber: String): AppResult<SellerReport>
}
