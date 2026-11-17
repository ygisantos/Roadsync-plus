package com.roadsyncplus

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadsyncplus.ui.AppTab
import com.roadsyncplus.ui.ConnectionScreen
import com.roadsyncplus.ui.NotificationScreen
import com.roadsyncplus.ui.ProtocolScreen
import com.roadsyncplus.ui.RoadSyncShell
import com.roadsyncplus.ui.ScannerScreen
import com.roadsyncplus.ble.AndroidBleRepository
import com.roadsyncplus.ble.hasBluetoothPermissions
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val repository by lazy { AndroidBleRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RoadSyncApp(repository, applicationContext, ::saveReport)
                }
            }
        }
    }

    private fun saveReport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(repository.createDiagnosticReport())
            } ?: throw IOException("Android could not open the selected document")
        } catch (exception: IOException) {
            repository.reportFailure("Report export failed: ${exception.message}")
        } catch (exception: SecurityException) {
            repository.reportFailure("Report export permission denied: ${exception.message}")
        }
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}

@Composable
private fun RoadSyncApp(
    repository: AndroidBleRepository,
    context: Context,
    saveReport: (Uri) -> Unit,
) {
    var selectedDevice by remember { mutableStateOf<com.roadsyncplus.ble.DiscoveredDevice?>(null) }
    var showAllDevices by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(AppTab.SCANNER) }

    val devices by repository.devices.collectAsStateWithLifecycle()
    val isScanning by repository.isScanning.collectAsStateWithLifecycle()
    val connectionState by repository.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by repository.connectedDevice.collectAsStateWithLifecycle()
    val gattSnapshot by repository.gattSnapshot.collectAsStateWithLifecycle()
    val mtu by repository.mtu.collectAsStateWithLifecycle()
    val mtuRequestState by repository.mtuRequestState.collectAsStateWithLifecycle()
    val events by repository.events.collectAsStateWithLifecycle()
    val enabledNotifications by repository.enabledNotifications.collectAsStateWithLifecycle()
    val characteristicReadResults by repository.characteristicReadResults.collectAsStateWithLifecycle()
    val isReadingDiagnostics by repository.isReadingDiagnostics.collectAsStateWithLifecycle()
    val statusMessage by repository.statusMessage.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.isNotEmpty() && results.values.all { it }) {
            repository.startScan()
        } else {
            repository.reportFailure("Nearby Devices permission is required to scan and connect")
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let(saveReport)
    }

    val requestScan = {
        if (hasBluetoothPermissions(context)) {
            repository.startScan()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(permissions)
        }
    }

    RoadSyncShell(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        statusMessage = statusMessage,
        onDismissStatus = repository::clearStatusMessage,
        onExport = {
            documentLauncher.launch("roadsync-diagnostics-${System.currentTimeMillis()}.txt")
        },
        scanner = {
            ScannerScreen(
                devices = devices,
                isScanning = isScanning,
                showAllDevices = showAllDevices,
                onShowAllChanged = { showAllDevices = it },
                connectionState = connectionState,
                connectedAddress = connectedDevice?.address,
                onScan = requestScan,
                onStopScan = repository::stopScan,
                onSelect = {
                    selectedDevice = it
                    selectedTab = AppTab.CONNECTION
                },
            )
        },
        connection = {
            ConnectionScreen(
                selectedDevice = selectedDevice ?: connectedDevice,
                connectedDevice = connectedDevice,
                connectionState = connectionState,
                snapshot = gattSnapshot,
                mtu = mtu,
                mtuRequestState = mtuRequestState,
                readResults = characteristicReadResults,
                isReadingDiagnostics = isReadingDiagnostics,
                onConnect = { selectedDevice?.let(repository::connect) },
                onDisconnect = repository::disconnect,
                onReadDiagnostics = repository::readDiagnosticCharacteristics,
                onRequestMtu = repository::requestMtu,
            )
        },
        monitor = {
            NotificationScreen(
                snapshot = gattSnapshot,
                events = events,
                enabledNotifications = enabledNotifications,
                onSetNotifications = repository::setNotifications,
            )
        },
        protocol = {
            ProtocolScreen(
                snapshot = gattSnapshot,
                connectionState = connectionState,
                readResults = characteristicReadResults,
            )
        },
    )
}
