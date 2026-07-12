package com.divarsmartsearch.app.data.webview

import com.divarsmartsearch.app.data.filters.FilterPipeline
import com.divarsmartsearch.app.data.filters.KeywordFilterEngine
import com.divarsmartsearch.app.data.filters.PhoneFilter
import com.divarsmartsearch.app.data.local.dao.AppSettingsDao
import com.divarsmartsearch.app.data.local.dao.KeywordFilterDao
import com.divarsmartsearch.app.data.local.dao.ListingDao
import com.divarsmartsearch.app.data.local.dao.SavedSearchDao
import com.divarsmartsearch.app.data.local.entity.ListingEntity
import com.divarsmartsearch.app.notification.LocalNotifier
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

data class IngestResult(val received: Int, val new: Int, val passedFilters: Int)

/**
 * Handles listings extracted by the in-app WebView. This is the
 * Kotlin/Room equivalent of the old backend's app/services/ingestion.py:
 * new listings are inserted, listings seen before are enriched in place
 * (e.g. a detail-page visit revealing a phone number), and each change
 * re-runs the full filter pipeline. Notifications are local and are
 * only ever sent once per listing (see `notified` on ListingEntity).
 */
@Singleton
class ListingIngestionService @Inject constructor(
    private val savedSearchDao: SavedSearchDao,
    private val listingDao: ListingDao,
    private val appSettingsDao: AppSettingsDao,
    private val filterPipeline: FilterPipeline,
    private val listingDetailFetcher: ListingDetailFetcher,
    private val localNotifier: LocalNotifier,
    private val keywordFilterDao: KeywordFilterDao,
) {
    suspend fun ingest(savedSearchId: Long, items: List<ExtractedListing>): IngestResult {
        val savedSearch = savedSearchDao.getById(savedSearchId) ?: return IngestResult(items.size, 0, 0)

        val brandNew = mutableListOf<ListingEntity>()
        val enriched = mutableListOf<ListingEntity>()

        for (item in items) {
            val existing = listingDao.findByToken(savedSearchId, item.divarToken)

            if (existing == null) {
                val entity = ListingEntity(
                    savedSearchId = savedSearchId,
                    divarToken = item.divarToken,
                    url = item.url,
                    title = item.title,
                    description = item.description,
                    price = item.price,
                    area = item.area,
                    pricePerMeter = item.pricePerMeter,
                    neighborhood = item.neighborhood ?: savedSearch.neighborhood,
                    city = savedSearch.city,
                    contactPhone = normalizeOrNull(item.contactPhone),
                )
                val newId = listingDao.insert(entity)
                brandNew.add(entity.copy(id = newId))
            } else {
                var changed = false
                var updated = existing
                if (item.description != null && item.description != existing.description) {
                    updated = updated.copy(description = item.description); changed = true
                }
                if (item.price != null && item.price != existing.price) {
                    updated = updated.copy(price = item.price); changed = true
                }
                if (item.area != null && item.area != existing.area) {
                    updated = updated.copy(area = item.area); changed = true
                }
                if (item.pricePerMeter != null && item.pricePerMeter != existing.pricePerMeter) {
                    updated = updated.copy(pricePerMeter = item.pricePerMeter); changed = true
                }
                if (item.contactPhone != null) {
                    val normalized = normalizeOrNull(item.contactPhone)
                    if (normalized != null && normalized != existing.contactPhone) {
                        updated = updated.copy(contactPhone = normalized); changed = true
                    }
                }
                if (changed) {
                    updated = updated.copy(isVisible = true, isLikelyAgency = false)
                    enriched.add(updated)
                }
            }
        }

        val toProcess = brandNew + enriched
        if (toProcess.isEmpty()) return IngestResult(items.size, 0, 0)

        // Actively go read each listing's real description from its detail
        // page over the network — this is what lets the "مشاور"/"املاک"
        // filter (and everything else in FilterPipeline) work against the
        // actual ad text instead of just the short preview visible on the
        // search-results card. Runs sequentially and best-effort: if a
        // fetch fails for any reason, that listing simply keeps whatever
        // weaker description it already had (from the card, or the old
        // stored value) and still goes through the rest of the pipeline.
        for ((index, listing) in toProcess.withIndex()) {
            val detail = listingDetailFetcher.fetchDetail(listing.url)
            if (!detail.description.isNullOrBlank()) {
                listing.description = detail.description
            }
            if (!detail.contactPhone.isNullOrBlank()) {
                normalizeOrNull(detail.contactPhone)?.let { listing.contactPhone = it }
            }
            // Small, deliberate pacing between requests: a cycle that
            // surfaces many new listings at once would otherwise fire
            // their detail-page fetches back-to-back, which looks like a
            // scraping burst to Divar and risks the IP getting
            // rate-limited or temporarily blocked. Skipped after the last
            // item so this never adds a trailing delay for nothing.
            if (index < toProcess.lastIndex) {
                delay(DETAIL_FETCH_SPACING_MS)
            }
        }

        val settings = appSettingsDao.get()
        val surviving = filterPipeline.apply(
            savedSearch = savedSearch,
            listings = toProcess,
            ownerDetectionThreshold = settings?.ownerDetectionThreshold ?: 0.55,
            anthropicApiKey = settings?.anthropicApiKey,
            anthropicModel = settings?.anthropicModel ?: "claude-haiku-4-5-20251001",
        )

        for (listing in toProcess) listingDao.update(listing)

        // Final, redundant safety net right before a notification is ever sent.
        // `surviving` should already exclude every "exclude"-type
        // keyword-filter-matched listing (FilterPipeline checks it before
        // this point), but the user was explicit that this must NEVER be
        // allowed to slip through, so every ENABLED exclude filter is
        // checked one more time, independently, at the very last moment —
        // if either the title or the description matches any one of them,
        // no notification. "owner_signal" rows (e.g. "من مالک هستم") are
        // deliberately left out of this recheck: a match there is a reason
        // TO notify, never a reason to block one.
        val activeExcludeFilters = keywordFilterDao.getAllEnabled().filter { it.filterType != "owner_signal" }
        val toNotify = surviving.filter { listing ->
            !listing.notified &&
                KeywordFilterEngine.findFirstMatch(listing.title, listing.description, activeExcludeFilters) == null
        }
        for (listing in toNotify) {
            if (settings?.notificationsEnabled != false) {
                localNotifier.notifyNewListing(listing)
            }
            listingDao.update(listing.copy(notified = true))
        }

        return IngestResult(received = items.size, new = brandNew.size, passedFilters = surviving.size)
    }

    /**
     * Bug fix: contactPhone used to be stored exactly as it came off the
     * page (sometimes "+98912...", sometimes "0912...", sometimes with
     * dashes/spaces from the tel: link) while every OTHER phone comparison
     * in the app (blocklist, keyword-adjacent checks) went through
     * [PhoneFilter.normalizePhone] first. Two representations of the same
     * real-world number therefore looked like two different numbers to
     * anything comparing raw contactPhone values — see
     * [com.divarsmartsearch.app.data.filters.ListingEnricher.computePhoneRepeatCount]
     * and the SQL in [com.divarsmartsearch.app.data.local.dao.ListingDao].
     * Normalizing once, right here at storage time, means every later
     * comparison is apples-to-apples.
     */
    private fun normalizeOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = PhoneFilter.normalizePhone(raw)
        return normalized.ifBlank { null }
    }

    private companion object {
        const val DETAIL_FETCH_SPACING_MS = 600L
    }
}
