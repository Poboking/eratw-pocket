<div align="center">

# EraTW Pocket

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_(API_21)-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**EraTW 的安卓移植版。**

壳层只做壳层的事：Compose 界面、资源解包、存档、音频、设置。
游戏语义一丝不动地交给 [eraKcore](https://github.com/Poboking/eraKcore) 引擎——
两边零语义耦合，引擎可在桌面独立验证。

</div>

---

## 仓库结构

| 模块 | 说明 |
|---|---|
| `app` | Compose 壳层 + 装配 + 出包 |
| `platform` | 平台能力：资源解包、存档 / 音频 / 字体 / 设置绑定（Android library） |
| `render-canvas` | Android 自绘 View：命中区与绘制，消费 eraKcore 的显示列表 |
| `devtools/` | 出包体检脚本（`pack_release.py`）与调试工具 |
| `docs/` | 设计稿与运行手册 |

## 构建

需要 JDK 17+ 与 Android SDK（AGP 8.8.2）。

**引擎仓必须与本仓同级检出**（Gradle composite build）：

```
parent/
├── eraKcore/      ← 引擎（core-vm / erbcc / render）
└── eratw-pocket/  ← 本仓
```

```bash
./gradlew :app:assembleDebug
```

本仓不分发游戏数据，随包资源用变量指定：

| 来源 | 作用 |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | 游戏根目录（`ERB/`、图片、配置）；不设则出「只随 erbc」小包 |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc 产物目录；缺省回退找 `../eraKcore/tools/erbcc/build/erbc-out/erbc.v1` |
| `-PeratwSkipAssets` | 完全跳过资源随包（真机已解包过数据时） |

正式发布请走唯一入口：

```bash
python devtools/pack_release.py            # 全量包 + 出包体检
python devtools/pack_release.py --skip-assets
```

报错上报的端点与令牌放 `local.report.properties`
（模板见 `local.report.properties.example`，已被 gitignore；不配置则仅关闭上报链，出包不受影响）。

## 与引擎的边界

本仓对游戏语义零知识：脚本行为、内建函数、存档格式的权威定义全在
[eraKcore](https://github.com/Poboking/eraKcore) 的 `core-vm` 与 `specs/`。
壳层的改动不应改变任何游戏行为；如果发现需要改引擎才能修的问题，请去引擎仓提 issue。

## 语料与版权

本仓不分发 EraTW 游戏本体（脚本、图片、音频、存档、配置），请自备合法获得的游戏数据。
出包时它们经变量进入产物，不进仓库。

## 状态

仍在活跃开发中，部分平台能力（音频焦点验收、边界机型冒烟）尚未收口。
issue 与 PR 均欢迎；涉及游戏行为的反馈请附上原版（Emuera / PC 版）的对照表现。

## 许可

[GPL-3.0](LICENSE)。引擎仓 eraKcore 以 MIT 发布（两者兼容，组合合法）；
Emuera 归因见其 [NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md)。
游戏本体的版权归其原作者所有，本仓与引擎仓均不包含、不代替。
