# 8. Codex 任务、依赖、测试与验收

> 🤖 本章是可直接派发给 Codex 的任务清单。任务按阶段排序，标注依赖；同一阶段内无依赖的任务可并行，但**不得同时修改同一个数据库版本或同一个文件的同一区域**。每张任务卡都包含：目标、依赖、现存文件、拟建文件、步骤、迁移影响、自动化测试、真机验收、回滚。

## 0. 给 Codex 的固定指令（每个任务都附上）

```text
你在仓库 meyumei/time-agent 工作，基线提交 660d129。
必须先阅读 docs/product/全面改造规格-2026-10-31/ 中的 00 总览硬约束、01 功能保留账本、本任务引用的章节。
硬约束：不删除任何现有功能（账本 L01–L36）；不改大肥鱼名字与形象；保留 Linux+DSH；只支持 BYOK；
底部导航为 今天/计划/语音动作/复盘/管家；时间轴默认 3 日；工具/陪伴双模式共用同一页面。
文档中标“拟建”的文件/字段尚不存在，需要你创建；不要假设已存在。
所有写操作走 CommandExecutor（T1.4 之后）。数据库变更必须有 Migration + schema JSON + MigrationTestHelper 测试。
完成后输出：改动摘要、受影响账本编号与验证方式、测试命令与结果、真机验收步骤、已知限制。
遇到规格未定义的产品决策：停止并列出选项，不要自行决定删减功能。
```

测试命令（README 中已有）：
```sh
./gradlew :core:test :agent-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd agent-plugins/time-agent && node --test test/*.test.mjs
```
Windows 用 `gradlew.bat` 或 `scripts/build.ps1`。新增的 UI/Macrobenchmark/仪器测试使用 `:app:connectedDebugAndroidTest`、`:benchmark:connectedBenchmarkAndroidTest`（拟建模块）。

## 1. 依赖总览

```
阶段0  T0.1 → T0.2 → T0.3
阶段1  T1.1 → T1.2 → T1.3 → T1.4 → T1.5 ; T1.6(并行于T1.3后)
阶段2  T2.1 → T2.2 → T2.3 ; T2.4(依赖T2.1) ; T2.5(依赖T2.2) ; T2.6(依赖T2.2,T1.5)
阶段3  T3.1 → T3.2 → T3.3 ; T3.4(依赖T3.1)
阶段4  T4.1..T4.8（依赖T2.2，可并行，按模块）
阶段5  N 系列（见各卡依赖）
阶段6  T6.1 → T6.2 → T6.3
```

## 2. 阶段 0：基线

### T0.1 功能保留账本与基线证据
- 目标：把第 1 章账本落为仓库文件 `docs/feature-ledger.md`（拟建），每行补“现状入口路径、操作步骤、截图/录屏文件名”。
- 步骤：逐个模块在真机/模拟器操作并截图到 `docs/ledger-evidence/`；记录现状缺陷（不修）。核对：任务完成是否自动生成实际记录；各类提醒是否在开机/时区变更后重排；`FishTimeTools` 10 个工具的参数与返回。
- 验收：36 行全部有证据；缺陷清单单列。
- 回滚：仅文档。

### T0.2 基线测试与构建
- 目标：在干净环境跑通现有测试命令，记录本轮真实结果（不引用历史数字）。
- 步骤：配置 Android SDK（compileSdk 36、minSdk 26、targetSdk 35）；运行测试命令；修复环境类问题（不改业务）；记录 APK 大小与各 ABI/资源占比。
- 产出：`docs/baseline-report.md`（拟建）。

### T0.3 真实数据样本与大数据集
- 目标：迁移测试与性能测试的数据基础。
- 步骤：编写 `scripts/seed-large-dataset`（拟建，生成 1 万实际记录、2 千日程、500 任务、周期规则含手工例外、记账 2 千条、训练 200 次）；导出一份脱敏的 v12 数据库作为迁移测试资产（`app/src/androidTest/assets/db/v12-sample.db`）。
- 验收：脚本可重复执行；样本库能被当前版本打开。

## 3. 阶段 1：架构地基（第 6 章）

### T1.1 Hilt 与数据库单例
- 现存：`data/TimeAgentDatabase.kt`（`open()` 非单例）、`workout/data/*`、各 Activity/Receiver。
- 步骤：引入 Hilt；`@Singleton` 提供主库与训练库；替换所有 `TimeAgentDatabase.open()` 调用（Activity、Receiver、Service、Listener）为注入；保留 `openInMemory` 供测试。
- 测试：单测确认同进程只创建一次；现有测试全绿。
- 真机：开机提醒、支付通知监听、训练通知仍工作。
- 回滚：单 PR 可 revert，无数据变更。

### T1.2 ClockSource 与秒级重组修复
- 现存：`ui/TimeViewModel.kt`、`ui/TimeAgentApp.kt`。
- 步骤：拟建 `core/.../ClockSource.kt`；移除根组件对 `now` 的订阅；计时条单独订阅 `secondTicks`；时间线用 `minuteTicks`。
- 测试：Compose 测试断言计时 5 秒内时间轴重组次数为 0。

### T1.3 v13 迁移：独立日程 + command_log
- 现存：`data/Entities.kt`（`ScheduleBlockEntity`）、`data/TimeAgentDatabase.kt`。
- 步骤：按第 6 章 §3.1 重建 `schedule_blocks`；新增 `command_log`；迁移前快照（若 T6 备份 v2 未完成，先用现有 `PersonalBackup` 生成迁移前备份）；更新 DAO 与所有读取 `taskId` 的代码以处理 null（显示 title）。
- 测试：`MigrationTestHelper` 从 v12 样本库迁移，断言行数、字段、外键、索引；独立日程增删改查单测。
- 真机：升级安装（不卸载）后旧日程完好。
- 回滚：迁移不可逆，回滚方式 = 恢复迁移前备份；发布前必须验证该路径。

### T1.4 CommandExecutor
- 拟建：`domain/command/*`。
- 步骤：按第 6 章 §4 实现 Command/Result/幂等/版本/事务/OperationEntity 撤销/DataChanged 事件；先迁移日程与实际记录的增删改、计时开始/停止。
- 测试：同 requestId 重复提交只写一次；版本冲突不写入；撤销恢复 before 快照；并发提交（两个协程）一致。

### T1.5 鱼工具与本地写入接入 CommandExecutor
- 现存：`fish/FishTimeTools.kt`、`assistant/LocalAssistantParser.kt`、`reminders/ReminderReceivers.kt`。
- 步骤：10 个 `time_*` 工具改为构造 Command；requestId 由 toolCallId 派生；通知按钮写入改走执行入口。
- 测试：插件协议测试（node）＋ Kotlin 工具桥单测：超时重试不重复创建。

### T1.6 按需查询替代全量快照（分步）
- 现存：`data/TimeRepository.kt`（`observe()` 全量 `LocalSnapshot`）。
- 步骤：新增范围查询 DAO（第 6 章 §5.1）与复合索引；先让时间轴使用范围查询；保留 `observe()` 供未迁移页面，T6.1 前删除。
- 测试：范围边界（跨天、跨月）单测；Macrobenchmark 基线建立（拟建 `:benchmark` 模块）。

## 4. 阶段 2：设计系统与导航（第 2、3 章）

### T2.1 设计 Token、主题与双模式
- 现存：`ui/Theme.kt`、`ThemePreference.kt`、`ThemeSwitch.kt`、`TimelinePalette*.kt`。
- 拟建：`ui/design/Tokens.kt`、`ui/design/Presentation.kt`（`LocalPresentation`）。
- 步骤：字号/间距/圆角/语义色 Token；模式×深浅色×动态取色三项偏好与旧偏好迁移（第 4 章 L35）；子 Activity（`LedgerActivity`、`WorkoutActivity` 等）使用同一主题。
- 测试：4 种外观 Token 对比度自动测试；切换模式不重建 Activity（测试断言草稿与计时状态保留）。

### T2.2 Navigation Compose 外壳
- 现存：`ui/TimeAgentApp.kt`（字符串导航栈）。
- 步骤：4 Tab + 中央语音动作；多返回栈；返回键规则与深链（第 2 章 §1.1）；旧页面先作为目的地挂入；设置入口两层结构。
- 测试：导航仪器测试（切 Tab 保留滚动/草稿、返回键顺序、深链返回）。
- 真机：每个账本模块可从新入口到达（逐项打勾）。

### T2.3 通用组件库
- 拟建：`ui/components/*`（第 2 章 §4 全部组件）。
- 测试：截图测试（Paparazzi 或 Roborazzi）覆盖 4 种外观与 200% 字体。

### T2.4 反馈与文案统一
- 步骤：字符串迁入 `strings.xml`；`AlertDialog` 错误替换为 InlineError/StatusBanner/UndoSnackbar（第 2 章 §4.1）；全局 lint 规则禁止新增硬编码中文（自定义 lint 或 detekt 规则）。

### T2.5 编辑器重做
- 现存：`ui/Editors.kt`、`ui/GridBlockEditor.kt`。
- 步骤：日程/任务/实际编辑器（第 3 章 §6），统一日期时间组件，草稿自动保存与恢复，校验表。
- 测试：校验规则单测；进程被杀后恢复草稿的仪器测试。

### T2.6 今天页与语音面板
- 步骤：第 3 章 §1、§3；InputRouter（第 7 章 §3）接入；终稿幂等。
- 测试：语音状态机单测（迟到终稿、重复终稿、取消）；飞行模式本地指令验收。

## 5. 阶段 3：时间轴（第 3 章 §1.3、第 5 章 N08）

### T3.1 视口两轴拆分
- 现存：`core/.../TimelineViewport.kt`。步骤与测试见第 4 章 L01。

### T3.2 TimelineView 组件化与 1/3/7 视图
- 现存：`ui/Timeline.kt`、`TimelineConflicts.kt`、`TimelineRenderCache.kt`、`TimelineSelectionOverlay.kt`。
- 步骤：拆分 Toolbar/Canvas/Gestures/AllDayRow；三种视图；全天行与提醒点；来源过滤；列表模式；移除触控限制。
- 验收：第 3 章 §1.6。

### T3.3 拖拽移动与调整时长（N08）
- 步骤：第 5 章 N08 全部规则；`TimelineGestures` 纯函数可单测。

### T3.4 计划 Tab
- 步骤：第 3 章 §2 全部子页；月视图；周期规则作用范围询问。

## 6. 阶段 4：现有模块迁移（第 4 章，可按模块并行）

| 任务 | 模块 | 账本 | 要点 |
| --- | --- | --- | --- |
| T4.1 | 任务/截止/项目/周目标 | L03–L05、L20 | 列表交互、安排时间、项目周目标 |
| T4.2 | 周期 | L06 | RecurrenceField、作用范围、manualOverride 保护 |
| T4.3 | 提醒 | L07 | 重排全覆盖、权限 Banner、通知操作 |
| T4.4 | 课表/健康 | L08–L09 | 导入向导、差异预览、来源追溯 |
| T4.5 | 记账 | L10 | 主题统一→迁入导航、金额组件、草稿卡片 |
| T4.6 | 训练 | L11 | 主题统一→迁入导航、主计时槽位、生成实际记录幂等；8 种记录类型逐一回归 |
| T4.7 | 附件/收件箱/提取/副驾/规则/问答/建议/简报 | L12–L19 | 统一预览组件、无 Key 提示 |
| T4.8 | 鱼拆分与运行时状态机 | L23–L33 | 第 7 章 §2、§4–§7；QQ/B 站/朗读/悬浮窗/记忆逐项回归 |

每个 T4 任务的验收 = 账本对应行在新入口完成全部子能力＋证据更新。

## 7. 阶段 5：新增功能（第 5 章）

| 任务 | 功能 | 依赖 | 数据库版本 |
| --- | --- | --- | --- |
| T5.1 | N07 重复/稍后提醒 | T4.3 | v14 |
| T5.2 | N03 专注 | T1.4、T2.6 | v15 |
| T5.3 | N06 常驻计时通知 | T5.2 | — |
| T5.4 | N09 模板与快捷记录 | T1.4、T3.2 | v16 |
| T5.5 | N02 小组件 | T5.3、T5.4 | — |
| T5.6 | N10 习惯统计 | T1.6、T5.2 | v17 |
| T5.7 | N05 报表与导出 | T5.6 | — |
| T5.8 | N04 日历双向同步 | T1.4、T4.2 | v18 |
| T5.9 | N01 引导与示例数据 | T2.2、T5.4 | v19 |

每个 N 任务的验收见第 5 章对应“验收”段，另加：备份 v2 覆盖新表（T6.2 后回补）、导出覆盖新数据、TalkBack 可操作。

## 8. 阶段 6：收尾

### T6.1 删除全量快照与遗留导航
- 前提：所有页面已迁移到按需查询与 Navigation Compose。删除 `LocalSnapshot` 相关代码与字符串导航栈，确认无引用。

### T6.2 备份 v2
- 现存：`backup/PersonalBackup.kt`、`BackupSql.kt`、`AttachmentBackup.kt`、`BackupRecoveryUi.kt`、`data/PersonalDataGate.kt`。
- 步骤：第 6 章 §6 全部；鱼数据导出接口（插件侧 + RPC）；兼容 format=1。
- 测试：v1 备份可恢复；v2 备份→恢复后全表计数与哈希一致；恢复中途失败（注入异常）回滚成功；运行时运行中备份标记 skipped。

### T6.3 最终验收与性能回归
- 运行完整测试矩阵（§9）；对比 T0.2 基线；输出 `docs/release-acceptance.md`（拟建）。

## 9. 测试矩阵

| 维度 | 覆盖 |
| --- | --- |
| Android 版本 | API 26、29、31（精确闹钟权限）、33（通知权限）、34（前台服务类型、可划掉通知）、35 |
| 设备 | arm64 真机至少 1 台（鱼运行时）；x86_64 模拟器（验证非 arm64 降级） |
| 外观 | 工具/陪伴 × 浅/深；动态取色开/关 |
| 字体 | 100%、130%、200% |
| 状态 | 冷启动、后台返回、进程被杀、锁屏、Doze、飞行模式、无 Key、运行时停止、权限拒绝/撤销 |
| 时间 | 跨午夜、跨月、闰日、时区切换、手动改系统时间 |
| 数据 | 空数据、示例数据、大数据集（T0.3）、v12 升级 |
| 无障碍 | TalkBack 走查今天/计划/复盘/编辑器/专注 |

## 10. 最终验收清单（全部满足才算完成）

- [ ] 账本 L01–L36 每项在新入口有证据，无能力缺失。
- [ ] 第 5 章 N01–N10 每项验收段全部通过。
- [ ] 底部导航、默认 3 日时间轴、双模式切换不丢状态。
- [ ] 飞行模式＋无 Key＋鱼停止时全部本地业务可用。
- [ ] 所有写入幂等、可撤销、版本冲突不静默覆盖。
- [ ] v12 → 最新版本升级无数据丢失；v1/v2 备份均可恢复。
- [ ] 性能基线达标或有书面说明。
- [ ] 测试矩阵执行记录完整；单元测试、插件测试、仪器测试结果为本轮实际运行结果。

## 附录 A：风险与说明（不作为删减功能的理由）

- 非官方平台接口（QQ、B 站）：可能变更或限制账号，保留功能并在设置页说明；活动日志便于追溯。
- 素材授权（GIF、3D 模型、角色素材、语音模型、Edge 朗读）：在 `THIRD_PARTY_NOTICES.md` 基础上逐项核对来源，未核实的标注“待核实”；本规格未核实全部授权。
- 厂商后台限制：只能诊断与恢复，不能保证。
- 系统日历云同步时效：由系统/账户应用决定。
- 运行时仅 arm64；推理依赖用户 Key 与网络。

## 附录 B：推荐开始顺序

先做 T0.1–T0.3（建立“不退化”的标尺），再做 T1.1–T1.4（地基）。在 T1.4 完成前不要开始任何新功能或 UI 大改。
