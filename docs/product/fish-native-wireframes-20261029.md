# 时间 Agent 原生页面线框（P1，2026-10-29）

范围：四个主页与三组重点子页面的原生结构，配套已交付的 `PlanHubScreen` 与 `ButlerHomeHeader`。
本文只描述现有 `LocalSnapshot` 可支撑的内容；不新增会话数据层、不新增业务存储、不使用桌面小组件。

## 1. 已交付组件（本次唯一代码文件）

文件：`app/src/main/java/com/timeagent/ui/ButlerHomeScreens.kt`

| 组件 | 签名 |
|---|---|
| `PlanHubScreen` | `data: LocalSnapshot, zone: ZoneId, onSelect: (TaskEntity)->Unit, onCreate: ()->Unit, onStart: (String?)->Unit, onOpen: (String)->Unit` |
| `ButlerHomeHeader` | `onChat: ()->Unit, onSettings: ()->Unit, onMemory: ()->Unit` |

`onOpen` 只使用约定值：`projects` / `recurring` / `deadlines` / `inbox` / `attachments` / `goals`。
`onStart(task.id)` 交给主代理现有的计时入口；`onStart(null)` 本组件不调用，保留给自由计时。
组件不持有 ViewModel、不读写 Repository、不发网络请求；只渲染传入快照并回调。

## 2. 计划首页线框

```text
┌──────────────────────────────┐
│ 计划                          │  标题
│ 把未来的事放回项目、习惯、期限… │
│ [ 新建任务 ]                  │  onCreate
├──────────────────────────────┤
│ 项目                查看全部  │  onOpen("projects")
│  · 项目名                     │
│    截止 10月31日 · 关联任务 3 个│  真实字段：dueAt / placements
│  · 项目名                     │
├──────────────────────────────┤
│ 任务                          │
│  · 任务名            (▶)      │  点行 onSelect；▶ onStart(id)
│    预计 30 分钟 · 中优先级 …   │  estimatedMinutes/priority/status/dueAt
│  另有 N 个未完成的任务          │  仅当超过 6 条
├──────────────────────────────┤
│ 周期习惯            查看全部  │  onOpen("recurring")
│  · 晨间阅读                   │
│    每周一三五 · 07:30 · 今天有… │  patternJson + 今日待完成实例
├──────────────────────────────┤
│ 生活期限            查看全部  │  onOpen("deadlines")
│  · 房租                      │
│    明天 · 10月30日 · 待处理    │  最近 PENDING 实例日期
├──────────────────────────────┤
│ 本周目标            查看周目标 │  onOpen("goals")
│  · 项目名                     │
│    本周目标 300 分钟           │  原值显示，不算完成度
├──────────────────────────────┤
│ 收件箱                        │
│  · 未安排的想法 2 个           │  onOpen("inbox")
│  · 附件收件箱 4 个             │  onOpen("attachments")
└──────────────────────────────┘
```

数据来源与过滤：

- 项目：`projects` 中 `deletedAt == null && status == "ACTIVE"`，按 `dueAt` 升序；数量为 `placements` 关联的非删除任务数。
- 任务：`tasks` 中 `deletedAt == null` 且 `status` 为 `TODO` / `IN_PROGRESS`，按截止时间、优先级排序，最多 6 条。
- 周期习惯：`recurringRules` 中 `domain == "HABIT"` 且未删除；`patternJson` 解析失败时显示“规则待完善”。
- 生活期限：`recurringRules` 中 `domain == "DEADLINE"` 且未删除；取该规则状态为 `PENDING`、原任务未完成/未删除的最近实例日期。
- 本周目标：`weeklyGoals` 中 `weekStart` 等于设备当天所在周的周一；只显示项目名或活动名与 `targetMinutes`。
- 收件箱：`ideas` 中 `status == "UNSCHEDULED"` 且未删除；附件为 `deletedAt == null && purgedAt == null` 的数量。

空数据、加载、滚动：

- `data.loading` 为真时显示一行“正在读取本地数据…”。
- 每个分区有独立空文案，不隐藏入口；收件箱分区在两项计数都为 0 时显示空文案。
- 整页 `verticalScroll`，分区内容随真实条数增长。

可访问性：

- 任务行 `onClickLabel = "查看任务 …"`；开始按钮 `contentDescription = "开始计时：…"`。
- 分区“查看全部”按钮带说明性 `contentDescription`（如“打开生活期限页面”）。
- 项目/习惯/期限/目标行使用 `onClickLabel` 说明将打开的页面与对象；不承诺打开该对象的详情页。

## 3. 管家页顶部线框

```text
┌──────────────────────────────┐
│ (鱼)  大肥鱼                  │  圆形占位，contentDescription 说明素材待提供
│       角色形象、口吻与动画待提供 │
│       当前为页面占位，不显示运行 │  不展示运行/平台在线
│       或平台状态               │
│ [ 和大肥鱼聊聊 ]   [ 设置 ]    │  onChat / onSettings
│ [ 共同记忆与角色约定 ]         │  onMemory
└──────────────────────────────┘
```

- 不使用立绘、动画或虚构状态；占位头像仅显示“鱼”字。
- 三个入口只回调，不读取会话、不显示消息数、不显示平台连接状态。
- 聊天历史、共同记忆与人格内容由主代理在 P2/P3 接入，本组件不建立数据层。

## 4. 四个主页（P1 线框约定）

### 今天

```text
日期 / 当前进行中的计时（如存在）
[时间轴：计划块 + 实际记录]
今日待办（可点开、可开始）
临近期限（只列真实待处理项）
补记 / 开始训练入口
```

保留现有时间轴与 `DailyBriefing` 能力；不新增聚合分数。

### 计划

见第 2 节。中央输入仍由 `AppChrome` 注入，计划页不复制语音按钮。

### 复盘

```text
周切换
计划投入 / 累计记录投入 / 去重时间跨度（现有 TimeStatistics）
项目进展（ProjectReviewSection）
分类投入
近 14 天趋势
```

沿用现有口径与说明文字，不新增“效率分”“健康分”等未记录指标。

### 管家

```text
ButlerHomeHeader
会话区（P2 接入真实会话后填充）
共同记忆 / 平台见闻（P3/P4）
设置入口
```

P1 只交付顶部占位；不伪造聊天记录、不伪造平台在线。

## 5. 三组重点子页面路径

| 流程 | 入口 | 路径 | 返回 |
|---|---|---|---|
| 项目 | 计划 → 项目 → 查看全部 | 项目列表 → 展开项目 → 打开任务 → 排进今天 / 开始计时 | 逐级返回，保留列表展开状态 |
| 周期 | 计划 → 周期习惯 → 查看全部 | 规则列表 → 本次实例 → 完成或调整 → 规则与以后安排 | 返回实例列表，再回计划 |
| 生活期限 | 计划 → 生活期限 → 查看全部 | 按日期分组 → 详情 → 处理或延期 | 返回原日期分组 |

现有 `ProjectScreen`、`RecurringScreen`、`DeadlineScreen` 已覆盖上述主路径；P1 计划只调整入口与视觉，本次未改动这三个文件，也不改写业务写入逻辑。

## 6. 与主代理的边界

- 本组件不修改 `TimeAgentApp`、`AppChrome`、`TimeViewModel`、`TimeRepository`。
- 是否把“计划”作为第二个主页、`ButlerHomeHeader` 放在管家页何处，由主代理决定。
- 页面标题、底栏四目的地与中央语音仍由主代理所有。
- 本组件未接入 `TimeViewModel.now`：周目标与“今天/明天”按 `LocalDate.now(zone)` 计算，跨日刷新依赖重组；主代理若提供统一的 `now`，可改为参数传入。

## 7. 未验证项

- 未执行完整 Gradle 构建或安装 APK；仅做源码级自查（字段、导入、Compose API、空数据分支）。
- 未在真机或 Compose 预览中检查窄屏、超大字体与深色主题下的换行。
- 未编写 UI 测试；testTag 已保留：`plan-hub`、`plan-create`、`plan-projects/tasks/habits/deadlines/goals/inbox`、`plan-start-{taskId}`、`butler-header/chat/settings/memory`。
- 未接入真实会话、人格素材、平台插件；本页不展示其状态。
- 未新增任何依赖、权限、数据库表或网络调用。
