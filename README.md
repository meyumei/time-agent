# 时间 Agent · Android

> 当前源码版本：`0.15.2-preview / code28`
>
> `compileSdk 36` · `targetSdk 35` · `minSdk 26`

时间 Agent 是一个本地优先的 Android 时间管理与个人管家应用。它在原生
Compose 界面中整合时间轴、任务与周期计划、训练记录、健康数据、个人记忆、
离线语音、内置大肥鱼陪伴和可扩展 Agent 运行时。

项目当前处于预览开发阶段。最新 `0.15.2` 已完成本机构建、定向检查和安装包
核对，但**尚未完成本版真机覆盖安装、全局悬浮窗、锁屏恢复和厂商后台策略
验证**；源码分析或模拟测试不能替代硬件验收。

## 功能概览

- **时间轴与任务**：00:00–24:00 连续时间轴、计划/实际双层记录、冲突提示、
  周期任务、项目、生活期限、提醒、撤销与操作历史。
- **个人管家**：收件箱建议、周目标复盘、个人记忆、历史问答、聊天文字提取、
  本地备份恢复、Health Connect 与 Gadgetbridge 睡眠/训练同步。
- **内置大肥鱼**：Q 版与御姐版角色、档案与换装、动作序列、语音朗读、
  共同记忆、QQ 群聊适配及 B 站连接入口；全局悬浮陪伴默认关闭。
- **语音与输入**：Vosk 实时预览、SenseVoice 整段离线转写、同页指令执行，
  可选 DeepSeek 结构化理解；语音识别与云端能力均保留可见的失败边界。
- **训练模块**：1,226 条动作、1,266 份资源、八类训练记录、模板、超级组、
  阶段计时与离线 3Dbody 肌肉模型。
- **本地优先**：时间、记忆和训练数据默认保存在设备本地；只有用户主动启用
  的在线朗读、云端理解、QQ/B 站连接会把相应内容发送到对应服务。

各版本能力和尚未完成项以[文档索引](docs/README.md)及对应验收记录为准。

## 工程结构

```text
app/                 Android 应用：Compose UI、Room、业务模块和资源
core/                纯 Kotlin/JVM 领域规则与测试
agent-runtime/       arm64 proot 运行时、资源校验和安全解包
agent-plugins/       大肥鱼、DSH memory 与 B 站工具的适配源码
design/              图集、动画、参考图、来源清单与生成记录
docs/                产品、调研、工程、版本与历史文档
scripts/             构建、资源准备、校验和真机辅助脚本
vendor/              固定版本的第三方运行时依赖及许可证
```

以下内容只属于本机工作区，不会进入 Git：

```text
.tmp/ .tools/ .workbuddy/ .archify/ .paused-workout/
evidence/ outputs/ **/build/ local.properties
```

`outputs/` 包含约 10 GB 的历史 APK，`evidence/` 包含本机日志和可能关联个人
数据的验证记录。GitHub 仓库只保留源码、文档、可复现输入及其校验信息。

## 获取与构建

大型模型和运行时归档使用 Git LFS。首次克隆后先执行：

```powershell
git lfs install
git lfs pull
```

构建需要 JDK 21 和 Android SDK 36；Gradle Wrapper 固定为项目当前版本。
Windows 推荐：

```powershell
./scripts/build.ps1 -JavaHome '你的 JDK 21 目录' -AndroidSdk '你的 Android SDK 目录'

# 只跑领域和运行时定向测试
./scripts/build.ps1 -Tasks ':core:test', ':agent-runtime:testDebugUnitTest'
```

也可以直接使用：

```powershell
./gradlew.bat :core:test :agent-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK 默认生成于 `app/build/outputs/apk/debug/app-debug.apk`。构建脚本只
修改当前进程的环境变量，不写入系统配置。个人 `local.properties`、密钥和
本机 SDK 路径不纳入版本控制。

## 当前验证边界

- 2026-10-31 仓库整理后在隔离副本复跑全量主机任务：`core` 82 项、
  `agent-runtime` 7 项、`app` 629 项，共 718 项，0 失败、2 跳过；Debug APK
  打包成功，全部 105 个 Gradle 任务成功。
- 同轮 Lint 为 0 错误、65 警告、2 提示；警告没有声明为已清零。
- 最近一次设备对 `0.15.2` 的覆盖安装与真实悬浮窗交互仍待验证。QQ 真实收发、
  B 站扫码登录、内置 TLS 与语音播放也需要在手机上复验。
- 第三方训练素材、模型与运行时的再分发边界未全部核实，因此本仓库保持私有；
  上线或公开前必须重新审查许可。

最新说明见
[全局悬浮陪伴 0.15.2](docs/releases/全局悬浮陪伴-0.15.2-2026-10-31.md)，
上一版完整整合见
[大肥鱼 0.15.1](docs/releases/大肥鱼档案-换装-声音与管家整合-0.15.1-2026-10-30.md)。

## 文档与第三方依赖

- [文档索引](docs/README.md)
- [MVP 产品需求与技术开发规格书 v0.1](docs/product/MVP-产品需求与技术开发规格书-v0.1.md)
- [课程导入说明](docs/engineering/COURSE_IMPORT.md)
- [课程 JSON 格式](docs/engineering/课程JSON格式.md)
- [第三方依赖与来源记录](THIRD_PARTY_NOTICES.md)

除第三方文件另有许可证外，本项目自身尚未声明开源许可证。未经授权不得复制、
再分发或用于公开商店发布；正式分发前仍需补齐第三方许可与完整依赖 notice。
