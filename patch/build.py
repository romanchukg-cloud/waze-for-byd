#!/usr/bin/env python3
"""Build Waze for BYD from the pinned local APK and an existing signing key."""
import hashlib, os, pathlib, subprocess, zipfile
from manifest_label import rename_application
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
source = root / 'decoded/smali_classes6/com/waze/mobile/WazeMobileApplication.smali'
s = source.read_text()
hook = '    invoke-static {p0}, Llocal/waze/cluster/ClusterHost;->install(Landroid/app/Application;)V'
if hook not in s:
    marker = '    invoke-super {p0}, Lcom/waze/ly;->onCreate()V'
    assert s.count(marker) == 1
    source.write_text(s.replace(marker, marker + '\n\n' + hook))
if not (root / 'car-api.jar').exists():
    run('curl', '-fL', 'https://dl.google.com/dl/android/maven2/androidx/car/app/app/1.7.0/app-1.7.0.aar', '-o', 'car-app.aar')
    with zipfile.ZipFile(root / 'car-app.aar') as z: (root / 'car-api.jar').write_bytes(z.read('classes.jar'))
run('javac', '-cp', apktool, 'Assemble.java')
run('java', '-Xmx2g', '-cp', f'.:{apktool}', 'Assemble', 'decoded/smali_classes6', 'classes6.dex')
(root / 'classes').mkdir(exist_ok=True)
(root / 'helper-dex').mkdir(exist_ok=True)
run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', f'{android}:car-api.jar', '-d', 'classes', 'src/local/waze/cluster/ClusterHost.java')
run(bt / 'd8', '--min-api', '29', '--lib', android, '--classpath', 'car-api.jar', '--output', 'helper-dex', *sorted((root / 'classes').rglob('*.class')))
with zipfile.ZipFile(original) as src, zipfile.ZipFile(root / 'unsigned.apk', 'w') as dst:
    assert 'classes11.dex' not in src.namelist()
    for item in src.infolist():
        if item.filename.upper().startswith('META-INF/') and item.filename.upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')): continue
        data = (root / 'classes6.dex').read_bytes() if item.filename == 'classes6.dex' else src.read(item)
        if item.filename == 'AndroidManifest.xml':
            data = rename_application(data, 'Waze for BYD')
        dst.writestr(item, data)
    dst.write(root / 'helper-dex/classes.dex', 'classes11.dex', compress_type=zipfile.ZIP_DEFLATED)
run(bt / 'zipalign', '-f', '-P', '16', '4', 'unsigned.apk', 'aligned.apk')
output = 'Waze-for-BYD-5.22.90.123-test.2.apk'
run(bt / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias, '--ks-pass', 'env:WAZE_KEYSTORE_PASSWORD', '--key-pass', 'env:WAZE_KEY_PASSWORD', '--out', output, 'aligned.apk')
run(bt / 'apksigner', 'verify', '--verbose', '--print-certs', output)
run(bt / 'zipalign', '-c', '-P', '16', '4', output)
with zipfile.ZipFile(original) as src, zipfile.ZipFile(root / output) as dst:
    for name in src.namelist():
        if name in ('classes6.dex', 'AndroidManifest.xml') or name.upper().startswith('META-INF/'): continue
        assert src.read(name) == dst.read(name), f'Unexpected modification: {name}'
    assert dst.read('AndroidManifest.xml') == rename_application(src.read('AndroidManifest.xml'), 'Waze for BYD')
checksum = hashlib.sha256((root / output).read_bytes()).hexdigest()
(root / (output + '.sha256')).write_text(f'{checksum}  {output}\n')
print('Payload integrity verified; SHA-256', checksum)
