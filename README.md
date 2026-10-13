# Waze for BYD

Experimental built-in instrument-cluster host for Waze on BYD DiLink.

[Українська документація](README.uk.md)

The project adds a local AndroidX Car App host to Waze 5.22.90.123. It opens
Waze's own cluster session on a display whose name contains
`XDJAScreenProjection`. The application label is **Waze for BYD**; the package
remains `com.waze`.

This is an independent experimental modification, with no affiliation to Waze,
Google or BYD. This repository contains the host source and build tools; the
original application, decoded application code, APKs and signing keys are local
inputs and are not included.

## Status

The preceding build completed a cluster handshake and received a
`NavigationTemplate` on an emulated display. The separate companion approach
was rejected by the original application's host validator and is not the active
implementation. Physical cluster rendering, long-running stability and Google
sign-in still require verification. The map currently stays open while the Waze
process is alive, rather than only during navigation.

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

Output: `patch/Waze-for-BYD-5.22.90.123-test.2.apk` and its SHA-256 file.
The build verifies the signature, 16 KiB ZIP alignment and byte equality of all
original payload entries except `classes6.dex`, the application label in
`AndroidManifest.xml`, and obsolete signature entries. It adds the host as
`classes11.dex`. Resources and native libraries remain unchanged.

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
- `patch/build.py`: pinned APK patch, assembly, signing and integrity checks.
- `patch/manifest_label.py`: targeted binary manifest label change.
- `patch/Assemble.java`: assemble the modified DEX with apktool's smali tools.
- `patch/test/VirtualCluster.java`: emulator display helper, not part of the APK.

The initial source was imported from the existing local cluster experiment.
Development continues in `patch/`; historical folders remain local and intact.
