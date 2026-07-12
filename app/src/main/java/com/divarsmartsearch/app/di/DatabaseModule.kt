package com.divarsmartsearch.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.divarsmartsearch.app.data.local.AppDatabase
import com.divarsmartsearch.app.data.local.dao.AppSettingsDao
import com.divarsmartsearch.app.data.local.dao.BlockedPhoneDao
import com.divarsmartsearch.app.data.local.dao.KeywordFilterDao
import com.divarsmartsearch.app.data.local.dao.ListingDao
import com.divarsmartsearch.app.data.local.dao.ListingInteractionDao
import com.divarsmartsearch.app.data.local.dao.SavedSearchDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Everything the app needs lives in a single on-device Room database —
 * there is no remote server/backend in this build (see README).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * The four keyword filters the app ships with, per explicit user
     * request: each word is now its own independent, toggleable filter
     * row (instead of one hardcoded combined list) — see
     * [com.divarsmartsearch.app.data.filters.KeywordFilterEngine].
     * Triple = (label shown to the user, matched root, category — used
     * only to pick an icon/color in the UI).
     */
    /**
     * The keyword filters the app ships with. Fourth element is
     * [KeywordFilterEntity.filterType] — "exclude" (reject on match) or
     * "owner_signal" (trust the ad's own claim, skip agency-probability
     * checks for it) — see the KDoc on that entity for the full story.
     */
    private data class KeywordSeed(val label: String, val keyword: String, val category: String, val filterType: String)

    private val defaultKeywordFilters = listOf(
        KeywordSeed("دفتر", "دفتر", "office", "exclude"),
        KeywordSeed("املاک", "املاک", "real_estate", "exclude"),
        KeywordSeed("مشاور", "مشاور", "consultant", "exclude"),
        KeywordSeed("مشاور املاک", "مشاور املاک", "consultant", "exclude"),
        KeywordSeed("کلید دفتر موجود است", "کلید دفتر موجود است", "office", "exclude"),
        KeywordSeed("دفتر املاک", "دفتر املاک", "office", "exclude"),
        KeywordSeed("من مالک هستم", "من مالک هستم", "owner", "owner_signal"),
    )

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration() // fine for a personal, single-user local DB
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    val now = System.currentTimeMillis()
                    defaultKeywordFilters.forEach { seed ->
                        db.execSQL(
                            "INSERT INTO keyword_filters (label, keyword, category, filterType, isEnabled, isBuiltIn, createdAt) " +
                                "VALUES (?, ?, ?, ?, 1, 1, ?)",
                            arrayOf(seed.label, seed.keyword, seed.category, seed.filterType, now),
                        )
                    }
                }
            })
            .build()

    @Provides
    fun provideSavedSearchDao(db: AppDatabase): SavedSearchDao = db.savedSearchDao()

    @Provides
    fun provideListingDao(db: AppDatabase): ListingDao = db.listingDao()

    @Provides
    fun provideBlockedPhoneDao(db: AppDatabase): BlockedPhoneDao = db.blockedPhoneDao()

    @Provides
    fun provideListingInteractionDao(db: AppDatabase): ListingInteractionDao = db.listingInteractionDao()

    @Provides
    fun provideAppSettingsDao(db: AppDatabase): AppSettingsDao = db.appSettingsDao()

    @Provides
    fun provideKeywordFilterDao(db: AppDatabase): KeywordFilterDao = db.keywordFilterDao()
}
