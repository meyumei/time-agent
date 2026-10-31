# 7. DSH 保留、统一助手与 BYOK

> 🐟 本章规定大肥鱼（DSH 运行时）如何保留、如何与原生业务解耦、输入如何路由，以及 BYOK。核心原则：**一条鱼、一套权威共同记忆、本地业务不依赖运行时**。

## 1. 现状（基线 660d129）

- 运行时：`agent-runtime/AgentRuntime.kt` 通过 proot 启动 rootfs（含 Node 与 Python），仅 arm64；`fish/FishRuntimeService.kt` 为前台服务（`specialUse`）。
- 协议：DSH SDK 的 **stdio JSON-RPC**，加上本机工具回调（旧文档中的 WebSocket 描述已过时，以代码为准）。
- 插件：`agent-plugins/time-agent/`（`index.mjs`、`host.mjs`、`protocol.mjs`、`qq-channel.mjs`、`qq-voice.mjs`、`character.mjs`、`speech-output.mjs`）、`agent-plugins/dsh-remembers/`（共同记忆）、`agent-plugins/bili-cli/`（B 站）。
- 工具：`fish/FishTimeTools.kt` 提供 10 个 `time_*` 工具。
- `fish/FishSession.kt`：一个巨型类，混合聊天、运行时生命周期、记忆、平台、朗读、角色；`start()` 读取 `AssistantSettings.readApiKey()` 并把 apiKey/model/timezone 传给运行时；`send()` 在运行时未就绪时直接提示“先启动内置角色，输入已保留”。
- `TimeViewModel.fish` 在正常运行时非空，导致**所有输入都发往鱼**，绕过 `LocalAssistantParser`。
- 运行时**不是离线大模型**：推理仍通过用户 Key 调用云端模型。

## 2. 目标拆分

把 `FishSession` 拆成职责单一的组件（Kotlin 侧，拟建名）：

| 组件 | 职责 |
| --- | --- |
| `FishRuntimeController` | 运行时生命周期状态机（§2.1）、前台服务、日志、重启策略 |
| `FishRpcClient` | stdio JSON-RPC 收发、请求超时、取消、重连；协议版本协商 |
| `FishConversationRepository` | 聊天消息本地缓存（显示用）、待发送队列、草稿；权威会话仍在 DSH |
| `FishToolBridge` | 接收工具回调 → 转为 Command → `CommandExecutor`（第 6 章 §4）→ 回传结构化结果 |
| `FishMemoryGateway` | 读取/编辑共同记忆（调用 dsh-remembers 暴露的方法），不在原生侧另存一份 |
| `FishPlatformController` | QQ、B 站连接状态、自主活动设置 |
| `FishVoiceController` | Edge 朗读、鱼语音、与系统 TTS 的选择 |
| `FishPresence` | 角色状态（待机/聆听/思考/说话/开心/困）供 UI、悬浮窗、小组件读取 |

### 2.1 运行时状态机

```
NotInstalled → Installing → Stopped ⇄ Starting → Ready ⇄ Busy
                                  ↘ Failed(reason) ↗
Ready/Busy → Stopping → Stopped
任意 → NeedsKey（无 Key 或 Key 无效）
任意 → UnsupportedDevice（非 arm64）
```
- 每个状态在 UI 有对应文案与操作（启动、重试、查看日志、去填 Key、了解为何不支持）。
- `Failed` 记录原因分类：rootfs 损坏、存储不足、进程退出码、协议不兼容、网络、Key 无效。
- 自动重启：用户可设置“需要时自动启动”（默认开）；连续失败 3 次后停止自动重启并提示。
- 非 arm64 设备：鱼相关入口仍显示，说明不支持原因；所有本地业务可用。

## 3. 输入路由（修复“所有输入都发往鱼”）

### 3.1 规则

所有文字/语音输入（语音面板、鱼聊天输入框、通知快捷回复）统一进入 `InputRouter`（拟建）：

1. **本地指令解析**（`LocalAssistantParser` + `NaturalRecordParser`，增强后）：
   - 高置信度命中 → 生成结构化草稿预览（或按设置直接执行低风险指令）。执行结果作为“鱼的快速通道”展示在同一会话中（卡片标注“本地执行”），并在鱼就绪后作为上下文事件同步给鱼（让鱼知道刚发生了什么，保持一条鱼的连续性）。
   - 低置信度/有歧义 → 若鱼可用，交给鱼；否则显示候选让用户选择。
2. **鱼可用（Ready）** → 发给鱼。
3. **鱼不可用** → 进入待发送队列（聊天页）或给出本地替代（语音面板：存入收件箱 / 作为任务标题 / 稍后交给大肥鱼）。

用户可在聊天中用“@鱼”或长按发送按钮选择“直接交给大肥鱼”跳过本地解析。

### 3.2 本地指令范围（需单测覆盖每条）

| 类别 | 示例 |
| --- | --- |
| 计时 | “开始计时 学习”“开始健身”（匹配快捷记录）“停止”“切换到看书” |
| 专注 | “专注 25 分钟”“开始番茄钟 写论文” |
| 新建日程 | “明天下午三点到四点组会”“周五 9 点提醒我交表”（提醒点） |
| 新建任务 | “添加任务 买牙膏”“下周一前交报告” |
| 补录 | “刚才 2 点到 3 点在开会” |
| 查询 | “今天还有什么安排”“明天下午有空吗”“这周学习了多久” |
| 完成 | “完成 买牙膏” |
| 撤销 | “撤销” |
| 记账 | “午饭 25 元”（生成记账草稿，需确认） |

不支持任意离线自然语言对话；超出范围的输入走鱼或给出本地替代，**不假装理解**。

## 4. 工具协议与业务边界

### 4.1 时间工具改造

- 保留 10 个 `time_*` 工具名与语义（兼容现有插件 `agent-plugins/time-agent/index.mjs` 中的注册）。
- 参数结构化：时间使用 ISO-8601 带时区；日程 kind；版本号；requestId（= toolCallId 派生）。
- 返回 `CommandResult`（第 6 章 §4）的 JSON：status、affected、message、conflict；鱼据此告诉用户结果，不得自行编造成功。
- 新增工具（拟建，按功能上线时加入）：`time_focus`（N03）、`time_habits`（N10 查询）、`time_report`（N05 本地报表数据）、`time_templates`（N09）。新增工具须在插件侧与原生侧同时有协议测试。
- 工具写入的条目 `source=FISH`、`sourceRef=sessionId/messageId`，UI 可回链到对话。
- 敏感操作（批量删除 > 10 条、清空、导出）需用户在 App 内确认，工具返回 `NEEDS_CONFIRMATION` 并在聊天中显示确认卡片。

### 4.2 原生与 DSH 的职责划分

| 归原生 | 归 DSH |
| --- | --- |
| 日程、任务、实际、提醒、专注、习惯、记账、训练、课表、健康的读写与规则 | 人格、对话、共同记忆、复杂多步规划、插件（QQ/B 站/朗读/角色） |
| 统计口径与报表数字 | 报表点评文字 |
| 本地指令解析 | 开放式意图理解 |
| 个人规则存储（`PersonalMemoryRule`） | 读取个人规则作为上下文（只读，修改经工具） |

### 4.3 共同记忆

- 权威存储在 DSH（dsh-remembers）；原生不复制、不建第二套。
- “记忆与规则”页通过 `FishMemoryGateway` 列出、搜索、编辑、删除共同记忆；运行时不可用时显示上次缓存的只读列表（标注“缓存，可能不是最新”），编辑按钮禁用并说明原因。
- 备份 v2 包含鱼数据导出（第 6 章 §6.3）。

## 5. BYOK（只支持自带 Key）

- 设置 → AI 与鱼：服务商（保留现有 DeepSeek，`assistant/DeepSeekClient.kt`；如已有其他兼容 OpenAI 格式的配置项则保留）、Base URL（高级）、模型、Key。
- Key 存储：现有 `AssistantSettings` 已使用 AndroidKeyStore 生成的 AES/GCM 密钥加密后存入 SharedPreferences——保留此实现，不降级；新增服务商配置沿用同一加密方式。
- “测试连接”按钮：发送最小请求，返回成功/Key 无效/余额不足/网络错误等分类结果。
- Key 传给运行时：通过启动参数或 RPC 下发，不写入 rootfs 内可被插件随意读取的明文文件；日志中脱敏（只显示后 4 位）。
- 无 Key：鱼进入 `NeedsKey`，所有本地业务可用；不提供官方代理、免费额度或付费入口。
- 公共导出与备份排除 Key（保持现状）。

## 6. 平台与主动活动

- 保留 QQ（文字与语音）、B 站与平台自主活动。**不改为逐条人工审批**，保留现有授权策略。
- 新安装默认关闭；开启时说明：会以你的账号在哪些平台做什么（发消息、评论、点赞等现有能力）、频率上限、允许时段；用户可随时一键暂停全部平台活动。
- 活动日志：记录每次平台动作（时间、平台、动作类型、摘要），可在“平台”页查看。
- 主动关心：频率、时段、免打扰联动系统勿扰；每天上限可设置。
- 非官方接口风险在附录注明（见第 8 章附录），不据此删除功能。

## 7. 悬浮窗、朗读与角色

- 悬浮窗（`FishOverlayService.kt`、`FishCompanionOverlay.kt`）：保留；读取 `FishPresence` 状态；权限缺失时显示授权引导；与呈现模式独立。
- 朗读：Edge 朗读（`speech-output.mjs`）保留；运行时不可用时可选降级为系统 TTS（设置项，默认关）。
- 角色：形象、精灵动画（`FishCharacterSprite.kt`）、陪伴玩法、贴纸全部保留；不改名字与形象。陪伴模式下在今天页、空状态、操作反馈中出现；工具模式下仅头像。

## 8. 验收

- 飞行模式＋鱼停止：全部本地业务、本地指令可用。
- 鱼 Ready 时说“开始计时 学习”：本地执行，聊天中有“本地执行”卡片，鱼随后知晓该事件。
- 工具重试（模拟超时后重发同 toolCallId）不重复创建。
- 停止生成后已执行的工具动作保留并可撤销。
- 运行时各状态 UI 正确；非 arm64 模拟器不崩溃。
- Key 仍为加密存储（现有 AES/GCM 测试保留）、日志脱敏（单测＋人工检查日志）。
- QQ、B 站、朗读、悬浮窗、共同记忆在新入口下全部可用（账本 L23–L32）。
