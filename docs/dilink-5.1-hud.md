# DiLink 5.1 standalone HUD transport

Build test.4 adds a gated transport for the supplied DiPlay handoff. It has not
been verified on a physical DiLink 5.1 car.

## Difference from test.3

Test.3 only sends AUTONAVI_STANDARD_BROADCAST_SEND to com.byd.amapservice, with
AMap integer extras and five-second keepalives. The reported DiLink 5.1 firmware
instead expects explicit byd.hud.NAVIGATION broadcasts to
com.byd.clusterdebug/com.byd.clusterdebug.BroadcastReceiverCAN, containing one
String extra named normal, with comma-separated uppercase hex bytes.

The new transport runs as the app's normal UID with FLAG_RECEIVER_FOREGROUND.
The application uses no ADB, root, system signing or synthetic input.

## Gate

All checks must pass:

- Android API >=28.
- Exact Build.FINGERPRINT:
  BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys.
- System/updated-system package com.byd.clusterdebug, versionCode 10601004.
- Enabled application and component, exported receiver, no receiver permission.
- One current signing certificate, SHA-256:
  efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc.

The manifest queries this additional package for Android 11+ visibility.
This local firmware fingerprint is excluded from diagnostics. Unknown firmware
is not claimed compatible. When selected, standalone HUD replaces AMap output:
the app does not send both. Otherwise the existing AMap transport remains.

## Packets and lifecycle

Start: 43,E0,00,3A,01,02. End/clear: 43,E0,00,3A,01,01.
Guidance contains int32 big-endian records for distance (0x43F01018) and two
identical BYD turn codes (0x43F01010, 0x43F01030), each with length byte 04.
A right turn at 300 metres begins:

    43,F0,10,18,04,00,00,01,2C,43,F0,10,10,04,00,00,00,02,43,F0,10,30,04,00,00,00,02

AMap icons 2..28 use the supplied instrument mapping. Roundabout exits 1..10
preserve direction. Unknown arrows or invalid distances end an owned session;
no guessed arrow is sent. Distance must be in 0..16777214 metres.

Road names use at most 48 UTF-16 units, whitespace normalization, UTF-16LE bytes,
byte count, checksum, FF padding and seven-byte chunks in split-string records.
Surrogate pairs are preserved; one space clears an empty name. Names are sent
to the car for navigation and never included in diagnostics.

A 250 ms loop sends changes immediately and keeps guidance alive each second.
The source timeout remains 90 seconds, matching the observed parked Waze cadence.
Keepalive never refreshes source freshness. Cancellation, empty/unknown guidance,
session loss, shutdown and stale source clear an owned HUD session. Arrival depends
on Waze emitting an ended state; a destination icon alone does not prove completion.

A separate pending-clear marker is committed before start; next startup recovers
it with end. Crash/force-stop cannot clear immediately. Start is sent once before
the session's first guidance. Only one navigation app should write to the HUD.

## Evidence and remaining validation

The supplied report attributes physical arrows, distance and street-name validation
to DiPlay/DiAuto's maintainer. That is not a Waze for BYD vehicle test.
Reference:
[DiPlay packet encoder](https://github.com/shihabal3amri/DiPlay/blob/c30d3f0df18dd132739d864a9e054c73b3d57d8c/shared/src/main/java/com/shilapi/xcertplay/hud/BydStandalonePackets.kt),
[turn mapping](https://github.com/shihabal3amri/DiPlay/blob/c30d3f0df18dd132739d864a9e054c73b3d57d8c/shared/src/main/java/com/shilapi/xcertplay/hud/BydFactoryTurnCode.kt).
This implementation independently encodes the supplied wire contract.

Local tests cover vectors, text checksum/chunks, gating, timing, expiry, recovery
and actual Android Intent types. They never broadcast to a car. Still required:
real receiver identity, normal-app-UID dispatch, visible turn/distance/street/end
behavior and stability on the specified firmware. A completed sendBroadcast call
is not display proof.
