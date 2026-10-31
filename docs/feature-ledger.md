# 功能保留账本（T0.1）

核查日期：2026-10-31（Asia/Shanghai）。业务基线：`660d129`，`0.15.2-preview / versionCode 28`。权威能力清单见 `product/全面改造规格-2026-10-31/01-产品边界-术语与功能保留账本.md`。

本轮只建立基线，不改业务实现、UI 或数据库版本。36 项原有子能力全部保留在下表。“目标入口”来自规格，尚未实施；“现状入口”来自当前代码，不把未来导航当作已存在。

**验收状态：36 项均已建立静态源码清单；36 项新截图/录屏和真机操作均待补。T0.1 的“36 行全部有真机证据”验收条件尚未达到。** 本机 ADB 无设备、mDNS 无服务、无模拟器，原手机 SSH 通道超时；见 `ledger-evidence/device-availability.txt`。历史截图和旧测试结果未充当本轮证据。

每行 `Lxx-source.json` 是实际生成的源码路径/哈希清单；`Lxx-device.png` 是约定的待补截图名，目前文件不存在。带有连续操作的步骤应再录制同名 `.mp4`，入口截图不能替代功能验收。后续 PR 须列出影响的 L 编号、逐项结果与新证据；不能删除行或减少子能力。

## 现状入口、操作与证据

| 编号 | 模块与必须保留的子能力 | 现状入口路径（代码路由） | 操作步骤（待设备执行） | 本轮证据文件名 / 待补文件名 | 目标入口（尚未实施） |
| --- | --- | --- | --- | --- | --- |
| L01 | **时间轴**：计划/实际双轨；实际可重叠；冲突可保存并提示；撤销/重做；乐观锁版本；历史 | 今天 → 时间轴（Timeline） | 创建任务和计划；补录两条重叠实际；保存冲突并检查提示；编辑后用旧版本保存；撤销、重做并查看历史。 | [静态清单](ledger-evidence/L01-source.json)；待补 `L01-device.png`/`.mp4` | 今天、计划 |
| L02 | **计时**：开始/停止/切换；补录；跨天；进程重启恢复 | 计划 → 任务 → 开始计时；复盘 → 新建 → 开始自由计时；顶部计时条 | 开始、停止、切换任务；补录时间；跨午夜计时；在示例设备杀进程再打开，对比起止与正在计时状态。 | [静态清单](ledger-evidence/L02-source.json)；待补 `L02-device.png`/`.mp4` | 今天顶部计时条、通知栏、小组件 |
| L03 | **任务**：新建/编辑/完成/恢复；项目归属；截止 | 计划 → 新建任务 / 任务 → 查看全部 → 任务详情 | 新建并编辑标题、时长、截止；在项目页加入已有任务；完成后确认未自动生成实际；在已完成筛选中点重新打开并核对原记录。 | [静态清单](ledger-evidence/L03-source.json)；待补 `L03-device.png`/`.mp4` | 计划 → 任务 |
| L04 | **截止**：截止列表、状态、提醒 | 计划 → 生活期限 → 查看全部；今天 → 处理期限（deadlines） | 新建期限规则；生成实例；完成、跳过和重开；配置提前提醒，核对状态、提醒时间与历史。 | [静态清单](ledger-evidence/L04-source.json)；待补 `L04-device.png`/`.mp4` | 计划 → 截止；今天卡片 |
| L05 | **项目**：项目列表、项目复盘 | 计划 → 项目 → 查看全部（projects/project:id） | 新建项目；关联任务/子任务；安排任务时间；进入项目详情查看计划/实际与项目复盘。 | [静态清单](ledger-evidence/L05-source.json)；待补 `L05-device.png`/`.mp4` | 计划 → 项目；复盘 → 项目 |
| L06 | **周期事务**：规则编辑、实例生成、手工例外、规则版本、历史快照 | 计划 → 周期习惯 → 查看全部；今天 → 本次习惯（recurring） | 新增各类规则并生成实例；改期单次/完成/跳过；再改规则，确认手工例外、规则版本及历史快照保持。 | [静态清单](ledger-evidence/L06-source.json)；待补 `L06-device.png`/`.mp4` | 计划 → 周期 |
| L07 | **提醒**：精确闹钟、通知、通知权限检测 | 计划 → 收集想法 → 收件箱 → 新提醒；提醒权限卡 | 新增将来的提醒；允许/拒绝通知与精确闹钟；检查提示、通知和送达状态；按专项步骤复验系统重排。 | [静态清单](ledger-evidence/L07-source.json)；待补 `L07-device.png`/`.mp4` | 全局；设置 → 提醒 |
| L08 | **课表**：JSON/PDF/华工教务导入；预览；去重；手工修改 | 管家 → 大肥鱼的一切 → 应用设置 → 课表 JSON 导入 / PDF 与教务导入（settings/import） | 分别导入示例 JSON/PDF/华工教务课表；查看预览后确认；重复导入验证去重；手改单次课程后再导入确认保护。 | [静态清单](ledger-evidence/L08-source.json)；待补 `L08-device.png`/`.mp4` | 管家 → 课表；计划中显示 |
| L09 | **健康**：Health Connect、Gadgetbridge 导入；睡眠来源去重；缺失不当零 | 复盘 → 健康与训练；管家 → 大肥鱼的一切 → 应用设置 → 健康同步（health） | 授权 Health Connect；导入示例 Gadgetbridge 数据；重复同步并比较睡眠来源去重；无数据日确认显示缺失而非零。 | [静态清单](ledger-evidence/L09-source.json)；待补 `L09-device.png`/`.mp4` | 管家 → 健康；复盘 |
| L10 | **记账**：支付通知监听（默认关）；草稿待确认；整数分；CSV；合并/拆分 | 管家 → 大肥鱼的一切 → 应用设置 → 支付记账（LedgerActivity） | 核对监听默认关闭；开启后用示例支付通知产生草稿并确认；按整数分编辑；CSV导入/导出；分别核对交易及记录整理支持的合并/拆分边界。 | [静态清单](ledger-evidence/L10-source.json)；待补 `L10-device.png`/`.mp4` | 管家 → 记账 |
| L11 | **训练**：动作库+GIF；3D 肌肉；8 种记录类型（WEIGHT_REPS、REPS、DURATION、BODYWEIGHT_REPS、ASSISTED_REPS、CARDIO、STRETCH、TABATA）；组类型 NORMAL/WARMUP/DROP/FAILURE；状态 ACTIVE/PAUSED/COMPLETED/DISCARDED；超级组；Tabata；模板；组间休息计时；训练通知 | 今天 → 训练；计划 → 开始训练（WorkoutActivity） | 打开动作库/GIF和3D肌肉；逐一使用8种记录类型、4种组类型及4种会话状态；超级组、Tabata、模板、组间休息和通知分别执行；结束训练对比实际记录。 | [静态清单](ledger-evidence/L11-source.json)；待补 `L11-device.png`/`.mp4` | 管家 → 训练；今天快捷入口 |
| L12 | **附件**：附件存储、OCR、关联到条目 | 计划 → 附件；任务详情 → 附件收件箱（attachments） | 选取示例文件并保存；图片/PDF OCR；关联任务/项目/条目后重开，检查附件原件、识别文本及关联。 | [静态清单](ledger-evidence/L12-source.json)；待补 `L12-device.png`/`.mp4` | 管家 → 附件；各详情页 |
| L13 | **收件箱**：收集、分拣到任务/日程/记账 | 计划 → 收集想法；任务列表 → 未安排（inbox） | 收集想法/文字；编辑后分拣到现有支持的任务或提醒/日程路径；逐一核查记账分拣是否存在，不将目标能力当作现状。 | [静态清单](ledger-evidence/L13-source.json)；待补 `L13-device.png`/`.mp4` | 管家 → 收件箱；语音结果 |
| L14 | **聊天提取**：从聊天文本提取事项 | 管家 → 大肥鱼的一切 → 应用设置 → 聊天事项提取（extract） | 粘贴不含个人信息的示例聊天；提取事项并核对时间歧义；确认后保存；取消时确认没有写入。 | [静态清单](ledger-evidence/L14-source.json)；待补 `L14-device.png`/`.mp4` | 管家 → 工具 |
| L15 | **回复副驾**：生成回复建议 | 计划 → 任务 → 查看全部 → 回复副驾（reply）；聊天提取 → 回复 | 输入示例对话；生成回复与安排建议；核对空档/澄清；确认后保存；返回检查建议和实际写入一致。 | [静态清单](ledger-evidence/L15-source.json)；待补 `L15-device.png`/`.mp4` | 管家 → 工具 |
| L16 | **个人规则**：规则增删改、被业务/鱼读取 | 管家 → 大肥鱼的一切 → 应用设置 → 个人记忆/规则（memory） | 新建、编辑、停用、删除示例规则；核对排程/鱼读取结果；与 DSH 共同记忆分开验证。 | [静态清单](ledger-evidence/L16-source.json)；待补 `L16-device.png`/`.mp4` | 管家 → 鱼 → 记忆与规则 |
| L17 | **历史问答**：基于历史数据回答 | 复盘 → 历史明细（history）；管家 → 大肥鱼的一切 → 应用设置 → 历史 | 在示例记录中查询历史；核对答案引用的实际条目；打开原记录验证；无数据时检查不虚构。 | [静态清单](ledger-evidence/L17-source.json)；待补 `L17-device.png`/`.mp4` | 复盘 → 问答 |
| L18 | **建议**：建议生成与一键执行 | 计划 → 收集想法 / 打开收件箱 → 助手建议（SuggestionActions） | 建立可触发建议的示例安排；查看建议；一键执行后核对实体、版本与撤销；过期/冲突建议检查拒绝行为。 | [静态清单](ledger-evidence/L18-source.json)；待补 `L18-device.png`/`.mp4` | 今天卡片；复盘 |
| L19 | **每日简报**：简报生成与展示 | 今天鱼摘要；管家聊天首项 → 完整简报（DailyBriefing） | 建立示例日程/截止/健康信息；展开、收起简报；打开下一项；检查无数据与跨日时的文案。 | [静态清单](ledger-evidence/L19-source.json)；待补 `L19-device.png`/`.mp4` | 今天顶部 |
| L20 | **周目标**：设定与追踪、与项目进度联动 | 计划 → 本周目标 → 查看周目标（goals）；项目复盘 | 设置项目/活动周目标；新增关联实际记录；检查目标分钟数、项目进度和周复盘之间的口径。 | [静态清单](ledger-evidence/L20-source.json)；待补 `L20-device.png`/`.mp4` | 复盘 → 周；计划 |
| L21 | **语音**：Vosk 实时预览＋SenseVoice 终稿；按住说话；Android 语音输入；终稿只提交一次；迟到结果不覆盖用户编辑 | 底部语音动作 / 按住说话（HomeVoiceDock/HoldToTalkButton） | 授权麦克风；按住说话观察Vosk预览与SenseVoice终稿；另测系统语音；重复/迟到终稿和手改草稿，确认不重复提交、不覆盖编辑。 | [静态清单](ledger-evidence/L21-source.json)；待补 `L21-device.png`/`.mp4` | 中央语音动作 |
| L22 | **本地指令**：查日程/任务/空闲、开始/停止计时、新建任务等 | 语音面板；管家聊天输入（LocalAssistantParser / NaturalRecordParser） | 分别查询日程、任务、空闲；开始/停止计时并新建任务；断网、无Key、鱼停止时重试；核对输入路由是否真正进入本地解析。 | [静态清单](ledger-evidence/L22-source.json)；待补 `L22-device.png`/`.mp4` | 语音面板；鱼聊天快速通道 |
| L23 | **鱼聊天**：会话、表情贴纸、工具调用结果 | 管家（直接显示 FishChatScreen） | 使用独立示例会话发送、取消和重开；检查历史、表情贴纸、工具状态与结果；不能用模型回复代替业务写入核对。 | [静态清单](ledger-evidence/L23-source.json)；待补 `L23-device.png`/`.mp4` | 管家 → 鱼；语音面板 |
| L24 | **鱼角色**：形象、精灵动画、状态、陪伴玩法 | 管家 → 聊天右上角 大肥鱼的一切 → 个人档案 / 换装 / 表情动作 / 陪伴与作息 | 预览原有形象、动画与状态；执行已有陪伴玩法；切页返回检查状态；保留原名和原始形象素材，不将娱乐玩法写入业务统计。 | [静态清单](ledger-evidence/L24-source.json)；待补 `L24-device.png`/`.mp4` | 陪伴模式全局；管家 → 鱼 |
| L25 | **悬浮窗**：悬浮鱼、交互、服务 | 大肥鱼的一切 → 陪伴与作息 → 全局悬浮陪伴（fish-appearance） | 先拒绝后允许悬浮权限；跨应用显示、拖动、点击和长按；锁屏/旋转后复验；关闭通知入口，确认不停止原鱼。 | [静态清单](ledger-evidence/L25-source.json)；待补 `L25-device.png`/`.mp4` | 管家 → 鱼 → 悬浮窗 |
| L26 | **朗读**：Edge 朗读（speech-output 插件）、鱼语音 | 大肥鱼的一切 → 声音（fish-voice）；聊天消息 → 朗读 | 启动原运行时；选Edge音色、试听、改语速和自动朗读；逐条朗读历史；断网失败与系统语音分别验证。 | [静态清单](ledger-evidence/L26-source.json)；待补 `L26-device.png`/`.mp4` | 管家 → 鱼 → 声音 |
| L27 | **共同记忆**：dsh-remembers 插件、记忆查看 | 管家 → 大肥鱼的一切 → 共同记忆（fish-memory） | 在示例会话形成记忆；查看/查询共同记忆与最近见闻；重启运行时确认同一权威存储；不把个人规则等同共同记忆。 | [静态清单](ledger-evidence/L27-source.json)；待补 `L27-device.png`/`.mp4` | 管家 → 鱼 → 记忆与规则 |
| L28 | **主动关心**：主动消息、频率与时段 | 大肥鱼的一切 → 主动话题（fish-proactive） | 读取主动配置；修改频率与允许时段；保存后重开检查；在时段内/外核对主动消息，不发送到真实联系人。 | [静态清单](ledger-evidence/L28-source.json)；待补 `L28-device.png`/`.mp4` | 管家 → 鱼 → 主动 |
| L29 | **QQ**：QQ 频道、QQ 语音 | 大肥鱼的一切 → 平台连接 → QQ群聊（FishQQSettings） | 使用测试OneBot服务配置、保存并检查连接；在授权测试群收发文本和语音；验证QQ语音转文字及开关边界，需用户另行授权实际发送。 | [静态清单](ledger-evidence/L29-source.json)；待补 `L29-device.png`/`.mp4` | 管家 → 鱼 → 平台 |
| L30 | **B 站**：bili-cli 插件、平台自主活动 | 大肥鱼的一切 → 平台连接 → B站（fish-platforms） | 检查bili-cli插件状态；使用测试账号完成登录/只读查询；核对自主活动配置和日志；真实发布/互动另行授权。 | [静态清单](ledger-evidence/L30-source.json)；待补 `L30-device.png`/`.mp4` | 管家 → 鱼 → 平台 |
| L31 | **运行时**：proot/Node/Python rootfs、前台服务、arm64 | 大肥鱼的一切 → 运行设置（fish-runtime） | arm64示例设备首次展开rootfs并启动Node/Python/DSH；验证前台通知、停止/重启及状态；非arm64检查降级提示。 | [静态清单](ledger-evidence/L31-source.json)；待补 `L31-device.png`/`.mp4` | 管家 → 鱼 → 运行时 |
| L32 | **鱼时间工具**：10 个 `time_*` 工具 | 鱼聊天 → 运行时工具桥 → FishTimeTools.execute（无独立页面） | 对照下方10工具逐个在示例数据上调用；核对参数、返回字段、业务副作用与失败；保存tool_result并去除私人内容。 | [静态清单](ledger-evidence/L32-source.json)；待补 `L32-device.png`/`.mp4` | 统一执行入口（第 6、7 章） |
| L33 | **AI 设置**：DeepSeek 等 Key、模型选择 | 管家 → 大肥鱼的一切 → 应用设置 → AI设置；运行设置 → 模型设置 | 使用用户自行提供的测试Key/模型并保存；检查无Key、错误Key、模型切换；不在截图、日志或PR中记录Key。 | [静态清单](ledger-evidence/L33-source.json)；待补 `L33-device.png`/`.mp4` | 管家 → 大肥鱼的一切 → 应用设置 → AI |
| L34 | **备份恢复**：主库+训练库+附件+部分偏好；跨库失败回滚；PersonalDataGate | 管家 → 大肥鱼的一切 → 应用设置 → 数据备份与恢复（backup） | 只在独立示例设备导出主库/训练库/附件/偏好；恢复后比较数量；注入跨库恢复失败验证回滚与PersonalDataGate；鱼记忆覆盖范围单独记录。 | [静态清单](ledger-evidence/L34-source.json)；待补 `L34-device.png`/`.mp4` | 管家 → 大肥鱼的一切 → 应用设置 → 数据 |
| L35 | **主题**：主题偏好、切换 | 今天/计划/复盘顶部 → 切换深浅色（ThemeSwitch/ThemePreference） | 切系统/浅色/深色；重开确认偏好；打开训练/记账等子Activity检查当前主题；工具/陪伴双模式仍为后续拟建。 | [静态清单](ledger-evidence/L35-source.json)；待补 `L35-device.png`/`.mp4` | 管家 → 大肥鱼的一切 → 应用设置 → 外观 |
| L36 | **日历交接**：现有“插入到系统日历”Intent | 今天时间轴 → 日程或课程详情 → 交给系统日历确认添加（calendar） | 选择示例日程/课程；确认交接参数；在系统日历取消与确认各一次；核对本地未偷偷写入；双向同步尚未实现。 | [静态清单](ledger-evidence/L36-source.json)；待补 `L36-device.png`/`.mp4` | 保留为“发送到日历”；双向同步为新增功能 N04 |

## 三项专项核查

### 任务完成与实际记录

结论：**普通任务和周期任务的“完成”均不会自动生成实际记录**，符合第 1 章的术语定义。`TimeRepository.saveTask()` 只更新任务、版本与操作历史；周期 `RecurringService.act(COMPLETE)` 更新任务/实例、关闭提醒并标记手工例外，提示“未自动补写实际时长”。`FishTimeTools.time_complete_task` 对周期实例走该路径，对普通任务走既有 Planner/Writer。任务详情的完成按钮也只保存状态。

这不表示停止计时或结束训练不会产生实际记录，它们是另外的写入操作。真机仍需对比完成前后 `time_records` 数量并验证原计时状态。静态入口：`data/TimeRepository.kt:361`、`recurring/RecurringService.kt`、`fish/FishTimeTools.kt:37`、`ui/TimeAgentApp.kt`。

### 提醒在开机与时区变化后的重排

| 类型 | 开机后 | 时区变更后 | 当前调用链与限制 |
| --- | --- | --- | --- |
| 普通提醒点 / 与任务关联的提醒 | 有恢复代码，未真机验证 | 有恢复代码，未真机验证 | Manifest → `ReminderRestoreReceiver` → `ReminderCoordinator.refresh()` → `reconcile()` → `AndroidReminderScheduler.schedule()`；只重新注册已存在、开启、未送达、将来的 `reminders` 行；不补发过期提醒，已完成/取消/删除任务会过滤。 |
| 日程 | 不存在由日程自动建立提醒的完整路径 | 同左；已有手工提醒可重排，但不重算日程本身 | Coordinator 读取 `reminders`/`tasks`，没有从 `schedule_blocks` 自动派生提醒；不能把接收到了 BOOT/TIMEZONE 广播写成“日程提醒已覆盖”。 |
| 截止 | 周期 DEADLINE 的提醒有恢复链；仅设置普通任务 dueAt 不等于有提醒 | 同左；按已保存时间戳重排 | `refresh()` 先调用 `RecurringService.refresh()`，再排提醒；周期规则需配置 `leadMinutes`。普通任务截止没有通用自动提醒生成链。 |
| 周期习惯 / 周期事务 | 有实例刷新与提醒重排代码 | 有刷新与提醒重排代码；规则按自身 `zoneId` 计算 | `RecurringService.refresh()` → `resyncReminder()` → `reminders`；保护手工例外和手动关闭；规则时区与设备时区不是同一概念。 |
| 训练休息 / Tabata | 有恢复代码，未真机验证 | **未覆盖** | Manifest 的 `WorkoutTimerReceiver` 和其 `restore` action 集合均没有 `TIMEZONE_CHANGED`；BOOT → 读取 activeSession → `WorkoutNotifications.sync()`。训练通知权限、过期阶段提示还需实测。 |

普通提醒 Receiver 同时接受应用更新、手动改系统时间和精确闹钟授权变化；训练 Receiver 接受相同事件但遗漏时区变更。仅凭代码不能保证厂商杀后台后实际触发或通知送达。本轮未发送伪造系统广播来冒充恢复验证。

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

已定位但本节未执行的现有测试：`fish/FishTimeToolsTest.kt` 的 `task tools write through repository and completion creates no actual records`、`completing a recurring task updates the same instance without inventing actual`、`future actual is rejected without creating a record`、`local quick action remains a real business tool on the same fish`、`a fish scheduled project task is visible in the same business snapshot`。执行结果另见 T0.2 报告，测试名称不能替代实际断言覆盖。

## 现状缺陷（只记录，不修复）

| 编号 | 关联账本 | 已确认的现状 / 影响 | 证据与验证边界 |
| --- | --- | --- | --- |
| D-T0-01 | L04、L07 | 日程/普通任务截止没有完整的自动提醒生成链；通用恢复 Receiver 的存在不等于这两类已覆盖。 | `ReminderCoordinator` 只排 reminders 行；需设备补证。 |
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
