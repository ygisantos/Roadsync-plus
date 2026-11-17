package com.roadsyncplus.ble

import kotlinx.coroutines.flow.StateFlow

interface BleRepository {
    val devices: StateFlow<List<DiscoveredDevice>>
    val isScanning: StateFlow<Boolean>
    val connectionState: StateFlow<ConnectionState>
    val connectedDevice: StateFlow<DiscoveredDevice?>
    val gattSnapshot: StateFlow<GattSnapshot>
    val mtu: StateFlow<Int>
    val mtuRequestState: StateFlow<MtuRequestState>
    val events: StateFlow<List<DiagnosticEvent>>
    val enabledNotifications: StateFlow<Set<CharacteristicKey>>
    val characteristicReadResults: StateFlow<List<CharacteristicReadResult>>
    val isReadingDiagnostics: StateFlow<Boolean>
    val statusMessage: StateFlow<String?>

    fun startScan()
    fun stopScan()
    fun connect(device: DiscoveredDevice)
    fun disconnect()
    fun setNotifications(
        serviceUuid: String,
        characteristicUuid: String,
        instanceId: Int,
        enabled: Boolean,
    )
    fun readDiagnosticCharacteristics()
    fun requestMtu()
    fun clearStatusMessage()
    fun createDiagnosticReport(): String
}
