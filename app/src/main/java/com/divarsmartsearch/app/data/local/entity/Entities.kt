package com.divarsmartsearch.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val searchUrl: String,
    val status: String, // "active" | "paused"
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minArea: Double? = null,
    val maxArea: Double? = null,
    val maxPricePerMeter: Double? = null,
    val city: String? = null,
    val neighborhood: String? = null,
    val propertyType: String? = null,
    val ownersOnly: Boolean = false,
    val maxListingAgeHours: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "listings",
    indices = [Index(value = ["savedSearchId", "divarToken"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = SavedSearchEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedSearchId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ListingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedSearchId: Long,
    val divarToken: String,
    val url: String,
    val title: String,
    var description: String? = null,
    var price: Double? = null,
    var area: Double? = null,
    var pricePerMeter: Double? = null,
    val neighborhood: String? = null,
    val city: String? = null,
    var contactPhone: String? = null,
    var detectedPhoneNumbers: String? = null, // comma-separated
    val publishedAt: Long? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    var ownerProbability: Double? = null,
    var isLikelyAgency: Boolean = false,
    var isVisible: Boolean = true,
    var notified: Boolean = false,
    // See ListingEnricher for how each of these is computed.
    var phoneRepeatCount: Int = 0,
    var isDuplicate: Boolean = false,
    var duplicateOfListingId: Long? = null,
    var pricePerMeterVsAreaAveragePercent: Double? = null,
    var starRating: Int = 3,
)

@Entity(tableName = "blocked_phone_numbers")
data class BlockedPhoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "listing_interactions",
    foreignKeys = [
        ForeignKey(
            entity = ListingEntity::class,
            parentColumns = ["id"],
            childColumns = ["listingId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["listingId"])],
)
data class ListingInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listingId: Long,
    val status: String, // "seen" | "saved" | "rejected"
    val rejectionReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A single, independent keyword filter. Per explicit user request, each
 * word (e.g. "دفتر", "املاک", "مشاور", "کلید") is now its own separate
 * filter row instead of one hardcoded combined list: every listing is
 * checked against EVERY row where [isEnabled] is true.
 *
 * [filterType] controls what a match *does*:
 *  - "exclude" (the original four+ built-ins): matching this REJECTS the
 *    listing outright, no matter what else is true about it.
 *  - "owner_signal" (e.g. "من مالک هستم"): matching this tells the app
 *    "trust this ad's own claim of being a private owner" — it skips the
 *    AI/heuristic agency-probability guess (and, under "فقط آگهی‌های
 *    شخصی", its extra phone-repeat check) for THIS ad. It does NOT
 *    un-reject an ad that also matches an "exclude" row — those stay a
 *    hard, absolute rule, exactly as requested earlier, so this phrase
 *    can't be used to slip past "دفتر"/"مشاور"/etc.
 *
 * [isBuiltIn] rows can be toggled off but never deleted; custom rows
 * added by the user can be both toggled and deleted.
 */
@Entity(tableName = "keyword_filters")
data class KeywordFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val keyword: String,
    val category: String = "custom", // "real_estate" | "consultant" | "office" | "key" | "owner" | "custom"
    val filterType: String = "exclude", // "exclude" | "owner_signal"
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val darkModeEnabled: Boolean = true,
    val notificationSoundEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val notificationSoundUri: String = "default",
    val ownerDetectionThreshold: Double = 0.55,
    val anthropicApiKey: String? = null,
    val anthropicModel: String = "claude-haiku-4-5-20251001",
    val backgroundScanEnabled: Boolean = false,
    val backgroundScanIntervalMinutes: Int = 5,
)
