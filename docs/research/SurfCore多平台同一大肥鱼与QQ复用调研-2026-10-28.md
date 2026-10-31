# SurfCore 多平台同一大肥鱼与 QQ 复用调研

日期：2026-10-28

本轮由 WorkBuddy/CodeBuddy CLI 2.171.0 分配两项独立研究任务。两项返回记录的实际模型均为 `deepseek-v4.1-flash`。请求 effort 为 high，返回记录没有独立确认实际 effort；不将请求值当作后端确认值。

## 下一步决定

先复用 SurfCore 的 QQ 私聊，验证日常聊天、外部见闻回流和同一角色记忆的体验。时间 Agent 继续整理功能归类和页面体验；长期主人聊天优先使用手机 QQ，App 内完整聊天界面可后续连接同一角色。

这是基于现成能力的产品建议，还没有在本机部署 SurfCore、登录账号或验证真实消息。此次只完成资料研究，没有修改产品代码。

## 用户目标

- QQ、B站、时间 Agent 里是同一条鱼，具有连续的人格和经历。
- 鱼在外部平台自主活动，并把自己感兴趣的内容带回与主人的自然闲聊。
- 主人对话以连续聊天呈现，不做视频推荐卡片列表。
- 具体人格、性格、语气、称呼和形象由用户后续提供。
- 优先复用已有项目，当前不开发新的自主规则引擎。

## 两项研究核实到的现成结构

| 问题 | 原始资料支持的结论 | 对本项目的意义 |
|---|---|---|
| 多平台同一条鱼 | SurfCore 使用共享角色包，QQ、B站、桌宠读取同一人格，使用共享记忆体系 | 以一个角色为中心接多个通道 |
| 是否共用原始聊天上下文 | QQ 各会话、B站、桌宠分别保存上下文，通过记忆内容关联 | 同一人格不等于所有窗口逐条实时同步 |
| 鱼怎样认出自己和主人 | 自我卡记录各平台昵称、稳定 ID、主页和人格名；身份对齐表关联账号 | 后续将时间 Agent 加为同一角色的新入口 |
| 主动找主人 | purpose 机制从近期 QQ/B站经历和记忆中形成话题，载体可选 QQ 或桌宠 | 主动分享已有参考实现，无需另造一套提醒式推荐 |
| 能否用手机 QQ 聊 | 已有 QQ 私聊通道，宿主运行在 Windows；手机 QQ 可作为主人收发消息的客户端 | 首期可省去 App 内完整聊天界面开发 |
| PC 是否需要运行 | 现成方案依赖 Windows 上的 DSH、桥接和 SnowLuma | QQ 客户端不能取代鱼的运行宿主 |
| App 内嵌 QQ 界面 | 本次资料未提供现成的嵌入能力 | 不能将复用 QQ 私聊等同于嵌入 QQ UI |
| App 直接接现有桌宠接口 | 桌宠接口面向本机；桌宠聊天不走完整 agent 回合 | 不能直接宣称安卓 App 已能复用所有接口和工具 |

## 外部自主活动

SurfCore 的 B站文档描述了浏览、评分、评论、回复、文字/图文动态和转发。动态、评论与上传视频投稿需要区分：本轮资料没有证实自主制作并上传视频的完整流程。

QQ 的主动聊天有现成 purpose 机制；其中 QQ 自动主动机制与 `reserved2` 模式有关，完整工具模式 `closed-agent` 的条件不同。后续若要让同一条鱼在陪聊时操作时间 Agent，需要进一步核对工具接入方式，不能把现成模式的所有能力直接相加。

抖音、小黑盒尚未核实与这一份 SurfCore 角色包联通的实现。其他平台存在大肥鱼账号，并不自动证明它们共享本项目采用的角色和记忆。

## AstrBot 与 SurfCore 的取舍

第二个视频属于 AstrBot 路线；第一个视频直接指向 SurfCore。两者都值得学习，但本项目优先围绕 SurfCore 的共享角色、记忆和主动目的机制评估复用。

AstrBot 的 QQ 适配和 OpenAPI 可作为参考。使用同一个人格配置，不足以证明两个框架共享同一个角色状态、经历和会话。不能因两个教程都可用，就同时部署两套彼此独立的鱼。

## 建议顺序

1. 对照第一个视频的 SurfCore 方案，列出本机运行所需现成组件。
2. 用 QQ 作为主人日常聊天入口，验证与 B站经历之间的连续感。
3. 等用户提供人格和形象，配置在共享角色上。
4. 将时间 Agent 的功能整理为今天、计划、复盘、管家，继续解决最初的功能管理和页面割裂问题。
5. 再评估让同一条鱼读取日程、理解项目、帮助复盘；App 内聊天按实际使用需要决定。

步骤 1 起属于下一阶段建议，本轮没有安装或登录行为。

## 三个视频的核实边界

- [重点：多平台同一大肥鱼 / SurfCore](https://www.bilibili.com/video/BV1Rbxg6WER5/)
- [AstrBot 部署与人格教程](https://www.bilibili.com/video/BV1x2Jq6NEbT/)
- [DSH 人格卡，全局人格与工作人格](https://www.bilibili.com/video/BV1sTnn6DEWv/)

已获得的材料包括视频标题、部分简介和章节，以及关联项目的原始文档。没有获得三个视频的完整字幕，也没有完整观看画面或听取口播；第二个视频要求的“参考全部”仍有内容待补。

仓库文档和部分源码读取不等于运行验收。以上结论按原始资料描述及 DS 4.1 研究结果归纳，没有声称真实账号全链路已通过。

## 原始来源

- [SurfCore README](https://github.com/Mieksido/surfcore)
- [跨平台身份](https://github.com/Mieksido/surfcore/blob/main/docs/guides/CROSS_PLATFORM_IDENTITY.md)
- [角色人格](https://github.com/Mieksido/surfcore/blob/main/docs/guides/CHARACTER_PERSONA.md)
- [共享记忆](https://github.com/Mieksido/surfcore/blob/main/docs/guides/MEMORY_CARD.md)
- [主动目的与主人私聊](https://github.com/Mieksido/surfcore/blob/main/docs/guides/PURPOSE_OUTREACH.md)
- [B站子系统](https://github.com/Mieksido/surfcore/blob/main/docs/guides/SUBSYSTEM-BILI.md)
- [架构](https://github.com/Mieksido/surfcore/blob/main/docs/guides/ARCHITECTURE.md)
- [桌宠对话](https://github.com/Mieksido/surfcore/blob/main/docs/guides/SUBSYSTEM-PET.md)
- [AstrBot](https://github.com/AstrBotDevs/AstrBot)
- [AstrBot OpenAPI](https://docs.astrbot.app/dev/openapi.html)

## 本地研究输出

- `.tmp\research\fish-qq-20261028\多平台同一大肥鱼-DS41.md`
- `.tmp\research\fish-qq-20261028\QQ对话复用-DS41.md`
- `.tmp\research\fish-qq-20261028\dispatch-evidence.json`

同目录中的 first-attempt 文件为网页读取许可未生效时的未完成输出，不能作为正式研究结论。正式原始记录为 `multiplatform-verified.json` 和 `qq-reuse-verified.json`。
