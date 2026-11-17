package com.roadsyncplus.ui

import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roadsyncplus.ble.CharacteristicKey
import com.roadsyncplus.ble.CharacteristicReadResult
import com.roadsyncplus.ble.BondingState
import com.roadsyncplus.ble.ConnectionState
import com.roadsyncplus.ble.DiagnosticEvent
import com.roadsyncplus.ble.DiagnosticReadTarget
import com.roadsyncplus.ble.DiscoveredDevice
import com.roadsyncplus.ble.GattOperationState
import com.roadsyncplus.ble.GattCharacteristicInfo
import com.roadsyncplus.ble.GattSnapshot
import com.roadsyncplus.ble.HondaProtocolReference
import com.roadsyncplus.ble.MtuRequestState
import com.roadsyncplus.ble.ServiceDiscoveryState
import com.roadsyncplus.ble.decodeCharacteristicValue
import com.roadsyncplus.ble.formatHex
import com.roadsyncplus.ble.propertyNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppTab(val label: String) {
    SCANNER("Scanner"),
    CONNECTION("Connection"),
    MONITOR("Monitor"),
    PROTOCOL("Protocol"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadSyncShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    statusMessage: String?,
    onDismissStatus: () -> Unit,
    onExport: () -> Unit,
    scanner: @Composable () -> Unit,
    connection: @Composable () -> Unit,
    monitor: @Composable () -> Unit,
    protocol: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RoadSync Plus") },
                actions = { TextButton(onClick = onExport) { Text("Export") } },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        icon = { Text(tab.label.take(1)) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (statusMessage != null) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(statusMessage, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onDismissStatus) { Text("Dismiss") }
                    }
                }
            }
            when (selectedTab) {
                AppTab.SCANNER -> scanner()
                AppTab.CONNECTION -> connection()
                AppTab.MONITOR -> monitor()
                AppTab.PROTOCOL -> protocol()
            }
        }
    }
}

@Composable
fun ScannerScreen(
    devices: List<DiscoveredDevice>,
    isScanning: Boolean,
    showAllDevices: Boolean,
    onShowAllChanged: (Boolean) -> Unit,
    connectionState: ConnectionState,
    connectedAddress: String?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (DiscoveredDevice) -> Unit,
) {
    val visibleDevices = devices.filter { showAllDevices || it.isHondaBtu }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Nearby BLE devices", style = MaterialTheme.typography.titleLarge)
        Text("Only the advertised name is used for the Honda BTU filter.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showAllDevices, onCheckedChange = onShowAllChanged)
            Text("Show all BLE devices")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (isScanning) onStopScan else onScan) {
                Text(if (isScanning) "Stop scan" else "Scan for motorcycle")
            }
            if (isScanning) Text("Scanning (15 second limit)…", Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(8.dp))
        if (visibleDevices.isEmpty()) {
            Text(if (isScanning) "Waiting for advertisements…" else "No matching devices discovered.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleDevices, key = { it.address }) { device ->
                Card(
                    Modifier.fillMaxWidth().clickable { onSelect(device) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.name ?: "Unnamed BLE device", style = MaterialTheme.typography.titleMedium)
                        Text(device.address, fontFamily = FontFamily.Monospace)
                        Text("RSSI ${device.rssi} dBm")
                                    Text(
                                        when {
                                            connectedAddress != device.address -> "Not connected"
                                            connectionState == ConnectionState.CONNECTED -> "Connected"
                                            else -> connectionState.name.lowercase().replaceFirstChar { it.uppercase() }
                                        },
                                    )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionScreen(
    selectedDevice: DiscoveredDevice?,
    connectedDevice: DiscoveredDevice?,
    connectionState: ConnectionState,
    snapshot: GattSnapshot,
    mtu: Int,
    mtuRequestState: MtuRequestState,
    serviceDiscoveryState: ServiceDiscoveryState,
    operationState: GattOperationState,
    adapterState: String,
    bondingState: BondingState,
    connectionStartedAtMillis: Long?,
    disconnectedAtMillis: Long?,
    disconnectStatus: Int?,
    connectionDurationMillis: Long,
    readResults: List<CharacteristicReadResult>,
    isReadingDiagnostics: Boolean,
    manufacturerReadCompleted: Boolean,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDiscoverServices: () -> Unit,
    onReadCharacteristic: (DiagnosticReadTarget) -> Unit,
    onRequestMtu: () -> Unit,
) {
    val displayedMtu = if (connectionState == ConnectionState.CONNECTED) {
        mtu
    } else {
        mtuRequestState.negotiatedMtu
    }
    val queueIdle = operationState.currentOperation == null &&
        operationState.pendingQueueSize == 0 && !isReadingDiagnostics
    val readyForRead = connectionState == ConnectionState.CONNECTED &&
        serviceDiscoveryState == ServiceDiscoveryState.COMPLETE && queueIdle
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("Connection details", style = MaterialTheme.typography.titleLarge)
                Text("Selected: ${selectedDevice?.name ?: "No device selected"}")
                selectedDevice?.let { Text(it.address, fontFamily = FontFamily.Monospace) }
                Text("Device RSSI: ${selectedDevice?.rssi?.let { "$it dBm" } ?: "N/A"}")
                Text("Bluetooth adapter: $adapterState")
                Text("BLE connection: $connectionState")
                Text("Bonding: $bondingState")
                Text("Connected device: ${connectedDevice?.name ?: "None"}")
                Text("Connection started: ${connectionStartedAtMillis?.let(::formatTimestamp) ?: "N/A"}")
                Text("Connection duration: ${formatDuration(connectionDurationMillis)}")
                Text("Disconnected: ${disconnectedAtMillis?.let(::formatTimestamp) ?: "N/A"}")
                Text("Disconnect status: ${disconnectStatus ?: "N/A"}")
                Text("Service discovery: $serviceDiscoveryState")
                Text(
                    "Services: ${snapshot.services.size} total, " +
                        "${snapshot.services.count(::isStandardService)} standard, " +
                        "${snapshot.services.count { !isStandardService(it) }} custom",
                )
                Text("Negotiated MTU: $displayedMtu")
                Text("Maximum characteristic write payload: ${(displayedMtu - 3).coerceAtLeast(0)} bytes (no writes are sent)")
                mtuRequestState.requestedMtu?.let { requested ->
                    Text(
                        "MTU request $requested: ${
                            when (mtuRequestState.callbackStatus) {
                                null -> if (mtuRequestState.requestAccepted == true) "waiting for callback" else "not accepted"
                                0 -> "callback success; negotiated ${mtuRequestState.negotiatedMtu}"
                                else -> "callback status ${mtuRequestState.callbackStatus}"
                            }
                        }",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) { Text("Scan") }
                    Button(
                        onClick = onConnect,
                        enabled = selectedDevice != null && connectionState == ConnectionState.DISCONNECTED,
                    ) { Text("Connect") }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = connectionState == ConnectionState.CONNECTED ||
                            connectionState == ConnectionState.CONNECTING,
                    ) { Text("Disconnect") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDiscoverServices,
                        enabled = connectionState == ConnectionState.CONNECTED &&
                            serviceDiscoveryState != ServiceDiscoveryState.DISCOVERING,
                    ) {
                        Text(if (serviceDiscoveryState == ServiceDiscoveryState.DISCOVERING) "Discovering…" else "Discover services")
                    }
                    OutlinedButton(
                        onClick = onRequestMtu,
                        enabled = connectionState == ConnectionState.CONNECTED &&
                            serviceDiscoveryState == ServiceDiscoveryState.COMPLETE &&
                            operationState.currentOperation == null && operationState.pendingQueueSize == 0,
                    ) {
                        Text("Request MTU 247")
                    }
                }
                Text("Current GATT operation: ${operationState.currentOperation ?: "Idle"}")
                Text("Pending queue: ${operationState.pendingQueueSize}")
                Text("Last successful operation: ${operationState.lastSuccessfulOperation ?: "N/A"}")
                Text("Last failed operation: ${operationState.lastFailedOperation ?: "N/A"}")
                Text("Last GATT error code: ${operationState.lastErrorCode ?: "N/A"}")
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Read-only diagnostic tests", style = MaterialTheme.typography.titleMedium)
                    Text("No reads start automatically. Test 3 requires successful service discovery and MTU negotiation.")
                    Button(
                        onClick = { onReadCharacteristic(HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET) },
                        enabled = readyForRead && mtuRequestState.callbackStatus == 0 &&
                            !manufacturerReadCompleted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (manufacturerReadCompleted) "Manufacturer Name test completed" else "Read Manufacturer Name (2A29)")
                    }
                    Text("Vehicle Information reads are enabled only after the Manufacturer Name test completes.")
                    HondaProtocolReference.vehicleInformationReadTargets.forEach { target ->
                        OutlinedButton(
                            onClick = { onReadCharacteristic(target) },
                            enabled = readyForRead && manufacturerReadCompleted,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Read ${target.label} (${target.characteristicUuid.take(8)}…)")
                        }
                    }
                }
            }
        }
        if (snapshot.services.isEmpty()) {
            item { Text("GATT services appear here after explicit service discovery.") }
        }
        items(snapshot.services, key = { it.uuid }) { service ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Service ${service.uuid}", style = MaterialTheme.typography.titleSmall)
                    service.characteristics.forEach { characteristic ->
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("Characteristic ${characteristic.uuid}", fontFamily = FontFamily.Monospace)
                        Text("Properties: ${propertyNames(characteristic.properties)}")
                        Text(
                            if (characteristic.descriptors.isEmpty()) {
                                "Descriptors: none"
                            } else {
                                "Descriptors: ${characteristic.descriptors.joinToString()}"
                            },
                        )
                    }
                }
            }
        }
        if (readResults.isNotEmpty()) {
            item {
                Text("Read-only values", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(readResults.asReversed()) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        (HondaProtocolReference.vehicleInformationReadTargets +
                            HondaProtocolReference.MANUFACTURER_NAME_READ_TARGET).firstOrNull {
                            it.serviceUuid.equals(result.serviceUuid, ignoreCase = true) &&
                                it.characteristicUuid.equals(result.characteristicUuid, ignoreCase = true)
                        }?.label ?: result.characteristicUuid,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("${result.serviceUuid} / ${result.characteristicUuid}", fontFamily = FontFamily.Monospace)
                    Text("Read started ${formatTimestamp(result.startedAtMillis)}")
                    Text("Read completed ${formatTimestamp(result.completedAtMillis)}")
                    Text("Properties: ${propertyNames(result.properties)}")
                    Text("GATT status ${result.statusCode ?: "N/A"}")
                    if (result.value != null) {
                        Text("Hex: ${formatHex(result.value)}", fontFamily = FontFamily.Monospace)
                        Text("Decoded: ${decodeCharacteristicValue(result.serviceUuid, result.characteristicUuid, result.value)}")
                    }
                    result.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
fun NotificationScreen(
    snapshot: GattSnapshot,
    events: List<DiagnosticEvent>,
    enabledNotifications: Set<CharacteristicKey>,
    onSetNotifications: (String, String, Int, Boolean) -> Unit,
) {
    val candidates = snapshot.services.flatMap { service ->
        if (service.uuid.equals(HondaProtocolReference.DFU_SERVICE, ignoreCase = true)) {
            emptyList()
        } else {
            service.characteristics.mapIndexedNotNull { instanceId, characteristic ->
                val supportsNotify =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val supportsIndicate =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (supportsNotify || supportsIndicate) {
                    NotificationCandidate(service.uuid, characteristic, instanceId)
                } else {
                    null
                }
            }
        }
    }
    val notifications = events.filter { it.category == "Notification" }.asReversed()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Notification monitor", style = MaterialTheme.typography.titleLarge)
        Text("Subscriptions write only the standard CCCD descriptor; no characteristic commands are sent.")
        if (candidates.isEmpty()) Text("No Notify/Indicate characteristics discovered.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                candidates,
                key = { "${it.serviceUuid}/${it.characteristic.uuid}/${it.instanceId}" },
            ) { candidate ->
                val serviceUuid = candidate.serviceUuid
                val characteristic = candidate.characteristic
                val key = CharacteristicKey(serviceUuid, characteristic.uuid, candidate.instanceId)
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(characteristic.uuid, fontFamily = FontFamily.Monospace)
                            Text("Characteristic instance ${candidate.instanceId + 1}")
                            Text("Service $serviceUuid")
                            Text("Properties: ${propertyNames(characteristic.properties)}")
                        }
                        Switch(
                            checked = key in enabledNotifications,
                            onCheckedChange = {
                                onSetNotifications(serviceUuid, characteristic.uuid, candidate.instanceId, it)
                            },
                        )
                    }
                }
            }
            if (notifications.isEmpty()) {
                item { Text("No notification payloads received yet.") }
            }
            items(notifications) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(formatTimestamp(event.timestampMillis))
                        Text("Service ${event.serviceUuid}", fontFamily = FontFamily.Monospace)
                        Text("Characteristic ${event.characteristicUuid}", fontFamily = FontFamily.Monospace)
                        event.instanceId?.let { Text("Characteristic instance ${it + 1}") }
                        Text(event.payloadHex.orEmpty(), fontFamily = FontFamily.Monospace)
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(event.payloadHex.orEmpty()))
                            },
                        ) { Text("Copy payload") }
                    }
                }
            }
        }
    }
}

@Composable
fun ProtocolScreen(
    snapshot: GattSnapshot,
    connectionState: ConnectionState,
    readResults: List<CharacteristicReadResult>,
) {
    val knownServices = listOf(
        "Security" to HondaProtocolReference.SECURITY_SERVICE,
        "Data communication" to HondaProtocolReference.DATA_SERVICE,
        "Assignment" to HondaProtocolReference.ASSIGNMENT_SERVICE,
        "Vehicle information" to HondaProtocolReference.VEHICLE_INFO_SERVICE,
        "Firmware update (DFU; no interaction)" to HondaProtocolReference.DFU_SERVICE,
    )
    val knownCharacteristics = listOf(
        KnownCharacteristic("Security.ControlPoint", HondaProtocolReference.SECURITY_SERVICE,
            HondaProtocolReference.SECURITY_CONTROL_POINT, setOf("Write")),
        KnownCharacteristic("Security.DataSource", HondaProtocolReference.SECURITY_SERVICE,
            HondaProtocolReference.SECURITY_DATA_SOURCE, setOf("Read")),
        KnownCharacteristic("Security.Status", HondaProtocolReference.SECURITY_SERVICE,
            HondaProtocolReference.SECURITY_STATUS, setOf("Read")),
        KnownCharacteristic("VehicleInfo.VehicleType", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.VEHICLE_TYPE, setOf("Read"), expectedValueHex = "01",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("VehicleInfo.DisplayCapability", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.DISPLAY_CAPABILITY, setOf("Read"), expectedValueHex = "07",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("VehicleInfo.SwitchType", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.SWITCH_TYPE, setOf("Read"), expectedValueHex = "00",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("VehicleInfo.CategoryId", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.CATEGORY_ID, setOf("Read"), expectedValueHex = "01",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("VehicleInfo.SeriesCode", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.SERIES_CODE, setOf("Read"), expectedValueHex = "AE 05",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("VehicleInfo.DestinationId", HondaProtocolReference.VEHICLE_INFO_SERVICE,
            HondaProtocolReference.DESTINATION_ID, setOf("Read"), expectedValueHex = "00",
            sourceNote = "UUID observed in supplied PCX160 report; value reference is PCX125 documentation"),
        KnownCharacteristic("Data.ControlPoint", HondaProtocolReference.DATA_SERVICE,
            HondaProtocolReference.DATA_CONTROL_POINT, setOf("WriteNoResponse", "Notify")),
        KnownCharacteristic("Data.Buttons", HondaProtocolReference.DATA_SERVICE,
            HondaProtocolReference.DATA_BUTTONS, setOf("Notify")),
        KnownCharacteristic("Data.DrivingInfo", HondaProtocolReference.DATA_SERVICE,
            HondaProtocolReference.DATA_DRIVING_INFO, emptySet(), documentedAbsent = true),
        KnownCharacteristic("Assignment.Control", HondaProtocolReference.ASSIGNMENT_SERVICE,
            HondaProtocolReference.ASSIGNMENT_CONTROL, setOf("WriteNoResponse", "Notify")),
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Protocol Explorer", style = MaterialTheme.typography.titleLarge)
        Text("PCX125 protocol comparison plus the GATT UUIDs observed in your supplied PCX160 report.")
        if (snapshot.discovered && connectionState != ConnectionState.CONNECTED) {
            Text("Showing the last discovered GATT structure; the device is not currently connected.")
        }
        Text("No protocol writes or pairing/authentication actions are available.")
        Spacer(Modifier.height(8.dp))
        Text("Known services", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(knownServices, key = { it.second }) { (label, uuid) ->
                val present = snapshot.services.any { it.uuid.equals(uuid, ignoreCase = true) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(uuid, fontFamily = FontFamily.Monospace)
                        Text(
                            when {
                                !snapshot.discovered -> "Not found / not yet discovered"
                                connectionState == ConnectionState.CONNECTED && present -> "Present on connected device"
                                present -> "Present in last discovery"
                                else -> "Not found in discovered services"
                            },
                        )
                    }
                }
            }
            item {
                Text("Known characteristics", style = MaterialTheme.typography.titleMedium)
            }
            items(knownCharacteristics, key = { it.uuid }) { known ->
                val actual = snapshot.services
                    .firstOrNull { it.uuid.equals(known.serviceUuid, ignoreCase = true) }
                    ?.characteristics
                    ?.firstOrNull { it.uuid.equals(known.uuid, ignoreCase = true) }
                val actualProperties = actual?.let { characteristicPropertySet(it) }
                val readResult = readResults.lastOrNull {
                    it.serviceUuid.equals(known.serviceUuid, ignoreCase = true) &&
                        it.characteristicUuid.equals(known.uuid, ignoreCase = true)
                }
                val actualHex = readResult?.value?.let(::formatHex)
                val comparison = when {
                    !snapshot.discovered -> "Connect and discover services to compare"
                    known.documentedAbsent && actual == null -> "Matches documentation: absent on PCX125"
                    known.documentedAbsent -> "Discrepancy: present despite PCX125 notes"
                    actual == null -> "Not found / not yet discovered"
                    actualProperties == known.expectedProperties -> "Properties match PCX125 notes"
                    else -> "Property discrepancy"
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(known.name, style = MaterialTheme.typography.titleSmall)
                        Text(known.uuid, fontFamily = FontFamily.Monospace)
                        Text("Actual: ${actual?.let { propertyNames(it.properties) } ?: "Not found"}")
                        Text("Expected: ${if (known.documentedAbsent) "not present" else known.expectedProperties.joinToString()}")
                        known.sourceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            "Notifications: ${
                                when {
                                    actual == null -> "unknown"
                                    actual.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> "Notify supported"
                                    actual.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> "Indicate supported"
                                    else -> "not supported"
                                }
                            }",
                        )
                        Text(comparison)
                        if (known.expectedValueHex != null) {
                            Text("PCX125 reference value: ${known.expectedValueHex}")
                            Text("Actual read: ${actualHex ?: readResult?.error ?: "Not read"}")
                            if (actualHex != null) {
                                Text(
                                    if (actualHex.replace(" ", "").equals(
                                            known.expectedValueHex.replace(" ", ""),
                                            ignoreCase = true,
                                        )
                                    ) {
                                        "Value matches the PCX125 reference"
                                    } else {
                                        "Value differs from the PCX125 reference"
                                    },
                                )
                            }
                        }
                        if (known.uuid == HondaProtocolReference.SECURITY_STATUS) {
                            val decodedStatus = readResult?.value?.let {
                                decodeCharacteristicValue(known.serviceUuid, known.uuid, it)
                            }
                            Text("Last read: ${decodedStatus ?: readResult?.error ?: "Not read"}")
                        }
                    }
                }
            }
            item {
                Text(
                    "The six full Vehicle Information UUIDs above were taken from your supplied PCX160 report; the ScooterLink document abbreviates them. Only PCX125 reference values are compared.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private data class KnownCharacteristic(
    val name: String,
    val serviceUuid: String,
    val uuid: String,
    val expectedProperties: Set<String>,
    val documentedAbsent: Boolean = false,
    val expectedValueHex: String? = null,
    val sourceNote: String? = null,
)

private data class NotificationCandidate(
    val serviceUuid: String,
    val characteristic: GattCharacteristicInfo,
    val instanceId: Int,
)

private fun characteristicPropertySet(characteristic: GattCharacteristicInfo): Set<String> =
    propertyNames(characteristic.properties)
        .split(", ")
        .filter { it != "None" }
        .toSet()

private fun formatTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault()).format(Date(timestampMillis))

private fun formatDuration(millis: Long): String =
    "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')} s"

private fun isStandardService(service: com.roadsyncplus.ble.GattServiceInfo): Boolean =
    service.uuid.startsWith("0000") && service.uuid.endsWith("-0000-1000-8000-00805F9B34FB")
