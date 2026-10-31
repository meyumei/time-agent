# bili-cli 手机适配说明

上游：`Alterssss/dsh-bili-whale` v0.4.5，commit `ef3f74171b4104249f5583c4d2df9bebad14934a`，MIT。
本目录只复用其**平台 CLI**（`tools/`）；`LICENSE` / `NOTICE.md` 由主代理复制，未改动。
没有复制或加载原版 `lib/index.js`，也没有把另一套鱼、记忆、人格、scheduler、WebUI 带进来。

## 手机运行位置

| 项目 | 值 |
| --- | --- |
| CLI 根 | `/root/time-agent/bili-cli` |
| 入口 | `node /root/time-agent/bili-cli/tools/bili.mjs <命令>` |
| Node | `/usr/local/bin/node` |
| `HOME` | `/root` |
| `DSH_HOME` | `/root/.timeagent-dsh` |

`tools/lib/runtime.mjs` 找 Node 的顺序：`DSH_NODE` → `DSH_DESKTOP_NODE_EXECUTABLE` → `$DSH_HOME/dsh-runtimes/*/dependencies/node/bin/node` → `PATH` →（Linux/Android）`/usr/local/bin/node`、`/usr/bin/node` → 当前进程（非 Electron 时）。
手机若 PATH 被裁掉，可显式设 `DSH_NODE=/usr/local/bin/node`。

## 数据与凭据路径（全部跟随 HOME / DSH_HOME）

| 内容 | 路径 |
| --- | --- |
| 配置 | `$DSH_HOME/bili-whale.config.json`（默认 `/root/.timeagent-dsh/bili-whale.config.json`；可用 `BILI_WHALE_CONFIG` 覆盖） |
| 登录凭据 | `$HOME/.bili-ai/credentials.json`（默认 `/root/.bili-ai/credentials.json`） |
| 配额 / 收藏 / 反馈 / 缓存 / 熔断 / 请求日志 | `$HOME/.bili-ai/` |
| 封面、字幕、抽帧 | `$DSH_HOME/shots/` |
| 日记 | `$DSH_HOME/whale-diary.md` |

没有读取作者机器路径，也不依赖电脑 Electron、剪映或固定 Windows 盘符。
凭据文件格式：`{"sessdata":"…","bili_jct":"…","buvid3":"…"}`；`buvid3` 可空。
本 commit 里**没有独立的 login 命令**：凭据由用户写入上述文件，`session` 只做校验。

## 本轮改动

1. `tools/lib/runtime.mjs`
   - Linux/Android 增加 `/usr/local/bin/node`、`/usr/bin/node` 候选。
   - 找不到运行时时的报错改为手机可执行的提示（`DSH_NODE=/usr/local/bin/node`）。
2. `tools/lib/config.mjs`
   - `persona.name` 改为明确占位 `<未设置角色名>`。
   - `preferences.like` / `preferences.dislike` 默认改为空数组，不再替用户决定喜好。
3. `tools/templates/config.example.json`
   - 同步空偏好、占位角色名、`notify.type: "none"`。
   - 删除作者的 Windows Node / 微信插件路径。
4. `tools/commands/debrief.mjs`
   - 凭据改为可选：元信息、封面、弹幕、热评等公开内容无凭据也能读；缺凭据时明确少哪一路。
   - 记录画面帧状态：`ok` / `failed` / `skipped`。
   - 抽帧失败（尤其缺 ffmpeg）时明确打印“没有拿到任何画面帧，不能声称看过视频”。
   - `--no-frames` 结尾明确说明没有画面帧、不能声称看过视频。
   - 防注入提示从“本会话与 persona”改为“用户与本会话”，CLI 不再暗示人格文件有指令优先权。
5. `tools/commands/feed.mjs`
   - 凭据改为可选：无登录时用匿名请求继续（热门/排行可读；关注源失败会跳过）。
   - 偏好只读 `config.preferences.like`，不再解析日记里的「爱看」旧写法；空偏好时明确提示、不做偏好加权；候选池为空时以退出码 4 明确失败。
6. `tools/commands/video-content.mjs`
   - 凭据改为可选；无登录时字幕轨道可能为空，脚本会如实打印条数。
7. `tools/commands/deps.mjs`
   - 生物钟/宿主调度改为**信息项**，不再因为“没有调度状态”判红；明确本 CLI 不启动调度器，唤醒由 `dsh-remembers/server.py` 负责。
   - 使用 `DSH_HOME` / `configPath`，不再读 `$HOME/.dsh`。
   - ffmpeg 修复提示改为 Linux/手机可用说法，并注明本移植未打包 ffmpeg。
8. `tools/commands/selftest.mjs`
   - 目录检查改用 `DSH_HOME`；`.bili-ai` / 配置 / 身份项在缺失时给警告而非硬失败。
   - 无凭据时不再强行跑 `session`，公开只读仍算可用。
   - 删除“未分组会话 / 完全权限”的桌面端指引，改为手机 CLI 的实际命令。
9. `tools/bili.mjs`
   - 新增 `selfcheck` 命令入口。
10. `tools/selfcheck.mjs`（新增）
    - 非网络静态自检：相对 import 是否存在、`bili.mjs` 命令目标是否存在、是否引用原版 `lib/index.js`、默认偏好是否为空、角色名是否占位、凭据路径是否跟随 HOME。
    - **它不是 B站 接通测试**，只证明文件与默认配置就绪。

`tools/lib/paths.mjs`、`tools/commands/comments.mjs` 未改：前者本来就读 `DSH_HOME` / `HOME`；后者本来就允许无凭据匿名读评论。

## 命令与能力边界

先在手机执行：

```bash
cd /root/time-agent/bili-cli
node tools/bili.mjs help
node tools/bili.mjs selfcheck          # 纯静态，不联网
node tools/bili.mjs deps --json        # 环境体检；会请求 nav 验证凭据
```

### 已保留、可直接用的只读命令

```bash
# 热评 / 最新评论（匿名可读；有凭据时楼层更全）
node tools/bili.mjs comments BV1xxxxxxxxx --ps 20 --sort hot

# 视频文字版（元信息 + 简介 + 字幕 + 弹幕；无凭据时可读公开部分）
node tools/bili.mjs video BV1xxxxxxxxx

# 一条龙但不抽帧：封面/简介/字幕/弹幕热区/热评
node tools/bili.mjs debrief BV1xxxxxxxxx --no-frames

# 选题（无偏好时不做偏好加权；无登录时热门/排行仍可作候选）
node tools/bili.mjs feed --source popular --n 6
```

真实可用边界：
- `comments` 的 `view` / `reply` 是公开接口，无凭据也应返回结果；失败会打印 B站 `code` / `message` 并以非零码退出。
- `debrief --no-frames` 的元信息、封面、弹幕、热评是公开路径；字幕依赖 `player/wbi/v2`，无登录可能返回空轨道，脚本会打印“无官方字幕轨道”，不会编造字幕。
- `feed` 无偏好时仍能给出热门/排行候选，但**没有“按用户喜好排序”**；有关注源才需要登录。
- `video` 无登录时仍能读元信息/弹幕；字幕条数会如实为 0 或更少。

### 需要额外条件、尚未在本轮验证的

```bash
# 画面理解：需要 ffmpeg + 可用的播放直链（通常要登录）
node tools/bili.mjs debrief BV1xxxxxxxxx
node tools/bili.mjs watch-video BV1xxxxxxxxx 3x3
```

- 本目录**没有打包 ffmpeg**。没有 ffmpeg 时 `debrief` 会明确报“抽帧失败 / 没有画面帧”，并提示不能声称看过视频；`bili-watch` 会直接以退出码 2 报缺 ffmpeg。
- `watch-video` / `bili-watch.mjs` 仍硬读凭据文件；没有凭据时会失败（`debrief` 会捕获并报告）。
- `subtitles` 命令仍按上游设计硬依赖登录凭据（字幕 `auth_key`），无凭据时会报凭据文件缺失；这是登录态能力，不是公开读取。
- 写操作（`comment`、`dm`、`dynamic`、`fav`、`danmaku` 等）**原样保留**，仍要求显式 `--dry` / `--go`；本轮没有登录、没有发送任何评论/私信/动态，也没有新增审批/审计/规则系统。
- 评论、动态、收藏、直播等自主能力仍在命令表里；是否使用由主代理/用户决定。

## 人格与偏好

- `tools/` 内没有任何原版人格文件加载逻辑，也没有 `lib/index.js` 注入。
- `config.persona` 只是占位配置；当前 CLI 只把 `persona.diaryTitle` 用作日记标题，`persona.name` 不会被用来冒充某个角色。
- 默认 `preferences.like` / `dislike` 为空；`feed` 只从这里取偏好，并会明确提示“偏好未配置”。用户以后可以在同一套鱼配置里提供关键词，或让主代理注入；CLI 不替用户决定，也不从 CLI 日记另立偏好来源。
- 唯一的人格/记忆来源应是同一套鱼（`dsh-remembers`），本 CLI 不另建人格。`diary` / `night` 等上游命令仍保留，但没有接入 `dsh-remembers`，不要把它们生成的文件当作鱼的正式记忆或调度状态。

## 未做 / 需主代理承担

- 未运行任何 B站 网络请求、未登录账号、未读取本机真实凭据、未发送外部消息。
- 未执行 `selfcheck` / Gradle（本环境没有 shell）；`selfcheck` 与静态 import 检查结果需主代理在手机上跑一次确认。
- 未打包 ffmpeg；未把 `server.py` 的 `BILI_DIR` / `NODE_BIN` 接到本目录（按约定由主代理改）。
- 未验证真实播放直链、字幕授权、风控与写操作；这些需要主代理在目标设备上做完整网络验证。
