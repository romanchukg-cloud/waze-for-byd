#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build an isolated Android test APK, with fake delivery and no INTERNET permission."""
import argparse, os, pathlib, shutil, subprocess, zipfile
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-serial', required=True)
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',pathlib.Path.home()/'Library/Android/sdk'))
bt=sdk/'build-tools/36.0.0'; android=sdk/'platforms/android-35/android.jar'
out=root/'diagnostics-test-out'
assert not out.is_symlink()
if out.exists(): shutil.rmtree(out)
(out/'classes').mkdir(parents=True); (out/'dex').mkdir()
def run(*args, **kw):
    return subprocess.run(list(map(str,args)),check=True,**kw)
manifest=out/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.waze.diagnostics.tests">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>\n<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<application android:name="local.waze.diagnostics.DiagnosticsTestApplication" android:label="Waze diagnostics tests" android:debuggable="true" android:allowBackup="false">
<activity android:name="local.waze.diagnostics.SupportActivity" android:exported="true"/>
<provider android:name="local.waze.diagnostics.DiagnosticsExportProvider" android:authorities="local.waze.diagnostics.tests.export" android:exported="false"/>\n<service android:name="local.waze.diagnostics.DiagnosticsJob" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="true"/>
</application>
<instrumentation android:name="local.waze.diagnostics.DiagnosticsTests" android:targetPackage="local.waze.diagnostics.tests"/>
</manifest>''')
sources=sorted((root/'src/local/waze/diagnostics').glob('*.java'))
# The Support screen shows local cluster status; these classes need no Car App library.
sources += [root/'src/local/waze/cluster'/(n+'.java') for n in ('ClusterStatus','ClusterSettings','DiLink51Layout','ClusterActivityMonitor','ClusterActivityState','StandaloneHudGate')]
sources += [root/'test/DiagnosticsTests.java',root/'test/DiagnosticsTestApplication.java',root/'test/DiagnosticsExportProvider.java']
run('javac','-source','8','-target','8','-Xlint:-options','-cp',android,'-d',out/'classes',*sources)
run(bt/'d8','--min-api','29','--lib',android,'--output',out/'dex',*sorted((out/'classes').rglob('*.class')))
run(bt/'aapt2','link','-I',android,'--manifest',manifest,'-o',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'dex/classes.dex','classes.dex')
run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk')
assert os.environ.get('WAZE_KEYSTORE_PASSWORD'), 'Existing signing-key password required'
run(bt/'apksigner','sign','--ks',root/'test.keystore','--ks-key-alias','cluster-test','--ks-pass','env:WAZE_KEYSTORE_PASSWORD','--out',out/'tests.apk',out/'aligned.apk')
adb=sdk/'platform-tools/adb'
run(adb,'-s',a.android_serial,'install','-r',out/'tests.apk')
result=run(adb,'-s',a.android_serial,'shell','am','instrument','-w','local.waze.diagnostics.tests/local.waze.diagnostics.DiagnosticsTests',capture_output=True,text=True)
print(result.stdout)
assert 'checks passed; fake transport only' in result.stdout, 'Diagnostics tests failed'
