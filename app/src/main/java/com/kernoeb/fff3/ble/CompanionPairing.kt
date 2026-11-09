package com.kernoeb.fff3.ble

import android.app.Activity
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.kernoeb.fff3.protocol.LedFamily
import java.util.regex.Pattern

/** A strip the user picked in the system pairing sheet. */
data class PairedDevice(
    val address: String,
    val name: String,
    val family: LedFamily,
    val associationId: Int?,
)

/**
 * Pairs strips through the Companion Device Manager (CDM). The system shows the device list,
 * so the app needs no scan permission. Location services must be on while the sheet is open.
 *
 * Flow: [start] gives an [IntentSender]; launch it with `StartIntentSenderForResult`;
 * pass the result to [parseResult]; save the device with `LedRepository.saveDevice`.
 */
class CompanionPairing(context: Context) {

    private val appContext = context.applicationContext
    private val manager: CompanionDeviceManager? = appContext.getSystemService(CompanionDeviceManager::class.java)

    val isAvailable: Boolean
        get() = manager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** Asks the system to search. [onSheet] or [onError] is called on the main thread. */
    fun start(onSheet: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val manager = manager ?: return onError("Companion device pairing is not available on this phone.")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(LedFamily.NAME_PATTERN))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        manager.associate(request, appContext.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onSheet(intentSender)

            // The activity result carries the same association, with the scan record we need for the name.
            override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit

            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed.")
        })
    }

    /** Reads the sheet's result. Returns null if the user cancelled. */
    fun parseResult(resultCode: Int, data: Intent?): PairedDevice? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val association = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        val scan = scanResultOf(association, data)
        val address = association?.deviceMacAddress?.toString() ?: scan?.device?.address ?: return null
        val name = scan?.scanRecord?.deviceName ?: association?.displayName?.toString() ?: address
        return PairedDevice(
            address = address.uppercase(),
            name = name.trim(),
            // The sheet only lists names that match NAME_PATTERN, so detection should not fail.
            family = LedFamily.detect(name) ?: LedFamily.DOM,
            associationId = association?.id,
        )
    }

    /** Removes the system association, if there is one. */
    fun forget(associationId: Int) {
        try {
            manager?.disassociate(associationId)
        } catch (e: RuntimeException) {
            Log.w("CompanionPairing", "Cannot remove association $associationId", e)
        }
    }

    private fun scanResultOf(association: AssociationInfo?, data: Intent): ScanResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            association?.associatedDevice?.bleDevice?.let { return it }
        }
        @Suppress("DEPRECATION") // The only way to get the scan record on Android 13.
        return data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
    }
}
