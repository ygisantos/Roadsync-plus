package com.roadsyncplus.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidBleRepository(context: Context) : BleRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { stopScan() }

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val connectionManager = BleConnectionManager(
        context = appContext,
        onEvent = { category, message, serviceUuid, characteristicUuid, payloadHex ->
            appendEvent(category, message, serviceUuid, characteristicUuid, payloadHex)
        },
        onFailure = { message, serviceUuid, characteristicUuid ->
            reportFailure(message, serviceUuid, characteristicUuid)
        },
    )

    override val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    override val connectedDevice: StateFlow<DiscoveredDevice?> = connectionManager.connectedDevice
    override val gattSnapshot: StateFlow<GattSnapshot> = connectionManager.gattSnapshot
    override val mtu: StateFlow<Int> = connectionManager.mtu
    override val enabledNotifications: StateFlow<Set<CharacteristicKey>> =
        connectionManager.enabledNotifications

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: try {
                device.name
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth connect permission denied while reading device name: ${exception.message}")
                null
            }
            val item = DiscoveredDevice(name, device.address, result.rssi, System.currentTimeMillis())
            _devices.value = (_devices.value.filterNot { it.address == item.address } + item)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            mainHandler.removeCallbacks(scanTimeout)
            reportFailure("BLE scan failed (code $errorCode)")
        }
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            reportFailure("Bluetooth is unavailable or turned off")
            return
        }
        try {
            _devices.value = emptyList()
            scanner.startScan(scanCallback)
            _isScanning.value = true
            appendEvent("Scan", "BLE scan started")
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, SCAN_DURATION_MILLIS)
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth scan permission denied: ${exception.message}")
        } catch (exception: IllegalStateException) {
            reportFailure("Unable to start BLE scan: ${exception.message}")
        }
    }

    override fun stopScan() {
        if (!_isScanning.value) return
        mainHandler.removeCallbacks(scanTimeout)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            appendEvent("Scan", "BLE scan stopped")
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth scan permission denied while stopping: ${exception.message}")
        } finally {
            _isScanning.value = false
        }
    }

    override fun connect(device: DiscoveredDevice) {
        stopScan()
        connectionManager.connect(device)
    }

    override fun disconnect() {
        connectionManager.disconnect()
    }

    override fun setNotifications(
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
    ) {
        connectionManager.setNotifications(serviceUuid, characteristicUuid, enabled)
    }

    override fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun reportFailure(message: String) {
        reportFailure(message, null, null)
    }

    private fun reportFailure(
        message: String,
        serviceUuid: String?,
        characteristicUuid: String?,
    ) {
        _statusMessage.value = message
        appendEvent("Error", message, serviceUuid, characteristicUuid)
    }

    override fun createDiagnosticReport(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val device = connectionManager.reportDevice
        return buildString {
            appendLine("RoadSync Plus BLE Diagnostic Report")
            appendLine("Generated: ${dateFormat.format(Date())}")
            appendLine("Device name: ${device?.name ?: "N/A"}")
            appendLine("Device address: ${device?.address ?: "N/A"}")
            appendLine("Last scan RSSI: ${device?.rssi?.let { "$it dBm" } ?: "N/A"}")
            appendLine(
                "Last observed: ${
                    device?.lastSeenMillis?.let { dateFormat.format(Date(it)) } ?: "N/A"
                }",
            )
            appendLine("Connection state: ${connectionManager.connectionState.value}")
            appendLine("MTU: ${connectionManager.mtu.value}")
            appendLine(
                "GATT discovery: ${
                    if (connectionManager.gattSnapshot.value.discovered) "available (last successful discovery)"
                    else "not completed"
                }",
            )
            appendLine()
            appendLine("GATT services:")
            connectionManager.gattSnapshot.value.services.forEach { service ->
                appendLine("  Service ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    appendLine(
                        "    Characteristic ${characteristic.uuid}; properties=${propertyNames(characteristic.properties)}",
                    )
                    characteristic.descriptors.forEach { descriptor ->
                        appendLine("      Descriptor $descriptor")
                    }
                }
            }
            appendLine()
            appendLine("Diagnostic events:")
            _events.value.forEach { event ->
                append(dateFormat.format(Date(event.timestampMillis)))
                append(" [${event.category}] ${event.message}")
                event.serviceUuid?.let { append(" service=$it") }
                event.characteristicUuid?.let { append(" characteristic=$it") }
                event.payloadHex?.let { append(" payload=$it") }
                appendLine()
            }
        }
    }

    fun close() {
        stopScan()
        connectionManager.close()
    }

    private fun appendEvent(
        category: String,
        message: String,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        payloadHex: String? = null,
    ) {
        _events.value = _events.value + DiagnosticEvent(
            System.currentTimeMillis(),
            category,
            message,
            serviceUuid,
            characteristicUuid,
            payloadHex,
        )
    }

    companion object {
        private const val SCAN_DURATION_MILLIS = 15_000L
    }
}
