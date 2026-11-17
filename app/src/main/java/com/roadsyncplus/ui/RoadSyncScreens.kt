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
import com.roadsyncplus.ble.ConnectionState
import com.roadsyncplus.ble.DiagnosticEvent
import com.roadsyncplus.ble.DiscoveredDevice
import com.roadsyncplus.ble.GattCharacteristicInfo
import com.roadsyncplus.ble.GattSnapshot
import com.roadsyncplus.ble.HondaProtocolReference
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
                Text(if (isScanning) "Stop scan" else "Scan")
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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Connection details", style = MaterialTheme.typography.titleLarge)
        Text("Selected: ${selectedDevice?.name ?: "No device selected"}")
        selectedDevice?.let { Text(it.address, fontFamily = FontFamily.Monospace) }
        Text("State: $connectionState")
        Text("Connected device: ${connectedDevice?.name ?: "None"}")
        Text("MTU: $mtu (no MTU request sent by this app)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Spacer(Modifier.height(8.dp))
        if (snapshot.services.isEmpty()) {
            Text("GATT services appear here after connection and discovery.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}

@Composable
fun NotificationScreen(
    snapshot: GattSnapshot,
    events: List<DiagnosticEvent>,
    enabledNotifications: Set<CharacteristicKey>,
    onSetNotifications: (String, String, Boolean) -> Unit,
) {
    val candidates = snapshot.services.flatMap { service ->
        service.characteristics.mapNotNull { characteristic ->
            val supportsNotify =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val supportsIndicate =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (supportsNotify || supportsIndicate) service.uuid to characteristic else null
        }
    }
    val notifications = events.filter { it.category == "Notification" }.asReversed()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Notification monitor", style = MaterialTheme.typography.titleLarge)
        Text("Subscriptions write only the standard CCCD descriptor; no characteristic commands are sent.")
        if (candidates.isEmpty()) Text("No Notify/Indicate characteristics discovered.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(candidates, key = { it.first + it.second.uuid }) { (serviceUuid, characteristic) ->
                val key = CharacteristicKey(serviceUuid, characteristic.uuid)
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(characteristic.uuid, fontFamily = FontFamily.Monospace)
                            Text("Service $serviceUuid")
                            Text("Properties: ${propertyNames(characteristic.properties)}")
                        }
                        Switch(
                            checked = key in enabledNotifications,
                            onCheckedChange = { onSetNotifications(serviceUuid, characteristic.uuid, it) },
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
fun ProtocolScreen(snapshot: GattSnapshot, connectionState: ConnectionState) {
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
        Text("PCX125 2025 documentation comparison only; PCX160 compatibility is unverified.")
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
                        Text(if (present) "Present on connected device" else "Not found / not yet discovered")
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
                    }
                }
            }
            item {
                Text(
                    "Vehicle-information characteristic UUIDs are abbreviated in the reference document and are not guessed here.",
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
)

private fun characteristicPropertySet(characteristic: GattCharacteristicInfo): Set<String> =
    propertyNames(characteristic.properties)
        .split(", ")
        .filter { it != "None" }
        .toSet()

private fun formatTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMillis))
