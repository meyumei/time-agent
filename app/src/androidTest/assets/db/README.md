# T0.3 数据库夹具

2026-11-01 补齐真实设备导出。真实脱敏样本与大规模合成夹具分开保存，均只用于测试资产，不进入主 APK。

- `v12-sample.db`、`workouts-sample.db`：从升级前 versionCode 27 的真实 v12 主库和 v1 训练库取得一致快照，再离线脱敏。保留 36 张表（含 Room/SQLite 元数据）的行数、空值、schema、Room identity、关联和合法 JSON；主库有 48 条实际记录、30 个任务、13 条日程，训练库有 3 次训练。
- `anonymized-manifest.json`：真实样本来源声明、脱敏方式、表计数；文件校验值见报告。原始库、附件原件、Key、共同记忆和脱敏映射均未提交。
- `v12-large.db`、`workouts-large.db`：由 `scripts/seed-large-dataset` 生成的纯合成数据，含 10,000 条实际记录、2,000 条日程、500 个任务、10 个周期规则、100 个实例（30 个手工例外）、2,000 条记账交易和 200 次训练。训练对应的 200 条实际记录已包含在 10,000 条总数内。
- `dataset-manifest.json`：合成夹具的规模、schema、文件校验值和资产文件名映射。

生成合成数据：

```sh
python scripts/seed-large-dataset --output-dir outputs/t0-large-dataset
python -B -m unittest discover -s scripts -p 'test_*.py' -v
```

生成器输出仍名为 `v12-sample.db`、`workouts-sample.db`。复制到本测试资产目录时必须分别重命名为 `v12-large.db`、`workouts-large.db`，并同步清单中的资产名映射；不要覆盖真实脱敏样本。

真实源库的导出和脱敏属于受控离线操作。`scripts/anonymize-v12-sample.py --help` 给出参数；输入应为已合并 WAL 的一致副本，输出目录与输入分离。脚本保留版本和结构，生成干净数据库，拒绝未定义的字段合同，随机密钥、ID 映射和日期偏移不输出。

`V12SampleDatabaseTest` 在 Robolectric 私有目录分别打开两组夹具，核对领域数据。`T0BaselineDeviceTest.anonymizedExportsOpenOnDeviceWithoutTouchingUserDatabase` 把真实脱敏样本复制到具名临时数据库后用当前 Room 打开，不覆盖用户库。真实导出中一条训练关联的实际记录已不在主库；样本如实保留该状态，测试分别核对一条空关联、一条有效关联和一条缺失目标的关联。

当前主库仍为 v12，没有添加迁移。后续 T1.3 还必须添加 `MigrationTestHelper` 验证 v12→v13；当前版本打开成功不代表未来迁移已验收。结果、数据来源和限制见 `docs/dataset-report.md`。
