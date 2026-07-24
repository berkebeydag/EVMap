package com.berke.ioniqscope.ui.screens.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions BLE scanning needs, which changed shape in API 31.
 *
 * Below 31 Android insists on location permission for BLE scanning even though the
 * app has no interest in where the phone is; from 31 up, BLUETOOTH_SCAN with
 * `neverForLocation` says so explicitly and location is not required.
 */
object BluetoothPermissions {

    val required: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** True on API < 31, where the location-permission caveat needs explaining. */
    val needsLocationRationale: Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun allGranted(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
