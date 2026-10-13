# Stock BYD guidance output

The reference transport is the stock AMap adapter on a 2024 Tang EV with
DiLink 5 / Android 12. Its behavior was supplied from the DiPlay integration.
Successful `sendBroadcast` calls do not confirm instrument-cluster or HUD output.
Other firmware and vehicle models need their own visual checks.

Every intent uses action `AUTONAVI_STANDARD_BROADCAST_SEND`, package
`com.byd.amapservice` and flag `0x01000000` (include background receivers).

| Extra | Android type | Guidance frame | End frame |
|---|---|---|---|
| `KEY_TYPE` | Int | `10001` | `10019` |
| `IS_BYD_MAP` | Boolean | `true` | `true` |
| `IS_BYD_BAIDU_MAP` | Boolean | `false` | `false` |
| `TYPE` | Int | `0` | Omitted |
| `EXTRA_STATE` | Int | `0` | `9` |
| `EXTRA_IS_FOREGROUND` | Int | `0` | `1` |
| `NEW_ICON` | Int | AMap maneuver code | `-1` |
| `ROUNG_ABOUT_NUM` | Int | Exit number for roundabout entry, otherwise `0` | Omitted |
| `SEG_REMAIN_DIS` | Int | Metres to the maneuver, `-1` if unavailable | `-1` |
| `NEXT_ROAD_NAME` | String | Road after the current maneuver, or empty string | Empty string |
| `ROUTE_REMAIN_DIS` | Int | Metres to the final destination, `-1` if unavailable | `-1` |
| `ROUTE_REMAIN_TIME` | Int | Seconds to the final destination, `-1` if unavailable | `-1` |

The spelling `ROUNG_ABOUT_NUM` is intentional. `ETA_TEXT`, lane information and
the maneuver after next are not sent.

| AMap code | Maneuver |
|---|---|
| `0` | Unknown or unspecified direction |
| `2`, `3` | Left, right |
| `4`, `5` | Slight left, slight right (also directional keep/fork/merge) |
| `6`, `7` | Sharp left, sharp right |
| `8`, `19` | U-turn left, U-turn right |
| `9` | Straight |
| `11`, `17` | Enter roundabout counterclockwise, clockwise |
| `12`, `18` | Leave roundabout counterclockwise, clockwise |
| `15` | Destination |

Combined AndroidX enter-and-exit roundabout instructions use the entry code
with the supplied exit number. Ramps retain the corresponding turn direction
and severity. Unknown types, departure, name changes, ferries and merges with
unspecified direction use `0` rather than inventing a straight instruction.

## Source and lifecycle

The primary source is a read-only observer call after Waze's immutable cluster
state (`com.waze.car_lib.d.r`) has initialized its fields. It reads the real
navigation-active flag, route estimate and current turn, then calls Waze's own
AndroidX converters. This happens before the template instructions/ETA gate.
The hook is version-specific and restricted to the SHA-256-pinned APK. Generated
smali overlays leave the shared decoded sources intact.

AndroidX `Trip` and `NavigationTemplate` / `RoutingInfo` remain fallback sources.
A fresh raw state takes priority over those sources; a fresh Trip takes priority
over templates. No primary Android Auto session is created and no server flags
are patched.

The map session binds even without an available projection display or overlay.
Map display loss only tears down the presentation. Binding failure, session
closure, empty/loading guidance and navigation-ended callbacks clear guidance.
Callbacks from older binding generations are ignored.

New source callbacks refresh a 90-second monotonic expiry clock. Parked Waze
was observed emitting unchanged snapshots about once per minute, so a 15-second
deadline would incorrectly expire healthy stationary guidance. Frames send
immediately when any value changes; a one-second scheduler resends unchanged
frames after five seconds. Keepalives do not refresh source freshness. Cached
templates are not polled to manufacture fresh data, and a template response
delayed by 90 seconds is discarded. A stalled source causes an end frame.

Ownership is committed to private preferences before the first guidance frame.
An end frame clears this marker only after a successful send. Send or persistence
failures retry on subsequent ticks. Startup recovery sends an end frame when
the marker survives a process kill. Idle startup without a marker does not clear
another navigation app's card. A force-stop or kill cannot run cleanup code;
the previous card can remain until the next start.

Waze cluster instruction flags can suppress `RoutingInfo` in templates. This
was observed as no guidance broadcasts during the first parked test; it motivated
the state observer. If the cluster navigation pipeline itself is disabled or
stops emitting data, the bridge still has no fresh turn data and does not fabricate
an arrow. Check the source and actual cluster, rather than treating a valid
template as a successful test.

## Vehicle validation

With the vehicle parked, update the existing modified APK using the same
certificate. Keep the app installed to retain its data. Start a real Waze route
and select **Turn on by navi** with the steering-wheel menu. Check the arrow,
distance, road and totals on the cluster and the arrow on the windshield HUD.
Cancel the route and confirm both clear. Restart after a process kill and confirm
the stale card is cleared. The app does not switch cluster modes.

Changing instructions and distance countdown while driving require a later
driving test with a passenger observing; parked checks cannot establish this.
