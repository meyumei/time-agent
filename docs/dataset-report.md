# 阶段 0 大数据集与 v12 夹具（T0.3）

日期：2026-10-31（Asia/Shanghai）。仅新增数据生成脚本、合成夹具及测试，没有修改业务源码、UI、实体、数据库版本、schema JSON 或依赖。

## 交付与来源

`scripts/seed-large-dataset` 使用 Python 3 标准库，读取仓库已有的主库 v12 schema 创建表、索引及 Room identity。固定参考时间为 `2026-10-31T12:00:00+08:00`；所有 ID、标题、备注、商户、账户及训练内容均为合成值，没有读取真实用户数据、聊天、共同记忆或 Key。训练沿用现有独立 v1 数据库，没有往主库塞入训练表。

本轮交付可重复生成的大数据集，并将可被当前 Room 打开的合成 v12 样本放入约定路径。**真实用户库的导出与脱敏尚未执行**：ADB 及 mDNS 无设备，既有手机连接尝试超时，本地也没有可用的真实 v12 导出。不能将合成夹具写成已完成“真实库导出脱敏”，不能把主机测试当作真机验收。设备情况见 [device-availability.txt](ledger-evidence/device-availability.txt)。

| 数据 | 数量 | 说明 |
| --- | ---: | --- |
| 实际记录 | 10,000 | 9,800 条手工来源 + 200 条训练来源；包含重叠与跨午夜记录 |
| 日程 | 2,000 | v12 中全部关联有效 taskId，含锁定条目 |
| 任务 | 500 | 100 个周期实例任务 + 400 个普通任务，含 TODO/IN_PROGRESS/COMPLETED/CANCELLED |
| 周期规则 / 实例 | 10 / 100 | HABIT、DEADLINE 各 5 个规则；手工完成、跳过、改期各 10 次 |
| 记账交易 | 2,000 | EXPENSE/INCOME/REFUND；5 种当前有效账户类型 |
| 支付捕获草稿来源 | 200 | 仅合成、CONFIRMED，关联交易，不含真实通知 |
| 训练 | 200 | 8 种 WorkoutMetric 各 25 次，覆盖 NORMAL/WARMUP/DROP/FAILURE、超级组及 Tabata |
| 其他关联 | 见清单 | 6 分类、6 活动、4 项目、4 周目标、3 标签及关联行 |

周期规则覆盖 `2026-10-01` 至 `2026-10-10`。实例保留 v1 规则快照，当前规则为 v2；手工例外状态和版本字段保留。结束日期早于固定测试日的回溯展开窗口，避免正常刷新补出未包含在 500 个任务内的新实例。

## 夹具与复现

| 文件（`app/src/androidTest/assets/db/`） | 字节 | SHA-256 |
| --- | ---: | --- |
| `v12-sample.db` | 3,858,432 | `14aefab54070d419cfd86c69eed417ab07aacbb1e3b531d38ec4be217aaaeba0` |
| `workouts-sample.db` | 856,064 | `2c7b71d47b554befeba3ecbc2488e6b7aa67ee61f1ef919b47c503962d8ea51d` |

主库 `PRAGMA user_version=12`，Room identity 为 `9f8125fa4618be68edd274dc29c9e8c2`。训练库 `user_version=1`，按当前 Room 实体和索引建立，首次 Room 打开时验证结构并建立 identity。两份提交夹具均为关闭连接后的独立 SQLite 文件，不需要 WAL/SHM 文件。精确计数与 schema SHA 见 [dataset-manifest.json](../app/src/androidTest/assets/db/dataset-manifest.json)。

```powershell
python scripts/seed-large-dataset --output-dir outputs/t0-large-dataset
python -m unittest discover -s scripts -p test_seed_large_dataset.py -v

$config = Get-Content .tools/environment.json -Raw | ConvertFrom-Json
$env:JAVA_HOME = $config.javaHome
$env:ANDROID_HOME = $config.androidSdk
$env:ANDROID_SDK_ROOT = $config.androidSdk
.\gradlew.bat :app:testDebugUnitTest --tests com.timeagent.data.V12SampleDatabaseTest --console=plain --no-parallel --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=GBK'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain --no-parallel --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=GBK'
```

生成结果默认在被 Git 忽略的 `outputs/t0-large-dataset/`。更新资产时同时复制两个 `.db` 和清单。`.gitignore` 对其他个人数据库仍生效，本轮只精确提交这两份纯合成夹具。

## 本轮验证

- Python 合同测试：**1 项通过**。实际生成、核对精确规模、SQLite 完整性/外键、8 种训练字段；在同一目录再次生成，两个数据库及清单逐字节 SHA-256 一致。
- 新增 Room 夹具测试：**1 项通过**。在 Robolectric API 28 私有目录打开两库副本，执行真实 Repository 初始化与查询，校验周期实例及规则、记账类型与金额、200 次训练的 `WorkoutRules.validateSession()`，核对训练记录关联；周期刷新后仍为 500 任务、10,000 实际记录，手工例外未改变。
- 新增测试后的完整 app 单元测试：**630 项，628 通过、2 跳过、0 失败/错误**，完整命令退出 0，耗时 1 分 46 秒。跳过的仍是需要私有 PDF 的两个测试，详情见 [test-summary.json](dataset-evidence/test-summary.json)；不回填 T0.2 的改造前总数。
- Lint 命令退出 0：**0 错误、73 警告、2 信息项**。未清理既有警告。新夹具位于测试资产目录，没有打入主 APK；最终 APK SHA-256 与 T0.2 完全一致。

实际执行日志：[Python](dataset-evidence/seed-final-test.txt)、[Room 定向测试](dataset-evidence/room-sample-final.txt)、[完整 app 回归与 Lint](dataset-evidence/t03-app-regression.txt)。Gradle 起止时刻、命令与退出码保存在同名 JSON 文件中。

初次失败与本轮修正仅涉及脚本、夹具或测试：

1. 先运行测试确认缺少生成器/夹具时失败，再实现生成器并生成资产。
2. Windows 上 SQLite 读连接未关闭导致同目录重跑文件被占用；使用 `contextlib.closing` 关闭生成器与测试连接。
3. 测试误将 Repository 的 activePlans 数量当作数据库全部日程数，遇到取消任务被过滤后得到 1,511。改为 DAO 核对 2,000 条原始日程，并保留可见日程非空断言，没有改变业务筛选逻辑。
4. 最后一轮首次 Room 测试在周期刷新后得到 570 个任务：原夹具结束于 10 月 30 日，刷新会合法回补 10 月 24–30 日的 70 个缺失实例。将合成规则结束日对齐已生成的 10 月 10 日，重新生成后通过；没有改动 `RecurringService` 或放宽精确任务数断言。

测试覆盖的是当前 v12/v1 打开及合成数据合法性，不是数据库迁移、真机性能或后台提醒验收。没有新增 v13、`Migration`、`MigrationTestHelper` 或拟建 benchmark 模块。

## 协作与验收缺口

本轮 WorkBuddy CLI 2.171.0 调用两次，均请求模型 `deepseek-v4.1-flash` 和 `effort=max`；返回模型均为 `deepseek-v4.1-flash`，服务端没有返回实际 effort，故记为未知。DS 负责数据契约参考和训练 JSON 构造函数，主代理负责 SQL、集成及独立执行测试。第一次只读契约建议中的无效枚举/路径没有照用；第二次禁用工具，仅接收指定源码片段、返回函数，未修改共享入口或实体。

请求 `max-turns=12/2`，返回 `num_turns=82/3`，不能据此声称 CLI 的次数限制已被服务端严格执行。第一次配置了可读文件白名单，但日志显示 34 次 Read 尝试，包含白名单外的模块源码/猜测路径，其中 19 次返回错误；未观察到 Write/Edit/Bash 或业务文件修改。该调用结束后改用完全禁用工具、只提供源码片段的第二次调用。总共两次调用、无自动重试，CLI 的 success 只表示调用结束，不等于测试通过。详细分工和实际观察见 [collaboration.json](dataset-evidence/collaboration.json)。

受影响账本：数据基础涉及 L01–L07、L10–L11、L20、L34；业务行为未变，L01–L36 的真机补证仍按 `docs/feature-ledger.md` 执行。待设备可连接后：

1. 在独立示例环境打开/恢复大数据集，核对任务、日程、实际、记账和训练列表及条数，验证跨午夜/重叠显示和手工例外。
2. 按现有备份流程取得真实 v12 主库及训练库，离线脱敏后检查完整性、外键和当前版本可打开，另存来源与计数，不把原库提交进仓库。
3. 补齐账本 36 项真机截图/录屏，以及重启、时区、权限、锁屏、后台和 Linux/DSH 的设备证据。上述项目本轮均未运行。

下一张开发任务为 **T1.1：Hilt 与数据库单例**，本 PR 不实施。
