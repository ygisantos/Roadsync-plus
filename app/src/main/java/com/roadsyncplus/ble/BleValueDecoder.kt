package com.roadsyncplus.ble

import java.nio.charset.StandardCharsets
import java.util.Locale

fun formatHex(bytes: List<Int>): String =
    bytes.joinToString(" ") { it.toString(16).padStart(2, '0').uppercase(Locale.ROOT) }

fun decodeCharacteristicValue(
    serviceUuid: String,
    characteristicUuid: String,
    bytes: List<Int>,
): String {
    val service = serviceUuid.uppercase(Locale.ROOT)
    val characteristic = characteristicUuid.uppercase(Locale.ROOT)
    val hex = formatHex(bytes)

    if (service == HondaProtocolReference.DEVICE_INFORMATION_SERVICE &&
        characteristic in DEVICE_INFORMATION_TEXT_CHARACTERISTICS
    ) {
        val text = String(bytes.map(Int::toByte).toByteArray(), StandardCharsets.UTF_8)
            .trimEnd('\u0000')
        if (text.isNotBlank() && text.none(Char::isISOControl)) return text
    }

    if (service == HondaProtocolReference.DEVICE_INFORMATION_SERVICE &&
        characteristic == PNP_ID_CHARACTERISTIC &&
        bytes.size == 7
    ) {
        fun littleEndian16(offset: Int): Int = bytes[offset] or (bytes[offset + 1] shl 8)
        val source = when (bytes[0]) {
            1 -> "Bluetooth SIG"
            2 -> "USB-IF"
            else -> "unknown source ${bytes[0]}"
        }
        return "Vendor source $source; vendor ID 0x${littleEndian16(1).toString(16).uppercase(Locale.ROOT)}; " +
            "product ID 0x${littleEndian16(3).toString(16).uppercase(Locale.ROOT)}; " +
            "product version 0x${littleEndian16(5).toString(16).uppercase(Locale.ROOT)}"
    }

    if (service == HondaProtocolReference.BATTERY_SERVICE &&
        characteristic == BATTERY_LEVEL_CHARACTERISTIC &&
        bytes.size == 1
    ) {
        val level = bytes.single()
        return if (level in 0..100) "$level%" else "Out-of-range battery value ($level)"
    }

    if (service == HondaProtocolReference.SECURITY_SERVICE &&
        characteristic == HondaProtocolReference.SECURITY_STATUS &&
        bytes.size == 1
    ) {
        return when (bytes.single()) {
            0 -> "0x00 — no stored key (per PCX125 reference)"
            1 -> "0x01 — stored key present (per PCX125 reference)"
            else -> "Unrecognized status 0x$hex"
        }
    }

    if (service == HondaProtocolReference.VEHICLE_INFO_SERVICE) {
        val label = HondaProtocolReference.vehicleInformationReadTargets
            .firstOrNull { it.characteristicUuid == characteristic }
            ?.label
        if (label != null) {
            val unsignedValues = bytes.joinToString(", ")
            return if (bytes.size == 1) {
                "$label code ${bytes.single()} (0x$hex)"
            } else {
                "$label bytes [$unsignedValues]"
            }
        }
    }

    return "Unsigned bytes [${bytes.joinToString(", ")}]"
}

private const val BATTERY_LEVEL_CHARACTERISTIC = "00002A19-0000-1000-8000-00805F9B34FB"
private const val PNP_ID_CHARACTERISTIC = "00002A50-0000-1000-8000-00805F9B34FB"

private val DEVICE_INFORMATION_TEXT_CHARACTERISTICS = setOf(
    "00002A29-0000-1000-8000-00805F9B34FB",
    "00002A24-0000-1000-8000-00805F9B34FB",
    "00002A25-0000-1000-8000-00805F9B34FB",
    "00002A27-0000-1000-8000-00805F9B34FB",
    "00002A26-0000-1000-8000-00805F9B34FB",
    "00002A28-0000-1000-8000-00805F9B34FB",
)
