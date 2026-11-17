# RoadSync Plus

RoadSync Plus is an Android BLE research prototype for inspecting the Honda PCX160 RoadSync Bluetooth unit. It scans, connects, discovers GATT structure, subscribes to explicitly selected notifications, and exports diagnostic reports.

## Safety and scope

- The app does not implement Honda authentication, key exchange, registration, commands, pairing management, or characteristic writes.
- Notification toggles only write the standard Client Characteristic Configuration Descriptor (CCCD) to enable or disable subscription.
- DFU is excluded from notification subscription and characteristic reads. Its discovered metadata is displayed only; do not interact with or write to the DFU service.
- Do not use this app to experiment with screen drawing or unverified registration commands.
- ScooterLink documents a PCX125 2025, not a PCX160. Its UUIDs and expected properties in Protocol Explorer are comparison references only; this project makes no compatibility claim.

## Features

- Honda BTU scan filter, with an option to show every discovered BLE device.
- Connect/disconnect and complete service, characteristic, and descriptor discovery.
- Connection-state, MTU, characteristic-property, and timestamped notification monitoring.
- Individual notification payload copying.
- Export as a plain-text report through Android's document picker.
- Protocol Explorer compares documented UUIDs plus the six Vehicle Information characteristic UUIDs observed in the supplied PCX160 report. ScooterLink abbreviates those UUIDs and its documented values remain PCX125 references.
- Connection Details can explicitly read the Vehicle Information values, Security.Status, readable Device Information characteristics, and Battery Level, and can explicitly request MTU 247.

## Build

Open the project in Android Studio, set **Gradle JDK** to Java 17, and allow Gradle sync. (This project uses Android Gradle Plugin 8.7.2 / Gradle 8.9, which require a Java 17 Gradle runtime.) The project uses Kotlin and Jetpack Compose. Build the `app` configuration from Android Studio or with the included Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

Grant Nearby Devices permissions when prompted. On Android versions before 12, location permission is required by the platform BLE scan API.

## Using the prototype

1. Turn on the motorcycle and open Device Scanner.
2. Scan for `HONDA BTU` or enable **Show all BLE devices**.
3. Select a device and connect. Discovery is automatic.
4. Inspect the discovered GATT tree and Protocol Explorer comparisons.
5. In Connection Details, tap **Read diagnostic values** to read the supported values sequentially, or explicitly tap **Request MTU 247**.
6. In Notification Monitor, explicitly enable notifications only for characteristics advertising Notify or Indicate.
7. Export a report from the top-bar action.

The app does not automatically pair, authenticate, modify registrations, or send application data. Characteristic reads and MTU negotiation occur only after a user action. The app does not send characteristic writes; notification toggles write only the standard CCCD descriptor. Whether Android displays a system pairing prompt depends on the peripheral and Android platform behavior; the app itself does not initiate pairing.

## Protocol reference

- [ScooterLink repository](https://github.com/gdorgian/ScooterLink)
- [ScooterLink PCX 2025 protocol notes](https://github.com/gdorgian/ScooterLink/blob/main/docs/PROTOCOL.md)
- [HondaCB350RS Reddit discussion](https://www.reddit.com/r/HondaCB350RS/s/NNyreHWtHx) (not retrievable from the research fetch due to Reddit returning HTTP 403)

The PCX160 must be inspected on-device before any compatibility conclusions are drawn.
