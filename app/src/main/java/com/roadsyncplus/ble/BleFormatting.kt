package com.roadsyncplus.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun hasBluetoothPermissions(context: Context): Boolean {
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

fun propertyNames(properties: Int): String {
    val names = mutableListOf<String>()
    if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names += "Broadcast"
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "Read"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "WriteNoResponse"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "Write"
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "Notify"
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "Indicate"
    if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names += "SignedWrite"
    if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) names += "Extended"
    return names.joinToString(", ").ifEmpty { "None" }
}
