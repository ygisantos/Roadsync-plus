package com.roadsyncplus.ble

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMillis: Long,
) {
    val isHondaBtu: Boolean
        get() = name?.startsWith("HONDA BTU", ignoreCase = true) == true
}

data class CharacteristicKey(
    val serviceUuid: String,
    val characteristicUuid: String,
)

data class GattCharacteristicInfo(
    val uuid: String,
    val properties: Int,
    val descriptors: List<String>,
)

data class GattServiceInfo(
    val uuid: String,
    val characteristics: List<GattCharacteristicInfo>,
)

data class GattSnapshot(
    val services: List<GattServiceInfo> = emptyList(),
    val discovered: Boolean = false,
)

data class DiagnosticEvent(
    val timestampMillis: Long,
    val category: String,
    val message: String,
    val serviceUuid: String? = null,
    val characteristicUuid: String? = null,
    val payloadHex: String? = null,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

object HondaProtocolReference {
    const val SECURITY_SERVICE = "592C1017-9C7B-4A35-ABA6-F268592FB8FC"
    const val DATA_SERVICE = "8536E103-6771-4D4B-9702-287CB5E1340F"
    const val ASSIGNMENT_SERVICE = "F0746E98-61D6-4570-BE7E-7635C65C21CB"
    const val VEHICLE_INFO_SERVICE = "4D87B1EA-528B-4798-B002-E2E1442A2E86"
    const val DFU_SERVICE = "0EA1BD92-5C48-41AD-9B8F-70DA1BD08F35"

    const val SECURITY_CONTROL_POINT = "4FA28B27-6938-48F8-980E-7C7C9ACC6C1E"
    const val SECURITY_DATA_SOURCE = "EBB3656F-03A5-44CE-A22C-70CFCCF5E247"
    const val SECURITY_STATUS = "A65B7152-589E-4933-A426-8F729BFAD439"
    const val DATA_CONTROL_POINT = "D2E23443-F1AD-4293-A70B-A9FD163F7B00"
    const val DATA_BUTTONS = "6C060578-D1D9-460A-B86F-EB97F01B2227"
    const val DATA_DRIVING_INFO = "BB1D5836-BF35-11ED-AFA1-0242AC120002"
    const val ASSIGNMENT_CONTROL = "4F5FBE6B-474B-4820-833F-95BFBF525D9F"
}
