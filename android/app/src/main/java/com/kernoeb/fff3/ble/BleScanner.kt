package com.kernoeb.fff3.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.kernoeb.fff3.protocol.LedFamily
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A supported strip seen during a scan. */
data class ScannedDevice(
    val address: String,
    val name: String,
    val family: LedFamily,
    val rssi: Int,
)

class ScanFailedException(val errorCode: Int) : Exception("Bluetooth scan failed with code $errorCode")

/**
 * Finds strips to add, only while the Add screen is open.
 * Needs the `BLUETOOTH_SCAN` permission (declared `neverForLocation`).
 */
class BleScanner(private val environment: BluetoothEnvironment) {

    /**
     * Supported strips found so far, strongest signal first. Starts with an empty list.
     * Scans until collection stops. Fails with [SecurityException] or [ScanFailedException].
     */
    @SuppressLint("MissingPermission") // SecurityException is caught and sent to the collector.
    fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        val scanner = environment.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }

            private fun handle(result: ScanResult) {
                val name = result.scanRecord?.deviceName?.trim() ?: return
                val family = LedFamily.detect(name) ?: return
                val address = result.device.address.uppercase()
                found[address] = ScannedDevice(address, name, family, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        trySend(emptyList())
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission revoked while scanning: the scan is already stopped.
            } catch (_: IllegalStateException) {
                // Bluetooth turned off while scanning.
            }
        }
    }
}
