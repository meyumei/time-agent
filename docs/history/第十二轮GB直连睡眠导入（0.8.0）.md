# 第十二轮：Gadgetbridge 直连睡眠导入（0.8.0）

日期：2026-10-25 / 26。本轮只解决一件事：**睡眠数据进不来**。前十一轮健康同步全程依赖 Health Connect，而华为运动健康国内版、小米运动健康国内版都不往 HC 写睡眠（见 `第十轮健康同步与无限时间轴.md` 实测），唯一在写的 Gadgetbridge 又只导出"本次设备同步新增"的数据。于是 0.8.0 改为**直接读 GB 的数据库导出文件**，绕开 HC 的睡眠管道。

## 需求

### R1201 GB 直连导入器（`health/GadgetbridgeImporter.kt`）

- 读 GB 导出的 SQLite（`HUAWEI_ACTIVITY_SAMPLE` 表，分钟级稀疏样本），`RAW_KIND` 6=浅睡、7=深睡、8=REM。
- 表 `HUAWEI_SLEEP_STAGE_SAMPLE` 为空，不作为数据源。
- 输出 `HealthSample(kind=SLEEP)`，`uid = gb-night:<deviceId>:<日期>`，天然幂等：同一夜重复导入只更新、不新增。

### R1202 夜间聚合（本轮的关键口径）

GB 的睡眠样本是散点，不是时段。若按样本逐条入库，一夜会变成几十段碎片；华为 App 显示的是一整晚。聚合规则：

- **夜锚**：18:00 之后的样本锚定"当天"，凌晨 0:00–18:00 的样本锚定"前一天"；跨午夜的两簇因此合并成同一夜。
- 夜内所有样本合并成**一条**记录（例：`01:44 → 08:25`），总时长对齐华为官方"在床窗口"口径（10-25 夜 ≈ 6h41m）。
- 中段未测量的空档不外推，如实保留起止。
- 会话仍未结束（endAt 晚于当前时间）的夜整条跳过，等它闭合。
- `detail` 写分期分钟摘要（深/浅/REM 分钟数），随 `notes` 一起落库。

### R1203 主动刷新（`health/Nudge.kt`）

向 GB 发 `...command.TRIGGER_EXPORT` 与 `ACTIVITY_SYNC` 广播，让 GB 先刷新导出文件再读。**前提是 GB 侧打开"意图接口"**；没开也不影响文件通道，只是读到的是上一次导出的内容。

### R1204 文件通道 UI（`ui/HealthSyncScreen.kt`）

新增"Gadgetbridge 数据文件（推荐）"卡片（testTag `gb-file-card` / `gb-file-state`）：OpenDocument 选文件 + `takePersistableUriPermission` + 持久化到 `HealthSyncSettings.gbExportUri`。选一次即可全自动。

### R1205 复用既有同步机制

去重（重叠 ≥30 分钟先到先得）、睡眠来源偏好替换、手工保护、删除墓碑、进行中跳过、`HealthSyncOutcome` 计数——全部沿用，不重写。

## 实测结果

- `core:test` 61/61；`:app:testDebugUnitTest` 401 项，399 过、2 跳过（个人 PDF 用例，同历轮）；`:app:lintDebug` 0 错误 31 警告；`BUILD SUCCESSFUL`。
- APK `outputs/time-agent-0.8.0-debug.apk`（versionCode 13，114.4MB），V2 签名与历史版本同证书（SHA-256 `581ae588…589b`）。
- 机器可读结果 `outputs/v0.8.0/verification.json`。
- GB 导入器测试 `GadgetbridgeImporterTest` 6 项全绿（夜合并 / 跨午夜合并 / 两夜分离 / 非睡眠忽略 / 进行中跳过 / 空文件）。

## 真机验证（Redmi K70，2026-10-26 01:45）

- 0.8.0（versionCode 13）覆盖安装 0.7.1（versionCode 12），数据保留。
- 用设备上 2026-10-25 22:50 的 GB 导出（3.3MB，`HUAWEI_ACTIVITY_SAMPLE` 里只有 RAW_KIND 6/8，无深睡行）跑一次"打开应用自动静默同步"：
  - **新增恰好 1 条**：`gb-night:1:2026-10-24`，`10-25 01:44 → 08:28`（6h44m，与华为"在床窗口"6h41m 对齐）；
  - 备注 `深 0 分钟 · 浅 8 分钟 · REM 8 分钟`（这份导出只有 16 个稀疏样本，深睡缺失见下方说明）；
  - `health_sync_items` 30 → 31、`time_records` 45 → 46，旧的 10-24 及更早夜全部按去重跳过，符合预期。
- **路径注意（新坑）**：把 GB 文件放 `/sdcard/Download` 并用 `file://` 直读会**静默失败**——Android 13 分区存储禁止应用以 `file://` 读公共目录，而导入处的 `runCatching` 把异常吞了，界面仍显示同步成功。真机只有两条路：**SAF 选文件（content://）**，或先用 `adb run-as` 把文件复制进应用私有目录再读。已按 SAF 方案收尾，测试副本与临时路径全部清掉。

## 深睡为 0 的解释

这份导出的最新一夜只有 16 个睡眠样本（浅 8 / REM 8，无深睡），不是聚合算错，是 GB 对华为表在"科学睡眠(TruSleep)"开启时只能拿到分段起止与偶尔的分期点。用户已在手表上关闭 TruSleep，**关闭之后新同步的夜才会有完整分期**；这份 10-25 的导出产生于关闭之前。

## 本轮踩的坑（重要，别再踩）

### 坑 1：两个 UI 测试的"协程挂起"是误判

`HealthSyncScreenTest` 的两项失败（10 秒超时等不到 outcome 摘要）在交接文档里被写成"同步协程挂起"，并加了 `SyncDiagnosticsTest` 去证明 `outcome=not-called, busy=true`。**这个诊断本身是假象**：Robolectric 默认 PAUSED looper，`viewModelScope.launch` 投递到主循环但没 idle，协程**根本没启动**，所以当然 `not-called`。它证明不了挂起。

真因很简单：0.8.0 新增的 GB 卡片把"立即同步"按钮推到了 891dp 视口之外，`performClick()` 把点击打在屏外坐标上，被静默丢弃——所以点了个寂寞。判别方法：日志里连 `HEALTH-SYNC-DONE clicked` 都没有；如果点击真的触达，至少会打印那一行。

修法：点击前 `performScrollTo()`（`onNodeWithTag("health-sync-now").performScrollTo().performClick()`）。诊断用的 `SyncDiagnosticsTest.kt`、`health/FakeReaderProbe.kt` 已删除，`HealthSyncScreen` 里的两条调试 println 已移除（`TimeViewModel` 的 `HEALTH-SYNC-ERR` 保留，用于真机排障）。

**通用教训**：Compose 测试里对可滚动页面里的节点直接 `performClick()` 不会报错，只会静默失效；凡是等待"点击后出现某内容"的用例，一律先 `performScrollTo()`。

### 坑 2：并行会话抢同一棵源码树

本轮期间另一个会话在开发健身训练模块（`com.timeagent.workout`，含 231MB `assets/workout` 3D 肌群素材），01:12 把 `.paused-workout/` 里的代码挪回源码树，01:14–01:22 持续编辑，并把 workout 入口写进了 `ui/TimeAgentApp.kt` 和 `AndroidManifest.xml`。

处理方式（本次采用）：

- **不动对方的任何文件**。0.8.0 的发布包在项目外的隔离副本中构建：拷贝源码 → 剔除 `com.timeagent.workout` 与 `assets/workout` → 去掉 `TimeAgentApp.kt` 里 4 处 workout 入口 → 去掉清单里的 `WorkoutActivity` / `WorkoutTimerReceiver`。
- 给 `app/build.gradle.kts` 加了**可选**开关 `-PwithoutWorkout`（`android.sourceSets` 的 java exclude + `KotlinCompile.exclude`）。不传该参数时行为完全不变，对方照常构建。

### 坑 3：APK 体积 350MB 的假象

在复用旧 `app/build` 的目录里重新打包，APK 会出现 235MB 的"空洞"（entry 之间的大段死数据），内容是 114MB、文件却是 350MB。原因：增量打包没有截断上一版更大的文件（上一版含 231MB workout 素材）。修法：删掉 `app/build/outputs/apk` 与 `app/build/intermediates/apk` 再打包。

## 用户侧待办（不变）

1. GB 设置 → 自动化 → 意图接口（开启 + 允许触发导出 + 允许活动同步）；自动导出（启用 + 位置 `/Download/Gadgetbridge.db` + 间隔 30 分钟）。
2. 时间 Agent → 我的 → 健康数据同步 → 选同一个 GB 导出文件 → 立即同步。
3. 期望：新增 1 条 `gb-night:...:<日期>`，时段 `01:44 → 08:25`，备注含深/浅/REM 分钟。
4. 10-24 及更早的夜会被去重跳过（HC 已写入同一晚），属预期。
