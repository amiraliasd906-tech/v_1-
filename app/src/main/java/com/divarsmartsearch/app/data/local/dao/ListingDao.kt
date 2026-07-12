package com.divarsmartsearch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.divarsmartsearch.app.data.local.entity.ListingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListingDao {

    @Query(
        "SELECT * FROM listings WHERE isVisible = 1 " +
            "AND (:savedSearchId IS NULL OR savedSearchId = :savedSearchId) " +
            "ORDER BY publishedAt DESC, firstSeenAt DESC"
    )
    fun observeVisible(savedSearchId: Long?): Flow<List<ListingEntity>>

    @Query(
        """
        SELECT listings.* FROM listings
        INNER JOIN listing_interactions ON listing_interactions.listingId = listings.id
        WHERE listing_interactions.status = :status
        GROUP BY listings.id
        ORDER BY listings.firstSeenAt DESC
        """
    )
    fun observeByInteractionStatus(status: String): Flow<List<ListingEntity>>

    @Query("SELECT * FROM listings WHERE savedSearchId = :savedSearchId")
    suspend fun getAllForSearch(savedSearchId: Long): List<ListingEntity>

    @Query("SELECT * FROM listings WHERE savedSearchId = :savedSearchId AND divarToken = :token LIMIT 1")
    suspend fun findByToken(savedSearchId: Long, token: String): ListingEntity?

    @Query("SELECT * FROM listings WHERE id = :id")
    suspend fun getById(id: Long): ListingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ListingEntity): Long

    @Update
    suspend fun update(entity: ListingEntity)

    // --- Owner/agent-repetition, duplicate-detection & price-comparison support ---

    /**
     * Bug fix: this used to be `LIKE '%' || :phone || '%'`, a plain
     * substring match against the comma-joined detectedPhoneNumbers list.
     * That falsely matched any number that happened to be a substring of
     * another stored number (e.g. "0912345678" matching inside
     * "09123456789,..."), and got *more* likely to misfire the longer the
     * app had been running and the more numbers had accumulated. Since
     * "فقط آگهی‌های شخصی" rejects a listing the instant its phone-repeat
     * count is above zero, even one such false match was enough to make
     * a perfectly fine, unique-numbered listing disappear. Every branch
     * below only matches :phone as a whole comma-delimited token.
     */
    @Query(
        """
        SELECT * FROM listings
        WHERE contactPhone = :phone
           OR detectedPhoneNumbers = :phone
           OR detectedPhoneNumbers LIKE :phone || ',%'
           OR detectedPhoneNumbers LIKE '%,' || :phone
           OR detectedPhoneNumbers LIKE '%,' || :phone || ',%'
        ORDER BY firstSeenAt DESC
        """
    )
    suspend fun getListingsForPhone(phone: String): List<ListingEntity>

    /** Distinct listing count for [phone], excluding [excludeId] itself. Same boundary-safe matching as [getListingsForPhone]. */
    @Query(
        """
        SELECT COUNT(DISTINCT id) FROM listings
        WHERE id != :excludeId
          AND (contactPhone = :phone
               OR detectedPhoneNumbers = :phone
               OR detectedPhoneNumbers LIKE :phone || ',%'
               OR detectedPhoneNumbers LIKE '%,' || :phone
               OR detectedPhoneNumbers LIKE '%,' || :phone || ',%')
        """
    )
    suspend fun countListingsForPhone(phone: String, excludeId: Long): Int

    @Query(
        "SELECT AVG(pricePerMeter) FROM listings " +
            "WHERE pricePerMeter IS NOT NULL AND id != :excludeId AND neighborhood = :neighborhood"
    )
    suspend fun averagePricePerMeterForNeighborhood(neighborhood: String, excludeId: Long): Double?

    @Query(
        "SELECT AVG(pricePerMeter) FROM listings " +
            "WHERE pricePerMeter IS NOT NULL AND id != :excludeId AND city = :city"
    )
    suspend fun averagePricePerMeterForCity(city: String, excludeId: Long): Double?

    /**
     * Recent candidates to compare against for duplicate/republish
     * detection. Bounded to a reasonable window since this is a personal,
     * single-device database — a full table scan of a few hundred rows is
     * cheap and simpler than a complex fuzzy-match SQL query.
     */
    @Query(
        "SELECT * FROM listings WHERE id != :excludeId AND (:city IS NULL OR city = :city) " +
            "ORDER BY firstSeenAt DESC LIMIT 300"
    )
    suspend fun getRecentCandidatesForDuplicateCheck(city: String?, excludeId: Long): List<ListingEntity>
}
