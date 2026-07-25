package com.berke.ioniqscope

import android.content.Context
import com.berke.ioniqscope.charging.ChargerRepository
import com.berke.ioniqscope.charging.ChargerSeeder
import com.berke.ioniqscope.charging.ChargerSource
import com.berke.ioniqscope.charging.OcmChargerSource
import com.berke.ioniqscope.charging.OsmChargerSource
import com.berke.ioniqscope.connection.AuxBatteryMonitor
import com.berke.ioniqscope.connection.DriveDetector
import com.berke.ioniqscope.connection.ObdConnectionManager
import com.berke.ioniqscope.connection.PerfRunRecorder
import com.berke.ioniqscope.data.AppDatabase
import com.berke.ioniqscope.data.CsvExporter
import com.berke.ioniqscope.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hand-rolled DI. A single-user hobby app does not need Hilt, and this keeps the
 * whole object graph readable in one screen.
 */
class ServiceLocator private constructor(context: Context) {

    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val connectionManager: ObdConnectionManager by lazy {
        ObdConnectionManager(appContext, appScope)
    }

    val csvExporter: CsvExporter by lazy {
        CsvExporter(appContext, database.tripDao())
    }

    /**
     * Charger sources, best-data-first. OSM needs nothing and is always available;
     * OCM turns itself on once the user has pasted their own free key.
     */
    private val osmChargerSource by lazy { OsmChargerSource() }

    val chargerSources: List<ChargerSource> by lazy {
        listOf(
            osmChargerSource,
            OcmChargerSource(apiKeyProvider = { cachedOcmKey })
        )
    }

    private val chargerSeeder: ChargerSeeder by lazy {
        ChargerSeeder(appContext, database.chargingStationDao(), osmChargerSource, appScope)
    }

    val chargerRepository: ChargerRepository by lazy {
        ChargerRepository(database.chargingStationDao(), chargerSources)
    }

    /**
     * Mirror of the stored OCM key.
     *
     * ChargerSource.isAvailable() is called from composition and menu building,
     * where suspending to read DataStore is not an option, so the value is kept
     * here and refreshed by the collector below.
     */
    @Volatile private var cachedOcmKey: String? = null

    private val perfRunRecorder: PerfRunRecorder by lazy {
        PerfRunRecorder(connectionManager, database.perfRunDao(), appScope)
    }

    private val auxBatteryMonitor: AuxBatteryMonitor by lazy {
        AuxBatteryMonitor(connectionManager, database.auxVoltageDao(), appScope)
    }

    private val driveDetector: DriveDetector by lazy {
        DriveDetector(appContext, connectionManager, settings, appScope)
    }

    fun warmUp() {
        perfRunRecorder.start()
        auxBatteryMonitor.start()
        driveDetector.start()
        chargerSeeder.seedIfEmpty()
        appScope.launch {
            settings.settings.collect { cachedOcmKey = it.ocmApiKey.takeIf { k -> k.isNotBlank() } }
        }
    }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
