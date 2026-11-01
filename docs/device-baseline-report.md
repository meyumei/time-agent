# 阶段 0 真机补证与数据恢复记录

日期：2026-11-01，Asia/Shanghai。范围仅 T0.1–T0.3；业务代码、UI、实体、数据库版本及依赖未改。初始无设备的记录保留为历史，本页为连接手机后的实际结果。

## 设备、协作与证据

Redmi K70 Ultra，Android 16 / API 36，arm64-v8a。原安装版本为 0.15.1-preview / code 27；在原数据完整备份后升级安装本轮 0.15.2-preview / code 28，没有卸载。APK 890073507 字节，SHA-256 `13ae925f380092dd707e35a0b3a14259dd6f587c213036def738810970bcdd36`。测试期间通知允许、精确闹钟未授权、悬浮权限未授权。

使用用户指定的 WorkBuddy `codebuddy.cmd`，实测版本 2.171.0，请求 `deepseek-v4.1-flash`、`effort=max`，返回模型均为 `deepseek-v4.1-flash`；**服务端没有返回实际 effort，记录为未知**。DS 顺序操作手机，主代理处理离线集成和独立验收，不同时操作 UI。DS 只能调用限定的手机采集器，不能读写源码、私有原库或 Key，也不能登录或向外部联系人发消息。每模块最多 110 个采集器动作、900 秒；入口最多找两次，权限/限额错误停止。CLI 的 num_turns 与动作数不同，不能据此声称 max-turns 得到服务端严格执行。

| DS 模块 | 实际采集器动作 | CLI 报告 turns | 独立判定 |
| --- | ---: | ---: | --- |
| business | 23 | 62 | 首轮 UI 导出失效，证据作废 |
| business2 | 82 | 204 | 部分原生入口与新建任务有效 |
| native2 | 82 | 200 | 完成任务、各原生表单与主题切换有效 |
| fish | 72 | 183 | 鱼各页和启动动作有效，模型回复未证实 |
| workout | 93 | 244 | 计时启停、训练页面和 3D 有效；完整训练未完成 |

首轮 stock uiautomator 在动画页面未能生成新树却返回成功，读到了陈旧树；该轮截图没有纳入交付。随后使用新鲜 UiAutomation 树并检查目标包/截图时前台，修正 Android 16 主 Looper 初始化后继续。原始截图和完整流保留在忽略目录，提交的 49 张截图覆盖 33 个模块，均遮挡未知私人文字及其他应用窗口。错误编号已纠正：冲突列表归 L01；课表那张只标“设置中的导入入口”。逐行证据与剩余子能力见 [功能账本](feature-ledger.md)、[截图清单](ledger-evidence/device-20261101/manifest.json)、[协作记录](ledger-evidence/device-20261101/collaboration.json)。截图不能证明 GIF 播放或全部流程通过。

另有 Codex 子代理 Anscombe 只在限定的两个脱敏脚本文件协作，未接触手机、私有源库、Key 或共享入口。脱敏器的最终修正、真实源库处理及所有验收由主代理完成。

## 已独立核对的实际行为

- **L03 完成不自动生成实际**：DS 完成 `T0_DS_COMPLETE`；主代理在停止应用后取一致副本，核对 COMPLETED、version 1，实际前后均 10000，该任务关联实际 0。[结果](ledger-evidence/device-20261101/task-completion-result.json)
- **L02 计时停止生成实际**：DS 为 `T0_DS_DONE` 启停计时，运行条出现/消失；独立读库实际为 10001，该任务关联实际 1，运行中实际 0。[结果](ledger-evidence/device-20261101/timer-workout-independent-check.json)
- **L07 真实时区重排**：独立仪器测试在内存库保存手工提醒、周期习惯、周期期限各 1 个；先确认 3 个 pending alarms，再 cancelAll 确认 0，实际修改系统时区触发系统事件后恢复 3。普通 plan/dueAt 没有自动生成提醒。通知允许、精确闹钟未授权，所以只验证重排，未验证精确送达。[结果](ledger-evidence/device-20261101/timezone-restore.json)
- **L32 十工具**：在设备内存 Room 上逐一执行全部十个原生工具，保存真实实参/返回，核对任务完成不增加实际、撤销和非法日期拒绝。没有使用运行时或 Key；这不是 DSH RPC/模型端到端。[结果](ledger-evidence/device-20261101/native-time-tools.json)
- **L11 训练和 L31 运行时**：人工看图确认 3D 人体确已渲染；动作详情有图像，但静态图不能证明 GIF 播放。自由训练启动后被放弃，独立库内为原 200 次合成完成训练 + 1 次 DISCARDED。UI 启动运行时后独立观察到 com.timeagent、libproot.so、node、python3.12。鱼消息回复未验收，失去前台没有独立崩溃证据。
- **T0.3 真实样本**：升级前原始 v12/v1 一致副本离线脱敏，当前 Room 在手机具名临时数据库打开成功，30 任务、48 实际、13 日程、3 训练均符合源库，完整性和外键通过。[结果](ledger-evidence/device-20261101/anonymized-device-open.json)

仪器测试命令（安装新测试 APK 后）：

```sh
adb -s SERIAL shell am instrument -w -e class com.timeagent.baseline.T0BaselineDeviceTest com.timeagent.test/androidx.test.runner.AndroidJUnitRunner
```

最终真实结果为 **OK (3 tests)**，用时 1.743 秒。首次 3 项中有 1 项超时：测量把 dumpsys 的移除历史也算成待触发闹钟；仅修正测试为独立 pending tag 行计数，重建测试 APK/Lint 后三项通过。失败和最终日志分别保留：[首次](ledger-evidence/device-20261101/instrumentation-first.txt)、[最终](ledger-evidence/device-20261101/instrumentation-final.txt)。测试结束后已还原原有测试 APK。

## 原记录与文件恢复

测试前停止应用，备份 databases、shared_prefs、附件和 DSH 会话/共同记忆目录；原始 tar 6883840 字节、SHA-256 `0c06795a86d849a9d15d578f49ea9f1defd1bf506ef3e7579b99626405b1f8a6`。原始 tar、源库和映射均留在本机被 Git 忽略的 private 目录，没有上传。

UI 测试用临时合成库；没有让 DS 操作原记录。结束后停止所有测试并恢复原四个目录，再独立读取校验：**39 个文件内容 SHA 全一致；主库 32 张表、训练库 4 张表（含元数据）计数和内容摘要全部一致；两库 integrity_check=ok、foreign_key_check=0**。保留原 DB/WAL/SHM 一致集。原 48 实际、30 任务、13 日程、3 训练、106 历史、175 修订、31 健康条目等均恢复。附件和鱼会话/共同记忆文件也逐文件一致；没有读取或修改 no_backup 中的 Key。恢复读取第一次发生在 tar 流仍写入附件/会话期间，未提前认定通过；再次独立取完整目录后 39 文件全部一致。

时区还原 `Asia/Shanghai`、自动时区 `1`，临时健康同步开关也随原偏好恢复。应用保持停止，以此状态作精确比对；用户下次打开后正常同步/刷新可能合法更新数据。原测试包已装回，具名 UI probe/XML/合成库中转文件已从手机清理。应用保留升级后的 code 28，没有降级或卸载。

[恢复核对](ledger-evidence/device-20261101/restore-verification.json)；[清理核对](ledger-evidence/device-20261101/cleanup-verification.json)。受控 ADB 恢复不能冒充 L34 应用内 ZIP 备份、跨库失败回滚或未来迁移通过。

## 旧 APK 清理（首次扫描）

扫描 `/sdcard/` 可读文件及 `/data/local/tmp` 三层内 APK 候选：33 个，32 个识别出包名，均非 com.timeagent；1 个未能读取有效 manifest。因此本轮 **删除 0、释放 0 字节**，未卸载应用，也未删其他应用 APK 或电脑历史包。不能写成全手机旧 APK 已清空；可读范围没有找到可确认的旧版时间 Agent 安装包。[记录](ledger-evidence/device-20261101/apk-cleanup.json)

## 缺陷与仍需补证

已确认缺陷沿用账本 D-T0-01–08：日程/普通截止没有自动提醒链；训练恢复遗漏 TIMEZONE_CHANGED；工具返回 ID/结构不一致、缺稳定 requestId、记录标题进入 notes；当前输入路由绕过本地解析；周期 REOPEN 不恢复提醒等。本轮仅记录，未修。

原库 3 次训练中有 1 个空关联、1 个有效实际关联、1 个已无主库目标的关联，脱敏后同样保留；没有证据断定原因，不能擅自补记录。DS 训练选择器的“完成（数量）”被采集器动态文案过滤，不能把采集失败写成产品按钮缺失。

**T0.1 仍为部分完成，PR 保持 Draft。** 仍缺真实重启、锁屏、后台/被杀、收音与朗读、悬浮交互、QQ/B站真实登录收发、日历交接；课表/健康导入、交易合并拆分、收件箱分拣目标、训练 8 种记录/全部组类型/超级组/Tabata、应用备份失败回滚，以及逐行列明的未执行操作，也不能标通过。已有页面截图的模块不再统称“全无截图”，但也不等于全部子能力验收。

下一张开发任务为 **T1.1：Hilt 与数据库单例**；本次未实施。

## 追加：由 DS 子代理实际执行 APK 清理

用户再次指定由子代理执行后，WorkBuddy DS 在一次独立调用中实际顺序执行扫描、删除已确认旧包两个命令，各一次。请求 deepseek-v4.1-flash / effort=max，返回模型确认，实际 effort 未返回。限制为两个具名采集器命令、180 秒；请求 max-turns=6，CLI 返回 8，实际工具调用只有 2 次。

本次扩大到全部可读共享存储、全部 /data/local/tmp 及本应用 files/cache 中 APK 文件，发现 **33 个候选，31 个可识别、2 个无法识别，确认的旧版 com.timeagent 为 0**；删除流程已真实执行，**删除 0、释放 0 字节**。没有可据此删除的旧版时间 Agent 文件，不能宣称全手机旧包已清空，也不能照抄 DS 对设备不存在旧包的过度结论。完整流留在本机；提交 [实际子代理清理摘要](ledger-evidence/device-20261101/ds-apk-cleanup.json)。

清理后复核发现应用期间自动刷新了 health_sync_items.lastSyncedAt、对应 revision 与一条自动建议；任务、实际、日程和训练内容未变。先将刷新后的状态另存私有快照，再还原测试前文件。最终独立比对再次为 **39 文件全一致、36 表计数/内容摘要全一致、完整性通过、外键 0**，时区和自动时区不变；最新结果已更新恢复核对文件。没有删除原记录或卸载应用。

## 2026-11-01 再次清理与训练续测

本节是用户要求“删除旧版本 APK 并继续”后的独立一轮，不覆盖前述历史结果。WorkBuddy DS 再次实际执行以下两个限定命令，各一次；返回模型 `deepseek-v4.1-flash`，请求 `effort=max`，实际 effort 仍未返回。执行约 100.6 秒，退出 0，实际工具调用 2 次；CLI 报告的 8 turns 另行记录。

```powershell
python .tmp/t0-phone/apk-cleanup-round2.py scan
python .tmp/t0-phone/apk-cleanup-round2.py delete-confirmed
```

扫描全部可读共享存储、`/data/local/tmp` 与本应用 files/cache 中的 APK，**33 个候选、32 个识别成功、确认的旧版 com.timeagent 为 0；实际删除 0，释放 0 字节**。此次用 Android SDK aapt2 补核了前次两个未识别文件：一个确认是其他应用；另一个报告 `failed opening zip: Invalid file`，无法判定包名，因此保留。不能称为全手机旧包已清空。已安装的时间 Agent code 28 与测试包保留，没有卸载、清空数据或删除其他应用安装包。[清理结果](ledger-evidence/device-20261101-followup/apk-cleanup.json)

测试前重新停止应用并保存**本轮开始时**的当前数据，未直接套用 10 月 31 日备份。新备份仍为 6883840 字节，SHA-256 `a73390ce8b6754d71e35f85c30ff10cb0ad051667b3ac22c85bbb19a92fd1805`，共 39 个文件。两库共 37 张表（含元数据，本次还计入 `sqlite_sequence`）；主库 30 任务、48 实际、13 日程，训练库 3 次训练。完整性通过、外键违规 0；原备份继续保留在本机忽略目录。[备份摘要](ledger-evidence/device-20261101-followup/backup-summary.json)

本次只调整忽略目录中的采集器：允许源码已有的动态公开按钮“完成（数量）”“完成第 n 组”等被 DS 看见，不改应用 UI 或业务。随后安装临时合成库，派 DS 补 L11 动作添加、完成一组、休息、暂停/恢复、结束和模板；日历交接夹具仅为后续准备，未执行交接。

```powershell
python .tmp/t0-phone/run-ds.py workout2
```

**该轮未验收**：25 次工具调用请求中，采集器实际记下 24 个动作、10 个错误，成功截图 0。一次短暂取得今天页后，目标应用持续不在前台，主代理独立检查也观察到其他应用在前台。DS 没有遵守“两次入口失败即停止”，主代理终止了该轮，进程退出 1；未得到训练保存、休息、暂停/恢复或模板证据，也没有证据证明前台切换是应用崩溃。因此不增加通过项、不新增产品缺陷，不将 DS 尝试当成流程完成。[失败动作摘要](ledger-evidence/device-20261101-followup/workout2-attempt.json)、[实际协作记录](ledger-evidence/device-20261101-followup/collaboration.json)

随即恢复**本轮新备份**并两次独立读回；最后一次仅校验：

```powershell
python .tmp/t0-phone/restore-round2.py --verify-only
```

结果退出 0：**39 个文件内容全部一致，37 张表行数及内容摘要全部一致，两库 integrity_check=ok、foreign_key_check=0**。附件、偏好、DSH 会话与共同记忆文件一并恢复，未读取或修改 no_backup 中的 Key；时区 `Asia/Shanghai`、自动时区 `1`。已删本轮上传的 UI probe，临时合成库中转文件也已删除，应用保持停止用于精确比对。[最终恢复校验](ledger-evidence/device-20261101-followup/restore-verification.json)

本轮只更新文档与证据，**未重新运行 Gradle、Node、Python 单元测试或三项仪器测试**；前述测试数字仍属于对应的上一轮实测。原 49 张有效截图与 33 个模块覆盖数未增加。L11 完整训练、L36 日历交接和前述重启、锁屏、语音等缺口仍在，T0.1 保持部分验收，PR 继续为 Draft。下一开发任务仍为 T1.1，本轮未实施。
