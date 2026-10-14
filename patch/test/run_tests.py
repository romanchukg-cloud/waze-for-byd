#!/usr/bin/env python3
"""Test conversion/lifecycle on JVM; optionally test Intent types on Android.

The Android tests construct intents but never send broadcasts or start Waze.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-serial', help='Optional adb serial, e.g. emulator-5580')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk'))
android = sdk / 'platforms/android-35/android.jar'
bt = sdk / 'build-tools/36.0.0'
out = root / 'test-out'
if out.exists():
    shutil.rmtree(out)
(out / 'classes').mkdir(parents=True)
sources = [root / 'src/local/waze/cluster' / (name + '.java') for name in
           ('GuidanceFrame', 'GuidanceSession', 'WazeGuidance', 'BydGuidanceBridge', 'StandaloneHudPackets', 'StandaloneHudGate', 'StandaloneHudOutput',
            'DiLink51Layout', 'ClusterActivityState', 'ClusterActivityMonitor', 'ClusterSettings', 'ClusterStatus')]
sources += sorted((root / 'src/local/waze/diagnostics').glob('*.java'))
sources += [root / 'test/GuidanceTests.java', root / 'test/GuidanceAndroidTests.java', root / 'test/StandaloneHudTests.java',
            root / 'test/ClusterLayoutTests.java']

def run(*command):
    subprocess.run(list(map(str, command)), check=True)

run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp',
    f'{android}:{root / "car-api.jar"}', '-d', out / 'classes', *sources)
run('java', '-cp', f'{out / "classes"}:{root / "car-api.jar"}:{android}', 'local.waze.cluster.GuidanceTests')
run('java', '-cp', f'{out / "classes"}:{root / "car-api.jar"}:{android}', 'local.waze.cluster.StandaloneHudTests')
run('java', '-cp', f'{out / "classes"}:{root / "car-api.jar"}:{android}', 'local.waze.cluster.ClusterLayoutTests')
if args.android_serial:
    (out / 'dex').mkdir()
    run(bt / 'd8', '--min-api', '29', '--lib', android, '--output', out / 'dex',
        *sorted((out / 'classes').rglob('*.class')), root / 'car-api.jar')
    remote = '/data/local/tmp/waze-for-byd-guidance-tests.dex'
    adb = sdk / 'platform-tools/adb'
    run(adb, '-s', args.android_serial, 'push', out / 'dex/classes.dex', remote)
    try:
        run(adb, '-s', args.android_serial, 'shell', f'CLASSPATH={remote}', 'app_process',
            '/system/bin', 'local.waze.cluster.GuidanceAndroidTests')
    finally:
        run(adb, '-s', args.android_serial, 'shell', 'rm', '-f', remote)
