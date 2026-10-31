# 阶段 0 基线测试与包体报告（T0.2）

日期：2026-10-31（Asia/Shanghai）。业务源码基线 `660d129`；执行时提交 `61e4806`，只新增 T0.1 文档。以下数字全部来自本轮执行日志、JUnit XML、Lint XML 与新 APK ZIP 目录，未引用 README 或历史测试数。

## 环境与命令

- Windows 11，Microsoft OpenJDK 21.0.12.1，Gradle 8.11.1，AGP 8.9.2，Kotlin 2.1.20。
- Android SDK：项目 `.tools/android-sdk`，已安装 platform android-36，Build Tools 35.0.0/36.0.0；`local.properties` 指向该目录。compileSdk=36、minSdk=26、targetSdk=35，本轮没有修改这些业务构建声明。
- Node v24.16.0、Python 3.12.10。JDK/SDK 路径读取已有 `.tools/environment.json`；环境设置仅对本轮进程生效，不向仓库提交机器绝对路径配置。
- 用 `--rerun-tasks` 实跑全部任务，不使用旧 XML 作为通过证据。105 个 Gradle 任务全部执行；Debug 打包与 Lint 成功。

PowerShell 复现：

```powershell
$config = Get-Content .tools/environment.json -Raw | ConvertFrom-Json
$env:JAVA_HOME = $config.javaHome
$env:ANDROID_HOME = $config.androidSdk
$env:ANDROID_SDK_ROOT = $config.androidSdk
.\gradlew.bat :core:test :agent-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain --no-parallel --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=GBK'
Push-Location agent-plugins/time-agent
node --test test/*.test.mjs
Pop-Location
```

GBK 只用于 Windows 原生 Java 参数文件编码，不改变 Kotlin 源码或业务数据编码。未修改任何业务源码、UI、实体、schema 或依赖版本。

## 本轮结果

| 项目 | 测试总数 | 通过 | 失败/错误 | 跳过 | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| core | 82 | 82 | 0 | 0 | 成功 |
| agent-runtime | 7 | 7 | 0 | 0 | 成功 |
| app | 629 | 627 | 0 | 2 | 成功 |
| Node time-agent 插件 | 19 | 19 | 0 | 0 | 退出 0 |

Gradle 单元测试合计：**718 项，716 通过、2 跳过、0 失败/错误**。最终完整命令退出 0，耗时 5 分 43 秒。Node 本轮 19 项均通过，耗时约 0.59 秒。

Lint：**0 错误、73 警告、2 信息项**。警告未清零，本轮不修改业务处理。

跳过项：

- `com.timeagent.imports.PdfTimetableExtractorTest.locallyProvidedScutPdfHasSixteenScheduledRows`。
- `com.timeagent.ui.AppFlowTest.private PDF imports into Room and renders the full day timeline`。

完整新日志与逐 suite 计数：[`gradle-baseline.txt`](baseline-evidence/gradle-baseline.txt)、[`node-baseline.txt`](baseline-evidence/node-baseline.txt)、[`test-summary.json`](baseline-evidence/test-summary.json)。执行起止时刻及退出码见 [`gradle-baseline.json`](baseline-evidence/gradle-baseline.json)。

### 首次失败与环境修复

首次原生 UTF-8 daemon 运行退出 1：`:agent-runtime:testDebugUnitTest` 的 Java worker 在加载 `worker.org.gradle.process.internal.worker.GradleWorkerMain` 时 ClassNotFoundException，并出现“管道正在被关闭”。这发生在测试执行器启动阶段，没有业务断言失败可报告。

按现有 Windows 构建范例将 daemon 参数文件编码改为 GBK，先单跑 runtime 证明执行器恢复，再完整重跑。初次错误原文见 [`gradle-initial.txt`](baseline-evidence/gradle-initial.txt)；探测记录见 [`gradle-encoding-probe.json`](baseline-evidence/gradle-encoding-probe.json)。没有为通过测试改断言、跳过测试或排除训练模块。最终运行仍出现 SDK XML 版本提示和一次 AAPT 中文路径 stat warning，任务最终成功，不能将这些警告写成已修复。

## 新 APK 与组成

- 文件：`app/build/outputs/apk/debug/app-debug.apk`；版本 `0.15.2-preview / 28`。
- 实测大小：**890,073,507 bytes = 848.84 MiB = 890.07 MB**。
- SHA-256：`13ae925f380092dd707e35a0b3a14259dd6f587c213036def738810970bcdd36`。
- 本轮 `apksigner verify` 退出 0；未安装到手机。

占比按 ZIP 条目的 `compress_size`（APK 内实际存储字节）除以 APK 文件字节计算；解压大小另列。下表互斥，不把运行时、GIF、模型或各 ABI 重复累加。

| 组成 | 条目数 | APK 内字节 | MiB | 占 APK | 解压 MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 3D 肌肉模型及配套 anatomy 资源 | 51 | 30,731,837 | 29.31 | 3.45% | 30.91 |
| ABI arm64-v8a | 12 | 21,209,838 | 20.23 | 2.38% | 50.65 |
| ABI armeabi-v7a | 8 | 18,585,552 | 17.72 | 2.09% | 36.22 |
| ABI x86 | 8 | 24,034,035 | 22.92 | 2.70% | 56.31 |
| ABI x86_64 | 8 | 23,417,049 | 22.33 | 2.63% | 55.11 |
| DEX/资源/其他载荷 | 831 | 40,974,882 | 39.08 | 4.60% | 102.64 |
| GIF 动作图 | 1205 | 202,426,773 | 193.05 | 22.74% | 193.05 |
| Linux/DSH 运行时 5 个 bin | 5 | 239,070,940 | 228.00 | 26.86% | 228.00 |
| 其余鱼素材/配置 | 11 | 3,564,002 | 3.40 | 0.40% | 3.42 |
| 其余训练资源 | 10 | 2,319,911 | 2.21 | 0.26% | 3.56 |
| 语音模型与词表 | 9 | 283,291,344 | 270.17 | 31.83% | 270.34 |
| ZIP 目录/签名/对齐开销 | — | 447,344 | 0.43 | 0.05% | — |

运行时细分（已包含在上表，不再次相加）：

| 文件 | 字节 | 占 APK |
| --- | ---: | ---: |
| `dsh-runtime.bin` | 127,308,331 | 14.30% |
| `glibc-python.bin` | 6,565,414 | 0.74% |
| `offline-rootfs.bin` | 85,783,057 | 9.64% |
| `python-support.bin` | 904,360 | 0.10% |
| `time-agent-overlay.bin` | 18,509,778 | 2.08% |

语音主模型 `assets/asr/sensevoice/model.int8.onnx` 为 239,233,841 bytes，Vosk 压缩模型为 43,898,754 bytes。GIF 共 1,205 个；3D 分类包含 `assets/workout/anatomy/` 配套文件，不能把分类总量都称作单个模型文件大小。

完整计算结果与最大 20 个条目见 [`apk-breakdown.json`](baseline-evidence/apk-breakdown.json)。本轮仅测量定位，没有删 ABI、rootfs、模型、GIF 或训练素材。

## 验收边界与下一步

本轮通过的是主机单元测试、Node 测试、构建和 Lint；未执行 `connectedDebugAndroidTest`，没有模拟器/真机性能、后台、开机、锁屏、时区、平台登录或运行时 arm64 启动的通过结论。主机通过不能替代账本的 36 项真机补证。受影响账本 L01–L36：业务代码未修改；这些结果建立后续回归基线。

T0.3 生成后新增夹具测试单独报告，不回填为本节的改造前测试总数。阶段 0 完成可执行交付后，建议下一张开发任务为 **T1.1：Hilt 与数据库单例**；仍不在本 PR 实施。
