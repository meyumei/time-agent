# 第三方依赖记录

本项目没有复制 TimePlanner、Android Week View、WakeUp、时间日志、Simple Time Tracker 或 Open Sunsama 的业务源码。课程导入解析器由本项目实现；训练模块资源集成另见下方记录。

| 直接依赖 / 工具 | 本轮版本 | 上游许可记录 |
| --- | --- | --- |
| [Gradle / Wrapper](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE) | 8.11.1 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt) | 2.1.20 | Apache-2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) | 1.10.2 | Apache-2.0 |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)（Compose、Room、Lifecycle、Activity、Test） | 见 app/build.gradle.kts | Apache-2.0 |
| [KSP](https://github.com/google/ksp/blob/main/LICENSE) | 2.1.20-1.0.32 | Apache-2.0 |
| [Gson](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE) | 2.11.0 | Apache-2.0；本机已下载的 Maven POM 含许可声明 |
| [ML Kit 中文文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) | bundled 16.0.1 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms)；仅处理用户主动选择的图片，识别文字由用户核对保存 |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android/blob/master/LICENSE.txt) | 2.0.27.0 | Apache-2.0；本地 PDF 文字坐标和表格线提取 |
| [jsoup](https://jsoup.org/license) | 1.23.2 | MIT；本地解析用户提取的教务表格 |
| [Vosk Android](https://github.com/alphacep/vosk-api/blob/master/COPYING) | 0.3.75 | Apache-2.0；应用内离线中文识别，APK 内置许可文本 |
| [Vosk 小型中文模型](https://alphacephei.com/vosk/models) | vosk-model-small-cn-0.22 | Apache-2.0；原始 ZIP SHA256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`，内置约 44 MB 模型 |
| [JNA](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE) | 5.18.1 Android AAR | Apache-2.0 / LGPL-2.1 双许可，本项目选择 Apache-2.0；Vosk JNI 桥接 |
| [JUnit 4](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) | 4.13.2 | EPL-1.0；仅测试 |
| [Robolectric](https://github.com/robolectric/robolectric/blob/robolectric-4.15.1/LICENSE) | 4.15.1 | MIT；仅测试 |

候选源码本地许可证核对：TimePlanner 为 Apache-2.0，Android Week View 为 MIT；检查的 commit 见技术选型记录。未将候选库整合进 APK。以上为直接依赖记录，正式分发前仍需对最终运行时依赖图核对并补齐传递依赖和打包 notice。

Android SDK 使用 Google SDK 许可，不作为应用源码分发。构建环境置于忽略目录，APK 不含 SDK/JDK 工具。

PDF 库引入 Bouncy Castle 1.72 作为传递依赖，用于 PDF 加密相关格式；上游许可为 [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html)。本应用不使用其 TLS 信任管理器；教务和 DeepSeek 使用 Android 默认 HTTPS 证书校验。第二轮 Lint 对该依赖中未使用的 TLS 类有三项提示，详见第二轮验收记录。

## 训练模块本地资源集成（2026-10-26）

按用户明确要求，从其提供的训记 7.00.406 与 3Dbody 8.9.50 APK 逆向工程接入原始素材。这些应用及其素材不因此被标记为开源；本次没有核实独立再分发许可，也没有发布到商店或公共站点。

- 训记：1,226 条去重动作数据，1,211 个本地媒体文件（1,205 GIF、5 JPG、1 PNG，204,260,487 字节）。保留原始文件名与 SHA-256 清单。远程讲解视频仅保留研究证据，没有下载或打包。
- 3Dbody：`all.emg` 男性全身模型、43 个原始纹理及原渲染/解码依赖，原始最小集 47 文件、31,695,112 字节。Three.js、Draco 与 GLTF/OrbitControls 取自原包；仅提取加载器与模型解码模块，宿主页及训练交互由本项目编写。没有执行原会员、登录或同步页面。
- 训练数据规则使用 Kotlin 重新实现；没有把训记完整 React Native 业务包嵌入应用。以 kg 保存重量，并对原版单位混用作了修正；原版“自重类别递减段强制按单位负荷计量”等特例没有照用。

资源校验结果：`evidence/training-reverse-20261025/asset-verification-20261026.json`。来源、逐文件哈希、逆向定位及原包边界见该目录的 `REPORT.md` 与 manifest，以及包内 `workout/asset-provenance.json`、`workout/anatomy/model-manifest.json`。

## 大肥鱼交互参考（2026-10-30）

- 上游：[luo_sang/qqbot](https://gitee.com/luo_sang/qqbot)，固定提交 `6cbae5ad19cd068e893539753b3f3c54d07543d5`。已读取该提交的 `bot.py`、`persons.py`、相关测试和 `LICENSE`；许可为 Apache-2.0，原文保存在 `docs/upstream/qqbot-LICENSE`。
- 本轮借鉴其全局心情随时间回落、作息时间窗、按情境选择表情、群聊短上下文和节制接话的设计。Android 状态机、Compose 角色与 OneBot HTTP 适配由本项目实现，没有复制其整个机器人、账号配置、人物档案或上游表情包资源。具体取舍见 `docs/releases/大肥鱼动态陪伴与群聊-0.14.0-2026-10-30.md`。
- 角色原始参考图由用户提供，只读保留。新增两版姿势由内置图像生成工具基于参考图生成，透明 PNG 切片、原生图集、动画和来源记录见 `design/fish-companion/asset-manifest.json`。没有下载上游第三方人物表情包。

## 离线语音转写与按住说话（2026-10-30，0.14.1）

- 延续上述固定提交的 `bot.py` 收到 `record` → 转为单声道 16 kHz WAV → 实际识别 → 放入原群会话的流程。上游使用 Python、pysilk、FFmpeg 和 faster-whisper；Android 版由 OneBot/NapCat 转换 QQ 音频，以原生 SenseVoice 完成转写，没有把上游服务器整套运行环境装入应用。
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1)：使用官方 `sherpa-onnx-2.0.1.aar`，Apache-2.0；包内包含 ONNX Runtime 与 sherpa 原生库。AAR SHA-256 为 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`。源码、AAR 与构建副本的匹配记录见本轮验收报告。
- [SenseVoice 代码](https://github.com/FunAudioLLM/SenseVoice)：代码仓库 LICENSE 为 MIT。所用 int8 模型来自 [csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07)，固定 revision `2365baeacb507f821a0c8120fcee3d484dba7a07`；权重 SHA-256 为 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`。该模型仓库 LICENSE 指向 FunASR 许可说明，不能据代码许可把模型权重独立标为 MIT 或 Apache-2.0；原始许可引用照原样保留。
- [RecordView](https://github.com/3llomi/RecordView)：阅读 `RecordView.java`、`RecordButton.java` 及 README，借鉴录音完成与取消事件分开的交互方式。没有引入该库，也没有复制其 View 实现。Compose 按钮继续由本项目实现，修正横向漂移误取消与布局移动造成的坐标误差。
- [NapCatQQ](https://github.com/NapNeko/NapCatQQ)：依据 `GetRecord.ts`、`DownloadFileRecordStream.ts`、`StreamBasic.ts`、`BaseDownloadStream.ts` 和 HTTP 路由实现适配；优先读取 `get_record` 返回的转换后 base64，对只返回服务器路径的情况读取 WAV 分块流。没有把服务端路径当成 Android 本地路径，也没有复制 NapCat 程序。
- 本轮取得的 sherpa、SenseVoice 代码许可和模型许可引用保存在 APK 的 `assets/asr/licenses/`；模型与 AAR 的固定版本、来源、大小和散列保存在 `assets/asr/sensevoice/manifest.json`。Vosk 仍用于实时预览，最终文字使用整段真实录音的 SenseVoice 结果，不调用云端语音识别。


## Payment notification accounting reference (2026-10-30)

- Upstream: FuShengDuoBuYu/AutoBookKeepingBeta, MIT, Copyright (c) 2026 FuShengDuoBuYu.
- Local notification parsing and pending-confirmation flow were informed by NotificationBillParser.java and NotificationReceiver.java. This app implements its own Room ledger, integer-cent amounts, Compose UI, deduplication and CSV reconciliation.
- Source archive SHA-256: `c7db767a542cffccf976ffe0dee6cbda8f823e230eab411837ae8c7973ccef3a`. Exact license and source paths: `docs/upstream/AutoBookKeepingBeta-LICENSE` and `docs/upstream/AutoBookKeepingBeta-source.json`.

## 大肥鱼朗读、角色卡与 HTTPS 证书（2026-10-30，0.15.1）

- 沿用上述固定提交的 qqbot `Tts` / `edge_tts.Communicate` 方案，Android 直接播放 MP3；没有引入 QQ 专用的 FFmpeg / SILK 转码链。在线朗读会把选中的文字发给 Edge 语音服务，也可选择手机系统语音引擎。
- `rany2/edge-tts` 7.3.1 使用未修改的官方 PyPI wheel。大部分代码采用 LGPLv3，只有 `srt_composer.py` 采用 MIT，不能把整个包标为 MIT。上游地址、各 wheel 名称、版本和 SHA-256 在 `vendor/edge-tts/manifest.json`，原始许可与说明在同目录的 `LICENSE`、`NOTICE.md`。打包保留各依赖的 `dist-info` 许可和 Edge TTS notice；Linux aarch64 / CPython 3.12 依赖随内置运行时解包，未修改依赖源码。
- 阅读了 Open-LLM-VTuber 的 `src/open_llm_vtuber/tts/edge_tts.py`，作为其 Edge 语音适配的参考；没有复制该项目或其模型。实现使用本项目的标准输入桥接、原有运行时消息和 Android MediaPlayer。
- 档案 JSON 参考 `malfoyslastname/character-card-spec-v2` 的 Character Card V2 格式，支持姓名、背景、性格、场景、问候语和对话示例，并保留导入卡中的未知字段。没有复制上游角色卡或人物设定。
- CA 证书包取自 curl 官方 `https://curl.se/ca/cacert.pem`，保留 Mozilla 来源、许可和生成日期头；本轮快照日期为 2026-10-20。完整文件在 `agent-plugins/time-agent/cacert.pem`，SHA-256 在运行资源锁 `agent-runtime/src/main/assets/fish/runtime-lock.json`。内置 Python / Node 显式使用该包，HTTPS 证书校验保持开启。
- 用户补充的红色工作服参考用于生成单张透明正面衍生图，另四张参考图按原文件复制；原图未覆盖。来源、逐文件散列和当前动作范围见 `design/fish-companion/refresh-20261030/asset-manifest.json`。红色工作服现为单姿势加呼吸效果，尚不是一整套新服装动作图集。
