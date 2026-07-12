package com.divarsmartsearch.app.data.filters

import com.divarsmartsearch.app.data.local.dao.BlockedPhoneDao
import com.divarsmartsearch.app.data.local.dao.KeywordFilterDao
import com.divarsmartsearch.app.data.local.dao.ListingInteractionDao
import com.divarsmartsearch.app.data.local.entity.ListingEntity
import com.divarsmartsearch.app.data.local.entity.ListingInteractionEntity
import com.divarsmartsearch.app.data.local.entity.SavedSearchEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full filter pipeline on a batch of listings, in order,
 * mirroring the original backend's apply_filters.py:
 *   1. Structured range filters (price/area/price-per-meter) from the SavedSearch.
 *   2. Permanent phone-number blocklist (official field + text-embedded numbers).
 *   3. Hard keyword exclusion — every ENABLED [com.divarsmartsearch.app.data.local.entity.KeywordFilterEntity]
 *      row (e.g. "دفتر", "املاک", "مشاور", "کلید", or any custom word the
 *      person added) is checked independently against the title and
 *      description; the listing is rejected the moment it matches ANY
 *      one of them (see [KeywordFilterEngine]).
 *   4. AI owner-detection (heuristic or LLM) for anything not already caught by step 3,
 *      applied either against the search's adjustable threshold, or — when
 *      [SavedSearchEntity.ownersOnly] ("فقط آگهی‌های مالک") is on — against a
 *      much stricter, multi-signal bar (see [isConfidentOwner]).
 *
 * Mutates each ListingEntity's isVisible/isLikelyAgency/ownerProbability
 * fields in place and returns the list that survived every stage.
 */
@Singleton
class FilterPipeline @Inject constructor(
    private val blockedPhoneDao: BlockedPhoneDao,
    private val listingInteractionDao: ListingInteractionDao,
    private val listingEnricher: ListingEnricher,
    private val keywordFilterDao: KeywordFilterDao,
) {
    companion object {
        // Used only when "فقط آگهی‌های مالک" (ownersOnly) is checked. This is
        // deliberately independent of, and stricter than, the user's
        // adjustable owner-detection slider: the slider is a tunable
        // preference, but this checkbox is a hard promise ("owner listings
        // only"), so it can't be satisfied by a loose slider value alone.
        private const val OWNERS_ONLY_MAX_AGENCY_PROBABILITY = 0.25

        // A phone number that repeats across other stored listings is a
        // strong professional-agent signal on its own; owners-only mode
        // refuses to show a listing that has this signal at all, even if
        // its text-based score looks clean.
        private const val OWNERS_ONLY_MAX_PHONE_REPEAT = 0
    }

    suspend fun apply(
        savedSearch: SavedSearchEntity,
        listings: List<ListingEntity>,
        ownerDetectionThreshold: Double,
        anthropicApiKey: String?,
        anthropicModel: String,
    ): List<ListingEntity> {
        if (listings.isEmpty()) return emptyList()

        populateDetectedPhoneNumbers(listings)

        // Cross-listing signal: how often this ad's phone number(s) show up
        // elsewhere. Computed before the agency check below so it can feed
        // straight into OwnerDetector as an extra signal.
        for (listing in listings) {
            listing.phoneRepeatCount = listingEnricher.computePhoneRepeatCount(listing)
        }

        val rangeSurvivors = applyRangeFilters(savedSearch, listings)
        for (listing in listings) {
            if (!listing.isVisible) recordRejection(listing, "out_of_filter_range")
        }

        val blockedNumbers = blockedPhoneDao.getAllNumbers().toSet()
        val phoneSurvivors = rangeSurvivors.filter { listing ->
            val blocked = PhoneFilter.isBlocked(listing, blockedNumbers)
            if (blocked) {
                listing.isVisible = false
                recordRejection(listing, "blocked_phone")
            }
            !blocked
        }

        // Every enabled keyword filter is its own independent check — a
        // listing must pass ALL "exclude" filters, in order, to survive
        // this stage. "owner_signal" filters (e.g. "من مالک هستم") are
        // handled separately below, per-listing, after this hard check.
        val activeKeywordFilters = keywordFilterDao.getAllEnabled()
        val excludeFilters = activeKeywordFilters.filter { it.filterType != "owner_signal" }
        val ownerSignalFilters = activeKeywordFilters.filter { it.filterType == "owner_signal" }

        val finalKept = mutableListOf<ListingEntity>()
        for (listing in phoneSurvivors) {
            // Checked against BOTH the title and the description: a huge share of
            // agency posts on Divar put the keyword in the TITLE only (e.g.
            // "مشاور املاک رضایی"، "فایل ویژه - املاک ..."), so checking the
            // description alone was letting most of them straight through.
            val matchedFilter = KeywordFilterEngine.findFirstMatch(
                listing.title, listing.description, excludeFilters
            )
            if (matchedFilter != null) {
                listing.isLikelyAgency = true
                listing.ownerProbability = 0.0
                listing.isVisible = false
                recordRejection(listing, "keyword_filter:${matchedFilter.label}")
                continue
            }

            // The ad explicitly claims to be from the owner (e.g. "من مالک
            // هستم"). This is trusted over the AI/heuristic guess — and,
            // under "فقط آگهی‌های شخصی", over that mode's extra phone-repeat
            // check too — but it can never override an "exclude" match
            // above; those stay an absolute rule.
            val ownerSignalMatch = KeywordFilterEngine.findFirstMatch(
                listing.title, listing.description, ownerSignalFilters
            )
            if (ownerSignalMatch != null) {
                listing.isLikelyAgency = false
                listing.ownerProbability = 1.0
                finalKept.add(listing)
                continue
            }

            val agencyProbability = OwnerDetector.agencyProbability(
                listing.description, anthropicApiKey, anthropicModel, listing.phoneRepeatCount
            )
            listing.ownerProbability = 1.0 - agencyProbability
            listing.isLikelyAgency = if (savedSearch.ownersOnly) {
                !isConfidentOwner(agencyProbability, listing.phoneRepeatCount, ownerDetectionThreshold)
            } else {
                agencyProbability >= ownerDetectionThreshold
            }

            if (listing.isLikelyAgency) {
                listing.isVisible = false
                recordRejection(listing, "likely_agency")
            } else {
                finalKept.add(listing)
            }
        }

        // Enrichment that only makes sense for listings the person will
        // actually see: duplicate/republish detection and price-vs-area
        // comparison, then a combined star rating from everything above.
        for (listing in finalKept) {
            listingEnricher.detectDuplicate(listing)
            listingEnricher.computePriceComparison(listing)
            listing.starRating = listingEnricher.computeStarRating(listing)
        }

        return finalKept
    }

    /**
     * The stricter, multi-signal bar used when "فقط آگهی‌های مالک" is on.
     * A listing only counts as a confident owner listing if EVERY signal
     * agrees:
     *   - its text-based agency probability is low, AND
     *   - it still respects whatever the user's own slider requires (so
     *     turning the checkbox on can only ever be stricter, never looser,
     *     than the slider), AND
     *   - its phone number hasn't already shown up on other listings —
     *     a real private owner sells one place, an agent's number keeps
     *     reappearing.
     */
    private fun isConfidentOwner(
        agencyProbability: Double,
        phoneRepeatCount: Int,
        ownerDetectionThreshold: Double,
    ): Boolean {
        val passesSlider = agencyProbability < ownerDetectionThreshold
        val passesStrictProbability = agencyProbability <= OWNERS_ONLY_MAX_AGENCY_PROBABILITY
        val passesPhoneHistory = phoneRepeatCount <= OWNERS_ONLY_MAX_PHONE_REPEAT
        return passesSlider && passesStrictProbability && passesPhoneHistory
    }

    private fun populateDetectedPhoneNumbers(listings: List<ListingEntity>) {
        for (listing in listings) {
            val numbers = PhoneExtraction.extractPhoneNumbers(listing.title, listing.description)
            listing.detectedPhoneNumbers = if (numbers.isNotEmpty()) numbers.joinToString(",") else null
        }
    }

    private fun applyRangeFilters(
        savedSearch: SavedSearchEntity,
        listings: List<ListingEntity>,
    ): List<ListingEntity> {
        for (listing in listings) {
            val price = listing.price
            val area = listing.area
            val pricePerMeter = listing.pricePerMeter
            val outOfRange = when {
                savedSearch.minPrice != null && price != null && price < savedSearch.minPrice -> true
                savedSearch.maxPrice != null && price != null && price > savedSearch.maxPrice -> true
                savedSearch.minArea != null && area != null && area < savedSearch.minArea -> true
                savedSearch.maxArea != null && area != null && area > savedSearch.maxArea -> true
                savedSearch.maxPricePerMeter != null && pricePerMeter != null &&
                    pricePerMeter > savedSearch.maxPricePerMeter -> true
                else -> false
            }
            if (outOfRange) listing.isVisible = false
        }
        return listings.filter { it.isVisible }
    }

    private suspend fun recordRejection(listing: ListingEntity, reason: String) {
        // Listing may not have a DB id yet if this is its first pass before
        // insertion; the repository re-associates interactions after insert.
        if (listing.id != 0L) {
            listingInteractionDao.insert(
                ListingInteractionEntity(listingId = listing.id, status = "rejected", rejectionReason = reason)
            )
        }
    }
}
