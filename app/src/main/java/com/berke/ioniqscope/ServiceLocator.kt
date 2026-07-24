package com.berke.ioniqscope

import android.content.Context
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
    }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
