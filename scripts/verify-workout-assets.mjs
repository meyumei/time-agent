import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert';

const METRICS = [
  'WEIGHT_REPS',
  'BODYWEIGHT_REPS',
  'ASSISTED_REPS',
  'REPS',
  'DURATION',
  'CARDIO',
  'STRETCH',
  'TABATA'
];

const EXPECTED_ACTIONS = 1226;
const EXPECTED_LOCAL_MEDIA = 1211;
const EXPECTED_MEDIA_BYTES = 204260487;
const EXPECTED_MEDIA_TYPES = { gif: 1205, jpg: 5, png: 1 };
const EXPECTED_ALL_EMG_SHA256 = '83ecd8552e97555f8f71ff517f79fd8413afcbf65fc84ebafe9329ab02d1ccb6';
const EXPECTED_LABELED_MESHES = 3532;
const EXPECTED_ORIGINAL_FILES = 47;
const EXPECTED_ORIGINAL_BYTES = 31695112;

function readJson(filePath, label) {
  let raw;
  try {
    raw = fs.readFileSync(filePath, 'utf8');
  } catch (err) {
    throw new Error(`${label}: cannot read ${filePath} (${err.message})`);
  }
  try {
    return JSON.parse(raw);
  } catch (err) {
    throw new Error(`${label}: invalid JSON in ${filePath} (${err.message})`);
  }
}

function sha256File(filePath) {
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

function toPosix(value) {
  return value.split(path.sep).join('/');
}

function resolveInside(baseDir, relativePath, label) {
  assert(
    typeof relativePath === 'string' && relativePath.trim() !== '',
    `${label}: path must be a non-empty string`
  );
  assert(!relativePath.includes('\0'), `${label}: path contains NUL byte`);
  assert(!path.isAbsolute(relativePath), `${label}: absolute paths are not allowed (${relativePath})`);

  const normalized = relativePath.split('\\').join('/');
  assert(
    !normalized.split('/').some((segment) => segment === '..'),
    `${label}: path traversal is not allowed (${relativePath})`
  );

  const absolute = path.resolve(baseDir, normalized);
  const relative = path.relative(baseDir, absolute);
  assert(
    relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative),
    `${label}: path escapes its base directory (${relativePath})`
  );
  return absolute;
}

function requireFile(filePath, label) {
  assert(fs.existsSync(filePath), `${label}: file does not exist (${filePath})`);
  assert(fs.statSync(filePath).isFile(), `${label}: not a regular file (${filePath})`);
}

function walkFiles(dir, baseDir, acc = []) {
  assert(fs.existsSync(dir), `directory does not exist: ${dir}`);
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkFiles(full, baseDir, acc);
    } else if (entry.isFile()) {
      acc.push(toPosix(path.relative(baseDir, full)));
    }
  }
  return acc;
}

function run() {
  const rootArg = process.argv[2];
  assert(
    typeof rootArg === 'string' && rootArg.trim() !== '',
    'missing project root argument (process.argv[2])'
  );

  const root = path.resolve(rootArg);
  assert(fs.existsSync(root), `project root does not exist: ${root}`);
  assert(fs.statSync(root).isDirectory(), `project root is not a directory: ${root}`);

  const workoutDir = path.join(root, 'app', 'src', 'main', 'assets', 'workout');
  const anatomyDir = path.join(workoutDir, 'anatomy');
  const exercisesDir = path.join(workoutDir, 'exercises');

  assert(fs.existsSync(workoutDir), `workout directory does not exist: ${workoutDir}`);
  assert(fs.existsSync(anatomyDir), `anatomy directory does not exist: ${anatomyDir}`);
  assert(fs.existsSync(exercisesDir), `exercises directory does not exist: ${exercisesDir}`);

  // ---- catalog.json ----
  const catalogPath = path.join(workoutDir, 'catalog.json');
  const catalog = readJson(catalogPath, 'catalog.json');
  assert(Array.isArray(catalog), 'catalog.json must be an array');
  assert.strictEqual(
    catalog.length,
    EXPECTED_ACTIONS,
    `catalog.json expected ${EXPECTED_ACTIONS} actions, found ${catalog.length}`
  );

  const ids = new Set();
  const metricsSeen = new Set();
  const missingActionMedia = [];
  const referencedAssets = new Set();

  for (let i = 0; i < catalog.length; i += 1) {
    const action = catalog[i];
    assert(action !== null && typeof action === 'object' && !Array.isArray(action), `catalog[${i}] must be an object`);

    const { id, name, primaryMuscles, metric, animationAsset } = action;

    assert(typeof id === 'string' && id.trim() !== '', `catalog[${i}].id must be a non-empty string`);
    assert(!ids.has(id), `duplicate action id: ${id}`);
    ids.add(id);

    assert(typeof name === 'string' && name.trim() !== '', `action ${id}: name must be a non-empty string`);

    assert(Array.isArray(primaryMuscles), `action ${id}: primaryMuscles must be an array`);
    assert(primaryMuscles.length > 0, `action ${id}: primaryMuscles must not be empty`);
    for (const muscle of primaryMuscles) {
      assert(
        typeof muscle === 'string' && muscle.trim() !== '',
        `action ${id}: primaryMuscles entries must be non-empty strings`
      );
    }

    assert(METRICS.includes(metric), `action ${id}: unexpected metric ${JSON.stringify(metric)}`);
    metricsSeen.add(metric);

    if (animationAsset === null) {
      missingActionMedia.push(id);
    } else {
      assert(
        typeof animationAsset === 'string' && animationAsset.trim() !== '',
        `action ${id}: animationAsset must be null or a non-empty string`
      );
      assert(
        animationAsset.startsWith('workout/exercises/'),
        `action ${id}: animationAsset must live under workout/exercises (${animationAsset})`
      );
      assert(
        !animationAsset.split('/').includes('..'),
        `action ${id}: animationAsset must not traverse directories (${animationAsset})`
      );
      referencedAssets.add(animationAsset);
    }
  }

  for (const metric of METRICS) {
    assert(metricsSeen.has(metric), `catalog.json does not cover metric ${metric}`);
  }
  assert.strictEqual(metricsSeen.size, METRICS.length, 'catalog.json must cover exactly the 8 metrics');
  assert(
    missingActionMedia.length <= 2,
    `expected at most 2 actions without media, found ${missingActionMedia.length}`
  );

  // ---- asset-provenance.json ----
  const provenance = readJson(path.join(workoutDir, 'asset-provenance.json'), 'asset-provenance.json');
  assert(provenance !== null && typeof provenance === 'object' && !Array.isArray(provenance), 'asset-provenance.json must be an object');
  assert(Array.isArray(provenance.files), 'asset-provenance.json: files must be an array');
  assert.strictEqual(provenance.actions, EXPECTED_ACTIONS, `asset-provenance.actions must be ${EXPECTED_ACTIONS}`);
  assert.strictEqual(provenance.localMedia, EXPECTED_LOCAL_MEDIA, `asset-provenance.localMedia must be ${EXPECTED_LOCAL_MEDIA}`);
  assert.strictEqual(provenance.mediaBytes, EXPECTED_MEDIA_BYTES, `asset-provenance.mediaBytes must be ${EXPECTED_MEDIA_BYTES}`);
  assert.strictEqual(
    provenance.files.length,
    EXPECTED_LOCAL_MEDIA,
    `asset-provenance.files expected ${EXPECTED_LOCAL_MEDIA} entries, found ${provenance.files.length}`
  );

  const manifestAssets = new Set();
  const mediaTypes = { gif: 0, jpg: 0, png: 0 };
  let mediaBytes = 0;

  for (let i = 0; i < provenance.files.length; i += 1) {
    const entry = provenance.files[i];
    assert(entry !== null && typeof entry === 'object' && !Array.isArray(entry), `asset-provenance.files[${i}] must be an object`);

    const asset = entry.asset;
    assert(typeof asset === 'string' && asset.startsWith('workout/exercises/'), `asset-provenance.files[${i}].asset must live under workout/exercises`);
    assert(!manifestAssets.has(asset), `duplicate asset path in provenance: ${asset}`);
    manifestAssets.add(asset);

    const absolute = resolveInside(path.dirname(workoutDir), asset, `asset-provenance.files[${i}].asset`);
    const relativeToExercises = path.relative(exercisesDir, absolute);
    assert(
      relativeToExercises !== '' && !relativeToExercises.startsWith('..') && !path.isAbsolute(relativeToExercises),
      `asset-provenance.files[${i}].asset is outside workout/exercises: ${asset}`
    );

    requireFile(absolute, `asset-provenance.files[${i}]`);
    const stat = fs.statSync(absolute);
    assert.strictEqual(stat.size, entry.bytes, `${asset}: byte size mismatch (manifest ${entry.bytes}, actual ${stat.size})`);

    const actualSha = sha256File(absolute);
    assert(
      typeof entry.sha256 === 'string' && /^[0-9a-f]{64}$/i.test(entry.sha256),
      `${asset}: sha256 must be a 64 character hex string`
    );
    assert.strictEqual(actualSha, entry.sha256.toLowerCase(), `${asset}: sha256 mismatch`);

    const extension = path.extname(asset).toLowerCase();
    assert(extension === '.gif' || extension === '.jpg' || extension === '.png', `${asset}: unsupported media extension ${extension}`);
    mediaTypes[extension.slice(1)] += 1;
    mediaBytes += stat.size;
  }

  assert.strictEqual(mediaBytes, provenance.mediaBytes, `media byte total mismatch (files ${mediaBytes}, declared ${provenance.mediaBytes})`);
  assert.strictEqual(mediaBytes, EXPECTED_MEDIA_BYTES, `media byte total must be ${EXPECTED_MEDIA_BYTES}, got ${mediaBytes}`);
  assert.strictEqual(mediaTypes.gif, EXPECTED_MEDIA_TYPES.gif, `expected ${EXPECTED_MEDIA_TYPES.gif} GIF files, found ${mediaTypes.gif}`);
  assert.strictEqual(mediaTypes.jpg, EXPECTED_MEDIA_TYPES.jpg, `expected ${EXPECTED_MEDIA_TYPES.jpg} JPG files, found ${mediaTypes.jpg}`);
  assert.strictEqual(mediaTypes.png, EXPECTED_MEDIA_TYPES.png, `expected ${EXPECTED_MEDIA_TYPES.png} PNG files, found ${mediaTypes.png}`);

  const actualExerciseFiles = new Set(walkFiles(exercisesDir, path.dirname(workoutDir)));
  for (const asset of manifestAssets) {
    assert(actualExerciseFiles.has(asset), `provenance lists an asset that is missing on disk: ${asset}`);
  }
  for (const actualAsset of actualExerciseFiles) {
    assert(manifestAssets.has(actualAsset), `exercises directory contains an asset absent from provenance: ${actualAsset}`);
  }
  assert.strictEqual(
    actualExerciseFiles.size,
    manifestAssets.size,
    `exercises file count (${actualExerciseFiles.size}) does not match provenance (${manifestAssets.size})`
  );

  for (const asset of referencedAssets) {
    assert(manifestAssets.has(asset), `catalog references an asset absent from provenance: ${asset}`);
  }

  // ---- anatomy/model-manifest.json ----
  const modelManifest = readJson(path.join(anatomyDir, 'model-manifest.json'), 'anatomy/model-manifest.json');
  assert(modelManifest !== null && typeof modelManifest === 'object' && !Array.isArray(modelManifest), 'anatomy/model-manifest.json must be an object');
  assert(Array.isArray(modelManifest.models), 'anatomy/model-manifest.json: models must be an array');
  assert(modelManifest.models.length > 0, 'anatomy/model-manifest.json: models must not be empty');

  let modelSha256 = null;
  let allEmgFound = false;

  for (let i = 0; i < modelManifest.models.length; i += 1) {
    const model = modelManifest.models[i];
    assert(model !== null && typeof model === 'object' && !Array.isArray(model), `anatomy/model-manifest.models[${i}] must be an object`);
    assert(typeof model.path === 'string' && model.path.trim() !== '', `anatomy/model-manifest.models[${i}].path must be a non-empty string`);
    assert(
      typeof model.sha256 === 'string' && /^[0-9a-f]{64}$/i.test(model.sha256),
      `anatomy/model-manifest.models[${i}].sha256 must be a 64 character hex string`
    );

    const absolute = resolveInside(anatomyDir, model.path, `anatomy/model-manifest.models[${i}].path`);
    requireFile(absolute, `anatomy/model-manifest.models[${i}]`);
    const actualSha = sha256File(absolute);
    assert.strictEqual(actualSha, model.sha256.toLowerCase(), `${model.path}: sha256 mismatch`);

    if (toPosix(model.path) === 'xitong/obj/all.emg') {
      allEmgFound = true;
      modelSha256 = actualSha;
      assert.strictEqual(actualSha, EXPECTED_ALL_EMG_SHA256, `xitong/obj/all.emg: unexpected sha256 ${actualSha}`);
    }
  }

  assert(allEmgFound, 'anatomy/model-manifest.json does not contain xitong/obj/all.emg');

  // ---- anatomy/mesh-map.json ----
  const meshMap = readJson(path.join(anatomyDir, 'mesh-map.json'), 'anatomy/mesh-map.json');
  assert(Array.isArray(meshMap), 'anatomy/mesh-map.json must be an array');
  assert.strictEqual(
    meshMap.length,
    EXPECTED_LABELED_MESHES,
    `anatomy/mesh-map.json expected ${EXPECTED_LABELED_MESHES} labeled entries, found ${meshMap.length}`
  );

  // ---- original 3D dependency manifest ----
  const originalManifestPath = path.join(
    root,
    'evidence',
    'training-reverse-20261025',
    'h3dbody-minimal-copy-manifest.json'
  );
  const originalManifest = readJson(originalManifestPath, 'h3dbody-minimal-copy-manifest.json');
  assert(originalManifest !== null && typeof originalManifest === 'object' && !Array.isArray(originalManifest), 'h3dbody-minimal-copy-manifest.json must be an object');
  assert(Array.isArray(originalManifest.files), 'h3dbody-minimal-copy-manifest.json: files must be an array');
  assert.strictEqual(
    originalManifest.files.length,
    EXPECTED_ORIGINAL_FILES,
    `h3dbody-minimal-copy-manifest.json expected ${EXPECTED_ORIGINAL_FILES} entries, found ${originalManifest.files.length}`
  );

  const originalRelatives = new Set();
  let originalBytes = 0;

  for (let i = 0; i < originalManifest.files.length; i += 1) {
    const entry = originalManifest.files[i];
    assert(entry !== null && typeof entry === 'object' && !Array.isArray(entry), `h3dbody-minimal-copy-manifest.files[${i}] must be an object`);
    assert(typeof entry.relative === 'string' && entry.relative.trim() !== '', `h3dbody-minimal-copy-manifest.files[${i}].relative must be a non-empty string`);
    assert(
      typeof entry.sha256 === 'string' && /^[0-9a-f]{64}$/i.test(entry.sha256),
      `h3dbody-minimal-copy-manifest.files[${i}].sha256 must be a 64 character hex string`
    );

    const relativeKey = entry.relative.split('\\').join('/');
    assert(!originalRelatives.has(relativeKey), `duplicate original relative path: ${relativeKey}`);
    originalRelatives.add(relativeKey);

    const absolute = resolveInside(anatomyDir, entry.relative, `h3dbody-minimal-copy-manifest.files[${i}].relative`);
    requireFile(absolute, `h3dbody-minimal-copy-manifest.files[${i}]`);

    const stat = fs.statSync(absolute);
    assert.strictEqual(stat.size, entry.bytes, `${relativeKey}: byte size mismatch (manifest ${entry.bytes}, actual ${stat.size})`);

    const actualSha = sha256File(absolute);
    assert.strictEqual(actualSha, entry.sha256.toLowerCase(), `${relativeKey}: sha256 mismatch`);

    originalBytes += stat.size;
  }

  assert.strictEqual(originalRelatives.size, EXPECTED_ORIGINAL_FILES, `expected ${EXPECTED_ORIGINAL_FILES} unique original paths, found ${originalRelatives.size}`);
  assert.strictEqual(
    originalBytes,
    EXPECTED_ORIGINAL_BYTES,
    `original 3D dependency byte total must be ${EXPECTED_ORIGINAL_BYTES}, got ${originalBytes}`
  );

  console.log(JSON.stringify({
    passed: true,
    actions: catalog.length,
    mediaFiles: provenance.files.length,
    mediaTypes,
    mediaBytes,
    missingActionMedia,
    metrics: METRICS.filter((metric) => metricsSeen.has(metric)),
    anatomyOriginalFiles: originalRelatives.size,
    anatomyOriginalBytes: originalBytes,
    labeledMeshes: meshMap.length,
    modelSha256
  }, null, 2));
}

try {
  run();
} catch (err) {
  console.error(err && err.message ? err.message : String(err));
  process.exitCode = 1;
}
