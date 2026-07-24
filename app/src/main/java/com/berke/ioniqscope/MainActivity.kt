package com.berke.ioniqscope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.berke.ioniqscope.service.TripLoggingService
import com.berke.ioniqscope.ui.IoniqScopeRoot
import com.berke.ioniqscope.ui.theme.IoniqScopeTheme

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
