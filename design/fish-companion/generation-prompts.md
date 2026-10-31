# 大肥鱼动作资产生成记录

工具：内置 `image_gen`。原始用户参考图未改写。下列提示词是本轮实际生成请求的结构与语义记录；输入和输出的逐文件 SHA-256 见 `asset-manifest.json`。

## Q版第一版

参考：`design/references/fish/Q版版本/标准Q版.png`。

Identity-preserve production Android companion sprite atlas. Preserve chibi dark blue long hair, curved ahoge, blue eyes, frilled white maid headband, blue bows and fin-like ears, navy maid dress, white whale apron and whale tail. Exactly 4 columns by 4 rows, transparent alpha, uniform cells, centered same scale and floor position. No borders, labels or background. Sixteen full-body poses in reading order: neutral open eyes; half blink; closed blink; slight head turn; hand half raised; high wave; side wave; delighted closed-eye smile; thinking finger at chin; curious tilt; holding notebook; writing notebook; sleepy; sleeping cheek on hands; concerned; apologetic. Keep costume, face identity, torso and foot continuity. Animate hands, eyelids and expression.

结果：`atlases/chibi-v1.png`。首版第一、二行动作边缘相接，保留为生成证据，未用作最终原生图集。

## Q版间距修正版

输入：上一步完整图集。

Keep the same sixteen poses, expressions, dress, whale apron and 4x4 order. Shrink each individual character to 75 percent of its current size in its own equal cell. At least twelve percent transparent padding on every side, including ahoge, shoes and tail. Separate all sixteen figures with transparent gutters. Keep identity and animation poses. No labels, borders, watermarks, background or blue glow fringe.

结果：`atlases/chibi-v2.png`。这是最终 Q 版切片来源。

## 御姐版

参考：`design/references/fish/御姐版本/标准御姐.jpg`。

Identity-preserve production Android animated companion sprite atlas. Preserve adult anime whale maid proportions and mature face, long navy hair with pale-blue tips, curved ahoge, aqua eyes, white frilled maid headband, blue bows and fin-like ears, long elegant navy dress, white whale-emblem apron and whale tail. Exactly 4 columns by 4 rows with sixteen evenly spaced full-body poses on true transparent alpha. One complete character per cell, same scale and feet baseline. Not chibi. No text, grids, scenery, watermark or drop shadows. Reading-order poses match Q version: open/half/closed blink and head turn; three waving poses and delighted smile; thinking, curious tilt, reading notebook, writing notebook; sleepy, sleeping, concerned and apologetic.

结果：`atlases/elegant-v1.png`。这是最终御姐版切片来源。

## 切片与连续性

`scripts/prepare-companion-assets.py` 按列寻找行间透明谷值，保留角色主体及抗锯齿边缘，丢弃相邻格尾鳍碎片与生成孤点。使用全套统一缩放和脚底基线，输出每版16张320×320透明PNG、1280×1280原生图集和6段透明动画WebP。逐帧边缘至少8像素透明留白。浅色、深色背景核对图为两个 `*-edge-proof.jpg`。

原生界面使用图集的实际姿势帧和短淡入过渡；独立 WebP 用于资产交付。没有用位移抖动充当角色动作。
