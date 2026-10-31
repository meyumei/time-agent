"""Check the current built APK, signatures, packaged assets and test/lint reports."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True)
parser.add_argument('--built', default=None)
parser.add_argument('--report', required=True)
parser.add_argument('--expected-version', required=True)
parser.add_argument('--expected-code', type=int, required=True)
parser.add_argument('--build-evidence', required=True)
parser.add_argument('--targeted-acceptance', action='store_true', help='User requested minimal regression before device testing')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
built = Path(args.built) if args.built else root / '.tmp/butler-verification'
apk = Path(args.apk).resolve()
environment = json.loads((root / '.tools/environment.json').read_text(encoding='utf-8-sig'))
sdk = Path(environment['androidSdk'])
java = Path(environment['javaHome']) / 'bin/java.exe'
build_tools = sorted((sdk / 'build-tools').iterdir(), key=lambda path: tuple(int(part) for part in path.name.split('.')))[-1]

def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def run(command, cwd=None):
    result = subprocess.run([str(part) for part in command], cwd=cwd, capture_output=True, text=True, encoding='utf-8', errors='replace', check=True)
    return result.stdout

build_evidence = json.loads(Path(args.build_evidence).read_text(encoding='utf-8-sig'))
assert build_evidence['exitCode'] == 0, 'Build verification did not complete successfully'
assert {':core:test', ':agent-runtime:testDebugUnitTest', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'}.issubset(build_evidence['tasks']), 'Final full build tasks missing'
assert '--tests' not in build_evidence['tasks'] or args.targeted_acceptance, 'Targeted acceptance must be explicitly selected'
assert apk.is_file() and apk.stat().st_size > 300_000_000, 'Full training APK is missing or implausibly small'
assert sha(apk) == sha(built / 'app/build/outputs/apk/debug/app-debug.apk'), 'Delivered APK differs from current build output'
# aapt's archive argument uses the Windows narrow encoding; keep it ASCII.
badging = run([build_tools / 'aapt.exe', 'dump', 'badging', apk.name], cwd=apk.parent)
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
assert package, 'APK package metadata missing'
assert package.group(1) == 'com.timeagent'
assert package.group(2) == str(args.expected_code) and package.group(3) == args.expected_version
signature = run([java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk])
baseline = root / 'outputs/time-agent-0.8.1-workout-health-debug.apk'
baseline_signature = run([java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', baseline])
certificate = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signature).group(1)
baseline_certificate = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', baseline_signature).group(1)
assert certificate == baseline_certificate, 'Signing certificate changed'
baseline010 = root / 'outputs/time-agent-0.10.0-personal-butler-debug.apk'
signature010 = run([java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--print-certs', baseline010])
assert certificate == re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signature010).group(1), 'Certificate differs from 0.10.0'
baseline011 = root / 'outputs/time-agent-0.11.1-record-narration-debug.apk'
signature011 = run([java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--print-certs', baseline011])
assert certificate == re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signature011).group(1), 'Certificate differs from 0.11.1'
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature

checked_assets = 0
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), 'Duplicate APK entries'
    for source in (root / 'app/src/main/assets/workout').rglob('*'):
        if not source.is_file():
            continue
        name = 'assets/' + source.relative_to(root / 'app/src/main/assets').as_posix()
        with archive.open(name) as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        assert actual == sha(source), f'Packaged asset differs: {name}'
        checked_assets += 1
    assert len(json.loads(archive.read('assets/workout/catalog.json'))) == 1226
    assert checked_assets == 1266
    chinese_models = [name for name in names if name.startswith('assets/mlkit-google-ocr-models/') and '/Hani_ctc/' in name]
    assert any(name.endswith('/conv_model.fb') for name in chinese_models), 'Bundled Chinese OCR model missing'
    with zipfile.ZipFile(baseline010) as previous:
        workout_names = {name for name in names if name.startswith('assets/workout/') and not name.endswith('/')}
        assert workout_names == {name for name in previous.namelist() if name.startswith('assets/workout/') and not name.endswith('/')}
        for name in workout_names:
            assert hashlib.sha256(archive.read(name)).digest() == hashlib.sha256(previous.read(name)).digest(), f'Training asset changed since 0.10.0: {name}'

protected = root / 'evidence/butler-next-20261027/baseline/app/src/main/java/com/timeagent/workout'
for before in protected.rglob('*'):
    if before.is_file():
        assert sha(before) == sha(root / 'app/src/main/java/com/timeagent/workout' / before.relative_to(protected)), f'Protected training source changed: {before.name}'

source_files = []
for folder in ['app/src/main/java', 'app/src/main/res', 'core/src/main', 'app/schemas', 'agent-runtime/src/main/java']:
    left = {path.relative_to(root).as_posix() for path in (root / folder).rglob('*') if path.is_file()}
    right = {path.relative_to(built).as_posix() for path in (built / folder).rglob('*') if path.is_file()}
    assert left == right, f'Production source set differs: {folder}'
    source_files.extend(sorted(left))
source_files += ['app/src/main/AndroidManifest.xml', 'app/build.gradle.kts', 'core/build.gradle.kts', 'agent-runtime/build.gradle.kts', 'agent-runtime/src/main/AndroidManifest.xml', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']
for relative in source_files:
    assert sha(root / relative) == sha(built / relative), f'Built snapshot differs: {relative}'

def test_counts(folder):
    result = dict(tests=0, failures=0, errors=0, skipped=0)
    files = sorted(folder.glob('TEST-*.xml'))
    assert files, f'No test reports: {folder}'
    for file in files:
        suite = ET.parse(file).getroot()
        for key in result:
            result[key] += int(suite.attrib.get(key, 0))
    result['passed'] = result['tests'] - result['failures'] - result['errors'] - result['skipped']
    assert result['failures'] == result['errors'] == 0, f'Test failures: {folder}'
    return result

tests = {'core': test_counts(built / 'core/build/test-results/test'), 'runtime': test_counts(built / 'agent-runtime/build/test-results/testDebugUnitTest'), 'app': test_counts(built / 'app/build/test-results/testDebugUnitTest')}
assert tests['core']['tests'] >= 70 and tests['app']['tests'] >= (3 if args.targeted_acceptance else 460), 'Required test reports are missing'
lint = {}
for issue in ET.parse(built / 'app/build/reports/lint-results-debug.xml').getroot().findall('issue'):
    level = issue.attrib.get('severity', 'Unknown')
    lint[level] = lint.get(level, 0) + 1
assert not lint.get('Error', 0) and not lint.get('Fatal', 0), 'Lint has errors'
report = {
    'verifiedAt': datetime.now(timezone.utc).isoformat(), 'apk': str(apk),
    'bytes': apk.stat().st_size, 'sha256': sha(apk), 'versionName': args.expected_version, 'versionCode': args.expected_code,
    'packageName': 'com.timeagent', 'signatureV2': True, 'certificateSha256': certificate,
    'sameCertificateAs081': True, 'sameCertificateAs0100': True, 'sameCertificateAs0111': True, 'workoutAssetsUnchangedFrom0100': True,
    'protectedWorkoutSourcesUnchanged': True, 'verifiedWorkoutAssetFiles': checked_assets,
    'bundledChineseOcrModelPresent': True,
    'productionSourcesMatchBuiltSnapshot': True, 'verifiedProductionSourceFiles': len(source_files),
    'buildEvidence': str(Path(args.build_evidence).resolve()), 'buildExitCode': 0,
    'tests': tests, 'testScope': 'targeted; user requested minimal simulation before phone testing' if args.targeted_acceptance else 'full', 'lint': lint,
    'deviceValidation': {'performedThisRelease': False, 'pending': True},
}
report_path = Path(args.report).resolve()
report_path.parent.mkdir(parents=True, exist_ok=True)
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
report_path.with_suffix('.signature.txt').write_text(signature, encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
