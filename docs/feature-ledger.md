# 功能保留账本（T0.1）

初始源码核查：2026-10-31；真机跟进：2026-11-01（Asia/Shanghai）。业务基线：`660d129`，`0.15.2-preview / versionCode 28`。权威能力清单见 `product/全面改造规格-2026-10-31/01-产品边界-术语与功能保留账本.md`。

本轮只建立基线，不改业务实现、UI 或数据库版本。36 项原有子能力全部保留在下表。“目标入口”来自规格，尚未实施；“现状入口”来自当前代码，不把未来导航当作已存在。

**验收状态：已补真机页面截图、专项数据比较和 3 项仪器测试；仍未达到 36 行全部子能力真机通过。** 逐行列明已测与缺口；不能把入口截图、DS success 或主机单测当作全项通过。设备跟进见 [真机报告](device-baseline-report.md)，[证据目录](ledger-evidence/device-20261101/manifest.json)。

各 `Lxx-source.json` 保留 2026-10-31 的静态清单及当时无设备的历史状态；本次真机证据另存 `device-20261101/`。下表操作步骤仍是完整保留能力的验收清单，只有证据列明确写出的动作实际执行。截图均已遮挡未知私人文字及其他应用窗口；没有提交原图、原库、Key、聊天或共同记忆原文。

2026-11-01 后续轮重新备份手机当前记录后续测 L11，但目标应用持续不在前台，主代理终止 DS，该轮没有有效新增截图或通过项；L36 仅准备了合成夹具，未执行交接。原 39 文件与两库 37 表（含元数据）的计数/内容全部恢复，详细失败及恢复证据见 [后续轮记录](device-baseline-report.md#2026-11-01-再次清理与训练续测)。不把此前动作选择器的采集文案问题或此次前台切换推断为产品缺陷。

用户随后明确结束后续基线测试并授权转入开发。本轮又完成 L02 进程重启恢复与 L11 基础训练流程，新增 7 张有效脱敏截图；原记录已再次完整恢复。新增证据见 [本轮报告](device-baseline-report.md#2026-11-01-当前测试结束并转入开发)。未测子能力继续列为缺口。

## 现状入口、操作与证据

| 编号 | 模块与必须保留的子能力 | 现状入口路径（代码路由） | 核查步骤（完整清单，非全部已执行） | 本轮证据文件名 / 待补文件名 | 目标入口（尚未实施） |
| --- | --- | --- | --- | --- | --- |
| L01 | **时间轴**：计划/实际双轨；实际可重叠；冲突可保存并提示；撤销/重做；乐观锁版本；历史 | 今天 → 时间轴（Timeline） | 创建任务和计划；补录两条重叠实际；保存冲突并检查提示；编辑后用旧版本保存；撤销、重做并查看历史。 | [静态清单](ledger-evidence/L01-source.json)；[L01-today-timeline.png](ledger-evidence/device-20261101/L01-today-timeline.png)；[L01-conflicts.png](ledger-evidence/device-20261101/L01-conflicts.png)；**部分验证**：已查看双轨与冲突列表；未完成重叠保存、撤销重做及版本冲突全流程。 | 今天、计划 |
| L02 | **计时**：开始/停止/切换；补录；跨天；进程重启恢复 | 计划 → 任务 → 开始计时；复盘 → 新建 → 开始自由计时；顶部计时条 | 开始、停止、切换任务；补录时间；跨午夜计时；在示例设备杀进程再打开，对比起止与正在计时状态。 | [静态清单](ledger-evidence/L02-source.json)；[L02-timer-running.png](ledger-evidence/device-20261101/L02-timer-running.png)；[L02-timer-stopped.png](ledger-evidence/device-20261101/L02-timer-stopped.png)；**部分验证**：DS 开始/停止计时；独立读库确认新增 1 条实际、无运行中记录。[进程重启前后截图及独立核对](ledger-evidence/device-20261101-continue/timer-independent-check.json)证实同一计时 id/startAt 恢复且结束后仅 1 条关联实际；切换、跨天仍未测。 | 今天顶部计时条、通知栏、小组件 |
| L03 | **任务**：新建/编辑/完成/恢复；项目归属；截止 | 计划 → 新建任务 / 任务 → 查看全部 → 任务详情 | 新建并编辑标题、时长、截止；在项目页加入已有任务；完成后确认未自动生成实际；在已完成筛选中点重新打开并核对原记录。 | [静态清单](ledger-evidence/L03-source.json)；[L03-task-list-after-create.png](ledger-evidence/device-20261101/L03-task-list-after-create.png)；[L03-completed-list.png](ledger-evidence/device-20261101/L03-completed-list.png)；[完成前后读库](ledger-evidence/device-20261101/task-completion-result.json)；**部分验证**：已新建并完成合成任务；完成前后实际均为 10000、关联实际为 0。编辑/恢复/项目归属未全测。 | 计划 → 任务 |
| L04 | **截止**：截止列表、状态、提醒 | 计划 → 生活期限 → 查看全部；今天 → 处理期限（deadlines） | 新建期限规则；生成实例；完成、跳过和重开；配置提前提醒，核对状态、提醒时间与历史。 | [静态清单](ledger-evidence/L04-source.json)；[L04-life-deadlines.png](ledger-evidence/device-20261101/L04-life-deadlines.png)；[真实时区重排](ledger-evidence/device-20261101/timezone-restore.json)；**部分验证**：已查看期限列表；周期截止的时区重排另有实机测试。状态操作和真实通知送达未测。 | 计划 → 截止；今天卡片 |
| L05 | **项目**：项目列表、项目复盘 | 计划 → 项目 → 查看全部（projects/project:id） | 新建项目；关联任务/子任务；安排任务时间；进入项目详情查看计划/实际与项目复盘。 | [静态清单](ledger-evidence/L05-source.json)；[L05-projects-list.png](ledger-evidence/device-20261101/L05-projects-list.png)；[L05-project-detail.png](ledger-evidence/device-20261101/L05-project-detail.png)；**部分验证**：已进入项目列表和详情；创建、关联与项目复盘口径未全测。 | 计划 → 项目；复盘 → 项目 |
| L06 | **周期事务**：规则编辑、实例生成、手工例外、规则版本、历史快照 | 计划 → 周期习惯 → 查看全部；今天 → 本次习惯（recurring） | 新增各类规则并生成实例；改期单次/完成/跳过；再改规则，确认手工例外、规则版本及历史快照保持。 | [静态清单](ledger-evidence/L06-source.json)；[L06-recurring-habits.png](ledger-evidence/device-20261101/L06-recurring-habits.png)；[真实时区重排](ledger-evidence/device-20261101/timezone-restore.json)；**部分验证**：已查看习惯列表；周期习惯/期限时区重排有实机测试。规则编辑和手工例外仅主机夹具验证。 | 计划 → 周期 |
| L07 | **提醒**：精确闹钟、通知、通知权限检测 | 计划 → 收集想法 → 收件箱 → 新提醒；提醒权限卡 | 新增将来的提醒；允许/拒绝通知与精确闹钟；检查提示、通知和送达状态；按专项步骤复验系统重排。 | [静态清单](ledger-evidence/L07-source.json)；[L07-reminder-entry.png](ledger-evidence/device-20261101/L07-reminder-entry.png)；[真实时区重排](ledger-evidence/device-20261101/timezone-restore.json)；**部分验证**：设备通知允许、精确闹钟未授权；真实时区变更后 3 个已有提醒恢复。开机、实际送达及权限拒绝场景未测。 | 全局；设置 → 提醒 |
| L08 | **课表**：JSON/PDF/华工教务导入；预览；去重；手工修改 | 管家 → 大肥鱼的一切 → 应用设置 → 课表 JSON 导入 / PDF 与教务导入（settings/import） | 分别导入示例 JSON/PDF/华工教务课表；查看预览后确认；重复导入验证去重；手改单次课程后再导入确认保护。 | [静态清单](ledger-evidence/L08-source.json)；[L08-import-settings-entry.png](ledger-evidence/device-20261101/L08-import-settings-entry.png)；**部分验证**：仅设置中的导入入口；点击 JSON 跳系统文件选择器。未导入、未预览，不能将返回后设置截图算作导入通过。 | 管家 → 课表；计划中显示 |
| L09 | **健康**：Health Connect、Gadgetbridge 导入；睡眠来源去重；缺失不当零 | 复盘 → 健康与训练；管家 → 大肥鱼的一切 → 应用设置 → 健康同步（health） | 授权 Health Connect；导入示例 Gadgetbridge 数据；重复同步并比较睡眠来源去重；无数据日确认显示缺失而非零。 | [静态清单](ledger-evidence/L09-source.json)；[L09-health-sync.png](ledger-evidence/device-20261101/L09-health-sync.png)；**部分验证**：已进入健康同步页；未读取/导入健康数据，去重与缺失口径未测。 | 管家 → 健康；复盘 |
| L10 | **记账**：支付通知监听（默认关）；草稿待确认；整数分；CSV；合并/拆分 | 管家 → 大肥鱼的一切 → 应用设置 → 支付记账（LedgerActivity） | 核对监听默认关闭；开启后用示例支付通知产生草稿并确认；按整数分编辑；CSV导入/导出；分别核对交易及记录整理支持的合并/拆分边界。 | [静态清单](ledger-evidence/L10-source.json)；[L10-ledger.png](ledger-evidence/device-20261101/L10-ledger.png)；**部分验证**：已查看账本和默认关闭的支付监听；未交易 CRUD、CSV、通知捕获或交易合并拆分。 | 管家 → 记账 |
| L11 | **训练**：动作库+GIF；3D 肌肉；8 种记录类型（WEIGHT_REPS、REPS、DURATION、BODYWEIGHT_REPS、ASSISTED_REPS、CARDIO、STRETCH、TABATA）；组类型 NORMAL/WARMUP/DROP/FAILURE；状态 ACTIVE/PAUSED/COMPLETED/DISCARDED；超级组；Tabata；模板；组间休息计时；训练通知 | 今天 → 训练；计划 → 开始训练（WorkoutActivity） | 打开动作库/GIF和3D肌肉；逐一使用8种记录类型、4种组类型及4种会话状态；超级组、Tabata、模板、组间休息和通知分别执行；结束训练对比实际记录。 | [静态清单](ledger-evidence/L11-source.json)；[L11-workout-home.png](ledger-evidence/device-20261101/L11-workout-home.png)；[L11-exercise-gif.png](ledger-evidence/device-20261101/L11-exercise-gif.png)；[L11-anatomy.png](ledger-evidence/device-20261101/L11-anatomy.png)；[L11-templates.png](ledger-evidence/device-20261101/L11-templates.png)；[L11-active-session.png](ledger-evidence/device-20261101/L11-active-session.png)；**部分验证**：动作详情图像可见、3D 模型已人工看图确认；打开模板编辑并启动/放弃空训练。本轮已完成 20kg×8次的 WEIGHT_REPS、组间休息、暂停/恢复、模板及结束训练，[完成截图](ledger-evidence/device-20261101-continue/L11-round2-completed.png)与[独立读库](ledger-evidence/device-20261101-continue/workout-independent-check.json)证实恰好 1 条关联实际。其他 7 类记录、全部组类型、超级组和 Tabata 未逐项实测。 | 管家 → 训练；今天快捷入口 |
| L12 | **附件**：附件存储、OCR、关联到条目 | 计划 → 附件；任务详情 → 附件收件箱（attachments） | 选取示例文件并保存；图片/PDF OCR；关联任务/项目/条目后重开，检查附件原件、识别文本及关联。 | [静态清单](ledger-evidence/L12-source.json)；[L12-attachment-inbox.png](ledger-evidence/device-20261101/L12-attachment-inbox.png)；**部分验证**：已进入附件页；未新增文件、执行 OCR 或关联。 | 管家 → 附件；各详情页 |
| L13 | **收件箱**：收集、分拣到任务/日程/记账 | 计划 → 收集想法；任务列表 → 未安排（inbox） | 收集想法/文字；编辑后分拣到现有支持的任务或提醒/日程路径；逐一核查记账分拣是否存在，不将目标能力当作现状。 | [静态清单](ledger-evidence/L13-source.json)；[L13-inbox.png](ledger-evidence/device-20261101/L13-inbox.png)；**部分验证**：已查看想法/未安排入口；未实际收集分拣，任务/日程/记账目标仍需核实。 | 管家 → 收件箱；语音结果 |
| L14 | **聊天提取**：从聊天文本提取事项 | 管家 → 大肥鱼的一切 → 应用设置 → 聊天事项提取（extract） | 粘贴不含个人信息的示例聊天；提取事项并核对时间歧义；确认后保存；取消时确认没有写入。 | [静态清单](ledger-evidence/L14-source.json)；[L14-chat-extraction.png](ledger-evidence/device-20261101/L14-chat-extraction.png)；**部分验证**：已进入聊天提取表单；未提交提取。 | 管家 → 工具 |
| L15 | **回复副驾**：生成回复建议 | 计划 → 任务 → 查看全部 → 回复副驾（reply）；聊天提取 → 回复 | 输入示例对话；生成回复与安排建议；核对空档/澄清；确认后保存；返回检查建议和实际写入一致。 | [静态清单](ledger-evidence/L15-source.json)；[L15-reply.png](ledger-evidence/device-20261101/L15-reply.png)；**部分验证**：已进入回复副驾表单；未生成/确认建议。 | 管家 → 工具 |
| L16 | **个人规则**：规则增删改、被业务/鱼读取 | 管家 → 大肥鱼的一切 → 应用设置 → 个人记忆/规则（memory） | 新建、编辑、停用、删除示例规则；核对排程/鱼读取结果；与 DSH 共同记忆分开验证。 | [静态清单](ledger-evidence/L16-source.json)；[L16-personal-rules.png](ledger-evidence/device-20261101/L16-personal-rules.png)；**部分验证**：已进入个人规则页；未增删改规则或验证业务/鱼读取。 | 管家 → 鱼 → 记忆与规则 |
| L17 | **历史问答**：基于历史数据回答 | 复盘 → 历史明细（history）；管家 → 大肥鱼的一切 → 应用设置 → 历史 | 在示例记录中查询历史；核对答案引用的实际条目；打开原记录验证；无数据时检查不虚构。 | [静态清单](ledger-evidence/L17-source.json)；[L17-history.png](ledger-evidence/device-20261101/L17-history.png)；**部分验证**：已进入历史查询页；未核对真实查询回答。 | 复盘 → 问答 |
| L18 | **建议**：建议生成与一键执行 | 计划 → 收集想法 / 打开收件箱 → 助手建议（SuggestionActions） | 建立可触发建议的示例安排；查看建议；一键执行后核对实体、版本与撤销；过期/冲突建议检查拒绝行为。 | [静态清单](ledger-evidence/L18-source.json)；[L18-suggestions.png](ledger-evidence/device-20261101/L18-suggestions.png)；**部分验证**：已查看建议卡片及操作入口；未执行、拒绝或延后建议。 | 今天卡片；复盘 |
| L19 | **每日简报**：简报生成与展示 | 今天鱼摘要；管家聊天首项 → 完整简报（DailyBriefing） | 建立示例日程/截止/健康信息；展开、收起简报；打开下一项；检查无数据与跨日时的文案。 | [静态清单](ledger-evidence/L19-source.json)；[L19-briefing.png](ledger-evidence/device-20261101/L19-briefing.png)；**部分验证**：已展开合成数据的每日简报；空数据/跨日与逐项口径未测。 | 今天顶部 |
| L20 | **周目标**：设定与追踪、与项目进度联动 | 计划 → 本周目标 → 查看周目标（goals）；项目复盘 | 设置项目/活动周目标；新增关联实际记录；检查目标分钟数、项目进度和周复盘之间的口径。 | [静态清单](ledger-evidence/L20-source.json)；[L20-weekly-goal.png](ledger-evidence/device-20261101/L20-weekly-goal.png)；**部分验证**：已打开周目标编辑后取消；未保存或验证项目联动。 | 复盘 → 周；计划 |
| L21 | **语音**：Vosk 实时预览＋SenseVoice 终稿；按住说话；Android 语音输入；终稿只提交一次；迟到结果不覆盖用户编辑 | 底部语音动作 / 按住说话（HomeVoiceDock/HoldToTalkButton） | 授权麦克风；按住说话观察Vosk预览与SenseVoice终稿；另测系统语音；重复/迟到终稿和手改草稿，确认不重复提交、不覆盖编辑。 | [静态清单](ledger-evidence/L21-source.json)；[L21-voice-panel.png](ledger-evidence/device-20261101/L21-voice-panel.png)；[L21-voice-text-input.png](ledger-evidence/device-20261101/L21-voice-text-input.png)；**部分验证**：已进入面板并输入合成文字；未收音，Vosk/SenseVoice/系统语音及迟到终稿未测。 | 中央语音动作 |
| L22 | **本地指令**：查日程/任务/空闲、开始/停止计时、新建任务等 | 语音面板；管家聊天输入（LocalAssistantParser / NaturalRecordParser） | 分别查询日程、任务、空闲；开始/停止计时并新建任务；断网、无Key、鱼停止时重试；核对输入路由是否真正进入本地解析。 | [静态清单](ledger-evidence/L22-source.json)；[原生工具实参/返回](ledger-evidence/device-20261101/native-time-tools.json)；**部分验证**：原生 time_quick_action 实机调用已返回任务查询；生产输入路由、无网/无 Key 的全部本地指令未验收。见 native-time-tools.json。 | 语音面板；鱼聊天快速通道 |
| L23 | **鱼聊天**：会话、表情贴纸、工具调用结果 | 管家（直接显示 FishChatScreen） | 使用独立示例会话发送、取消和重开；检查历史、表情贴纸、工具状态与结果；不能用模型回复代替业务写入核对。 | [静态清单](ledger-evidence/L23-source.json)；[L23-fish-chat.png](ledger-evidence/device-20261101/L23-fish-chat.png)；**部分验证**：合成草稿往返保留，并尝试发送一次；回复及工具 RPC 未证实。短时失去前台不能据此断言崩溃。 | 管家 → 鱼；语音面板 |
| L24 | **鱼角色**：形象、精灵动画、状态、陪伴玩法 | 管家 → 聊天右上角 大肥鱼的一切 → 个人档案 / 换装 / 表情动作 / 陪伴与作息 | 预览原有形象、动画与状态；执行已有陪伴玩法；切页返回检查状态；保留原名和原始形象素材，不将娱乐玩法写入业务统计。 | [静态清单](ledger-evidence/L24-source.json)；[L24-fish-hub.png](ledger-evidence/device-20261101/L24-fish-hub.png)；[L24-profile.png](ledger-evidence/device-20261101/L24-profile.png)；[L24-outfit.png](ledger-evidence/device-20261101/L24-outfit.png)；[L24-expressions.png](ledger-evidence/device-20261101/L24-expressions.png)；**部分验证**：已查看原角色、档案、换装和表情页面；未保存修改或遍历陪伴玩法。 | 陪伴模式全局；管家 → 鱼 |
| L25 | **悬浮窗**：悬浮鱼、交互、服务 | 大肥鱼的一切 → 陪伴与作息 → 全局悬浮陪伴（fish-appearance） | 先拒绝后允许悬浮权限；跨应用显示、拖动、点击和长按；锁屏/旋转后复验；关闭通知入口，确认不停止原鱼。 | [静态清单](ledger-evidence/L25-source.json)；[L25-companion.png](ledger-evidence/device-20261101/L25-companion.png)；**部分验证**：只验证陪伴设置页；悬浮权限未授权，未跨应用显示、拖动或锁屏实测。 | 管家 → 鱼 → 悬浮窗 |
| L26 | **朗读**：Edge 朗读（speech-output 插件）、鱼语音 | 大肥鱼的一切 → 声音（fish-voice）；聊天消息 → 朗读 | 启动原运行时；选Edge音色、试听、改语速和自动朗读；逐条朗读历史；断网失败与系统语音分别验证。 | [静态清单](ledger-evidence/L26-source.json)；[L26-voice-settings.png](ledger-evidence/device-20261101/L26-voice-settings.png)；[L26-voice-preview.png](ledger-evidence/device-20261101/L26-voice-preview.png)；**部分验证**：已进入声音页并点试听；没有听觉或音频证据，Edge 链路/断网恢复未验收。 | 管家 → 鱼 → 声音 |
| L27 | **共同记忆**：dsh-remembers 插件、记忆查看 | 管家 → 大肥鱼的一切 → 共同记忆（fish-memory） | 在示例会话形成记忆；查看/查询共同记忆与最近见闻；重启运行时确认同一权威存储；不把个人规则等同共同记忆。 | [静态清单](ledger-evidence/L27-source.json)；[L27-shared-memory.png](ledger-evidence/device-20261101/L27-shared-memory.png)；**部分验证**：进入时提示需运行时，未读记忆内容；存储已随原始备份恢复，未验证新增/查询记忆。 | 管家 → 鱼 → 记忆与规则 |
| L28 | **主动关心**：主动消息、频率与时段 | 大肥鱼的一切 → 主动话题（fish-proactive） | 读取主动配置；修改频率与允许时段；保存后重开检查；在时段内/外核对主动消息，不发送到真实联系人。 | [静态清单](ledger-evidence/L28-source.json)；[L28-proactive-topics.png](ledger-evidence/device-20261101/L28-proactive-topics.png)；**部分验证**：已查看主动话题配置；未改配置或触发主动消息。 | 管家 → 鱼 → 主动 |
| L29 | **QQ**：QQ 频道、QQ 语音 | 大肥鱼的一切 → 平台连接 → QQ群聊（FishQQSettings） | 使用测试OneBot服务配置、保存并检查连接；在授权测试群收发文本和语音；验证QQ语音转文字及开关边界，需用户另行授权实际发送。 | [静态清单](ledger-evidence/L29-source.json)；[L29-platform-list.png](ledger-evidence/device-20261101/L29-platform-list.png)；[L29-qq-group.png](ledger-evidence/device-20261101/L29-qq-group.png)；**部分验证**：仅平台/QQ群聊配置页；未配置测试 OneBot、登录或外部收发。 | 管家 → 鱼 → 平台 |
| L30 | **B 站**：bili-cli 插件、平台自主活动 | 大肥鱼的一切 → 平台连接 → B站（fish-platforms） | 检查bili-cli插件状态；使用测试账号完成登录/只读查询；核对自主活动配置和日志；真实发布/互动另行授权。 | [静态清单](ledger-evidence/L30-source.json)；[L30-bilibili.png](ledger-evidence/device-20261101/L30-bilibili.png)；**部分验证**：仅 B 站配置页；未登录、生成二维码、查询或互动。 | 管家 → 鱼 → 平台 |
| L31 | **运行时**：proot/Node/Python rootfs、前台服务、arm64 | 大肥鱼的一切 → 运行设置（fish-runtime） | arm64示例设备首次展开rootfs并启动Node/Python/DSH；验证前台通知、停止/重启及状态；非arm64检查降级提示。 | [静态清单](ledger-evidence/L31-source.json)；[L31-runtime-settings.png](ledger-evidence/device-20261101/L31-runtime-settings.png)；[L31-runtime-running.png](ledger-evidence/device-20261101/L31-runtime-running.png)；**部分验证**：从 UI 启动后独立观察到 proot、Node、Python 进程；模型回复、完整 RPC、通知及非 arm64 降级未测。 | 管家 → 鱼 → 运行时 |
| L32 | **鱼时间工具**：10 个 `time_*` 工具 | 鱼聊天 → 运行时工具桥 → FishTimeTools.execute（无独立页面） | 对照下方10工具逐个在示例数据上调用；核对参数、返回字段、业务副作用与失败；保存tool_result并去除私人内容。 | [静态清单](ledger-evidence/L32-source.json)；[原生工具实参/返回](ledger-evidence/device-20261101/native-time-tools.json)；**部分验证**：10 个原生工具在手机内存库上逐一调用，实参/返回见 native-time-tools.json；未覆盖 DSH/模型到工具的 RPC 端到端。 | 统一执行入口（第 6、7 章） |
| L33 | **AI 设置**：DeepSeek 等 Key、模型选择 | 管家 → 大肥鱼的一切 → 应用设置 → AI设置；运行设置 → 模型设置 | 使用用户自行提供的测试Key/模型并保存；检查无Key、错误Key、模型切换；不在截图、日志或PR中记录Key。 | [静态清单](ledger-evidence/L33-source.json)；[L33-model-settings.png](ledger-evidence/device-20261101/L33-model-settings.png)；**部分验证**：已查看模型配置页且遮挡私密文字；未改 Key/模型或测试错误 Key。 | 管家 → 大肥鱼的一切 → 应用设置 → AI |
| L34 | **备份恢复**：主库+训练库+附件+部分偏好；跨库失败回滚；PersonalDataGate | 管家 → 大肥鱼的一切 → 应用设置 → 数据备份与恢复（backup） | 只在独立示例设备导出主库/训练库/附件/偏好；恢复后比较数量；注入跨库恢复失败验证回滚与PersonalDataGate；鱼记忆覆盖范围单独记录。 | [静态清单](ledger-evidence/L34-source.json)；[L34-backup.png](ledger-evidence/device-20261101/L34-backup.png)；[受控原样恢复](ledger-evidence/device-20261101/restore-verification.json)；**部分验证**：已进入应用备份页；本轮 ADB 受控备份/原样恢复校验通过，不等同应用 ZIP 备份/跨库失败回滚通过。 | 管家 → 大肥鱼的一切 → 应用设置 → 数据 |
| L35 | **主题**：主题偏好、切换 | 今天/计划/复盘顶部 → 切换深浅色（ThemeSwitch/ThemePreference） | 切系统/浅色/深色；重开确认偏好；打开训练/记账等子Activity检查当前主题；工具/陪伴双模式仍为后续拟建。 | [静态清单](ledger-evidence/L35-source.json)；[L35-theme-changed.png](ledger-evidence/device-20261101/L35-theme-changed.png)；[L35-theme-restored.png](ledger-evidence/device-20261101/L35-theme-restored.png)；**部分验证**：已浅→深→浅切换；原偏好最终原样恢复。系统跟随和全部子 Activity 未全测，双模式尚未实施。 | 管家 → 大肥鱼的一切 → 应用设置 → 外观 |
| L36 | **日历交接**：现有“插入到系统日历”Intent | 今天时间轴 → 日程或课程详情 → 交给系统日历确认添加（calendar） | 选择示例日程/课程；确认交接参数；在系统日历取消与确认各一次；核对本地未偷偷写入；双向同步尚未实现。 | [静态清单](ledger-evidence/L36-source.json)；**未验收**：DS 两次查找未到达日历交接，未保存系统日历；仍缺有效实机证据，不以新建计划表单代替。 | 保留为“发送到日历”；双向同步为新增功能 N04 |

## 三项专项核查

### 任务完成与实际记录

结论：**普通任务和周期任务的“完成”均不会自动生成实际记录**，符合第 1 章的术语定义。`TimeRepository.saveTask()` 只更新任务、版本与操作历史；周期 `RecurringService.act(COMPLETE)` 更新任务/实例、关闭提醒并标记手工例外，提示“未自动补写实际时长”。`FishTimeTools.time_complete_task` 对周期实例走该路径，对普通任务走既有 Planner/Writer。任务详情的完成按钮也只保存状态。

这不表示停止计时或结束训练不会产生实际记录，它们是另外的写入操作。2026-11-01 已完成真机对比：DS 完成 `T0_DS_COMPLETE`，实际记录前后均为 10000、该任务关联实际为 0；状态为 COMPLETED、版本为 1。随后独立计时启停新增 1 条实际记录且无运行中记录，见专项 JSON；周期完成的全部 UI 例外流程仍需补测。静态入口：`data/TimeRepository.kt:361`、`recurring/RecurringService.kt`、`fish/FishTimeTools.kt:37`、`ui/TimeAgentApp.kt`。

### 提醒在开机与时区变化后的重排

| 类型 | 开机后 | 时区变更后 | 当前调用链与限制 |
| --- | --- | --- | --- |
| 普通提醒点 / 与任务关联的提醒 | 有恢复代码，真实重启未测 | 真实系统时区变更已恢复 1 个手工提醒 | Manifest → `ReminderRestoreReceiver` → `ReminderCoordinator.refresh()` → `reconcile()` → `AndroidReminderScheduler.schedule()`；只重新注册已存在、开启、未送达、将来的 `reminders` 行；不补发过期提醒，已完成/取消/删除任务会过滤。 |
| 日程 | 不存在由日程自动建立提醒的完整路径 | 同左；已有手工提醒可重排，但不重算日程本身 | Coordinator 读取 `reminders`/`tasks`，没有从 `schedule_blocks` 自动派生提醒；不能把接收到了 BOOT/TIMEZONE 广播写成“日程提醒已覆盖”。 |
| 截止 | 周期 DEADLINE 的提醒有恢复链；仅设置普通任务 dueAt 不等于有提醒 | 周期截止已实测时区重排 1 个；按已保存时间戳重排 | `refresh()` 先调用 `RecurringService.refresh()`，再排提醒；周期规则需配置 `leadMinutes`。普通任务截止没有通用自动提醒生成链。 |
| 周期习惯 / 周期事务 | 有实例刷新与提醒重排代码 | 已实测时区重排 1 个习惯提醒；规则按自身 `zoneId` 计算 | `RecurringService.refresh()` → `resyncReminder()` → `reminders`；保护手工例外和手动关闭；规则时区与设备时区不是同一概念。 |
| 训练休息 / Tabata | 有恢复代码，未真机验证 | **未覆盖** | Manifest 的 `WorkoutTimerReceiver` 和其 `restore` action 集合均没有 `TIMEZONE_CHANGED`；BOOT → 读取 activeSession → `WorkoutNotifications.sync()`。训练通知权限、过期阶段提示还需实测。 |

普通提醒 Receiver 同时接受应用更新、手动改系统时间和精确闹钟授权变化；训练 Receiver 接受相同事件但遗漏时区变更。仅凭代码不能保证厂商杀后台后实际触发或通知送达。2026-11-01 通过实际修改系统时区触发广播：3 个已注册提醒 → AlarmManager.cancelAll 后 0 个 → 广播后 3 个。设备通知允许、精确闹钟未授权；因此本轮验证的是恢复排程，不能扩写为精确送达。最初计数误含 dumpsys 的移除历史而超时，修正测试只统计 pending tag 行后通过；业务代码未改。真实开机、训练时区恢复及通知送达均未实测，未发送伪造保护广播冒充。

### FishTimeTools 的十个工具

本轮对照 `app/src/main/java/com/timeagent/fish/FishTimeTools.kt` 与 `agent-plugins/time-agent/index.mjs`。共 **10** 个 `time_*` 工具。除明确列出的默认分支外，没有隐含的日期/起止时间默认值。

插件中另有 `fish_presence`，不计入这 10 个工具；不存在独立的 `tool-definitions.mjs`。插件禁止 `timeagent-qq-group-` 群会话调用这 10 个私人业务工具。参数声明没有完整长度/日期限制，实际限制还来自 `AssistantPlanner`/Repository：

- 任务、记录及无 taskId 的计划标题：trim 后 1–120 字，不允许 NUL。
- 起止时间：`OffsetDateTime.parse()`，必须含 offset 或 `Z`；epoch 毫秒在 `[946684800000, 4133980800000]`，end > start，跨度不超过 10080 分钟。
- 新任务默认预计 30 分钟、priority=1、status=TODO、source=ASSISTANT，无截止；无 taskId 创建计划时预计分钟数来自时段。
- `taskId` / `projectId` 缺省或空白表示未提供；非空 ID 不自动 trim。关联实体必须存在且有效；已完成任务不能新建计划，但可以关联补录。
- 通用写工具拒绝 Planner 的全部 warnings，包括同名任务、日程冲突和已结束实际记录重叠；这与业务页允许确认重叠的能力不同。

| 工具 | 参数（`*` 必填） | 成功返回 / 副作用 |
| --- | --- | --- |
| `time_quick_action` | `text* : string`；trim 后 1–2000 字 | 普通指令返回 `{message, needsConfirmation:boolean, undoAvailable:boolean}`；涉及预览时由现有会话处理，不能把 message 当作已写入。显式“记住”分支返回 `{message, destination:"memory"}`，写原生个人规则。 |
| `time_undo` | 无 | `{message}`，撤销最近一项未撤销的全局业务操作；不是只撤销当前工具调用。没有历史时失败。 |
| `time_today` | 无 | `{date, zone, now, plans[], courses[], tasks[], actual[]}`；date 使用 Repository 当前时区当天。只读。 |
| `time_schedule` | `date* : string`，ISO `YYYY-MM-DD` | 同 `time_today`，按该日期与 Repository 时区取跨日相交条目。只读。 |
| `time_tasks` | 无 | `{tasks:[{id,title,status,estimatedMinutes,dueAt?}]}`；仅未删除且 TODO/IN_PROGRESS；dueAt 为 epoch 毫秒。只读。 |
| `time_projects` | 无 | `{projects:[{id,name,status,tasks:[{id,title,status}]}]}`；项目和子任务过滤已删除行。只读。 |
| `time_create_task` | `title* : string`；`projectId? : string`，空白当未提供 | 常规返回 `{id,message,undoAvailable:true}`；创建任务，可在同一 assistantTransaction 中保存项目归属。标题/项目有效性由 Planner/Repository 验证。 |
| `time_complete_task` | `taskId* : string` | 普通任务常规返回 `{id,message,undoAvailable:true}`；周期任务返回 `{id:taskId,message:"本次已完成，实际投入未自动生成"}`，没有 undoAvailable 字段；已处理周期实例失败。 |
| `time_create_plan` | `title* : string`；`start*`,`end* : string`（带偏移 ISO 日期时间）；`taskId? : string`，空白当未提供 | 有 taskId：创建关联日程，id 为日程 ID，title 不用于改任务标题。无 taskId：现有 CreateEvent 同时建任务和日程，返回 **任务 ID**。常规 `{id,message,undoAvailable:true}`。 |
| `time_record` | `title* : string`；`start*`,`end* : string`（带偏移 ISO 日期时间）；`taskId? : string` | 补录实际记录；常规 `{id:recordId,message,undoAvailable:true}`；完全重复只返回 `{message}`。未来实际时间或冲突 warning 会拒绝。当前入参 title 存入 `notes`，实体 title 为空，不会停止计时或完成任务。 |

查询明细：

- `plans[]`：`{id,taskId,title,start,end}`；仅 SCHEDULED、与日期相交、关联未删除任务存在的行。
- `courses[]`：`{id,title?,start,end}`；排除 CANCELLED，与日期相交。
- `tasks[]`（today/schedule）：`{id,title,dueAt?,status}`；未完成且截止早于所查日期结束，**无截止任务也包含**，不是仅当天新建任务。
- `actual[]`：`{id,title,start,end?}`；包含正在计时行，title 可回退到任务标题。起止与 tasks.dueAt 在该查询中是带偏移的字符串，顶层 now 为 UTC Instant 字符串；Gson 默认省略 null 字段。

错误契约：`FishTimeTools.execute()` 自身抛出异常（缺参、非法日期、找不到实体、冲突等）；Android `FishSession` 捕获并包装 `{error:string}`，通过 `{action:"tool_result", id:toolCallId, result:...}` 发回运行时。业务失败通常为 HTTP 200 + `{error}`，插件再抛 JS Error；鉴权/路径/解析/超时/关闭分别有 401/404/400/504/503 路径，等待超时为 120000ms。插件对非 2xx 只保留状态码描述。没有统一 `code/requestId/entityVersion` 结果结构，不能假设 T1.4 拟建协议已存在。`AssistantReply.Answer` 可能只返回 `{message}`；quick action 还可能将失败转换为 message。因此没有 error 不等于成功写入，也不能假设所有写工具都有 id/undoAvailable。

现有主机测试已在完整回归执行；另在真机内存库直接调用全部 10 工具，实参/返回见 [native-time-tools.json](ledger-evidence/device-20261101/native-time-tools.json)。原生调用不包含 DSH RPC/模型端到端；缺参、全部非法 ID 与超时矩阵仍未全测。现有测试包括：`fish/FishTimeToolsTest.kt` 的 `task tools write through repository and completion creates no actual records`、`completing a recurring task updates the same instance without inventing actual`、`future actual is rejected without creating a record`、`local quick action remains a real business tool on the same fish`、`a fish scheduled project task is visible in the same business snapshot`。执行结果另见 T0.2 报告，测试名称不能替代实际断言覆盖。

## 现状缺陷（只记录，不修复）

| 编号 | 关联账本 | 已确认的现状 / 影响 | 证据与验证边界 |
| --- | --- | --- | --- |
| D-T0-01 | L04、L07 | 日程/普通任务截止没有完整的自动提醒生成链；通用恢复 Receiver 的存在不等于这两类已覆盖。 | `ReminderCoordinator` 只排 reminders 行；实机内存夹具也确认普通 plan/dueAt 不自动生成 reminders；真实通知送达未测。 |
| D-T0-02 | L07、L11 | 训练提醒未监听时区变更，未实现该事件后的统一恢复。 | Manifest 与 `WorkoutTimerReceiver.restore` action 集合均缺该 action。 |
| D-T0-03 | L32 | `time_create_plan` 的返回 id 含义随 taskId 分支改变：关联日程返回日程 ID，隐式建任务分支返回任务 ID；完成周期实例返回结构也不同。 | `FishTimeTools.kt` 的 `createdId` / 周期提前返回。后续统一协议需兼容。 |
| D-T0-04 | L32 | 多数写工具请求没有稳定 requestId；`time_quick_action` 的显式记忆和周期完成使用新 UUID。超时重试不能仅凭工具桥声称幂等。 | 现有 execute/assistantTransaction 参数；拟由 T1.4/T1.5 处理。 |
| D-T0-05 | L31–L34 | v12 主库实际有 30 张业务表，规格 00 的现状速览写 29。共同记忆不属于原生个人规则表。 | `app/schemas/com.timeagent.data.TimeAgentDatabase/12.json`，本轮逐实体计数；不修改规格原文。 |
| D-T0-06 | L22 | 当前正常创建了 fish 的输入路由直接调用 fish.send，本地解析被绕过；鱼未启动时，普通输入不能假设已落到本地业务。 | `TimeAgentApp.kt:197`、`FishSession.send()`；后续离线解耦范围，本轮未修。 |
| D-T0-07 | L32 | `time_record.title` 写入 notes，日期查询又不从 notes 回退标题，无关联任务时可读回“实际记录”。 | `AssistantPlanner.kt:147`、`FishTimeTools.kt:60`，仅源码核查。 |
| D-T0-08 | L04、L06、L07 | 周期/期限完成后 REOPEN 只恢复状态，不恢复此前关闭的提醒；代码提示用户核对。 | `RecurringService.kt:101`、`:112`，本轮未修复。 |

入口与覆盖未查全项（不等同“功能不存在”，不得据此删减）：L10 已找到的合并/拆分对象是实际时间记录，支付交易合并/拆分未核实；L13 直接分拣到任务/时段日程/记账未核实，已找到的是安排为提醒；Android 系统语音实现存在，但当前生产界面的切换入口未定位；当前 FishChatScreen 的会话切换/表情选择器和独立陪伴玩法入口未定位；编辑草稿后的迟到语音终稿保护需补证。QQ 已核实的是 OneBot 群聊，不得扩写为所有频道类型均验收。

以上为本轮源码可定位的问题和协议差异，未修复。其余模块的真机缺陷数量未知；“未测试”不能写作“未发现缺陷”。

## 真机补证步骤

使用单独的示例数据和当前版本，记录设备/API、APK SHA-256、通知与精确闹钟权限、鱼运行时/Key/网络状态。按每行操作逐一验证，截图保存到约定文件名，记录预期和实际结果。

专项复验：完成一个没有实际记录的任务，对比完成前后记录列表；分别创建未来的日程、截止、周期与训练提醒，记录 `dumpsys alarm`，重启后重新核对，随后切换时区再次核对；训练休息/Tabata/动作计时需分别覆盖。开机与时区测试需真实系统事件，直接调用 Receiver 不等于真实恢复成功。

鱼工具需用示例数据逐个验证成功、缺参、非法 ID 和超时结果；保留结构化结果，隐藏 Key、真实日程、交易、聊天与共同记忆。QQ/B 站登录、发送与平台活动、支付通知捕获、健康读取、系统悬浮窗、语音与朗读需要相应外部条件，静态核查和本地测试不能代替这些结果。

## 2026-11-01 真机跟进边界

真实导出的一条训练 timelineRecordId 已无主库目标；原库和脱敏库均为 1 条空关联、1 条有效关联、1 条缺失目标关联。可能与历史手动删除有关，只记录现状，不擅自补写或据此判定训练完成丢记录。DS 的动作选择器卡点源于采集器隐藏动态“完成（数量）”文案，不能据此断言产品按钮不存在；发送鱼消息后短时失去前台也没有独立崩溃证据。

本轮测试使用临时合成库。结束后原 39 个备份文件 SHA 全一致、两库 36 张表计数/内容摘要全一致、完整性通过且外键错误 0；时区恢复 Asia/Shanghai、自动时区 1。此为 ADB 受控恢复，不替代 L34 应用备份流程与未来迁移测试。真实重启、锁屏、后台被杀、收音/朗读、悬浮交互、QQ/B站登录与收发、日历交接，以及逐行列出的未执行子能力仍待补证。下一开发任务仍是 T1.1；本次没有开始阶段 1。
