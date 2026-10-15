#!/usr/bin/env python3
"""Build Waze for BYD from the pinned local APK and an existing signing key."""
import hashlib, os, pathlib, shutil, subprocess, zipfile
from manifest_label import rename_application
from manifest_support import add_support_components
root = pathlib.Path(__file__).resolve().parent
sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', pathlib.Path.home() / 'Library/Android/sdk'))
bt = sdk / 'build-tools/36.0.0'
android = sdk / 'platforms/android-35/android.jar'
apktool = pathlib.Path(os.environ.get('APKTOOL_JAR', '/opt/homebrew/Cellar/apktool/2.11.1/libexec/apktool_2.11.1.jar'))
original = pathlib.Path(os.environ.get('WAZE_INPUT_APK', root.parent / 'waze-5-22-90-123.apk')).expanduser().resolve()
key = pathlib.Path(os.environ.get('WAZE_KEYSTORE', root / 'test.keystore')).expanduser().resolve()
alias = os.environ.get('WAZE_KEY_ALIAS', 'cluster-test')
password = os.environ.get('WAZE_KEYSTORE_PASSWORD')
assert key.is_file(), 'Existing signing key required; refusing to generate a replacement'
assert password, 'Set WAZE_KEYSTORE_PASSWORD for the existing signing key'
os.environ.setdefault('WAZE_KEY_PASSWORD', password)
expected = 'a45040acce4dbf79c1445a76415b8e3457dd8a720d373f81c8a98c5529f08c80'
def run(*args): subprocess.run([str(x) for x in args], cwd=root, check=True)
assert hashlib.sha256(original.read_bytes()).hexdigest() == expected, 'Unexpected input APK'
if not (root / 'decoded').exists(): run('java', '-jar', apktool, 'd', '-r', original, '-o', 'decoded')
for generated in ('smali-overrides', 'classes', 'helper-dex'):
    directory = root / generated
    assert not directory.is_symlink(), f'Refusing to clean a symlink: {directory}'
    if directory.exists(): shutil.rmtree(directory)
source = root / 'decoded/smali_classes6/com/waze/mobile/WazeMobileApplication.smali'
s = source.read_text()
hook = '    invoke-static {p0}, Llocal/waze/cluster/ClusterHost;->install(Landroid/app/Application;)V'
if hook not in s:
    marker = '    invoke-super {p0}, Lcom/waze/ly;->onCreate()V'
    assert s.count(marker) == 1
    s = s.replace(marker, marker + '\n\n' + hook)
application_overlay = root / 'smali-overrides/classes6/com/waze/mobile/WazeMobileApplication.smali'
application_overlay.parent.mkdir(parents=True, exist_ok=True)
application_overlay.write_text(s)
state_source = root / 'decoded/smali_classes5/com/waze/car_lib/d/r.smali'
state = state_source.read_text()
state_hook = '    invoke-static {p0}, Llocal/waze/cluster/ClusterHost;->onClusterState(Ljava/lang/Object;)V'
assert state_hook not in state, 'Decoded cache must retain the original cluster state'
state_marker = '    iput-object p3, p0, Lcom/waze/car_lib/d/r;->c:Lcom/waze/car_lib/q/by;'
assert state.count(state_marker) == 1, 'Pinned cluster state hook location not found'
state_overlay = root / 'smali-overrides/classes5/com/waze/car_lib/d/r.smali'
state_overlay.parent.mkdir(parents=True, exist_ok=True)
state_overlay.write_text(state.replace(state_marker, state_marker + '\n\n' + state_hook))
if not (root / 'car-api.jar').exists():
    run('curl', '-fL', 'https://dl.google.com/dl/android/maven2/androidx/car/app/app/1.7.0/app-1.7.0.aar', '-o', 'car-app.aar')
    with zipfile.ZipFile(root / 'car-app.aar') as z: (root / 'car-api.jar').write_bytes(z.read('classes.jar'))
run('javac', '-cp', apktool, 'Assemble.java')
run('java', '-Xmx2g', '-cp', f'.:{apktool}', 'Assemble', 'decoded/smali_classes6', 'classes6.dex', 'smali-overrides/classes6')
run('java', '-Xmx2g', '-cp', f'.:{apktool}', 'Assemble', 'decoded/smali_classes5', 'classes5.dex', 'smali-overrides/classes5')
(root / 'classes').mkdir(exist_ok=True)
(root / 'helper-dex').mkdir(exist_ok=True)
run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', f'{android}:car-api.jar', '-d', 'classes', *sorted((root / 'src').rglob('*.java')))
run(bt / 'd8', '--min-api', '29', '--lib', android, '--classpath', 'car-api.jar', '--output', 'helper-dex', *sorted((root / 'classes').rglob('*.class')))
with zipfile.ZipFile(original) as src, zipfile.ZipFile(root / 'unsigned.apk', 'w') as dst:
    assert 'classes11.dex' not in src.namelist()
    for item in src.infolist():
        if item.filename.upper().startswith('META-INF/') and item.filename.upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')): continue
        data = (root / item.filename).read_bytes() if item.filename in ('classes5.dex', 'classes6.dex') else src.read(item)
        if item.filename == 'AndroidManifest.xml':
            data = add_support_components(rename_application(data, 'Waze for BYD'))
        dst.writestr(item, data)
    dst.write(root / 'helper-dex/classes.dex', 'classes11.dex', compress_type=zipfile.ZIP_DEFLATED)
run(bt / 'zipalign', '-f', '-P', '16', '4', 'unsigned.apk', 'aligned.apk')
output = 'Waze-for-BYD-5.22.90.123-test.6.apk'
run(bt / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias, '--ks-pass', 'env:WAZE_KEYSTORE_PASSWORD', '--key-pass', 'env:WAZE_KEY_PASSWORD', '--out', output, 'aligned.apk')
run(bt / 'apksigner', 'verify', '--verbose', '--print-certs', output)
run(bt / 'zipalign', '-c', '-P', '16', '4', output)
with zipfile.ZipFile(original) as src, zipfile.ZipFile(root / output) as dst:
    for name in src.namelist():
        if name in ('classes5.dex', 'classes6.dex', 'AndroidManifest.xml') or name.upper().startswith('META-INF/'): continue
        assert src.read(name) == dst.read(name), f'Unexpected modification: {name}'
    assert dst.read('AndroidManifest.xml') == add_support_components(rename_application(src.read('AndroidManifest.xml'), 'Waze for BYD'))
checksum = hashlib.sha256((root / output).read_bytes()).hexdigest()
(root / (output + '.sha256')).write_text(f'{checksum}  {output}\n')
print('Payload integrity verified; SHA-256', checksum)
