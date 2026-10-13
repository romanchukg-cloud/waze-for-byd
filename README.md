# Waze for BYD

Experimental built-in instrument-cluster host for Waze on BYD DiLink.

The project adds a local AndroidX Car App host to Waze 5.22.90.123. It opens
Waze's own cluster session on a display whose name contains
`XDJAScreenProjection`. The application label is **Waze for BYD**; the package
remains `com.waze`.

This is an independent experimental modification, with no affiliation to Waze,
Google or BYD. This repository contains the host source and build tools; the
original application, decoded application code, APKs and signing keys are local
inputs and are not included.

## Project website

[Visit Waze for BYD](https://romanchukg-cloud.github.io/waze-for-byd/) for the
APK download, compatibility requirements, test status and installation help.

## Download

Get the APK and SHA-256 checksum from the
[test.3 release](https://github.com/romanchukg-cloud/waze-for-byd/releases/tag/v5.22.90.123-byd.test.3).
See the [release notes](docs/releases/v5.22.90.123-byd.test.3.md) for validation
results, update requirements and known limitations.

**Before installing:** Android 10+ and an ARM64/ARMv7 device are required.
This APK uses package `com.waze` with our own signing certificate. It cannot
update official Waze or another modification signed with a different key.
See [installation requirements and troubleshooting](docs/installation.md),
including what to report if the installer says the package is invalid.

Project documentation and release notes are maintained in English. Additional
languages will be added as needed.

## Status

The preceding build completed a cluster handshake and received a
`NavigationTemplate` on an emulated display. The separate companion approach
was rejected by the original application's host validator and is not the active
implementation. Physical cluster rendering, long-running stability and Google
sign-in still require verification. The map currently stays open while the Waze
process is alive, rather than only during navigation.

## Turn-by-turn guidance (test.3)

Build test.3 adds the stock DiLink 5 AMap broadcast output for the maneuver
arrow, distance to turn, next road, remaining route distance and remaining time.
It targets `com.byd.amapservice` with `AUTONAVI_STANDARD_BROADCAST_SEND`.
The car draws the guidance card. On the reference Tang EV, the windshield HUD
mirrors the cluster; this behavior still needs validation with Waze for BYD.

The embedded host observes Waze's immutable cluster navigation state before
its template instructions/ETA gate, using Waze's own converters to AndroidX
`RoutingInfo`. It also handles AndroidX `Trip` callbacks and cluster templates
as fallback sources. Source callbacks, rather than cached-template polling,
refresh guidance freshness. No server flags are changed. The observer uses
version-specific names and is restricted to the SHA-256-pinned input APK.
A received template alone does not prove that guidance is available.

Changed frames send immediately; unchanged frames repeat every five seconds.
Ninety seconds without a new source update ends the guidance. Cancellation,
empty/loading guidance, session loss and orderly shutdown also clear the card.
A persisted ownership flag clears a card left by a process kill on the next
start. Abrupt process death cannot send an immediate end frame. Idle startup
without this flag does not clear another navigation app's card.

Unknown maneuver types use icon `0`. Metric and imperial distances are converted
to metres; remaining time is in seconds. Roundabouts preserve direction and exit
number. No arrival-time text, lanes, next-next maneuver or SOME/IP output is sent.
Guidance binding does not require the map display or a successful map overlay.

Use **Turn on by navi** to test the stock guidance card. The map still requires
**Small screen navi** or **Full screen navi**. Neither mode is changed by the app.
The current adapter target is DiLink 5 / Android 12; DiLink 3 and DiLink 5.1
transports are not implemented. Diagnostic tags: `WazeClusterTest` and
`WazeBydGuidance`. A successful broadcast is not an acknowledgment from the car.

### Tests

After obtaining `patch/car-api.jar` through the build, run:

```sh
python3 patch/test/run_tests.py
python3 patch/test/run_tests.py --android-serial emulator-5580
```

The JVM tests cover conversion, keepalive, expiry, ownership persistence and
failed-send recovery. Android tests also verify the exact intent keys and extra
types. Neither test sends a broadcast or starts navigation.

Parked vehicle testing confirmed state-observer callbacks, broadcast output and
cluster handshake. The owner reported a visible arrow card in native Waze and
confirmed cancellation cleared the cluster and HUD. Driving maneuvers, complete
field rendering and long-running stability remain unverified. Waze route-dialog
AppCompat theme errors were also recorded and need separate reproduction.

## Build

Requires Python 3, JDK 17+, Android SDK platform 35, build-tools 36.0.0,
apktool 2.11.1 and network access to Google Maven on the first build.

```sh
export ANDROID_SDK_ROOT=/path/to/android/sdk
export APKTOOL_JAR=/path/to/apktool_2.11.1.jar
export WAZE_INPUT_APK=/path/to/waze-5-22-90-123.apk
export WAZE_KEYSTORE=/path/to/existing/test.keystore
export WAZE_KEY_ALIAS=cluster-test
read -r -s WAZE_KEYSTORE_PASSWORD
export WAZE_KEYSTORE_PASSWORD
python3 patch/build.py
```

For a distinct key password, set `WAZE_KEY_PASSWORD` as well. The script refuses
to create a new signing key. The input APK must have SHA-256
`a45040acce4dbf79c1445a76415b8e3457dd8a720d373f81c8a98c5529f08c80`.

Output: `patch/Waze-for-BYD-5.22.90.123-test.3.apk` and its SHA-256 file.
The build verifies the signature, 16 KiB ZIP alignment and byte equality of all
original payload entries except `classes5.dex`, `classes6.dex`, the application label in
`AndroidManifest.xml`, and obsolete signature entries. It adds the host as
`classes11.dex`. DEX 5 contains one read-only state observer call; DEX 6 contains
the startup hook. Smali overlays leave the original decoded cache intact.
Resources and native libraries remain unchanged.

## Updates and device checks

Keep the exact signing key used for the installed modified APK. The package
and versionCode (`1030811`) remain unchanged. Update compatibility also depends
on the installed version and its signing certificate: verify those on the target
device before installation. An official Waze installation uses a different
certificate. Do not uninstall an existing app as part of this build workflow.

No install, data reset or vehicle setting change is performed by the script.
Select Small / Full screen navi on the cluster and check overlay permission
when testing. Diagnostic tag: `WazeClusterTest`.

## Source layout

- `patch/src/local/waze/cluster/ClusterHost.java`: embedded cluster host.
- `patch/src/local/waze/cluster/WazeGuidance.java`: AndroidX to AMap conversion.
- `patch/src/local/waze/cluster/WazeClusterState.java`: pinned Waze state observer.
- `patch/src/local/waze/cluster/GuidanceSession.java`: keepalive and source expiry.
- `patch/src/local/waze/cluster/BydGuidanceBridge.java`: stock adapter broadcast output.
- `patch/build.py`: pinned APK patch, assembly, signing and integrity checks.
- `patch/manifest_label.py`: targeted binary manifest label change.
- `patch/Assemble.java`: assemble the modified DEX with apktool's smali tools.
- `patch/test/VirtualCluster.java`: emulator display helper, not part of the APK.

The initial source was imported from the existing local cluster experiment.
Development continues in `patch/`; historical folders remain local and intact.


## Website maintenance

The GitHub Pages site is buildless HTML/CSS in `docs/index.html` and `docs/assets/`.
Pages publishes the `/docs` directory from `main`; `.nojekyll` disables Jekyll.
When a new APK is released, update the version, download URLs, file size,
checksum and test status on the page along with the release documentation.
The cluster illustration uses sample data and is explicitly labeled as a demo.
