# AGENTS.md

本仓库当前的改造工作以 `docs/product/全面改造规格-2026-10-31/` 为准。开始任何任务前，先阅读 `00-总览与硬约束.md`、`01-产品边界-术语与功能保留账本.md`，再阅读任务引用的章节，并按 `08-Codex任务-依赖-测试与验收.md` 的顺序与任务卡执行。

## 硬约束（与规格冲突时以规格 00 章为准）

- 不删除、不削弱任何现有功能（功能保留账本 L01–L36）；可以重构实现、统一入口。
- 不改大肥鱼的名字与形象。
- 保留 Linux（proot）＋DSH 运行时；原生业务不得依赖运行时、Key 或网络。只有一条鱼、一套共同记忆。
- AI 只支持用户自带 API Key（BYOK），不做官方代理、免费额度或计费。
- 底部导航：今天／计划／中央语音动作／复盘／管家；时间轴默认昨天/今天/明天三日，可切 1/3/7 日。
- 工具模式与陪伴模式共用同一套页面，只改变呈现；切换不丢草稿、计时、聊天，不重启鱼。
- 规格中标“拟建”的文件、字段、表尚不存在，需要你创建；不要假设它们已存在。
- 数据库变更：每个版本一个 Migration、提交 schema JSON、`MigrationTestHelper` 测试；禁止 `fallbackToDestructiveMigration`。
- 写操作在 CommandExecutor（T1.4）落地后一律经由它：requestId 幂等、版本检查、可撤销。
- 遇到规格未定义的产品决策：停止并列出选项，不要自行决定删减。

## 测试

```sh
./gradlew :core:test :agent-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd agent-plugins/time-agent && node --test test/*.test.mjs
```

只报告本轮实际运行的结果。涉及 UI、权限、后台、锁屏、进程被杀、迁移的改动，需附真机或模拟器验收步骤与结果；仅单元测试通过不算完成。

## 每个任务完成时输出

改动摘要、受影响的账本编号与验证方式、测试命令与结果、真机验收步骤、已知限制与未完成项。
