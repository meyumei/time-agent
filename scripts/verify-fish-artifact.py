"""Verify this APK contains the pinned, current runtime rather than an older build copy."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zipfile
from PIL import Image

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--apk',required=True,type=Path)
parser.add_argument('--built',type=Path,default=root/'.tmp/butler-verification')
parser.add_argument('--report',required=True,type=Path)
args=parser.parse_args()

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

assets=root/'agent-runtime/src/main/assets/fish'
lock=json.loads((assets/'runtime-lock.json').read_text(encoding='utf-8'))
checked=[]
with zipfile.ZipFile(args.apk) as apk:
    packaged_lock=json.loads(apk.read('assets/fish/runtime-lock.json'))
    assert packaged_lock==lock,'APK runtime lock differs from source'
    archive_names={n for n in apk.namelist() if n.startswith('assets/fish/') and '/' not in n[len('assets/fish/'):] and not n.endswith('/') and n!='assets/fish/runtime-lock.json'}
    assert archive_names=={'assets/fish/'+a['name'] for a in lock['archives']},'APK has missing, renamed, or stale runtime archives'
    for asset in lock['archives']:
        name='assets/fish/'+asset['name']
        assert sha(assets/asset['name'])==asset['sha256']
        assert sha(args.built/'agent-runtime/src/main/assets/fish'/asset['name'])==asset['sha256'],'Build copy has stale runtime bytes'
        with apk.open(name) as stream:
            assert hashlib.file_digest(stream,'sha256').hexdigest()==asset['sha256'],f'APK has wrong runtime asset: {name}'
        assert apk.getinfo(name).compress_type==zipfile.ZIP_STORED,f'Runtime progress asset was recompressed: {name}'
        checked.append(name)
    for library in ['libproot.so','libprootloader.so','libtalloc.so','libandroidshmem.so']:
        name='lib/arm64-v8a/'+library
        binary=apk.read(name)
        assert hashlib.sha256(binary).hexdigest()==sha(root/'agent-runtime/src/main/jniLibs/arm64-v8a'/library),f'Native runtime differs: {library}'
        assert binary[:4]==b'\x7fELF' and int.from_bytes(binary[18:20],'little')==183,'Native runtime is not AArch64'
        checked.append(name)
    with tarfile.open(fileobj=io.BytesIO(apk.read('assets/fish/time-agent-overlay.bin')),mode='r:gz') as overlay:
        source_pairs=[]
        source_pairs += [
            (root/'agent-plugins/time-agent/tts_bridge.py','root/time-agent/tts_bridge.py'),
            (root/'agent-plugins/time-agent/cacert.pem','usr/local/share/time-agent/cacert.pem'),
        ]
        for source in (root/'agent-plugins/time-agent').glob('*.mjs'):
            source_pairs.append((source,'root/time-agent/'+source.name))
        source_pairs.append((root/'agent-plugins/time-agent/native.patch.yml','root/time-agent/native.patch.yml'))
        for source in (root/'agent-plugins/dsh-remembers').glob('*.mjs'):
            source_pairs.append((source,'root/time-agent/dsh-remembers/'+source.name))
        for source in (root/'agent-plugins/dsh-remembers/service').iterdir():
            if source.suffix in ('.py','.sql') and not source.name.startswith('test_'):
                source_pairs.append((source,'root/time-agent/dsh-remembers/service/'+source.name))
        for source in (root/'agent-plugins/bili-cli/tools').rglob('*'):
            if source.is_file():
                source_pairs.append((source,'root/time-agent/bili-cli/tools/'+source.relative_to(root/'agent-plugins/bili-cli/tools').as_posix()))
        for package in ['dsh-tools','dsh-tool-bash','dsh-tool-fs','dsh-tool-str-replace-editor']:
            relative='@deepseek-ai/'+package+'/lib/index.js'
            source_pairs.append((root/'.tmp/fish-dev-20261029/host-test/node_modules'/relative,
                'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'+relative))
        for source,name in source_pairs:
            assert overlay.extractfile(name).read()==source.read_bytes(),f'Packaged adapter is stale: {name}'
        stickers = {'happy':'delighted','thinking':'thinking','concerned':'concerned',
                    'sleeping':'sleeping','wave':'wave-high','apologetic':'apologetic'}
        for style in ['chibi','elegant']:
            for kind,frame in stickers.items():
                name=f'root/time-agent/stickers/{style}-{kind}.png'
                source=root/'design/fish-companion/frames'/style/(frame+'.png')
                assert overlay.extractfile(name).read()==source.read_bytes(),f'Packaged sticker differs: {name}'
        names=set(overlay.getnames())
        assert not any(n.endswith('/credentials.json') or n.endswith('/native-session.json') for n in names),'User state was bundled'
        assert 'root/time-agent/bili-cli/lib/index.js' not in names,'A second fish host was bundled'
        assert 'usr/local/lib/python3.12/site-packages/numpy/__init__.py' in names
        assert 'usr/local/lib/python3.12/site-packages/qrcode/__init__.py' in names
        voice_manifest=json.loads((root/'vendor/edge-tts/manifest.json').read_text(encoding='utf-8'))
        assert lock['edgeTts']==voice_manifest
        for wheel in voice_manifest['wheels']:
            path=root/'vendor/edge-tts'/wheel['file']
            assert sha(path)==wheel['sha256']
            with zipfile.ZipFile(path) as package:
                for name in package.namelist():
                    if name.endswith('/') or '.data/' in name: continue
                    assert overlay.extractfile('usr/local/lib/python3.12/site-packages/'+name).read()==package.read(name),f'Voice package differs: {name}'
    character_assets=[]
    for source in (root/'app/src/main/assets/fish').rglob('*'):
        if source.is_file():
            name='assets/fish/'+source.relative_to(root/'app/src/main/assets/fish').as_posix()
            assert apk.read(name)==source.read_bytes(),f'Character asset differs: {name}'
            character_assets.append(name)
    for style in ['chibi','elegant']:
        matches=[name for name in apk.namelist() if name.endswith(f'/fish_{style}_atlas.png')]
        assert len(matches)==1,f'Native fish atlas missing or duplicated: {style}'
        source=Image.open(root/'app/src/main/res/drawable-nodpi'/f'fish_{style}_atlas.png').convert('RGBA')
        packaged=Image.open(io.BytesIO(apk.read(matches[0]))).convert('RGBA')
        assert source.size==packaged.size and source.tobytes()==packaged.tobytes(),f'Native fish atlas pixels differ: {style}'
    speech_assets=root/'app/src/main/assets/asr'
    speech_manifest=json.loads((speech_assets/'sensevoice/manifest.json').read_text(encoding='utf-8'))
    for relative in ['sensevoice/model.int8.onnx','sensevoice/tokens.txt','sensevoice/manifest.json',
                     'licenses/sherpa-onnx-LICENSE','licenses/SenseVoice-LICENSE','licenses/SenseVoice-model-LICENSE']:
        name='assets/asr/'+relative
        with apk.open(name) as stream:
            assert hashlib.file_digest(stream,'sha256').hexdigest()==sha(speech_assets/relative),f'APK speech asset differs: {relative}'
        assert sha(args.built/'app/src/main/assets/asr'/relative)==sha(speech_assets/relative),f'Build speech asset differs: {relative}'
    assert sha(speech_assets/'sensevoice/model.int8.onnx')==speech_manifest['modelSha256']
    assert (speech_assets/'sensevoice/model.int8.onnx').stat().st_size==speech_manifest['modelSize']
    assert sha(speech_assets/'sensevoice/tokens.txt')==speech_manifest['tokensSha256']
    aar_relative='app/libs/sherpa-onnx-'+speech_manifest['engineVersion']+'.aar'
    assert sha(root/aar_relative)==sha(args.built/aar_relative)==speech_manifest['aarSha256']
    assert apk.getinfo('assets/asr/sensevoice/model.int8.onnx').compress_type==zipfile.ZIP_STORED,'Speech model was recompressed'
    speech_libraries=[]
    for library in ['libonnxruntime.so','libsherpa-onnx-c-api.so','libsherpa-onnx-cxx-api.so','libsherpa-onnx-jni.so']:
        name='lib/arm64-v8a/'+library
        binary=apk.read(name)
        assert binary[:4]==b'\x7fELF' and int.from_bytes(binary[18:20],'little')==183,f'Speech library is not AArch64: {library}'
        speech_libraries.append(name)

report=dict(apk=str(args.apk.resolve()),sha256=sha(args.apk),dshVersion=lock['dshVersion'],fish=lock['fish'],biliCli=lock['biliCli'],
    sourceAdapterFilesMatchApk=len(source_pairs),stickerFilesMatchApk=12,nativeAtlasPixelsMatchApk=2,runtimeAssetsMatchApk=checked,nativeAbi='arm64-v8a',bundledUserCredentials=False,
    speech=dict(manifest=speech_manifest,assetsMatchSourceAndBuild=6,aarMatchesSourceAndBuild=True,modelStoredWithoutCompression=True,nativeLibraries=speech_libraries),
    voice=dict(version=voice_manifest['version'],unmodifiedWheels=len(voice_manifest['wheels']),caBundle=lock['caBundle']),
    characterAssetsMatchSource=character_assets,
    realModelInferenceVerified=False,phoneRuntimeVerified=False)
args.report.parent.mkdir(parents=True,exist_ok=True)
args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
