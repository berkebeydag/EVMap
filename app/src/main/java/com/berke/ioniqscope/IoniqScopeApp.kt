package com.berke.ioniqscope

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.berke.ioniqscope.service.TripLoggingService

class IoniqScopeApp : Application() {

    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator.get(this)
        services.warmUp()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            TripLoggingService.CHANNEL_ID,
            getString(R.string.trip_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.trip_notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
