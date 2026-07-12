package com.divarsmartsearch.app.data.repository

import com.divarsmartsearch.app.data.filters.PhoneFilter
import com.divarsmartsearch.app.data.local.dao.ListingDao
import com.divarsmartsearch.app.data.local.dao.ListingInteractionDao
import com.divarsmartsearch.app.data.local.entity.ListingInteractionEntity
import com.divarsmartsearch.app.data.local.toDomain
import com.divarsmartsearch.app.domain.model.HistoryTab
import com.divarsmartsearch.app.domain.model.Listing
import com.divarsmartsearch.app.domain.model.SellerReport
import com.divarsmartsearch.app.domain.repository.ListingRepository
import com.divarsmartsearch.app.util.AppResult
import com.divarsmartsearch.app.util.safeCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListingRepositoryImpl @Inject constructor(
    private val listingDao: ListingDao,
    private val interactionDao: ListingInteractionDao,
) : ListingRepository {

    override suspend fun getVisibleListings(searchId: Int?): AppResult<List<Listing>> = safeCall {
        listingDao.observeVisible(searchId?.toLong()).first().map { it.toDomain() }
    }

    override fun observeVisibleListings(searchId: Int?): Flow<List<Listing>> =
        listingDao.observeVisible(searchId?.toLong()).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getHistory(tab: HistoryTab): AppResult<List<Listing>> = safeCall {
        val status = when (tab) {
            HistoryTab.SEEN -> "seen"
            HistoryTab.SAVED -> "saved"
            HistoryTab.REJECTED -> "rejected"
        }
        listingDao.observeByInteractionStatus(status).first().map { it.toDomain() }
    }

    override suspend fun markSeen(listingId: Int): AppResult<Unit> = safeCall {
        interactionDao.insert(ListingInteractionEntity(listingId = listingId.toLong(), status = "seen"))
    }

    override suspend fun saveListing(listingId: Int): AppResult<Unit> = safeCall {
        interactionDao.insert(ListingInteractionEntity(listingId = listingId.toLong(), status = "saved"))
        // Bug fix: without this, the listing's `isVisible` flag never
        // changes, so ResultsViewModel's live observeVisibleListings() Flow
        // (see ListingDao.observeVisible) keeps re-emitting it on every
        // collection and silently undoes the screen's optimistic removal —
        // the button looked like it did nothing. rejectListing already did
        // this; saveListing needs the exact same flip so a saved listing
        // moves into "ذخیره‌شده‌ها" in History instead of staying stuck in
        // the live results list.
        val listing = listingDao.getById(listingId.toLong())
        if (listing != null) {
            listingDao.update(listing.copy(isVisible = false))
        }
    }

    override suspend fun rejectListing(listingId: Int): AppResult<Unit> = safeCall {
        interactionDao.insert(
            ListingInteractionEntity(
                listingId = listingId.toLong(),
                status = "rejected",
                rejectionReason = "user_rejected",
            )
        )
        val listing = listingDao.getById(listingId.toLong())
        if (listing != null) {
            listingDao.update(listing.copy(isVisible = false))
        }
    }

    override suspend fun getSellerReport(phoneNumber: String): AppResult<SellerReport> = safeCall {
        val normalized = PhoneFilter.normalizePhone(phoneNumber)
        val entities = listingDao.getListingsForPhone(normalized)
        val listings = entities.map { it.toDomain() }

        val agencyPercents = listings.mapNotNull { it.ownerProbability }.map { (1.0 - it) * 100 }

        SellerReport(
            phoneNumber = normalized,
            totalListings = listings.size,
            cities = listings.mapNotNull { it.city }.distinct(),
            neighborhoods = listings.mapNotNull { it.neighborhood }.distinct(),
            averageAgencyLikelihoodPercent = if (agencyPercents.isEmpty()) null else {
                (agencyPercents.sum() / agencyPercents.size).toInt()
            },
            listings = listings,
        )
    }
}
