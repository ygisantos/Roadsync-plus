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
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _connectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val connectedDevice: StateFlow<DiscoveredDevice?> = _connectedDevice.asStateFlow()
    private val _gattSnapshot = MutableStateFlow(GattSnapshot())
    val gattSnapshot: StateFlow<GattSnapshot> = _gattSnapshot.asStateFlow()
    private val _serviceDiscoveryState = MutableStateFlow(ServiceDiscoveryState.NOT_STARTED)
    val serviceDiscoveryState: StateFlow<ServiceDiscoveryState> = _serviceDiscoveryState.asStateFlow()
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
    private val _operationState = MutableStateFlow(GattOperationState())
    val operationState: StateFlow<GattOperationState> = _operationState.asStateFlow()
    private val _connectionStartedAtMillis = MutableStateFlow<Long?>(null)
    val connectionStartedAtMillis: StateFlow<Long?> = _connectionStartedAtMillis.asStateFlow()
    private val _disconnectedAtMillis = MutableStateFlow<Long?>(null)
    val disconnectedAtMillis: StateFlow<Long?> = _disconnectedAtMillis.asStateFlow()
    private val _disconnectStatus = MutableStateFlow<Int?>(null)
    val disconnectStatus: StateFlow<Int?> = _disconnectStatus.asStateFlow()
    private val _manufacturerReadCompleted = MutableStateFlow(false)
    val manufacturerReadCompleted: StateFlow<Boolean> = _manufacturerReadCompleted.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var lastDevice: DiscoveredDevice? = null
    private var connectionStartMillis: Long? = null
    private val operationQueue = ArrayDeque<GattOperation>()
    private var activeOperation: GattOperation? = null
    private val operationTimeout = Runnable { timeoutActiveOperation() }

    val reportDevice: DiscoveredDevice?
        get() = _connectedDevice.value ?: lastDevice

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (callbackGatt !== gatt) return@post
                val state = when (newState) {
                    BluetoothProfile.STATE_CONNECTING -> ConnectionState.CONNECTING
                    BluetoothProfile.STATE_CONNECTED -> ConnectionState.CONNECTED
                    BluetoothProfile.STATE_DISCONNECTING -> ConnectionState.DISCONNECTING
                    else -> ConnectionState.DISCONNECTED
                }
                _connectionState.value = state
                recordEvent(
                    if (status == BluetoothGatt.GATT_SUCCESS) "Connection" else "Error",
                    "GATT state: $state; callback status=$status",
                )
                if (state == ConnectionState.DISCONNECTED &&
                    connectionStartMillis == null &&
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    recordEvent("Connection", "Connection failed; GATT status=$status")
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordGattFailure("Connection state callback", status)
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectionStartMillis = System.currentTimeMillis()
                    _connectionStartedAtMillis.value = connectionStartMillis
                    _disconnectedAtMillis.value = null
                    _disconnectStatus.value = null
                    recordEvent("Connection", "GATT connection established")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _disconnectedAtMillis.value = System.currentTimeMillis()
                    _disconnectStatus.value = status
                    val started = connectionStartMillis
                    val duration = started?.let { (_disconnectedAtMillis.value!! - it).coerceAtLeast(0) }
                    recordEvent(
                        "Disconnection",
                        "Disconnected; status=$status; duration=${duration?.let(::formatDuration) ?: "N/A"}",
                    )
                    clearOperations("Connection lost before queued GATT operations completed")
                    _connectedDevice.value = null
                    _enabledNotifications.value = emptySet()
                    _isReadingDiagnostics.value = false
                    connectionStartMillis = null
                    try {
                        callbackGatt.close()
                    } catch (exception: SecurityException) {
                        reportFailure("Bluetooth permission denied while closing GATT: ${exception.message}")
                    }
                    if (gatt === callbackGatt) gatt = null
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        recordGattFailure("Disconnection callback", status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (!isCurrentOperation(callbackGatt, OperationType.DISCOVERY)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _serviceDiscoveryState.value = ServiceDiscoveryState.FAILED
                    finishOperation(success = false, status = status, message = "Service discovery failed")
                    return@post
                }
                val discovered = callbackGatt.services.map { service ->
                    GattServiceInfo(
                        uuid = service.uuid.toString().uppercase(Locale.ROOT),
                        characteristics = service.characteristics.mapIndexed { index, characteristic ->
                            GattCharacteristicInfo(
                                uuid = characteristic.uuid.toString().uppercase(Locale.ROOT),
                                properties = characteristic.properties,
                                descriptors = characteristic.descriptors.map {
                                    it.uuid.toString().uppercase(Locale.ROOT)
                                },
                                instanceId = index,
                            )
                        },
                    )
                }
                _gattSnapshot.value = GattSnapshot(discovered, discovered = true)
                _serviceDiscoveryState.value = ServiceDiscoveryState.COMPLETE
                val standard = discovered.count { it.uuid.startsWith("0000") }
                recordEvent(
                    "GATT",
                    "Service discovery completed: ${discovered.size} total services, $standard standard, ${discovered.size - standard} custom",
                )
                finishOperation(success = true, status = status, message = "Service discovery")
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (!isCurrentOperation(callbackGatt, OperationType.MTU)) return@post
                val succeeded = status == BluetoothGatt.GATT_SUCCESS
                if (succeeded) _mtu.value = mtu
                _mtuRequestState.value = _mtuRequestState.value.copy(
                    negotiatedMtu = if (succeeded) mtu else _mtu.value,
                    callbackStatus = status,
                    callbackAtMillis = System.currentTimeMillis(),
                )
                recordEvent("Connection", "MTU callback received: mtu=$mtu status=$status")
                finishOperation(
                    success = succeeded,
                    status = status,
                    message = "MTU negotiation (requested ${REQUESTED_MTU}, negotiated ${if (succeeded) mtu else _mtu.value})",
                )
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            mainHandler.post {
                val operation = activeOperation
                if (callbackGatt !== gatt || operation?.type != OperationType.DESCRIPTOR_WRITE) return@post
                val key = operation.key ?: return@post
                val enabled = operation.enabled ?: return@post
                if (!descriptor.characteristic.uuid.toString().equals(key.characteristicUuid, true)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _enabledNotifications.value = if (enabled) {
                        _enabledNotifications.value + key
                    } else {
                        _enabledNotifications.value - key
                    }
                    recordEvent(
                        "Subscription",
                        "Notification subscription ${if (enabled) "completed" else "disabled"}",
                        key.serviceUuid,
                        key.characteristicUuid,
                    )
                } else {
                    val characteristic = findCharacteristic(callbackGatt, key)
                    if (characteristic != null) restoreLocalNotification(callbackGatt, characteristic, !enabled)
                }
                finishOperation(
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status,
                    message = "CCCD descriptor write",
                    key = key,
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeRead(callbackGatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(callbackGatt, characteristic, value, status)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            recordNotification(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            recordNotification(characteristic, value)
        }
    }

    fun connect(device: DiscoveredDevice) {
        mainHandler.post {
            releaseGatt()
            val bluetoothDevice = try {
                adapter?.getRemoteDevice(device.address)
            } catch (exception: IllegalArgumentException) {
                reportFailure("Invalid Bluetooth address: ${device.address}")
                return@post
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth connect permission denied: ${exception.message}")
                return@post
            }
            if (bluetoothDevice == null) {
                reportFailure("Bluetooth adapter is unavailable")
                return@post
            }
            lastDevice = device
            _connectedDevice.value = device
            _connectionState.value = ConnectionState.CONNECTING
            _gattSnapshot.value = GattSnapshot()
            _serviceDiscoveryState.value = ServiceDiscoveryState.NOT_STARTED
            _mtu.value = 23
            _mtuRequestState.value = MtuRequestState()
            _characteristicReadResults.value = emptyList()
            _manufacturerReadCompleted.value = false
            _enabledNotifications.value = emptySet()
            _connectionStartedAtMillis.value = null
            _disconnectedAtMillis.value = null
            _disconnectStatus.value = null
            recordEvent("Connection", "Connection requested for ${device.name ?: "unnamed device"} (${device.address})")
            try {
                gatt = bluetoothDevice.connectGatt(
                    appContext,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                )
                if (gatt == null) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDevice.value = null
                    reportFailure("Android did not create a GATT connection")
                }
            } catch (exception: SecurityException) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedDevice.value = null
                reportFailure("Bluetooth connect permission denied: ${exception.message}")
            }
        }
    }

    fun disconnect() {
        mainHandler.post {
            val currentGatt = gatt ?: return@post
            clearOperations("Operations cancelled by disconnect request")
            _connectionState.value = ConnectionState.DISCONNECTING
            recordEvent("Connection", "Disconnect requested")
            try {
                currentGatt.disconnect()
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth connect permission denied while disconnecting: ${exception.message}")
                releaseGatt()
            }
        }
    }

    fun discoverServices() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            reportFailure("Connect to a device before discovering services")
            return
        }
        _serviceDiscoveryState.value = ServiceDiscoveryState.DISCOVERING
        enqueue(
            GattOperation(
                type = OperationType.DISCOVERY,
                label = "Discover services",
                start = { currentGatt ->
                    recordEvent("GATT", "Service discovery started")
                    currentGatt.discoverServices()
                },
            ),
        )
    }

    fun requestMtu() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            reportFailure("Connect to a device before requesting an MTU")
            return
        }
        if (_serviceDiscoveryState.value != ServiceDiscoveryState.COMPLETE) {
            reportFailure("Complete service discovery before requesting an MTU")
            return
        }
        val now = System.currentTimeMillis()
        _mtuRequestState.value = MtuRequestState(
            requestedMtu = REQUESTED_MTU,
            negotiatedMtu = _mtu.value,
            requestedAtMillis = now,
        )
        enqueue(
            GattOperation(
                type = OperationType.MTU,
                label = "Request MTU $REQUESTED_MTU",
                start = { currentGatt ->
                    recordEvent("Connection", "MTU request started: $REQUESTED_MTU")
                    val accepted = currentGatt.requestMtu(REQUESTED_MTU)
                    _mtuRequestState.value = _mtuRequestState.value.copy(requestAccepted = accepted)
                    accepted
                },
            ),
        )
    }

    fun readCharacteristic(target: DiagnosticReadTarget) {
        val allowedTargets = HondaProtocolReference.vehicleInformationReadTargets +
            HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET
        if (target !in allowedTargets) {
            reportFailure("This characteristic is not enabled for read-only diagnostics")
            return
        }
        if (target.serviceUuid == HondaProtocolReference.VEHICLE_INFO_SERVICE &&
            !_manufacturerReadCompleted.value
        ) {
            reportFailure("Complete the Manufacturer Name read test before testing Vehicle Information")
            return
        }
        if (target == HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET &&
            _mtuRequestState.value.callbackStatus != BluetoothGatt.GATT_SUCCESS
        ) {
            reportFailure("Complete successful MTU negotiation before the Manufacturer Name read test")
            return
        }
        if (activeOperation != null || operationQueue.isNotEmpty()) {
            reportFailure("Wait for the GATT operation queue to become idle before starting a read")
            return
        }
        if (_connectionState.value != ConnectionState.CONNECTED || !_gattSnapshot.value.discovered) {
            reportFailure("Connect and discover services before reading a characteristic")
            return
        }
        val serviceUuid = parseUuid(target.serviceUuid) ?: return
        val characteristicUuid = parseUuid(target.characteristicUuid) ?: return
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(serviceUuid)?.characteristics
            ?.firstOrNull { it.uuid == characteristicUuid }
        if (characteristic == null) {
            recordReadFailure(
                target,
                0,
                System.currentTimeMillis(),
                "Characteristic is not present on the connected device",
                null,
            )
            return
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            recordReadFailure(
                target,
                characteristic.properties,
                System.currentTimeMillis(),
                "Characteristic does not support READ",
                null,
            )
            return
        }
        val instanceId = characteristic.service.characteristics.indexOfFirst { it === characteristic }
        val key = CharacteristicKey(target.serviceUuid, target.characteristicUuid, instanceId)
        val startedAt = System.currentTimeMillis()
        val operation = GattOperation(
            type = OperationType.READ,
            label = "Read ${target.label}",
            serviceUuid = target.serviceUuid,
            characteristicUuid = target.characteristicUuid,
            key = key,
            readTarget = target,
            readStartedAtMillis = startedAt,
            properties = characteristic.properties,
            start = { current ->
                recordEvent(
                    "Read",
                    "Characteristic read started; properties=${propertyNames(characteristic.properties)}",
                    key.serviceUuid,
                    key.characteristicUuid,
                    instanceId = key.instanceId,
                )
                @Suppress("DEPRECATION")
                current.readCharacteristic(characteristic)
            },
        )
        enqueue(operation)
    }

    fun setNotifications(
        serviceUuid: String,
        characteristicUuid: String,
        instanceId: Int,
        enabled: Boolean,
    ) {
        if (serviceUuid.equals(HondaProtocolReference.DFU_SERVICE, ignoreCase = true)) {
            reportFailure("Notification subscriptions are disabled for the DFU service")
            return
        }
        val serviceId = parseUuid(serviceUuid) ?: return
        val characteristicId = parseUuid(characteristicUuid) ?: return
        val currentGatt = gatt
        val key = CharacteristicKey(
            serviceUuid.uppercase(Locale.ROOT),
            characteristicUuid.uppercase(Locale.ROOT),
            instanceId,
        )
        val characteristic = currentGatt?.getService(serviceId)?.characteristics
            ?.getOrNull(instanceId)?.takeIf { it.uuid == characteristicId }
        if (_connectionState.value != ConnectionState.CONNECTED || characteristic == null) {
            reportFailure("Characteristic is no longer available", key.serviceUuid, key.characteristicUuid)
            return
        }
        val supportsNotifications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotifications && !supportsIndications) {
            reportFailure("This characteristic does not support Notify or Indicate", key.serviceUuid, key.characteristicUuid)
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            reportFailure("No standard CCCD descriptor was discovered", key.serviceUuid, key.characteristicUuid)
            return
        }
        val value = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            supportsNotifications -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        enqueue(
            GattOperation(
                type = OperationType.DESCRIPTOR_WRITE,
                label = "${if (enabled) "Subscribe" else "Unsubscribe"} ${key.characteristicUuid}",
                serviceUuid = key.serviceUuid,
                characteristicUuid = key.characteristicUuid,
                key = key,
                enabled = enabled,
                start = { activeGatt ->
                    recordEvent(
                        "Subscription",
                        "Notification subscription started",
                        key.serviceUuid,
                        key.characteristicUuid,
                        instanceId = key.instanceId,
                    )
                    if (!activeGatt.setCharacteristicNotification(characteristic, enabled)) {
                        return@GattOperation false
                    }
                    try {
                        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            activeGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = value
                            @Suppress("DEPRECATION")
                            activeGatt.writeDescriptor(descriptor)
                        }
                        if (!accepted) restoreLocalNotification(activeGatt, characteristic, !enabled)
                        accepted
                    } catch (exception: SecurityException) {
                        restoreLocalNotification(activeGatt, characteristic, !enabled)
                        throw exception
                    } catch (exception: IllegalStateException) {
                        restoreLocalNotification(activeGatt, characteristic, !enabled)
                        throw exception
                    }
                },
            ),
        )
    }

    fun close() {
        mainHandler.post { releaseGatt() }
    }

    private fun enqueue(operation: GattOperation) {
        mainHandler.post {
            if (_connectionState.value != ConnectionState.CONNECTED || gatt == null) {
                reportFailure("The GATT connection is no longer active", operation.serviceUuid, operation.characteristicUuid)
                return@post
            }
            val duplicate = (listOfNotNull(activeOperation) + operationQueue.toList())
                .any { it.identity == operation.identity }
            if (duplicate) {
                reportFailure("Duplicate GATT operation ignored: ${operation.label}", operation.serviceUuid, operation.characteristicUuid)
                return@post
            }
            operationQueue.addLast(operation)
            updateOperationState()
            startNextOperation()
        }
    }

    private fun startNextOperation() {
        if (activeOperation != null || _connectionState.value != ConnectionState.CONNECTED) return
        val next = operationQueue.pollFirst() ?: run {
            updateOperationState()
            return
        }
        val currentGatt = gatt ?: run {
            clearOperations("GATT connection was lost")
            return
        }
        activeOperation = next
        updateOperationState()
        _isReadingDiagnostics.value = next.type == OperationType.READ
        try {
            if (!next.start(currentGatt)) {
                finishOperation(
                    success = false,
                    status = null,
                    message = "${next.label} was rejected by Android",
                    key = next.key,
                )
            } else {
                mainHandler.removeCallbacks(operationTimeout)
                mainHandler.postDelayed(operationTimeout, OPERATION_TIMEOUT_MILLIS)
            }
        } catch (exception: SecurityException) {
            finishOperation(
                success = false,
                status = null,
                message = "${next.label} permission denied: ${exception.message}",
                key = next.key,
            )
        } catch (exception: IllegalStateException) {
            finishOperation(
                success = false,
                status = null,
                message = "${next.label} failed to start: ${exception.message}",
                key = next.key,
            )
        }
    }

    private fun completeRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        mainHandler.post {
            val operation = activeOperation
            if (callbackGatt !== gatt || operation?.type != OperationType.READ) return@post
            val key = operation.key ?: return@post
            val serviceUuid = characteristic.service.uuid.toString().uppercase(Locale.ROOT)
            val characteristicUuid = characteristic.uuid.toString().uppercase(Locale.ROOT)
            if (key.serviceUuid != serviceUuid || key.characteristicUuid != characteristicUuid) return@post
            val completedAt = System.currentTimeMillis()
            val bytes = value.map { it.toInt() and 0xFF }
            val succeeded = status == BluetoothGatt.GATT_SUCCESS
            val error = if (succeeded) null else "Characteristic read failed (GATT status $status)"
            _characteristicReadResults.value = _characteristicReadResults.value + CharacteristicReadResult(
                startedAtMillis = operation.readStartedAtMillis ?: completedAt,
                completedAtMillis = completedAt,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                properties = characteristic.properties,
                value = bytes,
                statusCode = status,
                error = error,
            )
            if (operation.readTarget == HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET) {
                _manufacturerReadCompleted.value = true
            }
            recordEvent(
                if (succeeded) "Read" else "Error",
                if (succeeded) {
                    "Characteristic read completed; status=$status; duration=${completedAt - (operation.readStartedAtMillis ?: completedAt)} ms"
                } else {
                    "Characteristic read failed; status=$status"
                },
                serviceUuid,
                characteristicUuid,
                formatHex(bytes),
                key.instanceId,
            )
            finishOperation(succeeded, status, operation.label, key, readResultRecorded = true)
        }
    }

    private fun recordReadFailure(
        target: DiagnosticReadTarget,
        properties: Int,
        startedAt: Long,
        message: String,
        status: Int?,
    ) {
        val completedAt = System.currentTimeMillis()
        _characteristicReadResults.value = _characteristicReadResults.value + CharacteristicReadResult(
            startedAtMillis = startedAt,
            completedAtMillis = completedAt,
            serviceUuid = target.serviceUuid,
            characteristicUuid = target.characteristicUuid,
            properties = properties,
            value = null,
            statusCode = status,
            error = message,
        )
        if (target == HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET) {
            _manufacturerReadCompleted.value = true
        }
        recordEvent(
            "Error",
            "Characteristic read failed before start: $message",
            target.serviceUuid,
            target.characteristicUuid,
        )
        reportFailure(message, target.serviceUuid, target.characteristicUuid)
    }

    private fun finishOperation(
        success: Boolean,
        status: Int?,
        message: String,
        key: CharacteristicKey? = null,
        readResultRecorded: Boolean = false,
    ) {
        mainHandler.removeCallbacks(operationTimeout)
        val completedOperation = activeOperation
        activeOperation = null
        _isReadingDiagnostics.value = false
        if (!success && completedOperation?.type == OperationType.DISCOVERY) {
            _serviceDiscoveryState.value = ServiceDiscoveryState.FAILED
        }
        if (!success && completedOperation?.type == OperationType.READ && !readResultRecorded) {
            recordReadOperationFailure(completedOperation, message, status)
        }
        val old = _operationState.value
        _operationState.value = old.copy(
            currentOperation = null,
            lastSuccessfulOperation = if (success) message else old.lastSuccessfulOperation,
            lastFailedOperation = if (success) old.lastFailedOperation else message,
            lastErrorCode = if (success) old.lastErrorCode else status,
        )
        recordEvent(
            if (success) "Operation" else "Error",
            "$message ${if (success) "completed" else "failed"}${status?.let { "; GATT status=$it" } ?: ""}",
            key?.serviceUuid,
            key?.characteristicUuid,
            instanceId = key?.instanceId,
        )
        if (!success && status != null) reportGattFailure(message, status, key)
        if (!success && status == GATT_STATUS_CONNECTION_TERMINATED_BY_PEER) {
            clearOperations("Peer terminated the GATT connection")
            _connectionState.value = ConnectionState.DISCONNECTING
            try {
                gatt?.disconnect()
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth permission denied after peer disconnect status: ${exception.message}")
                releaseGatt()
            }
            return
        }
        updateOperationState()
        startNextOperation()
    }

    private fun timeoutActiveOperation() {
        val operation = activeOperation ?: return
        val message = "${operation.label} timed out waiting for its GATT callback"
        _operationState.value = _operationState.value.copy(
            currentOperation = null,
            lastFailedOperation = operation.label,
            lastErrorCode = null,
        )
        recordEvent("Error", message, operation.serviceUuid, operation.characteristicUuid)
        recordReadOperationFailure(operation, message, null)
        reportFailure(message, operation.serviceUuid, operation.characteristicUuid)
        activeOperation = null
        operationQueue.clear()
        _isReadingDiagnostics.value = false
        updateOperationState()
        _connectionState.value = ConnectionState.DISCONNECTING
        try {
            gatt?.disconnect()
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth permission denied while ending timed-out GATT operation: ${exception.message}")
            releaseGatt()
        }
    }

    private fun isCurrentOperation(callbackGatt: BluetoothGatt, type: OperationType): Boolean =
        callbackGatt === gatt && activeOperation?.type == type

    private fun clearOperations(reason: String) {
        val running = activeOperation
        mainHandler.removeCallbacks(operationTimeout)
        if (running?.type == OperationType.READ) {
            recordReadOperationFailure(running, reason, null)
        }
        if (running != null || operationQueue.isNotEmpty()) {
            recordEvent(
                "Operation",
                "$reason; active=${running?.label ?: "none"}; queued=${operationQueue.size}",
                running?.serviceUuid,
                running?.characteristicUuid,
            )
        }
        activeOperation = null
        operationQueue.clear()
        _isReadingDiagnostics.value = false
        _operationState.value = _operationState.value.copy(
            currentOperation = null,
            pendingQueueSize = 0,
        )
    }

    private fun updateOperationState() {
        _operationState.value = _operationState.value.copy(
            currentOperation = activeOperation?.label,
            pendingQueueSize = operationQueue.size,
        )
    }

    private fun recordReadOperationFailure(
        operation: GattOperation,
        message: String,
        status: Int?,
    ) {
        val target = operation.readTarget ?: return
        val completedAt = System.currentTimeMillis()
        val startedAt = operation.readStartedAtMillis ?: completedAt
        _characteristicReadResults.value = _characteristicReadResults.value + CharacteristicReadResult(
            startedAtMillis = startedAt,
            completedAtMillis = completedAt,
            serviceUuid = target.serviceUuid,
            characteristicUuid = target.characteristicUuid,
            properties = operation.properties,
            value = null,
            statusCode = status,
            error = message,
        )
        if (target == HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET) {
            _manufacturerReadCompleted.value = true
        }
        recordEvent(
            "Error",
            "Characteristic read failed; status=${status ?: "N/A"}; $message",
            target.serviceUuid,
            target.characteristicUuid,
        )
    }

    private fun releaseGatt() {
        clearOperations("Old GATT connection released")
        val oldGatt = gatt
        gatt = null
        if (oldGatt != null) {
            recordEvent("Connection", "Closing previous GATT connection before reconnect or shutdown")
            try {
                oldGatt.disconnect()
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth permission denied while disconnecting old GATT: ${exception.message}")
            }
            try {
                oldGatt.close()
            } catch (exception: SecurityException) {
                reportFailure("Bluetooth permission denied while closing old GATT: ${exception.message}")
            }
        }
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            _disconnectedAtMillis.value = System.currentTimeMillis()
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _enabledNotifications.value = emptySet()
        _serviceDiscoveryState.value = ServiceDiscoveryState.NOT_STARTED
    }

    private fun recordGattFailure(operation: String, status: Int) {
        val active = activeOperation
        val key = active?.key
        recordEvent(
            "Error",
            "$operation; GATT status=$status; connection=${_connectionState.value}",
            key?.serviceUuid ?: active?.serviceUuid,
            key?.characteristicUuid ?: active?.characteristicUuid,
        )
        reportGattFailure(operation, status, key)
    }

    private fun reportGattFailure(operation: String, status: Int, key: CharacteristicKey?) {
        val message = "$operation failed (GATT status $status; connection active=${_connectionState.value == ConnectionState.CONNECTED})"
        reportFailure(message, key?.serviceUuid, key?.characteristicUuid)
    }

    private fun findCharacteristic(gatt: BluetoothGatt, key: CharacteristicKey): BluetoothGattCharacteristic? =
        try {
            gatt.getService(UUID.fromString(key.serviceUuid))?.characteristics?.getOrNull(key.instanceId)
                ?.takeIf { it.uuid.toString().equals(key.characteristicUuid, true) }
        } catch (exception: IllegalArgumentException) {
            null
        }

    private fun recordNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val serviceUuid = characteristic.service.uuid.toString().uppercase(Locale.ROOT)
        val characteristicUuid = characteristic.uuid.toString().uppercase(Locale.ROOT)
        val instanceId = characteristic.service.characteristics.indexOfFirst { it === characteristic }
        recordEvent(
            "Notification",
            "Received ${value.size} byte(s)",
            serviceUuid,
            characteristicUuid,
            formatHex(value.map { it.toInt() and 0xFF }),
            instanceId,
        )
    }

    private fun restoreLocalNotification(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ) {
        try {
            currentGatt.setCharacteristicNotification(characteristic, enabled)
        } catch (exception: SecurityException) {
            reportFailure("Bluetooth permission denied while restoring notification state: ${exception.message}")
        }
    }

    private fun parseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            reportFailure("Invalid UUID: $value")
            null
        }

    private fun reportFailure(message: String, serviceUuid: String? = null, characteristicUuid: String? = null) {
        onFailure(message, serviceUuid, characteristicUuid)
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

    private fun formatDuration(millis: Long): String =
        "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')} s"

    private data class GattOperation(
        val type: OperationType,
        val label: String,
        val serviceUuid: String? = null,
        val characteristicUuid: String? = null,
        val key: CharacteristicKey? = null,
        val enabled: Boolean? = null,
        val readTarget: DiagnosticReadTarget? = null,
        val readStartedAtMillis: Long? = null,
        val properties: Int = 0,
        val start: (BluetoothGatt) -> Boolean,
    ) {
        val identity: String
            get() = "$type/${serviceUuid.orEmpty()}/${characteristicUuid.orEmpty()}/${key?.instanceId ?: 0}"
    }

    private enum class OperationType {
        DISCOVERY,
        MTU,
        READ,
        DESCRIPTOR_WRITE,
    }

    companion object {
        private const val REQUESTED_MTU = 247
        private const val OPERATION_TIMEOUT_MILLIS = 20_000L
        private const val GATT_STATUS_CONNECTION_TERMINATED_BY_PEER = 19
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
