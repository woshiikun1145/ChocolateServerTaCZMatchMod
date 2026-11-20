# 第三方依赖与许可证声明

本模组包含以下第三方软件与移植代码。本文档随模组 jar 一起分发（jar 根目录 `THIRD_PARTY_NOTICES.md`）。

---

## CSG（CounterStrikeGrenades）1.5.2 — 投掷物源代码移植

| 项目 | 内容 |
| --- | --- |
| 组件 | CSG 投掷物系统（高爆手雷 / 闪光弹 / 烟雾弹 / 燃烧弹 / 莫洛托夫 / 诱饵弹） |
| 版本 | 1.5.2 |
| 上游形态 | Minecraft Forge 1.20.1 / Kotlin |
| 引入方式 | **源代码改写移植**（改写为 Minecraft Fabric 1.21.1 / Java 并接入本模组），**不以二进制依赖形式打包**；本模组 jar 内不含 CSG 的 jar 或类文件 |
| 移植内容 | 六类投掷物的投掷弹道、引信、爆炸 / 燃烧 / 烟雾 / 致盲 / 诱饵行为，以及客户端渲染、粒子与音效；数值集中在 `GrenadeSettings` |
| 修改说明 | 为适配 Fabric 1.21.1 API、本模组网络层与 TACZ（反射接入）已做改写，不保证与原 mod 逐行一致 |
| 版权归属 | 原始创意、代码与美术 / 音效资源版权归 CSG 原作者（TheDarkColour）所有 |
| 许可证 | **MIT License**（Copyright (c) 2020 TheDarkColour），全文见下方「原始许可证」 |
| 许可证文件 | 仓库根目录 `LICENSE_CSG`；随模组分发为 jar 根目录 `LICENSE-CSG-MIT.txt` |

> 本模组仅移植投掷物玩法逻辑与表现，物品命名空间统一为 `cstmm`（`cstmm:hegrenade`、`cstmm:flashbang`、`cstmm:smokegrenade`、`cstmm:incendiary`、`cstmm:molotov`、`cstmm:decoy`），与原 mod 的物品 ID 不通用。
> 原项目地址请以其公开发布页为准；玩家可见说明见 `README.md` §4.7。

### 原始许可证（完整保留）

```text
MIT License

Copyright (c) 2020 TheDarkColour

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## JLayer 1.0.1

| 项目 | 内容 |
| --- | --- |
| 组件 | JLayer（MP3 decoder/player/converter） |
| 版本 | 1.0.1 |
| 上游项目 | <https://github.com/javazoom/jlayer> ｜ <https://www.javazoom.net/javalayer/javalayer.html> |
| Maven 坐标（本项目本地化前的来源） | `javazoom:jlayer:1.0.1` |
| 本仓库文件 | `libs/jlayer-1.0.1.jar`（二进制）、`libs/jlayer-1.0.1-sources.jar`（完整源代码） |
| 模组内位置 | 独立嵌套 JAR：`META-INF/jars/jlayer-1.0.1.jar`（jar-in-jar，不合并进模组 class） |
| 用途 | 客户端解码 MVP 音乐（MP3 → PCM → `javax.sound.sampled.SourceDataLine`） |
| 是否修改 | **未修改原始源码/二进制** |
| 许可证 | GNU Lesser General Public License（LGPL）2.1 |

许可证全文：仓库 `libs/LGPL-2.1.txt`；随模组分发为 jar 根目录 `LICENSE-JLAYER-LGPL-2.1.txt`。

### 原始版权声明（完整保留）

JLayer 源码文件头部包含以下声明（原文照录）：

```text
/*
 * 11/19/04		1.0 moved to LGPL.
 * 01/12/99		Initial version.	mdm@techie.com
 *-----------------------------------------------------------------------
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU Library General Public License as published
 *   by the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Library General Public License for more details.
 *
 *   You should have received a copy of the GNU Library General Public
 *   License along with this program; if not, write to the Free Software
 *   Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *----------------------------------------------------------------------
 */
```

JLayer 源代码中出现的其他版权行（原文照录）：

```text
 *  @(#) Copyright (C) 1993, 1994 Tobias Bading (bading@cs.tu-berlin.de)
 * Copyright (c) 1991 MPEG/audio software simulation group, All Rights Reserved
```

项目归属：JLayer 由 JavaZOOM 维护；原始源码头部声明为 GNU Library General Public License
version 2 or later，本项目按 LGPL 2.1 条款分发（LGPL 2.1 为该许可证的后续版本）。

### 源码获取

- 完整源代码包随仓库提供：`libs/jlayer-1.0.1-sources.jar`（与二进制完全对应，版本 1.0.1）；
- 上游源码仓库：<https://github.com/javazoom/jlayer>。

### 替换方法（LGPL 允许用户替换该库）

1. 解压模组 jar（zip）：`unzip chocolate-server-tacz-match-mod-<版本>.jar -d cstmm-unpacked`；
2. 用你自己编译的、与 JLayer 1.0.1 API 兼容的 jar 替换
   `cstmm-unpacked/META-INF/jars/jlayer-1.0.1.jar`（建议保持文件名不变）；
   若新库文件名不同，需同步修改 `cstmm-unpacked/fabric.mod.json` 中 `jars` 列表的 `file` 字段；
3. 重新打包（保持原目录结构与 `META-INF/MANIFEST.MF`）：
   `cd cstmm-unpacked && zip -r ../chocolate-server-tacz-match-mod-<版本>-jlayer-replaced.jar .`；
4. 服务端与客户端都替换为同一份修改后的 jar（本模组两端必须同版本）。

> 替换后的库仍需满足 LGPL 条款；若修改了 JLayer 源码，必须公开修改后的源码。
> 详细说明见 `DEVELOPER.md` §11。
