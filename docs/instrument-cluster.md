# Instrument cluster settings

Build test.6 adds an **Instrument cluster** button to the top row of the Waze map,
left of Waze's music and sound buttons. It opens a panel that works like the one in
Google Maps for BYD: the same names, the same defaults where they apply to Waze, and
changes that apply immediately.
Settings are stored in Waze's private preferences and are never uploaded.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Show map on instrument cluster | On | Turning it off removes the Waze map from the cluster without restarting Waze. The cluster layer is cleared instead of keeping a stale frame. |
| Send turn guidance to cluster and HUD | On | Turning it off ends the guidance card on the cluster and HUD within a second. |
| HUD status line | — | The HUD transport in use: AMap on DiLink 5, or the standalone receiver on DiLink 5.1. Use only one HUD navigation app at a time. |
| Try standalone HUD on this firmware (experimental) | Off | Shown only on another DiLink 5.1 (Android 13) build whose HUD receiver passes every check. It applies after **Restart Waze to apply**. Turn off HUD output in OpenBYD or any other HUD app first. After you opt out, one end packet clears what a killed process left on the HUD. |
| Cluster map colours | — | Not a separate choice in Waze: the cluster follows Waze's own map colours. See below. |
| Pointer vertical offset (- up, + down) | 0 % | Moves the area that Waze uses to place the car marker, from -25 % to +25 % of the cluster height. 0 keeps the layout of earlier builds. |
| DiLink 5.1 cluster layout | Automatic / Scenario / Theme default | Shown on Android 13. See [DiLink 5.1 cluster map](dilink-5.1-cluster-map.md). |
| Support and diagnostics | — | Opens the Support screen with the local cluster and HUD status. |

Google Maps' route card, logo position and picture-in-picture settings have no
Waze counterpart, so they are not part of this panel.

## How cluster colours work in Waze

**Original behaviour.** Waze hard-codes a dark style for its cluster map, so the
cluster stayed dark whatever the car or Waze used.

**Test.6.** A pinned smali change in `car_lib/d/t` uses Waze's own Automatic mode
instead. The cluster now follows the same colours as the centre-screen map:

- Waze's map colour setting;
- **Apply for car display**;
- the head-unit theme.

The car session also receives the head unit's day or night theme, polled every two
seconds, because BYD can switch it without a configuration callback.

**Why there is no separate colour choice.** An independent Light, Dark, Auto or
Match screen option like Google Maps' was tried in test.6 candidates:

- **Map mode only.** Waze applies the cluster map mode only to map content, while the
  base map style is shared with the centre screen. Changing it live had no visible effect.
- **Session restart.** Recreating the car session to apply it crashed Waze's native
  map engine on the reference car.

**Status.** Support shows the car theme given to Waze and Waze's current map colours.

## Other changes in test.6

- **Route start.** It now shows a straight-ahead arrow. Earlier builds sent icon 0,
  which BYD draws as a finish flag.
- **Settings location.** The DiLink 5.1 layout settings moved from Support to this
  panel. Support keeps the status.

## After test.6 (not released yet)

These changes are in the source after test.6. So far they were checked only on an
Android 13 emulator at the car's resolution (2560 × 1440, 320 dpi), not in a car.

- **Button design.** The Instrument cluster button is now a 56 dp round button with a
  gauge icon, like Waze's music and sound buttons beside it: white with a dark icon by
  day, black with a light icon at night (colours from Waze's palette, following Waze's
  map colours).
- **Layering.** The button sits just above Waze's map layer. Waze's menu, sheets and
  route screens cover it the same way they cover Waze's own buttons. It also hides
  while a Waze panel in the map layer (such as the expanded ETA overview) covers its place.
- **Screen edge margin (Waze buttons and panels).** 0–48 dp, default 16 dp. It moves
  Waze's buttons, the Where to? sheet, the side menu and the ETA bar away from the
  left, right and bottom edges; the map still fills the screen. 0 keeps Waze's own layout.
  - Waze adds its own 16 dp beside any non-zero side inset, so the sides are never
    closer than 16 dp once the margin is on.
  - The side menu takes its height from the screen, not from the insets, so its panel is
    shortened instead.
  - Changes apply immediately.
- **Pointer horizontal offset (- left, + right).** −25 % to +25 % of the cluster width.
- **Cluster zoom test.** Zoom out and Zoom in buttons send a pinch to Waze's cluster
  map. Whether the cluster really zooms still needs checking in a car.

## Validation

**Reference car: DiLink 5.0 / Android 12.**

- Test.6 was installed as an update that kept the UID and app data.
- **Colours:** changing the head-unit theme switched both the centre screen and the
  cluster, as reported by the owner. The independent colour choice was removed after
  the owner saw no effect on the cluster.
- **Panel:** the Instrument cluster button and panel were visible on the head unit.

**Android 13 emulator:**

- map off and on for the DiLink 5 and 5.1 layouts;
- the pointer offset, also on the 5.1 Map theme;
- the guidance switch;
- 120 diagnostics checks.

**JVM:** 61 guidance, 207 HUD and 53 cluster layout and theme checks.

**Not yet confirmed on a car:**

- the pointer offset on the physical cluster;
- the map switch on the physical cluster;
- the route-start arrow while driving;
- the untested-firmware HUD opt-in;
- every DiLink 5.1 behaviour.
