package com.roadsyncplus.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
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
    private val durationTicker = object : Runnable {
        override fun run() {
            val startedAt = connectionManager.connectionStartedAtMillis.value
            val disconnectedAt = connectionManager.disconnectedAtMillis.value
            _connectionDurationMillis.value = when {
                startedAt == null -> 0L
                connectionManager.connectionState.value == ConnectionState.CONNECTED ||
                    connectionManager.connectionState.value == ConnectionState.DISCONNECTING ->
                    (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
                disconnectedAt != null -> (disconnectedAt - startedAt).coerceAtLeast(0)
                else -> 0L
            }
            if (!closed) mainHandler.postDelayed(this, 1_000L)
        }
    }
    private var closed = false
    private val lastLoggedRssi = mutableMapOf<String, Int>()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    private val _adapterState = MutableStateFlow(adapterStateName(adapter?.state))
    override val adapterState: StateFlow<String> = _adapterState.asStateFlow()
    private val _bondingState = MutableStateFlow(BondingState.NONE)
    override val bondingState: StateFlow<BondingState> = _bondingState.asStateFlow()
    private val _connectionDurationMillis = MutableStateFlow(0L)
    override val connectionDurationMillis: StateFlow<Long> = _connectionDurationMillis.asStateFlow()

    private val connectionManager = BleConnectionManager(
        context = appContext,
        onEvent = { category, message, serviceUuid, characteristicUuid, payloadHex, instanceId ->
            appendEvent(category, message, serviceUuid, characteristicUuid, payloadHex, instanceId)
        },
        onFailure = { message, serviceUuid, characteristicUuid ->
            reportFailure(message, serviceUuid, characteristicUuid)
        },
    )

    override val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    override val connectedDevice: StateFlow<DiscoveredDevice?> = connectionManager.connectedDevice
    override val gattSnapshot: StateFlow<GattSnapshot> = connectionManager.gattSnapshot
    override val mtu: StateFlow<Int> = connectionManager.mtu
    override val mtuRequestState: StateFlow<MtuRequestState> = connectionManager.mtuRequestState
    override val serviceDiscoveryState: StateFlow<ServiceDiscoveryState> =
        connectionManager.serviceDiscoveryState
    override val operationState: StateFlow<GattOperationState> = connectionManager.operationState
    override val connectionStartedAtMillis: StateFlow<Long?> =
        connectionManager.connectionStartedAtMillis
    override val disconnectedAtMillis: StateFlow<Long?> = connectionManager.disconnectedAtMillis
    override val disconnectStatus: StateFlow<Int?> = connectionManager.disconnectStatus
    override val manufacturerReadCompleted: StateFlow<Boolean> =
        connectionManager.manufacturerReadCompleted
    override val enabledNotifications: StateFlow<Set<CharacteristicKey>> =
        connectionManager.enabledNotifications
    override val characteristicReadResults: StateFlow<List<CharacteristicReadResult>> =
        connectionManager.characteristicReadResults
    override val isReadingDiagnostics: StateFlow<Boolean> = connectionManager.isReadingDiagnostics

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
            if (lastLoggedRssi[item.address] != item.rssi) {
                val isNew = item.address !in lastLoggedRssi
                lastLoggedRssi[item.address] = item.rssi
                appendEvent(
                    if (isNew) "Discovery" else "RSSI",
                    "${if (isNew) "Device discovered" else "RSSI updated"}: ${item.name ?: "unnamed"} " +
                        "(${item.address}), RSSI ${item.rssi} dBm",
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            mainHandler.removeCallbacks(scanTimeout)
            reportFailure("BLE scan failed (code $errorCode)")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val next = adapterStateName(
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR),
                    )
                    if (_adapterState.value != next) {
                        _adapterState.value = next
                        appendEvent("Bluetooth", "Adapter state changed: $next")
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = getBluetoothDevice(intent) ?: return
                    val expectedAddress = connectionManager.reportDevice?.address
                    if (expectedAddress != null && !expectedAddress.equals(device.address, true)) return
                    val bondState = when (
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    ) {
                        BluetoothDevice.BOND_BONDING -> BondingState.BONDING
                        BluetoothDevice.BOND_BONDED -> BondingState.BONDED
                        else -> BondingState.NONE
                    }
                    val previous = _bondingState.value
                    _bondingState.value = bondState
                    appendEvent("Bonding", "Bonding state changed: $previous -> $bondState")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            appContext,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        mainHandler.post(durationTicker)
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            reportFailure("Bluetooth is unavailable or turned off")
            return
        }
        try {
            _devices.value = emptyList()
            lastLoggedRssi.clear()
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
        try {
            val remoteDevice = adapter?.getRemoteDevice(device.address)
            _bondingState.value = when (remoteDevice?.bondState) {
                BluetoothDevice.BOND_BONDING -> BondingState.BONDING
                BluetoothDevice.BOND_BONDED -> BondingState.BONDED
                else -> BondingState.NONE
            }
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth connect permission denied while checking bond state: ${exception.message}")
        }
        connectionManager.connect(device)
    }

    override fun disconnect() {
        connectionManager.disconnect()
    }

    override fun setNotifications(
        serviceUuid: String,
        characteristicUuid: String,
        instanceId: Int,
        enabled: Boolean,
    ) {
        connectionManager.setNotifications(serviceUuid, characteristicUuid, instanceId, enabled)
    }

    override fun discoverServices() {
        connectionManager.discoverServices()
    }

    override fun readCharacteristic(target: DiagnosticReadTarget) {
        connectionManager.readCharacteristic(target)
    }

    override fun requestMtu() {
        connectionManager.requestMtu()
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val device = connectionManager.reportDevice
        val startedAt = connectionManager.connectionStartedAtMillis.value
        val disconnectedAt = connectionManager.disconnectedAtMillis.value
        val durationMillis = when {
            startedAt == null -> null
            connectionManager.connectionState.value == ConnectionState.CONNECTED ->
                (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            disconnectedAt != null -> (disconnectedAt - startedAt).coerceAtLeast(0)
            else -> null
        }
        val mtuState = connectionManager.mtuRequestState.value
        val snapshot = connectionManager.gattSnapshot.value
        val appVersion = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
        } catch (exception: PackageManager.NameNotFoundException) {
            "unavailable (${exception.message})"
        }
        return buildString {
            appendLine("RoadSync Plus BLE Diagnostic Report")
            appendLine("Generated: ${dateFormat.format(Date())}")
            appendLine("App version: $appVersion")
            appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Bluetooth adapter state: ${_adapterState.value}")
            appendLine()
            appendLine("Device information")
            appendLine("  Name: ${device?.name ?: "N/A"}")
            appendLine("  Address: ${device?.address ?: "N/A"}")
            appendLine("  Last observed RSSI: ${device?.rssi?.let { "$it dBm" } ?: "N/A"}")
            appendLine("  Bonding state: ${_bondingState.value}")
            appendLine("  BLE connection state: ${connectionManager.connectionState.value}")
            appendLine()
            appendLine("Connection timeline")
            appendLine("  Connected: ${startedAt?.let { dateFormat.format(Date(it)) } ?: "N/A"}")
            appendLine("  Disconnected: ${disconnectedAt?.let { dateFormat.format(Date(it)) } ?: "not disconnected"}")
            appendLine("  Disconnect status: ${connectionManager.disconnectStatus.value ?: "N/A"}")
            appendLine("  Total connection duration: ${durationMillis?.let(::formatDuration) ?: "N/A"}")
            appendLine("  Service discovery state: ${connectionManager.serviceDiscoveryState.value}")
            appendLine("  MTU requested: ${mtuState.requestedMtu ?: "none"}")
            appendLine("  MTU request accepted: ${mtuState.requestAccepted ?: "N/A"}")
            appendLine("  MTU callback status: ${mtuState.callbackStatus ?: "not received"}")
            appendLine("  MTU negotiated: ${mtuState.negotiatedMtu}")
            appendLine("  Maximum ATT payload: ${(mtuState.negotiatedMtu - 3).coerceAtLeast(0)} bytes")
            appendLine("  MTU request time: ${mtuState.requestedAtMillis?.let { dateFormat.format(Date(it)) } ?: "N/A"}")
            appendLine("  MTU callback time: ${mtuState.callbackAtMillis?.let { dateFormat.format(Date(it)) } ?: "N/A"}")
            appendLine()
            appendLine("GATT information (${snapshot.services.size} total services)")
            appendLine("  Standard services: ${snapshot.services.count(::isStandardService)}")
            appendLine("  Custom services: ${snapshot.services.count { !isStandardService(it) }}")
            snapshot.services.forEach { service ->
                appendLine("  Service ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    appendLine(
                        "    Characteristic ${characteristic.uuid}; instance=${characteristic.instanceId}; " +
                            "properties=${propertyNames(characteristic.properties)}",
                    )
                    characteristic.descriptors.forEach { appendLine("      Descriptor $it") }
                }
            }
            val operation = connectionManager.operationState.value
            appendLine()
            appendLine("GATT operation queue")
            appendLine("  Current operation: ${operation.currentOperation ?: "idle"}")
            appendLine("  Pending queue size: ${operation.pendingQueueSize}")
            appendLine("  Last successful operation: ${operation.lastSuccessfulOperation ?: "N/A"}")
            appendLine("  Last failed operation: ${operation.lastFailedOperation ?: "N/A"}")
            appendLine("  Last GATT error code: ${operation.lastErrorCode ?: "N/A"}")
            appendLine()
            appendLine("Characteristic read results")
            connectionManager.characteristicReadResults.value.forEach { result ->
                val label = (HondaProtocolReference.vehicleInformationReadTargets +
                    HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET)
                    .firstOrNull {
                        it.serviceUuid.equals(result.serviceUuid, true) &&
                            it.characteristicUuid.equals(result.characteristicUuid, true)
                    }?.label ?: "Characteristic"
                appendLine("  $label: ${result.serviceUuid}/${result.characteristicUuid}")
                appendLine("    Properties: ${propertyNames(result.properties)}")
                appendLine("    Started: ${dateFormat.format(Date(result.startedAtMillis))}")
                appendLine("    Completed: ${dateFormat.format(Date(result.completedAtMillis))}")
                appendLine("    GATT status: ${result.statusCode ?: "N/A"}")
                result.value?.let { value ->
                    appendLine("    Raw bytes (hex): ${formatHex(value)}")
                    utf8Interpretation(value)?.let { appendLine("    UTF-8: $it") }
                    asciiInterpretation(value)?.let { appendLine("    ASCII: $it") }
                }
                result.error?.let { appendLine("    Error: $it") }
            }
            appendLine()
            appendLine("Timestamped event log")
            _events.value.forEach { event ->
                append(dateFormat.format(Date(event.timestampMillis)))
                append(" [${event.category}] ${event.message}")
                event.serviceUuid?.let { append(" service=$it") }
                event.characteristicUuid?.let { append(" characteristic=$it") }
                event.instanceId?.let { append(" instance=$it") }
                event.payloadHex?.let { append(" payload=$it") }
                appendLine()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacks(durationTicker)
        stopScan()
        connectionManager.close()
        try {
            appContext.unregisterReceiver(bluetoothReceiver)
        } catch (exception: IllegalArgumentException) {
            reportFailure("Bluetooth state receiver was not registered: ${exception.message}")
        }
    }

    private fun appendEvent(
        category: String,
        message: String,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        payloadHex: String? = null,
        instanceId: Int? = null,
    ) {
        _events.value = _events.value + DiagnosticEvent(
            timestampMillis = System.currentTimeMillis(),
            category = category,
            message = message,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payloadHex = payloadHex,
            instanceId = instanceId,
        )
    }

    private fun getBluetoothDevice(intent: Intent): BluetoothDevice? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth permission denied while reading bond event: ${exception.message}")
            null
        }

    private fun adapterStateName(state: Int?): String = when (state) {
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "UNKNOWN"
    }

    private fun isStandardService(service: GattServiceInfo): Boolean =
        service.uuid.startsWith("0000") && service.uuid.endsWith("-0000-1000-8000-00805F9B34FB")

    private fun utf8Interpretation(value: List<Int>): String? =
        try {
            val bytes = value.map(Int::toByte).toByteArray()
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .trimEnd('\u0000')
            decoded.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
        } catch (_: CharacterCodingException) {
            null
        }

    private fun asciiInterpretation(value: List<Int>): String? =
        value.takeIf { bytes -> bytes.isNotEmpty() && bytes.all { it in 0x20..0x7E } }
            ?.map(Int::toChar)
            ?.joinToString("")

    private fun formatDuration(millis: Long): String =
        "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')} s"

    companion object {
        private const val SCAN_DURATION_MILLIS = 15_000L
    }
}
