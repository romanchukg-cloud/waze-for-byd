# DiLink 5.1 cluster map layout

Build test.5 adds a cluster map layout for DiLink 5.1 / Android 13. It has not
been verified on a physical DiLink 5.1 car. The geometry comes from measurements
by DiPlay's maintainer on one firmware build. It was then checked in Waze on an
Android 13 emulator with display layers that match the vehicle's.

## When it is used

**Settings → Support · Waze for BYD → Cluster map layout → Layout profile**:

- **Automatic** (default): DiLink 5.1 layout only on a verified firmware build.
  Every other car keeps the existing DiLink 5 layout without change.
- **DiLink 5.1 layout, also on unverified firmware**: for testers with a different
  5.1 build. This is an explicit owner choice, and it can be switched back at any time.
- **DiLink 5 layout**: the previous behaviour, also on verified firmware.

Verified fingerprints, as in DiPlay:

    BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys
    BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.021352:user/release-keys

The second build is a Leopard 5 Ultra build that DiPlay supports with the same layers.

The DiLink 5 layout is unchanged from test.4. It uses the first display whose
name contains `XDJAScreenProjection` and a full-size opaque map, with the
previous visible area at 35–64 % × 16–75 %.

## Layers and geometry

The cluster is composed from 1920×720 presentation displays owned by
`com.byd.containerservice`. Our DiLink 5.0 reference car exposes the same three
layers, at 320 dpi:

- `fission_bg_XDJAScreenProjection`
- `shared_fission_bg_XDJAScreenProjection_0`
- `shared_fission_bg_XDJAScreenProjection_1`

| Cluster theme | Display | Waze surface inside 1920×720 | Waze visible area |
|---|---|---|---|
| Map (full width) | `_0` | x 0, y 144, 1920×480 | 460,64 – 1460,432 |
| Scenario (right side card) | `_1` | x 1320, y 0, 600×720 | whole card |
| Simple (right side card) | `_1` | x 1320, y 0, 600×720 | whole card |

- **Native size:** Waze renders at the surface size, so no video crop is needed.
- **Display checks:** a display is accepted only with the exact name and a size of 1920×720.
- **No fallback:** a missing `_1` is never replaced with `_0`, because the full layer would hide Scenario/Simple.
- **Map theme:** the stock speed/status and gear/range readouts sit above and below
  the map strip. The rest of the display gets a backdrop with DiPlay's measured
  fades: 64 px at the top, 48 px at the bottom and 460 px side panels. The backdrop
  follows **Map theme background**: theme default (light), light or dark.
- **Side card:** everything outside x 1320–1920 stays transparent, with no shading.

## Theme selection

**DiLink 5.1 cluster theme** offers a fixed Scenario, Map or Simple theme (default:
Scenario), or **Follow the cluster automatically**.

Follow mode reads stock cluster activity events through Android Usage Access. It
polls every 500 ms, with a 2-second overlap and deduplication.

| Stock activity | Meaning |
|---|---|
| `com.byd.automap` or `com.byd.launchermap` / `com.byd.automap.meter.MeterActivity` | Map theme, full map |
| same packages / `com.byd.automap.meter.MeterSmallScreenActivity` | mini-map card |
| `com.byd.sr` / `com.byd.sr.cluster.ClusterActivity` | Scenario theme |
| `com.byd.cluster` / `com.byd.cluster.SimpleClusterDynastyActivity` | Simple theme |

How Follow mode reads these events:

- **Visibility:** RESUMED shows an activity and STOPPED hides it. A paused activity stays visible.
- **Reset:** device startup and shutdown events clear the state.
- **Which map:** the Map theme shows the full map. Otherwise the side card is shown
  only while the mini-map is visible.
- **No guessing:** missing access or no events means no map.

DiLink 5.1 has no working Usage Access settings page, so it is granted once from a computer:

    adb shell appops set com.waze GET_USAGE_STATS allow

Replace `allow` with `default` to revoke it. The manifest declares
`PACKAGE_USAGE_STATS`; installing the APK does not grant it.

## Clearing the cluster layers

A virtual display keeps showing its last buffer after its only window is removed.
On the emulator this left a stale map on a layer after a theme change.

Test.5 never leaves a DiLink 5.1 layer empty:

- **Hiding a map:** the SurfaceView itself is hidden and the window stays as a
  transparent, non-touchable layer.
- **Replacement:** that window is dismissed only after a replacement is shown on the
  same display.
- **After a crash:** at startup, each `_0`/`_1` layer that the map does not use gets
  one transparent window once, to cover frames left by a previous crash or force-stop.

Changing the theme or background recreates only the presentation. The Waze
cluster session stays bound.

## Support status

The Support screen shows a local status that is never uploaded. A screenshot of it
is the most useful test report.

- firmware fingerprint and the active layout;
- Usage Access and the detected stock cluster state;
- the chosen display, viewport, Waze surface size and visible area;
- every display with its size and whether it is a presentation display;
- each DiLink 5.1 HUD gate check: firmware, receiver version, system/enabled/exported,
  permission and signer.

Diagnostics reports still carry only the existing projection event
(display id, width, height). The logcat tag is `WazeClusterTest`.

## Validation

**Emulator: Android 13 / API 33, three 1920×720 320 dpi layers.**

- Waze rendered into a 600×720 surface on `_1` and a 1920×480 surface on `_0`.
- Captured layer pixels confirmed:
  - **Side card:** transparency outside the card.
  - **Map theme:** the backdrop, the fades and an open map strip.
  - **Clearing:** a fully transparent previous layer after each theme change, and after a force-stop and restart.
- **Follow mode:** stub activities with the stock package and class names produced
  real Usage Access events:
  - without access: hidden;
  - Scenario with the mini-map closed: hidden;
  - mini-map open: side card;
  - Map theme: full width;
  - every activity stopped, or access revoked: hidden.
- The DiLink 5 profile produced the earlier full-display surface and visible area.
- **Tests:** 39 JVM layout and state checks; 60 guidance and 200 HUD checks; Android
  intent checks; 120 diagnostics checks on API 33.

The emulator's Waze rendered no map tiles. Map content, legibility of the stock
readouts, real stock activity events and the behaviour of stale layers on the car
need a physical DiLink 5.1 test.

Reference: DiPlay
[DiLink51ClusterLayout](https://github.com/shihabal3amri/DiPlay/blob/3f554012a5a60817ef18e3e4fcdb2ff9ed8a7069/common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterLayout.kt),
[ClusterActivityState](https://github.com/shihabal3amri/DiPlay/blob/3f554012a5a60817ef18e3e4fcdb2ff9ed8a7069/common/src/main/java/com/shilapi/xcertplay/ClusterActivityState.kt),
[DiLink51ClusterMonitor](https://github.com/shihabal3amri/DiPlay/blob/3f554012a5a60817ef18e3e4fcdb2ff9ed8a7069/common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterMonitor.kt).
Waze for BYD implements the same contract in its own code.
