package com.roadsyncplus.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleConnectionManager(
    context: Context,
    private val onEvent: (
        category: String,
        message: String,
        serviceUuid: String?,
        characteristicUuid: String?,
        payloadHex: String?,
        instanceId: Int?,
    ) -> Unit,
    private val onFailure: (
        message: String,
        serviceUuid: String?,
        characteristicUuid: String?,
    ) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _connectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val connectedDevice: StateFlow<DiscoveredDevice?> = _connectedDevice.asStateFlow()
    private val _gattSnapshot = MutableStateFlow(GattSnapshot())
    val gattSnapshot: StateFlow<GattSnapshot> = _gattSnapshot.asStateFlow()
    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()
    private val _mtuRequestState = MutableStateFlow(MtuRequestState())
    val mtuRequestState: StateFlow<MtuRequestState> = _mtuRequestState.asStateFlow()
    private val _enabledNotifications = MutableStateFlow<Set<CharacteristicKey>>(emptySet())
    val enabledNotifications: StateFlow<Set<CharacteristicKey>> =
        _enabledNotifications.asStateFlow()
    private val _characteristicReadResults = MutableStateFlow<List<CharacteristicReadResult>>(emptyList())
    val characteristicReadResults: StateFlow<List<CharacteristicReadResult>> =
        _characteristicReadResults.asStateFlow()
    private val _isReadingDiagnostics = MutableStateFlow(false)
    val isReadingDiagnostics: StateFlow<Boolean> = _isReadingDiagnostics.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var lastDevice: DiscoveredDevice? = null
    private var pendingDescriptorKey: CharacteristicKey? = null
    private var pendingDescriptorEnable: Boolean? = null
    private val readQueue = ArrayDeque<DiagnosticReadTarget>()
    private var pendingReadKey: CharacteristicKey? = null
    private var pendingReadTarget: DiagnosticReadTarget? = null

    val reportDevice: DiscoveredDevice?
        get() = _connectedDevice.value ?: lastDevice

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val state = when (newState) {
                BluetoothProfile.STATE_CONNECTING -> ConnectionState.CONNECTING
                BluetoothProfile.STATE_CONNECTED -> ConnectionState.CONNECTED
                BluetoothProfile.STATE_DISCONNECTING -> ConnectionState.DISCONNECTING
                else -> ConnectionState.DISCONNECTED
            }
            _connectionState.value = state
            recordEvent(
                if (status == BluetoothGatt.GATT_SUCCESS) "Connection" else "Error",
                "GATT state: $state (status $status)",
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Connection error (GATT status $status)")
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (!gatt.discoverServices()) {
                        onError("Android could not start GATT service discovery")
                    }
                } catch (exception: SecurityException) {
                    onError("Service discovery permission denied: ${exception.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pendingReadTarget?.let {
                    recordReadFailure(it, "Disconnected before characteristic read completed")
                }
                pendingReadTarget = null
                while (readQueue.isNotEmpty()) {
                    recordReadFailure(
                        readQueue.removeFirst(),
                        "Disconnected before characteristic read started",
                    )
                }
                _connectedDevice.value = null
                _enabledNotifications.value = emptySet()
                _mtu.value = 23
                pendingDescriptorKey = null
                pendingDescriptorEnable = null
                readQueue.clear()
                pendingReadKey = null
                pendingReadTarget = null
                _isReadingDiagnostics.value = false
                if (this@BleConnectionManager.gatt === gatt) {
                    this@BleConnectionManager.gatt = null
                }
                try {
                    gatt.close()
                } catch (exception: SecurityException) {
                    onError("Bluetooth permission denied while closing GATT: ${exception.message}")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("Service discovery failed (GATT status $status)")
                return
            }
            val discovered = gatt.services.map { service ->
                GattServiceInfo(
                    uuid = service.uuid.toString().uppercase(Locale.ROOT),
                    characteristics = service.characteristics.mapIndexed { instanceId, characteristic ->
                        GattCharacteristicInfo(
                            uuid = characteristic.uuid.toString().uppercase(Locale.ROOT),
                            properties = characteristic.properties,
                            descriptors = characteristic.descriptors.map {
                                it.uuid.toString().uppercase(Locale.ROOT)
                            },
                            instanceId = instanceId,
                        )
                    },
                )
            }
            _gattSnapshot.value = GattSnapshot(discovered, discovered = true)
            recordEvent("GATT", "Discovered ${discovered.size} services")
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val succeeded = status == BluetoothGatt.GATT_SUCCESS
            if (succeeded) {
                _mtu.value = mtu
            } else {
                onError("MTU update failed (GATT status $status)")
            }
            _mtuRequestState.value = _mtuRequestState.value.copy(
                negotiatedMtu = if (succeeded) mtu else _mtu.value,
                callbackStatus = status,
                callbackAtMillis = System.currentTimeMillis(),
            )
            recordEvent("Connection", "MTU callback: mtu=$mtu status=$status")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val key = pendingDescriptorKey
            val enabled = pendingDescriptorEnable
            pendingDescriptorKey = null
            pendingDescriptorEnable = null
            if (key == null || enabled == null) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                _enabledNotifications.value = if (enabled) {
                    _enabledNotifications.value + key
                } else {
                    _enabledNotifications.value - key
                }
                recordEvent(
                    "Subscription",
                    "${if (enabled) "Enabled" else "Disabled"} notification subscription",
                    key.serviceUuid,
                    key.characteristicUuid,
                )
            } else {
                val characteristic = gatt.getService(UUID.fromString(key.serviceUuid))
                    ?.characteristics
                    ?.getOrNull(key.instanceId)
                    ?.takeIf { it.uuid.toString().equals(key.characteristicUuid, ignoreCase = true) }
                if (characteristic != null) {
                    restoreLocalNotification(gatt, characteristic, !enabled)
                }
                onError("CCCD write failed (GATT status $status)")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            recordNotification(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            recordNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(gatt, characteristic, value, status)
        }
    }

    fun connect(device: DiscoveredDevice) {
        if (gatt != null) {
            onError("Disconnect the current device before connecting to another")
            return
        }
        val bluetoothDevice = try {
            adapter?.getRemoteDevice(device.address)
        } catch (exception: IllegalArgumentException) {
            onError("Invalid Bluetooth address: ${device.address}")
            return
        } catch (exception: SecurityException) {
            onError("Bluetooth connect permission denied: ${exception.message}")
            return
        }
        if (bluetoothDevice == null) {
            onError("Bluetooth adapter is unavailable")
            return
        }
        _connectedDevice.value = device
        lastDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        _gattSnapshot.value = GattSnapshot()
        _mtu.value = 23
        _mtuRequestState.value = MtuRequestState()
        _isReadingDiagnostics.value = false
        _characteristicReadResults.value = emptyList()
        readQueue.clear()
        pendingReadKey = null
        pendingReadTarget = null
        recordEvent("Connection", "Connecting to ${device.name ?: "unnamed device"} (${device.address})")
        try {
            val newGatt = bluetoothDevice.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            if (newGatt == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedDevice.value = null
                onError("Android did not create a GATT connection")
            } else {
                gatt = newGatt
            }
        } catch (exception: SecurityException) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedDevice.value = null
            onError("Bluetooth connect permission denied: ${exception.message}")
        }
    }

    fun disconnect() {
        val currentGatt = gatt ?: return
        _connectionState.value = ConnectionState.DISCONNECTING
        recordEvent("Connection", "Disconnect requested")
        try {
            currentGatt.disconnect()
        } catch (exception: SecurityException) {
            onError("Bluetooth connect permission denied while disconnecting: ${exception.message}")
            try {
                currentGatt.close()
            } catch (closeException: SecurityException) {
                onError("Bluetooth permission denied while closing GATT: ${closeException.message}")
            }
            gatt = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedDevice.value = null
        }
    }

    fun setNotifications(
        serviceUuid: String,
        characteristicUuid: String,
        instanceId: Int,
        enabled: Boolean,
    ) {
        if (serviceUuid.equals(HondaProtocolReference.DFU_SERVICE, ignoreCase = true)) {
            onError("Notification subscriptions are disabled for the DFU service")
            return
        }
        val currentGatt = gatt
        val serviceId = parseUuid(serviceUuid) ?: return
        val characteristicId = parseUuid(characteristicUuid) ?: return
        val key = CharacteristicKey(
            serviceUuid.uppercase(Locale.ROOT),
            characteristicUuid.uppercase(Locale.ROOT),
            instanceId,
        )
        if (pendingDescriptorKey != null || pendingReadKey != null ||
            (_mtuRequestState.value.requestAccepted == true &&
                _mtuRequestState.value.callbackStatus == null)
        ) {
            onError("Wait for the current GATT operation to finish")
            return
        }
        val characteristic = currentGatt
            ?.getService(serviceId)
            ?.characteristics
            ?.getOrNull(instanceId)
            ?.takeIf { it.uuid == characteristicId }
        if (currentGatt == null || characteristic == null) {
            onError("Characteristic is no longer available")
            return
        }
        val supportsNotifications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotifications && !supportsIndications) {
            onError("This characteristic does not support Notify or Indicate")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            onError(
                "No standard CCCD descriptor was discovered",
                key.serviceUuid,
                key.characteristicUuid,
            )
            return
        }
        val localEnabled = try {
            currentGatt.setCharacteristicNotification(characteristic, enabled)
        } catch (exception: SecurityException) {
            onError("Bluetooth connect permission denied: ${exception.message}")
            return
        }
        if (!localEnabled) {
            onError("Android could not change the local notification state")
            return
        }
        val descriptorValue = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            supportsNotifications -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        pendingDescriptorKey = key
        pendingDescriptorEnable = enabled
        val queued = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeDescriptor(descriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = descriptorValue
                @Suppress("DEPRECATION")
                currentGatt.writeDescriptor(descriptor)
            }
        } catch (exception: SecurityException) {
            pendingDescriptorKey = null
            pendingDescriptorEnable = null
            restoreLocalNotification(currentGatt, characteristic, !enabled)
            onError("Bluetooth connect permission denied while writing CCCD: ${exception.message}")
            return
        }
        if (!queued) {
            pendingDescriptorKey = null
            pendingDescriptorEnable = null
            restoreLocalNotification(currentGatt, characteristic, !enabled)
            onError(
                "Android could not queue the CCCD descriptor write",
                key.serviceUuid,
                key.characteristicUuid,
            )
        }
    }

    fun readDiagnosticCharacteristics() {
        val currentGatt = gatt
        if (currentGatt == null || _connectionState.value != ConnectionState.CONNECTED) {
            onError("Connect to a device before reading diagnostic characteristics")
            return
        }
        if (!_gattSnapshot.value.discovered) {
            onError("Wait for GATT discovery to finish before reading characteristics")
            return
        }
        if (pendingDescriptorKey != null || pendingReadKey != null || readQueue.isNotEmpty() ||
            (_mtuRequestState.value.requestAccepted == true &&
                _mtuRequestState.value.callbackStatus == null)
        ) {
            onError("A GATT operation is already in progress")
            return
        }
        readQueue.addAll(HondaProtocolReference.diagnosticReadTargets)
        _isReadingDiagnostics.value = true
        processNextRead()
    }

    fun requestMtu() {
        val currentGatt = gatt
        if (currentGatt == null || _connectionState.value != ConnectionState.CONNECTED) {
            onError("Connect to a device before requesting an MTU")
            return
        }
        if (pendingDescriptorKey != null || pendingReadKey != null || readQueue.isNotEmpty() ||
            (_mtuRequestState.value.requestAccepted == true &&
                _mtuRequestState.value.callbackStatus == null)
        ) {
            onError("Wait for the current GATT operation to finish before requesting an MTU")
            return
        }
        val timestamp = System.currentTimeMillis()
        _mtuRequestState.value = MtuRequestState(
            requestedMtu = REQUESTED_MTU,
            negotiatedMtu = _mtu.value,
            requestedAtMillis = timestamp,
        )
        try {
            val accepted = currentGatt.requestMtu(REQUESTED_MTU)
            _mtuRequestState.value = _mtuRequestState.value.copy(requestAccepted = accepted)
            recordEvent(
                "Connection",
                "MTU request $REQUESTED_MTU ${if (accepted) "accepted" else "rejected"}",
            )
            if (!accepted) onError("Android did not accept the MTU request")
        } catch (exception: SecurityException) {
            _mtuRequestState.value = _mtuRequestState.value.copy(requestAccepted = false)
            onError("Bluetooth connect permission denied while requesting MTU: ${exception.message}")
        }
    }

    fun close() {
        gatt?.let { currentGatt ->
            try {
                currentGatt.disconnect()
            } catch (exception: SecurityException) {
                onError("Bluetooth permission denied while disconnecting: ${exception.message}")
            }
            try {
                currentGatt.close()
            } catch (exception: SecurityException) {
                onError("Bluetooth permission denied while closing GATT: ${exception.message}")
            }
        }
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _enabledNotifications.value = emptySet()
        pendingDescriptorKey = null
        pendingDescriptorEnable = null
        readQueue.clear()
        pendingReadKey = null
        pendingReadTarget = null
        _isReadingDiagnostics.value = false
    }

    private fun recordNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val serviceUuid = characteristic.service.uuid.toString().uppercase(Locale.ROOT)
        val characteristicUuid = characteristic.uuid.toString().uppercase(Locale.ROOT)
        val instanceId = characteristic.service.characteristics.indexOfFirst { it === characteristic }
        val payload = value.joinToString(" ") { "%02X".format(it) }
        recordEvent(
            "Notification",
            "Received ${value.size} byte(s)",
            serviceUuid,
            characteristicUuid,
            payload,
            instanceId,
        )
    }

    private fun processNextRead() {
        val currentGatt = gatt ?: run {
            _isReadingDiagnostics.value = false
            readQueue.clear()
            return
        }
        while (readQueue.isNotEmpty()) {
            val target = readQueue.removeFirst()
            val service = currentGatt.getService(UUID.fromString(target.serviceUuid))
            val characteristic = service?.characteristics
                ?.firstOrNull { it.uuid.toString().equals(target.characteristicUuid, ignoreCase = true) }
            if (characteristic == null) {
                recordReadFailure(target, "Characteristic not present on connected device")
                continue
            }
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                recordReadFailure(target, "Characteristic does not support Read")
                continue
            }

            val instanceId = service.characteristics.indexOfFirst { it === characteristic }
            pendingReadKey = CharacteristicKey(target.serviceUuid, target.characteristicUuid, instanceId)
            pendingReadTarget = target
            val queued = try {
                @Suppress("DEPRECATION")
                currentGatt.readCharacteristic(characteristic)
            } catch (exception: SecurityException) {
                pendingReadKey = null
                pendingReadTarget = null
                recordReadFailure(target, "Bluetooth read permission denied: ${exception.message}")
                continue
            }
            if (queued) return

            pendingReadKey = null
            pendingReadTarget = null
            recordReadFailure(target, "Android could not queue the characteristic read")
        }
        _isReadingDiagnostics.value = false
    }

    private fun completeRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (callbackGatt !== gatt) return
        val target = pendingReadTarget ?: return
        val key = pendingReadKey ?: return
        val serviceUuid = characteristic.service.uuid.toString().uppercase(Locale.ROOT)
        val characteristicUuid = characteristic.uuid.toString().uppercase(Locale.ROOT)
        val instanceId = characteristic.service.characteristics.indexOfFirst { it === characteristic }
        if (key.serviceUuid != serviceUuid ||
            key.characteristicUuid != characteristicUuid ||
            key.instanceId != instanceId
        ) {
            return
        }
        pendingReadKey = null
        pendingReadTarget = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val bytes = value.map { it.toInt() and 0xFF }
            val result = CharacteristicReadResult(
                timestampMillis = System.currentTimeMillis(),
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = bytes,
                statusCode = status,
            )
            _characteristicReadResults.value = _characteristicReadResults.value + result
            recordEvent(
                "Read",
                "${target.label}: ${decodeCharacteristicValue(serviceUuid, characteristicUuid, bytes)}",
                serviceUuid,
                characteristicUuid,
                formatHex(bytes),
                instanceId,
            )
        } else {
            recordReadFailure(target, "Characteristic read failed (GATT status $status)", status)
        }
        processNextRead()
    }

    private fun recordReadFailure(
        target: DiagnosticReadTarget,
        message: String,
        statusCode: Int? = null,
    ) {
        _characteristicReadResults.value = _characteristicReadResults.value + CharacteristicReadResult(
            timestampMillis = System.currentTimeMillis(),
            serviceUuid = target.serviceUuid,
            characteristicUuid = target.characteristicUuid,
            value = null,
            statusCode = statusCode,
            error = message,
        )
        onError(message, target.serviceUuid, target.characteristicUuid)
    }

    private fun recordEvent(
        category: String,
        message: String,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        payloadHex: String? = null,
        instanceId: Int? = null,
    ) {
        onEvent(category, message, serviceUuid, characteristicUuid, payloadHex, instanceId)
    }

    private fun restoreLocalNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, enabled)
        } catch (exception: SecurityException) {
            onError("Bluetooth permission denied while restoring notification state: ${exception.message}")
        }
    }

    private fun parseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            onError("Invalid UUID: $value")
            null
        }

    private fun onError(
        message: String,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
    ) {
        onFailure(message, serviceUuid, characteristicUuid)
    }

    companion object {
        private const val REQUESTED_MTU = 247
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
