"""Materialize the pinned phone runtime and owned adapter into the Android module."""
import argparse
import hashlib
import gzip
import io
import json
from pathlib import Path
import shutil
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DSHA_SHA = 'd62f17f23a7949f0f25285ec7afa44cb35de24a6bc91887ba4600023d18ffee0'

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, default=ROOT/'.tmp/fish-dev-20261029/dsha-upstream.apk')
    args = parser.parse_args()
    if digest(args.input) != DSHA_SHA:
        raise SystemExit('DSHA input APK checksum mismatch')
    assets = ROOT/'agent-runtime/src/main/assets/fish'
    native = ROOT/'agent-runtime/src/main/jniLibs/arm64-v8a'
    assets.mkdir(parents=True, exist_ok=True)
    native.mkdir(parents=True, exist_ok=True)
    archives = []
    with zipfile.ZipFile(args.input) as apk:
        for name in ['offline-rootfs.bin', 'dsh-runtime.bin', 'python-support.bin', 'glibc-python.bin']:
            target = assets/name
            with apk.open('assets/'+name) as src, target.open('wb') as dst:
                shutil.copyfileobj(src, dst)
            archives.append(dict(name=name, sha256=digest(target), strip=0))
        for name in ['libproot.so','libprootloader.so','libtalloc.so','libandroidshmem.so']:
            (native/name).write_bytes(apk.read('lib/arm64-v8a/'+name))
    # AAPT transparently inflates .gz assets and strips the suffix. Keep gzip bytes
    # under .bin so the packaged name and SHA match the runtime lock exactly.
    overlay = assets/'time-agent-overlay.bin'
    (assets/'time-agent-overlay.tar.gz').unlink(missing_ok=True)
    unpacked = ROOT/'.tmp/fish-dev-20261029/time-agent-overlay.tar'
    official_modules = []
    with tarfile.open(unpacked, 'w', format=tarfile.PAX_FORMAT) as tar:
        # The DSHA release patches these core files to require its separate native
        # consent host. This app uses the same pinned official DSH modules and its
        # own Repository tools; keep the Android/arm64 dependencies from DSHA.
        deps = ROOT/'.tmp/fish-dev-20261029/host-test/node_modules'
        npm_lock = json.loads((deps.parent/'package-lock.json').read_text(encoding='utf-8'))
        for package in ['dsh-tools','dsh-tool-bash','dsh-tool-fs','dsh-tool-str-replace-editor']:
            relative='@deepseek-ai/'+package
            source=deps/relative/'lib/index.js'
            metadata=json.loads((deps/relative/'package.json').read_text(encoding='utf-8'))
            if metadata['version']!='0.2.1-rc.2' or b'dsha-destructive-policy' in source.read_bytes():
                raise SystemExit('Expected pinned official DSH module: '+package)
            target='usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'+relative+'/lib/index.js'
            tar.add(source,arcname=target)
            official_modules.append(dict(name=relative,version=metadata['version'],sha256=digest(source),
                npmIntegrity=npm_lock['packages']['node_modules/'+relative]['integrity'],path=target))
        for file in (ROOT/'agent-plugins/time-agent').glob('*.mjs'):
            tar.add(file, arcname='root/time-agent/'+file.name)
        for file in ['package.json','native.patch.yml']:
            tar.add(ROOT/'agent-plugins/time-agent'/file, arcname='root/time-agent/'+file)
        tar.add(ROOT/'agent-plugins/time-agent/tts_bridge.py', arcname='root/time-agent/tts_bridge.py')
        tar.add(ROOT/'agent-plugins/time-agent/cacert.pem', arcname='usr/local/share/time-agent/cacert.pem')
        speech_wheels = ROOT/'vendor/edge-tts'
        speech_manifest = json.loads((speech_wheels/'manifest.json').read_text(encoding='utf-8'))
        for entry in speech_manifest['wheels']:
            if digest(speech_wheels/entry['file']) != entry['sha256']:
                raise SystemExit('Speech wheel checksum mismatch: '+entry['file'])
        for wheel in sorted(speech_wheels.glob('*.whl')):
            with zipfile.ZipFile(wheel) as package:
                for name in package.namelist():
                    if name.endswith('/') or '.data/' in name:
                        continue
                    info = tarfile.TarInfo('usr/local/lib/python3.12/site-packages/'+name)
                    data = package.read(name); info.size = len(data); info.mode = 0o644
                    tar.addfile(info, io.BytesIO(data))
        stickers = {'happy':'delighted', 'thinking':'thinking', 'concerned':'concerned',
                    'sleeping':'sleeping', 'wave':'wave-high', 'apologetic':'apologetic'}
        for style in ['chibi','elegant']:
            for kind,frame in stickers.items():
                tar.add(ROOT/'design/fish-companion/frames'/style/(frame+'.png'),
                        arcname=f'root/time-agent/stickers/{style}-{kind}.png')
        memory = ROOT/'agent-plugins/dsh-remembers'
        for file in memory.glob('*.mjs'):
            tar.add(file, arcname='root/time-agent/dsh-remembers/'+file.name)
        for file in (memory/'service').iterdir():
            if file.suffix in ('.py','.sql') and not file.name.startswith('test_'):
                tar.add(file, arcname='root/time-agent/dsh-remembers/service/'+file.name)
        tar.add(memory/'LICENSE',arcname='root/time-agent/dsh-remembers/LICENSE')
        bili = ROOT/'agent-plugins/bili-cli'
        tar.add(bili/'tools',arcname='root/time-agent/bili-cli/tools')
        for name in ['LICENSE','NOTICE.md']:
            tar.add(bili/name,arcname='root/time-agent/bili-cli/'+name)
        qr = ROOT/'.tmp/fish-dev-20261029/qrcode.whl'
        qr_meta = json.loads((qr.parent/'qrcode-wheel.json').read_text(encoding='utf-8-sig'))
        if digest(qr) != qr_meta['digests']['sha256']:
            raise SystemExit('qrcode wheel checksum mismatch')
        with zipfile.ZipFile(qr) as package:
            for name in package.namelist():
                if name.endswith('/'):
                    continue
                info=tarfile.TarInfo('usr/local/lib/python3.12/site-packages/'+name)
                data=package.read(name);info.size=len(data);info.mode=0o644
                tar.addfile(info,io.BytesIO(data))
        wheel = ROOT/'.tmp/fish-dev-20261029/numpy-arm64.whl'
        wheel_meta = json.loads((wheel.parent/'numpy-wheel.json').read_text(encoding='utf-8-sig'))
        if digest(wheel) != wheel_meta['digests']['sha256']:
            raise SystemExit('NumPy wheel checksum mismatch')
        with zipfile.ZipFile(wheel) as numpy:
            for name in numpy.namelist():
                if name.endswith('/'):
                    continue
                info = tarfile.TarInfo('usr/local/lib/python3.12/site-packages/'+name)
                info.mode = 0o755 if name.endswith('.so') else 0o644
                data = numpy.read(name)
                info.size = len(data)
                tar.addfile(info,io.BytesIO(data))
        sdk = ROOT/'.tmp/fish-dev-20261029/npm/dsh-sdk-client/package'
        if not (sdk/'lib/index.js').exists():
            raise SystemExit('Pinned official SDK client input missing')
        for name in ['package.json', 'lib/index.js']:
            tar.add(sdk/name, arcname='usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-sdk-client/'+name)
        link = tarfile.TarInfo('root/time-agent/node_modules')
        link.type = tarfile.SYMTYPE
        link.linkname = '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules'
        tar.addfile(link)
    with unpacked.open('rb') as src, overlay.open('wb') as raw:
        with gzip.GzipFile(fileobj=raw,mode='wb',filename='',mtime=0) as dst:
            shutil.copyfileobj(src,dst)
    archives.append(dict(name=overlay.name, sha256=digest(overlay), strip=0))
    lock = dict(schema=1, dsha=dict(repository='https://github.com/DSH-APP/DSHA',commit='ff32d420d2b3318f9cbd11877158e349daebc1ca',apkSha256=DSHA_SHA),dshVersion='0.2.1-rc.2',sdkVersion='0.2.1-rc.2',fish=dict(repository='https://github.com/derkinL/dsh-remembers',commit='cadbc0219156de3dd2f1c312b3ec41c9b2615736',enabled=True,status='Adapted; real SDK loading and resume checked on desktop; phone pending'),numpy=dict(version='2.1.3',wheel=wheel_meta['filename'],sha256=wheel_meta['digests']['sha256']),archives=archives)
    lock['biliCli'] = dict(repository='https://github.com/Alterssss/dsh-bili-whale',version='0.4.5',commit='ef3f74171b4104249f5583c4d2df9bebad14934a',scope='tools only; one shared dsh-remembers role')
    lock['qrcode'] = dict(version='8.2',sha256=qr_meta['digests']['sha256'])
    lock['edgeTts'] = speech_manifest
    lock['caBundle'] = dict(source='https://curl.se/ca/cacert.pem', snapshot='2026-10-20',
        sha256=digest(ROOT/'agent-plugins/time-agent/cacert.pem'))
    lock['officialDshModules'] = official_modules
    (assets/'runtime-lock.json').write_text(json.dumps(lock,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    notices = assets/'licenses'
    notices.mkdir(exist_ok=True)
    upstream=ROOT/'.tmp/fish-dev-20261029/DSHA'
    for name in ['LICENSE','THIRD_PARTY_NOTICES.md']:
        shutil.copy2(upstream/name,notices/('DSHA-'+name))
    shutil.copy2(ROOT/'agent-plugins/dsh-remembers/LICENSE',notices/'dsh-remembers-LICENSE')
    shutil.copy2(speech_wheels/'LICENSE',notices/'edge-tts-LICENSE')
    shutil.copy2(speech_wheels/'NOTICE.md',notices/'edge-tts-NOTICE.md')
    print(json.dumps(dict(archives=len(archives), compressedBytes=sum((assets/a['name']).stat().st_size for a in archives), nativeLibraries=4),indent=2))

if __name__ == '__main__':
    main()
