# T0.3 数据库夹具

这两份数据库完全由 `scripts/seed-large-dataset` 生成，只含合成数据。它们不是从真实用户数据库导出后脱敏得到的样本；本轮没有取得设备数据库，规格 T0.3 要求的真实库脱敏导出仍待补。

- `v12-sample.db`：沿用主库已提交的 v12 schema、索引及 Room identity，含 10,000 条实际记录、2,000 条日程、500 个任务、10 个周期规则、100 个实例（30 个手工例外）、2,000 条记账交易。
- `workouts-sample.db`：现有独立训练库 v1，含 200 次完成的训练。训练关联的 200 条实际记录已经包含在主库的 10,000 条之内，不能重复累计。
- `dataset-manifest.json`：来源声明、精确表计数、schema identity、文件字节数与 SHA-256。

在项目根目录用 Python 3 标准库生成：

```sh
python scripts/seed-large-dataset --output-dir outputs/t0-large-dataset
python -m unittest discover -s scripts -p test_seed_large_dataset.py -v
```

生成器重跑只替换目标目录中的两个具名数据库及清单。更新夹具时将三份输出一并复制到本目录，再运行 `:app:testDebugUnitTest --tests com.timeagent.data.V12SampleDatabaseTest`。该测试在 Robolectric 私有目录中通过当前版本的 `TimeAgentDatabase.open()` 和 `WorkoutDatabase.open()` 打开副本，不访问手机数据。

当前主库仍是 v12，没有添加迁移。后续 T1.3 可用本文件作为 v12→v13 迁移输入，但必须另外添加 `MigrationTestHelper` 测试；本轮 Room 打开成功不代表未来迁移已验收。

设备上验证时使用独立示例设备和 Debug 应用数据，将两库副本分别放入应用的 `databases/time-agent.db` 和 `databases/workouts.db` 后启动；不要覆盖个人数据。本目录不是现有备份功能的 ZIP 导入包。本轮未安装或恢复到设备，也未测大数据集性能。具体结果和未完成项见 `docs/dataset-report.md`。
