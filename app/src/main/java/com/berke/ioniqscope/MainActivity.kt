package com.berke.ioniqscope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.berke.ioniqscope.service.TripLoggingService
import com.berke.ioniqscope.ui.IoniqScopeRoot
import com.berke.ioniqscope.ui.screens.connect.BluetoothPermissions
import com.berke.ioniqscope.ui.theme.IoniqScopeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var services: ServiceLocator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        services = ServiceLocator.get(this)

        setContent {
            IoniqScopeTheme {
                IoniqScopeRoot(services)
            }
        }

        maybeAutoConnect()
    }

    /**
     * Reconnects to the last adapter on launch when the user has opted in.
     *
     * Guarded on permissions and on not already being connected — this also runs on
     * an activity recreation (rotation, theme change), where a second connect would
     * tear down a working link.
     */
    private fun maybeAutoConnect() {
        lifecycleScope.launch {
            val settings = services.settings.settings.first()
            val address = settings.lastDeviceAddress ?: return@launch
            if (!settings.autoConnect) return@launch
            if (services.connectionManager.isConnected) return@launch
            if (!BluetoothPermissions.allGranted(this@MainActivity)) return@launch

            services.connectionManager.connect(
                address = address,
                name = settings.lastDeviceName,
                type = settings.adapterType
            )
        }
    }

    /**
     * Release the adapter when the user actually leaves the app — but never while a
     * trip is being recorded, since that is precisely the case where the app is
     * expected to keep working in the background.
     */
    override fun onDestroy() {
        if (isFinishing && TripLoggingService.activeTripId.value == null) {
            services.connectionManager.disconnect()
        }
        super.onDestroy()
    }
}
