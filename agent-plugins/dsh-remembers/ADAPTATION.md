# dsh-remembers —— 时间 Agent 适配说明

上游：`derkinL/dsh-remembers` v1.0.0，commit `cadbc0219156de3dd2f1c312b3ec41c9b2615736`，MIT。
本目录只做「能在时间 Agent（DSH 0.2.1-rc.2，Ubuntu arm64 / Node 24 / Python 3.12）里跑起来」的最小改动，
没有重写人格、共同记忆或主动调度。

## 1. 实际改动

| 文件 | 改动 | 原因 |
|---|---|---|
| `client.mjs` | 补齐后恢复为上述固定 commit 的上游原文件 | 初始复制遗漏了该文件。独立验收时撤回代理重写，保留上游客户端语义；认证由 `index.mjs` 的本机请求包装处理。 |
| `persona-default.mjs` | **新增** | 没有角色卡时的明确占位，导出的纯函数便于测试。 |
| `index.mjs` | 空人格卡 → 占位段；`主要用户/用户` 替换上游「主人/老大」；画像段标题同步 | 不再让模型自己补一个预设角色；不预设助手姓名、口头禅、对用户的称呼。 |
| `package.json` | `exports["./client"]` 指向 `client.mjs` | 原来指向不存在的 `client.js`。 |
| `service/server.py` | 数据目录默认 `/root/.timeagent-dsh/memory`；`proactive.json` 跟随 `CMEM_DATA_DIR`；监听地址走 `resolve_bind_host`；主动动作默认文案中性化、去掉 QQ 专用动作 | 原默认 `/data`、`PROACTIVE_FILE` 写死另一台机器路径、默认 `0.0.0.0`。 |
| `service/netcfg.py` | **新增** | `CMEM_HOST` 只接受回环；`0.0.0.0`/局域网地址会被拒绝并强制 `127.0.0.1`，同时打印原因。 |
| `service/paths.py` | **新增** | 数据目录默认值单点定义，可单测。 |
| `service/scheduler.py` | `_inject` 增加 App 桥；`_owner_qq` 去掉硬编码 QQ 号 | 见下节。 |
| `tests/` | **新增** 3 个测试 | 占位、App 出口、回环。 |

没有 systemd / docker / 安装脚本改动；`service/deploy*.sh`、`Dockerfile`、`docker-compose.yml`、
`install-cron.sh` 原样保留但**不使用**。没有完整跑 Gradle。

## 2. 主动消息出口（唯一改动的发送函数）

原版链路：`Scheduler._inject()` 把一条 synthetic 事件追加到 `CMEM_INJECT_PATH`（QQ/onebot 文件通道）。
现在：

1. 配了 `TIME_AGENT_BRIDGE` → `POST http://<bridge>/proactive`，头 `Authorization: Bearer $TIME_AGENT_TOKEN`，
   body `{"text": "...", "source": "proactive"}`，可选 `sourceUrl`。**不再写 QQ 文件**。
2. 没配 bridge、但配了 `CMEM_INJECT_PATH` → 保留原文件行为（QQ 兼容）。
3. 两个都没有 → 只打日志跳过。

桥的另一端由主代理 `host.mjs` 提供：它把内容送进 `TIME_AGENT_SESSION_ID` 对应的同一个 DSH 会话，
由现有人格与模型自然生成回复。**本插件不生成回复、不另开模型、不做本地假回复，也没有新增心跳/规则引擎**；
唤醒间隔、时间窗、静默比例、B 站节奏全部沿用原版 `Scheduler._tick_*`。

`_owner_qq()` 原来在找不到配置时返回硬编码的 `3169341724`，会把主动内容发给无关的人；
现在返回 `None`，文件通道拿不到号就跳过。App 通道不需要 QQ 号。

`TIME_AGENT_SESSION_ID` 由 host 在启动 service 时传入；插件侧不读它（会话绑定在 host 的桥里完成），
避免两处各存一份会话 ID 后不一致。

## 3. 运行参数（实际生效）

```
CMEM_HOST=127.0.0.1          # 非回环会被拒绝并强制回环
CMEM_PORT=<port>
CMEM_DATA_DIR=/root/.timeagent-dsh/memory
CMEM_AUTH_TOKEN=<可选，插件用 Bearer 调服务>
TIME_AGENT_BRIDGE=127.0.0.1:<host随机端口>
TIME_AGENT_TOKEN=<host 生成>
TIME_AGENT_SESSION_ID=<App 主会话>
python3 service/server.py
```

插件侧 `CMEM_URL`（或 `CMEM_ENDPOINT`）指向 `http://127.0.0.1:<port>`；默认值已是
`http://127.0.0.1:8080`。`CMEM_AUTH_TOKEN` 与服务端一致。

## 4. 测试

先测失败、再改：三个测试断言的都是原版不满足的行为（无占位 / 只写文件 / 绑 `0.0.0.0`）。
本代理环境没有 Bash，**未实际执行**；命令交给主代理：

```bash
cd agent-plugins/dsh-remembers
node --test tests/persona-default.test.mjs
python3 -m unittest discover -s tests -p "test_*.py" -v
```

Python 测试只依赖标准库；`scheduler.py` 导入无副作用，不会建库。
`test_app_bridge.py` 在本机起一个随机端口的捕获服务器，验证路径 `/proactive`、Bearer、payload 字段，
以及「桥可用时不写 QQ 文件」。

## 5. 未验证 / 兼容性说明

- **未在真机跑过 DSH 加载**。已核对上游 v1.0.0 本体对 DSH 0.2.1 的适配（`systemPrompt.section`、
  `agent/pre-step`、`session/event`、`agent.inject`、`tool.register` 均按原版保留），本轮没有改这些调用。
  需主代理在 `.tmp/fish-dev-20261029` 的宿主上确认插件被激活、`persona_pick` 日志出现。
- **`client.mjs` 已由主代理恢复上游原文件**，保留上游的客户端行为。
- **numpy wheel**：`service/embedding.py` 依赖 numpy，由主代理打 linux aarch64 cp312 wheel；
  本目录未改依赖，也未安装。
- **B 站**：保留原版 `bili-cli` 适配与 `bili_feed`/`bili_run` 工具，未新增登录、评论或「真实经历」。
  缺少 `bili` 配置时相关接口应明确不可用（沿用原版报错路径），本轮没有伪造成功状态。
  默认动作里仍保留「去评论区说两句」的文案（原版能力），但不会自动发外部评论：需要模型显式调用工具，
  且工具在配置缺失时不可用。
- **QQ**：未配置、未测试。文件通道代码保留，仅作为 bridge 缺失时的兼容分支。
- 不请求用户密钥/账号，不访问私聊或健康数据，不发送外部评论；本轮没有新增任何此类调用。

## 6. 回滚

只改了上表文件。恢复上游：用 commit `cadbc02` 覆盖 `index.mjs`、`config.mjs`、`service/`，
保留上游 `client.mjs`，删除新增的 `persona-default.mjs`、`service/netcfg.py`、`service/paths.py`、`tests/`。

## 7. 主代理集成验收补充（2026-10-29）

以上未验证项描述子代理交接当时的状态，最终交付状态以 `docs/releases/内置大肥鱼-本轮交付与验收-2026-10-29.md` 为准。
主代理已执行占位人格 4 项、Python 适配 7 项测试，并以真实 DSH/官方 SDK 完成初始化、共同记忆服务读取及同一会话恢复；这些检查不含真实模型推理或 Android proot 运行。
主代理另将 B站 CLI 以工具层接入本服务，补齐真实视频来源链接及内容层级，保留简介、可用字幕和评论；本包没有 ffmpeg，不声明看过视频画面。
`CMEM_AUTH_TOKEN` 服务认证使用 `X-Auth-Token`；App 唤醒桥使用 Bearer。B站公开读取的本机探测失败，详见最终交付说明。
