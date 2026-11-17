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
    val instanceId: Int = 0,
)

data class GattCharacteristicInfo(
    val uuid: String,
    val properties: Int,
    val descriptors: List<String>,
    val instanceId: Int = 0,
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
    val instanceId: Int? = null,
)

data class CharacteristicReadResult(
    val startedAtMillis: Long,
    val completedAtMillis: Long,
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: Int,
    val value: List<Int>?,
    val statusCode: Int?,
    val error: String? = null,
)

data class MtuRequestState(
    val requestedMtu: Int? = null,
    val negotiatedMtu: Int = 23,
    val requestAccepted: Boolean? = null,
    val callbackStatus: Int? = null,
    val requestedAtMillis: Long? = null,
    val callbackAtMillis: Long? = null,
)

data class GattOperationState(
    val currentOperation: String? = null,
    val pendingQueueSize: Int = 0,
    val lastSuccessfulOperation: String? = null,
    val lastFailedOperation: String? = null,
    val lastErrorCode: Int? = null,
)

data class DiagnosticReadTarget(
    val serviceUuid: String,
    val characteristicUuid: String,
    val label: String,
    val pcx125ValueHex: String? = null,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

enum class ServiceDiscoveryState {
    NOT_STARTED,
    DISCOVERING,
    COMPLETE,
    FAILED,
}

enum class BondingState {
    NONE,
    BONDING,
    BONDED,
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

    const val VEHICLE_TYPE = "5738696D-45B3-475F-81D8-CC685D56EC18"
    const val DISPLAY_CAPABILITY = "0AD39E0B-B78D-4252-9917-1AA31A915DA7"
    const val SWITCH_TYPE = "DDD14E97-636D-4338-A798-94CF7CE13667"
    const val CATEGORY_ID = "3F033888-0211-472E-9405-6525D4E4DDC7"
    const val SERIES_CODE = "5074E0E8-006F-41AC-AA0F-DE6E4649A830"
    const val DESTINATION_ID = "55F7910B-8CDA-458B-9B54-8A7DA8CD79D4"

    const val DEVICE_INFORMATION_SERVICE = "0000180A-0000-1000-8000-00805F9B34FB"
    const val BATTERY_SERVICE = "0000180F-0000-1000-8000-00805F9B34FB"

    val vehicleInformationReadTargets = listOf(
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, VEHICLE_TYPE, "Vehicle type", "01"),
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, DISPLAY_CAPABILITY, "Display capability", "07"),
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, SWITCH_TYPE, "Switch type", "00"),
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, CATEGORY_ID, "Category ID", "01"),
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, SERIES_CODE, "Series code", "AE 05"),
        DiagnosticReadTarget(VEHICLE_INFO_SERVICE, DESTINATION_ID, "Destination ID", "00"),
    )
    val MANUFACTURER_NAME_READ_TARGET = DiagnosticReadTarget(
        DEVICE_INFORMATION_SERVICE,
        "00002A29-0000-1000-8000-00805F9B34FB",
        "Manufacturer name",
    )
}
