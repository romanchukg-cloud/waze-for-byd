# Steering-wheel map zoom: research

Status on 2026-10-13: source inspection only. Steering-wheel zoom is **not
implemented or verified in Waze for BYD**. The published test.3 APK does not
include a wheel-key accessibility service.

## What renders the cluster map

The embedded host in `patch/src/local/waze/cluster/ClusterHost.java` starts
Waze's instrument-cluster Car App session in the `com.waze` process. It supplies
a `SurfaceView` through a `Presentation` on `XDJAScreenProjection`. Waze's own
native map engine draws the map; the host does not maintain a separate map or
receive a CarPlay video stream.

Source inspection of the pinned Waze 5.22.90.123 APK found this cluster path:

1. `com.waze.car_lib.d.n` starts the cluster map via `h.b.z.a`, creating
   `h.b.ac` and its `WazeMapRenderer` wrapper `h.b.n`.
2. `h.a.a` installs a one-to-many `SurfaceCallback` (`h.a.f`). Its renderer
   presenter `h.a.v.c` selects `h.a.y.g` for `h.b.n`.
3. `h.a.y.g` calls `h.a.t.d`, which registers the touch controller's callback.
   `h.a.y.f` supplies the renderer's map ID; surface dimensions come through
   `h.a.o`. Both are required to create the canvas gesture controller.
4. `h.a.n.onScale` (variant 0) calls `h.a.j.d(focusX, focusY, scaleFactor)`.
   That method creates two-point gesture events and multiplies their separation
   by the supplied scale factor.
5. `car_lib.q.z.b` builds this controller for the map ID through
   `map.isolated.b`. Its implementation `map.isolated.q` creates
   `NativeCanvasTouchController`, which queues events for the native canvas.

These obfuscated names describe this pinned APK only. Decompiled sources stay
local and are not part of this repository.

The candidate host entry point is the existing
`ISurfaceCallback.onScale(float, float, float)`. The corresponding public
[AndroidX SurfaceCallback API](https://developer.android.com/reference/androidx/car/app/SurfaceCallback#onScale(float,float,float))
defines a focal point in surface pixels and a current/previous scale ratio.
The callback chain provides a plausible same-process zoom path without shell
input injection. It does **not** prove that Waze's native cluster map accepts
the gesture or retains its scale during navigation. Both zoom directions,
automatic recentering/zoom, and isolation from the main map require a car test.

The geometry callback `h.a.g.onScale` is a no-op, but it is only one listener;
it does not by itself establish that the complete callback chain ignores zoom.
DiPlay's `changeMapZoomLevel` command is not applicable to this map.

## Accessibility and physical keys

Android supports key filtering with an AccessibilityService. The
[Android 12L platform source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.1.0_r1/core/java/android/accessibilityservice/AccessibilityServiceInfo.java)
requires both `FLAG_REQUEST_FILTER_KEY_EVENTS` and the XML capability
`canRequestFilterKeyEvents`; setting the flag alone is insufficient. Window
content retrieval is unnecessary for the proposed feature.

No car was connected during this research. `adb devices -l` returned no
devices, and connection to the previous endpoint timed out. Therefore the
following remain unverified for Waze for BYD:

- whether its own service can be registered, bound, and receive physical keys;
- which existing BYD/third-party accessibility services filter those keys;
- actual keyCode, scanCode, input-device name, source, and down/up sequence;
- enablement persistence through update, force-stop, and restart.

The supplied DiPlay report establishes reference values for its tested Tang,
not a Waze for BYD validation:

| Role | keyCode | scanCode | Input device |
| --- | --- | --- | --- |
| Toggle zoom mode | 305 | 300 | `simulate-keys` |
| Zoom in / volume up | 291 | 115 | `simulate-keys` |
| Zoom out / volume down | 292 | 114 | `simulate-keys` |

According to that report, BYD has no accessibility settings page on this car:
**initial service enablement requires ADB**. Add only the new component to the
current secure enabled-services list and preserve all existing entries.
DiPlay's local ADB authorization and force-stop recovery are separate features;
Waze for BYD currently implements neither. Do not claim automatic recovery.
Once bound, key filtering itself does not require ADB.

## Conditions for a future implementation

- Validate actual cluster-map visibility. A live Presentation/Surface does not
  establish that the instrument panel currently displays the Waze map. Current
  host-supplied visible-area coordinates do not measure BYD's selected mode.
  On unknown/ineligible state, keep the stock custom-key action and exit zoom.
- Latch consumption on initial key-down and retain it through repeats and
  key-up, including when eligibility, call state, or the timer changes midway.
- Outside zoom mode, pass volume through. When AudioManager mode is not NORMAL,
  pass new volume sequences through to preserve call-volume control.
- Support toggle mode and an optional five-second mode, rearmed by each zoom
  press. Allow role capture with a ten-second timeout, matching keyCode,
  scanCode and device name; do not persist transient device IDs.
- The supplied report says console volume is indistinguishable from wheel
  volume. Disclose that limitation if reproduced. Do not use phone/menu/short
  voice keys without evidence they reach the filter.
- Keep package and signing identity for an update preserving user data.
  Adding the service also requires compiled XML metadata and a manifest entry;
  the current label-only binary-manifest patch does not provide these.

## Parked-car validation before a feature release

1. Reconnect ADB, identify the actual installed APK/certificate and Android
   build, and inspect enabled/bound accessibility services without changing them.
2. Probe the existing cluster callback with a controlled in/out scale request;
   visually verify changes on the cluster and check the main display separately.
   A successful callback return is not visual proof.
3. Enable a diagnostic key service while preserving BYD's entries; log physical
   key-down/up, repeats, scanCode, device name and source before assigning roles.
   Shell-generated key events do not establish the physical wheel mapping.
4. Verify mode on: in/out zoom works and music volume is unchanged. Verify mode
   off, map hidden, expired timer and call state: normal volume behavior returns.
5. Verify compatible install-r and force-stop/restart behavior. Only claim
   restoration if implemented and demonstrated on this car.

The requested pre-build report is recorded here. No new APK, accessibility
settings changes, or release was produced during this offline research.
