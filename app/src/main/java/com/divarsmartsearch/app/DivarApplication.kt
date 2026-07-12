package com.divarsmartsearch.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DivarApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val newListingChannel = NotificationChannel(
                getString(R.string.new_listing_notification_channel_id),
                getString(R.string.new_listing_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.new_listing_notification_channel_desc)
            }

            // Low-importance and silent on purpose: this channel is only
            // for the persistent "background scan is running" status
            // notification, never for actual listing alerts.
            val backgroundScanChannel = NotificationChannel(
                getString(R.string.background_scan_channel_id),
                getString(R.string.background_scan_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.background_scan_channel_desc)
                setShowBadge(false)
            }

            manager.createNotificationChannel(newListingChannel)
            manager.createNotificationChannel(backgroundScanChannel)
        }
    }
}
