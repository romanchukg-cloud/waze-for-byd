# Installation requirements and troubleshooting

## Supported input and devices

- The APK requires Android 10 or later (`minSdkVersion=29`).
- It contains ARM64 (`arm64-v8a`) and ARMv7 (`armeabi-v7a`) native libraries.
- The stock guidance output targets DiLink 5 / Android 12 with the
  `com.byd.amapservice` adapter. The APK's minimum Android version does not
  establish guidance support on every vehicle or firmware.
- Package: `com.waze`; versionName: `5.22.90.123`; versionCode: `1030811`.
- It is a complete APK, not a split-APK bundle.

## Existing Waze installations

The modified APK is signed with this project's key, rather than Waze's official
key. An update requires a matching certificate and a compatible installed
version. Official Waze and modifications signed by other authors cannot be
updated in place with this APK. A newer installed version can also prevent an
update to this build.

Do not uninstall Waze, clear its data, force a downgrade or replace its signing
key as a troubleshooting step. Uninstalling erases local app data. Diagnose the
failure first; this project does not provide a migration that preserves official
Waze's private data across a signing-key change.

## Verify the downloaded APK

Download `Waze-for-BYD-5.22.90.123-test.5.apk` directly from the
[test.5 release](https://github.com/romanchukg-cloud/waze-for-byd/releases/tag/v5.22.90.123-byd.test.5).

Expected file size: **131,824,445 bytes**.

Expected SHA-256:
`e4586fdae35aabd0fc2efb76876a36ef16ddb563c35cc11fc65b735bb7f17f35`

The adjacent `.sha256` attachment contains the same checksum. If the downloaded
file has a different size or hash, download it again before attempting to install.
The published APK has a verified v3 signature and passes ZIP integrity and
16 KiB ZIP-alignment checks. It installed on the reference DiLink 5 / Android 12
vehicle and an Android API 35 emulator. This does not prove compatibility with
every OEM installer or firmware.

Signing-certificate SHA-256:
`50120cc57caa95d380fdf280e3a9c0853ed09ec8a617fe7bed33893c39b66276`

Android 9 and later support APK Signature Scheme v3; the absence of v1/v2
signatures alone is not evidence that this Android 10+ APK is invalid.
[Android APK Signature Scheme v3 documentation](https://source.android.com/docs/security/features/apksigning/v3)

## If the installer says the package is invalid

The generic dialog does not identify the underlying Package Manager error.
Do not conclude from the screenshot alone that the file is corrupted or that
there is a signing conflict. Report:

1. BYD model, model year and Android / DiLink version.
2. Whether Waze is already installed, its version and whether it is official
   or modified.
3. Download source, downloaded file size and SHA-256 when available.
4. The exact `INSTALL_FAILED_*` or `INSTALL_PARSE_FAILED_*` message from ADB,
   if ADB is available.

To attempt a normal in-place installation and obtain the precise result:

```sh
adb install --no-incremental --no-streaming -r Waze-for-BYD-5.22.90.123-test.5.apk
```

This command attempts an install/update. It does not uninstall the existing
application, clear its data or bypass a signing conflict. Select the intended
device with `adb -s SERIAL` when more than one device is connected. With older
ADB versions that do not recognize those flags, use `adb install -r` instead.

| Error | What to check |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app's signing certificate differs; do not remove it merely to bypass the error. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The installed versionCode is newer than this build. |
| `INSTALL_FAILED_OLDER_SDK` | The device runs an Android version below the APK's minimum. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The device does not support either included ARM ABI. |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` or a digest/signature failure | Check the exact message and compare the downloaded checksum; a device-specific verifier issue also needs investigation. |
| `INSTALL_FAILED_INVALID_APK` | Read the detailed text; this category has several possible packaging causes. |

Android defines separate errors for invalid packages, incompatible updates and
unsupported device requirements.
[Android Package Manager error definitions](https://android.googlesource.com/platform/frameworks/base/+/android12-release/core/java/android/content/pm/PackageManager.java)
