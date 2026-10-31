# agent-runtime implementation

Minimal Android-side owner for the pinned DSH host inside proot. Public API is
`com.timeagent.runtime.AgentRuntime`; everything else in the module is internal.

Integration update (2026-10-29): the original agent handoff notes below describe
its own verification scope. The main agent subsequently compiled the module,
executed its unit tests, and audited all five pinned archives for parent/link
layout compatibility (29,748 final virtual paths). This is not proof of Android
syscall or proot execution. Final evidence and device limitations are recorded in
`docs/releases/内置大肥鱼-本轮交付与验收-2026-10-29.md`.

Device follow-up: Python in the DSHA glibc archive is 0644; required-entry
verification now adds execute bits on a no-follow descriptor for that regular
file. Four DSHA-host-patched core JS modules are overlaid with pinned official
DSH 0.2.1-rc.2 sources. See `docs/releases/内置大肥鱼-DS真机测试-2026-10-29.md` for the
separate diagnostic-phone proof and the final APK acceptance boundary.

The integrated installer now updates only `time-agent-overlay.bin` when base
asset hashes match, preserving existing DSH sessions and memory. A mismatched
base environment or an existing rootfs without a marker fails explicitly rather
than erasing the directory. Runtime exit handling drains final stdout events
before clearing the owned process. App subscribers attach before starting it.

## Public API

```kotlin
data class RuntimeState(
    val stage: String = "未准备",
    val progress: Float? = null,
    val ready: Boolean = false,
    val error: String? = null,
)

class AgentRuntime(context: Context) {
    val state: StateFlow<RuntimeState>
    val events: SharedFlow<JSONObject>
    suspend fun start(config: JSONObject)
    suspend fun send(message: JSONObject)
    suspend fun stop()
}
```

`ready` is set only by a stdout frame whose `type` is `ready`. An `error` frame
sets `error` (and clears `ready`). stdout is parsed line by line; non-JSON text
is ignored. stderr is drained on its own stream and is never merged into events.

## Files changed / added

- `build.gradle.kts` — Android library, namespace `com.timeagent.runtime`,
  compileSdk 36 / minSdk 26 / Java 17, coroutines-android 1.10.2,
  commons-compress 1.27.1, arm64-v8a only. Assets `.bin`/`.gz` are not
  compressed by aapt; JNI libs use legacy extraction so `libproot.so` is
  executable from `nativeLibraryDir`.
- `src/main/AndroidManifest.xml` — only `ACCESS_NETWORK_STATE` (DNS lookup).
- `src/main/java/com/timeagent/runtime/RuntimeState.kt` — public state.
- `AgentRuntime.kt` — lifecycle, proot argv/env, stdout/stderr pumps, stop.
- `RootfsInstaller.kt` — hash verification, staging, marker, atomic publish.
- `ArchiveExtractor.kt` — trusted tar/gzip extraction with guest path boundary.
- `GuestPaths.kt` — archive member / symlink / hardlink path rules.
- `RuntimeLock.kt` — `assets/fish/runtime-lock.json` parsing and fingerprint.
- `RuntimeFiles.kt` — `android.system.Os` lstat/chmod/symlink-safe writes.
- `NativeSupport.kt` — `libproot`/loader lookup and `libtalloc.so.2` /
  `libandroid-shmem.so` private copies.
- `LocalConfig.kt` — `filesDir/fish/config.json`, mode 0600, atomic replace.
- `AndroidDns.kt` — system `LinkProperties` DNS, else `1.1.1.1`.
- `HardlinkProbe.kt` — decides whether `--link2symlink` is actually needed.
- `src/test/java/com/timeagent/runtime/GuestPathsTest.kt`,
  `StdoutEventsTest.kt` — boundary tests.

No files outside `agent-runtime/` were modified.

## Install layout

```
filesDir/fish/
  config.json                    # 0600, host config bound into guest
  lib/                           # libtalloc.so.2, libandroid-shmem.so (0600)
  tmp/                           # PROOT_TMP_DIR, 0700
  linux/
    .ubuntu-<uuid>/              # candidate, only while installing
    ubuntu/                      # published rootfs
      .timeagent-rootfs.json     # 0600 marker with lock fingerprint
```

- All archives are SHA-256 checked before the first extraction.
- A fresh candidate is extracted, required entries are checked, the marker is
  written, then the candidate is renamed to `ubuntu`.
- A rootfs with a valid marker is never re-extracted or overwritten. A retry
  after failure reuses it. A marker whose fingerprint differs from the current
  lock fails with `ROOTFS_VERSION_MISMATCH` instead of deleting a completed
  rootfs; changing pinned assets therefore requires the host app to clear
  `filesDir/fish/linux/ubuntu` explicitly. An incomplete `ubuntu/` (no marker)
  is removed before a new candidate is installed. Leftover `.ubuntu-*`
  candidates are cleaned.
- Archives are extracted in `runtime-lock.json` order with the lock's `strip`
  value. Current lock values are all 0; DSHA `RootfsInstaller.java:50,63` also
  extracts `glibc-python.bin` and `python-support.bin` with strip 0, and checks
  `usr/bin/python3.12` afterwards. If the supplied `glibc-python.bin` really has
  an extra top-level directory, the lock (owned by the main agent) must say
  `strip: 1`; the installer will then use it. It does not guess.

## proot launch

argv shape:

```
<libproot.so> [--link2symlink] -L --kill-on-exit -0 \
  --rootfs=<filesDir/fish/linux/ubuntu> --cwd=/root \
  [-b /dev:/dev ... /proc /sys /system /apex when the host path exists] \
  -b <filesDir/fish/config.json>:/root/time-agent-config.json \
  /usr/local/bin/node /root/time-agent/host.mjs /root/time-agent-config.json
```

`--link2symlink` is added only when a real hardlink probe in `filesDir/fish`
fails. Guest bind targets (`dev`, `proc`, `sys`, `system`, `apex`, `root`) and a
regular `/root/time-agent-config.json` placeholder are created before exec.

Environment:

```
HOME=/root
PATH=/usr/local/bin:/usr/bin:/bin
LANG=C.UTF-8
DSH_HOME=/root/.timeagent-dsh
NARB_DISABLE_NATIVE_CACHE=1
TMPDIR=/tmp
PROOT_TMP_DIR=<filesDir/fish/tmp>
PROOT_LOADER=<native libprootloader.so>
LD_LIBRARY_PATH=<filesDir/fish/lib>:<dir of libproot.so>
```

`LD_PRELOAD` inherited from the app is removed. `libproot.so` and
`libprootloader.so` are located in `nativeLibraryDir` (with the DSHA-style
sibling-directory fallback for split installs). `libtalloc.so.2` and
`libandroid-shmem.so` are copied from the native directory into `fish/lib`.

DNS: `ConnectivityManager.getLinkProperties(activeNetwork).dnsServers` (up to
three, scope-id addresses dropped), else `1.1.1.1`, written to
`rootfs/etc/resolv.conf` without following an existing symlink.

## Lifecycle semantics

- `start` is serialized by a mutex. If a Process is alive it returns without
  touching the session (idempotent); a second caller waits and then returns.
  A new config is applied by stop/start, not by calling start on a live session.
- The host config is written before preparation, so a failed prepare leaves a
  local 0600 config for the next retry. Config text is never logged.
- Preparation reports `准备中` -> `校验 <asset>` -> `解压 <asset>` -> `启动中`.
  On failure the state is `准备失败` / `启动失败` and `start` can be called again.
- `stop` writes `{"action":"stop"}` and waits up to 5 s, then destroys only the
  held Process, waits 2 s, then destroys forcibly. It never scans or kills other
  processes. Rootfs and config are kept, so a later start reuses the session
  directory (`/root/.timeagent-dsh`).
- `send` serializes writes and throws `IllegalStateException("RUNTIME_NOT_RUNNING")`
  when no session is alive.

## Archive boundary

- Member names: no absolute path, no `..`, no backslash/NUL; `.` is dropped;
  strip is applied to leading components.
- Guest-absolute symlink targets are preserved (proot resolves them inside the
  rootfs). Relative targets may not climb above the rootfs root.
- Files are written to a hidden temp in a verified real parent and renamed over
  the leaf; an existing symlink at the leaf is replaced, never followed.
- Parent components must be real directories; a symlink parent is rejected.
- Symlinks are created after files; hardlinks require a real, non-symlink source
  (so an absolute guest symlink can never make the host link a host file).
- Modes are taken from the tar header (masked to 0777); directories are
  finalized deepest-first. Device/fifo entries are skipped.
- Extraction is gzip-magic detected, so a plain tar also works.

## Tests written (not executed here)

`GuestPathsTest` covers absolute/traversal/NUL member rejection, strip
normalization, guest-absolute symlink acceptance, relative symlink escape, and
hardlink target confinement/escape. `StdoutEventsTest` proves only JSON object
lines become events, diagnostics/malformed lines are dropped, and distinct
frames produce distinct payloads rather than a fixed echo.

## Actual verification status

Verified by inspection only. Per instructions, full Gradle and device runs were
not performed by this agent.

Not verified:

- Gradle compilation / unit-test execution. The module expects the main agent
  to register `com.android.library` (the module applies it without a version,
  matching the root's `apply false` convention) and include `:agent-runtime`.
- Real APK install and arm64 proot execution, rootfs extraction, DNS, hardlink
  probe, and the JS ready/error frames.
- Archive internals (member layouts, hardlink/symlink patterns). Only the
  lock's names/hashes/strip values were used; no asset was unpacked here.
- Native library hashes are not in the lock; only presence/size/exec permission
  is checked. The app must extract JNI libs (`extractNativeLibs` behavior).
  This module requests `noCompress`/`useLegacyPackaging`, but the final app
  packaging must keep those settings for the assets and native libraries.
- `commons-compress` transitives are resolved by Gradle; no runtime test of PAX
  long names on Android was run.

Reference material: DSHA `ContainerRuntime.Proot`, `RuntimeLauncher`,
`RuntimeNativeFiles`, commit `ff32d420d2b3318f9cbd11877158e349daebc1ca`
(MIT). Only the necessary proot argv/env and native-file conventions were
reused; the backup/approval/audit subsystems were not copied.
