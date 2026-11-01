# 阶段 0 大数据集与真实 v12 脱敏样本（T0.3）

初次交付：2026-10-31；真实设备补齐：2026-11-01（Asia/Shanghai）。只修改生成/脱敏脚本、测试资产、测试与文档，没有修改业务代码、UI、实体、数据库版本、schema 或依赖。

## 两组资产与来源

最初交付仅有可复现的合成库；设备连接后已取得升级前 code 27 的真实 v12 主库与 v1 训练库一致快照，离线脱敏并替换约定的 sample 路径。原合成库保留并更名为 large，不能将它冒充真实导出。两组资产都只进入测试 APK。

| 文件（app/src/androidTest/assets/db/） | 来源 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| v12-sample.db | 真实主库脱敏 | 790528 | `57645b16d0f69d1f5f2358ed12a139db8cedd48f03297aa8deccbf9e287372e9` |
| workouts-sample.db | 真实训练库脱敏 | 57344 | `c9ab97fd601ff8e26642f0bdcaf441fd3075f8a54a70b6215094f9c6c604a5d2` |
| v12-large.db | 纯合成主库 | 3858432 | `14aefab54070d419cfd86c69eed417ab07aacbb1e3b531d38ec4be217aaaeba0` |
| workouts-large.db | 纯合成训练库 | 856064 | `2c7b71d47b554befeba3ecbc2488e6b7aa67ee61f1ef919b47c503962d8ea51d` |

主库 user_version=12、Room identity=`9f8125fa4618be68edd274dc29c9e8c2`，训练库 user_version=1。真实样本有 48 实际、30 任务、13 日程、3 训练，另保留课程、健康、历史、版本等行；全表计数见 [anonymized-manifest.json](../app/src/androidTest/assets/db/anonymized-manifest.json)。原库的一条训练关联目标已不存在，如实保留：1 空关联、1 有效关联、1 缺失目标关联。没有偷偷修复源库或增删业务行。

## 脱敏与独立核验

`scripts/anonymize-v12-sample.py` 从已合并 WAL 的只读副本逐表重建干净 SQLite；不复制原库空闲页。ID 一致映射，私人文字替换，日期作非零整周平移并预检周期关联，保留合法结构/枚举/空值、JSON 及主库/训练库关联。公开内置动作 ID、肌群、GIF 路径和源码固定操作文案保持可用；自定义内容脱敏。遇到未定义的源字段合同会拒绝输出；随机密钥、映射与偏移不写出。

没有复制原附件、原始聊天、Key 或共同记忆。样本中的附件仅脱敏元数据，不声称含可打开的附件原件。原始一致副本与恢复备份留在本机忽略目录，没有进入 Git。

主代理独立比对源/输出两库共 36 张表（含 Room/SQLite 元数据），行数和每列空值数完全一致；完整性通过、外键错误 0。扫描 769 个敏感原文在输出 UTF-8/UTF-16LE 字节中的命中数为 0；源码公开文案及组合“助手 · …”经核对排除。[独立核验](ledger-evidence/device-20261101/anonymization-independent-check.json)

## 大数据集规模与复现

`scripts/seed-large-dataset` 只使用 Python 标准库和仓库已提交 v12 schema。参考时间为 2026-10-31T12:00:00+08:00，无真实数据输入。

| 合成数据 | 规模 | 内容 |
| --- | ---: | --- |
| 实际记录 | 10000 | 含重叠、跨午夜；其中 200 条训练来源已包含在总数内 |
| 日程 / 任务 | 2000 / 500 | 当前 v12 的有效 taskId 外键，含锁定/多状态 |
| 周期规则 / 实例 | 10 / 100 | HABIT/DEADLINE、历史快照；30 个手工例外 |
| 记账交易 / 捕获来源 | 2000 / 200 | 整数分、有效账户类型、纯合成已确认草稿 |
| 训练 | 200 | 8 种 metric 各 25 次、4 种组类型、超级组、Tabata |

周期结束于已生成的 10 月 10 日，避免测试初始化合法回补额外实例而改变约定规模。生成器同目录重复执行后数据库和清单 SHA 一致。

```sh
python scripts/seed-large-dataset --output-dir outputs/t0-large-dataset
python -B -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/anonymize-v12-sample.py --main-source PRIVATE_MAIN_EXPORT --workout-source PRIVATE_WORKOUT_EXPORT --output-dir PRIVATE_OUTPUT
```

生成器的输出名仍为 v12-sample.db / workouts-sample.db；复制到测试 assets 时必须改为 v12-large.db / workouts-large.db，并同步 dataset-manifest 的 assetPackaging 映射。不要用合成输出覆盖真实 sample。四个数据库均精确强制加入 Git，其余个人数据库仍忽略。

## 本轮实际测试

- Python：**9/9 通过**，9.009 秒；包括大数据生成可复现及脱敏关系/历史快照/训练 JSON/未知字段/WAL/源输出隔离等合同测试。[日志](dataset-evidence/t0-followup-python.txt)
- Robolectric 当前 Room 打开两组副本，验证规模、领域数据、周期与跨库关联；本轮新增真实样本断言后 app **631 项：629 通过、2 跳过、0 失败/错误**。[汇总](dataset-evidence/t0-followup-test-summary.json)
- 完整要求的 Gradle 五任务加测试 APK 构建最终退出 0，2 分 4 秒。core 82、runtime 7 在本轮首次 `--rerun-tasks` 已真实执行通过；最后同命令复用它们的未变更结果。主机总计 **720 项：718 通过、2 跳过**。Node **19/19 通过**，Lint **0 错误、73 警告、2 信息项**。主 APK 字节和 SHA 与初始 T0.2 一致。[最终日志](dataset-evidence/t0-followup-gradle.txt)
- 真机新建的 3 项仪器测试最终全过，其中真实样本通过独立具名数据库由当前 Room 打开，核对 30/48/13/3 计数、完整性与外键；不打开用户正式库。另两项覆盖十个原生工具及真实时区重排。[设备结果](device-baseline-report.md)

本次失败与修正没有改业务：第一次误用 LIMIT 80 的 history() 查询断言源库 106 条历史，改为 SQL 精确 count；第二次误断言所有训练都关联仍存在的实际记录，独立查源库确认 1 个原有缺失目标后改为精确保留三类关联状态。首次/第二次失败日志均保留。仪器测试首次误把闹钟移除历史计入 pending 导致超时，仅修测试测量后通过。

初始 2026-10-31 的单独结果保留于 dataset-evidence/t03-app-regression.txt 等历史文件，不把本轮数字回填为当时的基线。两个跳过项仍为依赖私有 PDF 的测试，不虚报为通过。

## 协作与边界

初次两轮 DS 数据契约/训练 JSON 协作仍见 [原记录](dataset-evidence/collaboration.json)。本次 DS 手机采集及 Codex Anscombe 两脚本协作的范围见 [新记录](ledger-evidence/device-20261101/collaboration.json)。主代理实际执行 Python、Room、手机仪器及原样恢复校验，没有把代理完成描述当作实测。

手机测试使用临时合成库，结束后原始 39 文件与 36 表逐项一致、时区和偏好还原；详情见设备报告。大库规模和当前 v12/v1 可打开已经验证；没有测性能指标、未来 v13 迁移或全 Android 版本矩阵，也没有新增 Migration/MigrationTestHelper。T1.3 仍须单独验证 v12→v13。

涉及账本 L01–L07、L10–L11、L20、L32、L34 的数据基础。T0.1 的未测子能力继续在账本显式列出，PR 保持 Draft。下一开发任务为 **T1.1：Hilt 与数据库单例**。
