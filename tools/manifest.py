"""CI helper: hash the game files and the native app, write the bundled manifest and the update feed.
usage: manifest.py <version> <commit sha> <owner/repo>   (prints NATIVE_HASH=... for $GITHUB_ENV)"""
import hashlib, json, os, sys
ver, sha, repo = int(sys.argv[1]), sys.argv[2], sys.argv[3]
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
www = os.path.join(root, 'app/src/main/assets/www')
files = {}
for d, _, fs in os.walk(www):
    for f in fs:
        p = os.path.join(d, f); rel = os.path.relpath(p, www).replace(os.sep, '/')
        files[rel] = hashlib.sha1(open(p, 'rb').read()).hexdigest()
# the native part: anything that needs a new APK when it changes
h = hashlib.sha1()
for base in ['app/src/main/java', 'app/src/main/res', 'app/src/main/AndroidManifest.xml', 'app/build.gradle']:
    b = os.path.join(root, base)
    paths = [b] if os.path.isfile(b) else sorted(os.path.join(d, f) for d, _, fs in os.walk(b) for f in fs)
    for p in sorted(paths): h.update(os.path.relpath(p, root).encode()); h.update(open(p, 'rb').read())
native = h.hexdigest()[:16]
# the Windows app: its own code only (game files update without a reinstall)
hd = hashlib.sha1()
for rel in ['desktop/main.js', 'desktop/preload.js', 'desktop/package.json', 'desktop/build/icon.png']:
    fp = os.path.join(root, rel)
    if os.path.exists(fp): hd.update(rel.encode()); hd.update(open(fp, 'rb').read())
desktop = hd.hexdigest()[:16]
os.makedirs(os.path.join(root, 'desktop'), exist_ok=True)
json.dump({'hash': desktop, 'version': ver}, open(os.path.join(root, 'desktop', 'build-info.json'), 'w'))
json.dump({'version': ver, 'files': files}, open(os.path.join(root, 'app/src/main/assets/www-manifest.json'), 'w'), separators=(',', ':'))
os.makedirs(os.path.join(root, 'build'), exist_ok=True)
json.dump({'version': ver, 'nativeHash': native, 'commit': sha,
           'apk': f'https://github.com/{repo}/releases/download/v{ver}/AdLords.apk',
           'base': f'https://raw.githubusercontent.com/{repo}/{sha}/app/src/main/assets/www/',
           'files': files,
           **({'exe': f'https://github.com/{repo}/releases/download/v{ver}/AdLords-Setup.exe', 'desktopHash': desktop} if os.environ.get('HAS_EXE') == '1' else {})},
          open(os.path.join(root, 'build/update.json'), 'w'), separators=(',', ':'))
print(f'NATIVE_HASH={native}')
