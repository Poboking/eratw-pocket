<div align="center">

# EraTW Pocket

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**EraTW 的安卓原生移植版。**

在手机上原生运行 EraTW，不借助模拟器。这个仓库只做壳层的事：Compose 界面、
资源解包、存档、音频、设置；游戏语义全部交给
[eraKcore](https://github.com/Poboking/eraKcore) 引擎，两个仓库之间没有语义
层面的耦合，引擎可以在桌面端独立验证。

</div>

---

## 架构与设计取舍

**对游戏语义零知识。** 壳层不解析脚本、不知道任何内建函数的行为，只消费引擎的
显示列表与回调。这保证上游语义修正在本仓零成本同步——没有需要跟改的游戏逻辑。
如果一个问题看起来非改引擎不可，请去
[eraKcore](https://github.com/Poboking/eraKcore) 提 issue；壳层里的绕过补丁，
往往就是下一个 bug。

**显示列表渲染边界。** `render-canvas` 把 eraKcore 的显示列表画到 Android
Canvas 上；命中区、滚动、输入预填都来自引擎侧纯 JVM 的 `render` 模型。桌面
预览与真机跑的是同一套布局计算，UI 在两端的差异只可能来自适配器本身。

**composite build 接线 + 构建期版本对账。** eraKcore 以 `includeBuild` 接入，
依赖替换之后，APK 里的引擎永远是同级检出的那份源码编出来的。erbcc 版本与编译器
源码指纹在 app 构建时校验，发现漂移即构建失败——「引擎升了、字节码没重编」的
错配窗口不存在。

**出包自检。** `pack_release.py` 打包后会回读 APK 内的真实字符串与配置逐项核对
（上报端点、令牌、语料包完整性），不合格即非零退出。上报链静默失效这类问题，
会在出包阶段被拦下，而不是等玩家先崩溃。

## 仓库结构

| 模块 | 说明 |
|---|---|
| `app` | Compose 壳层 + 装配 + 出包 |
| `platform` | 平台能力：资源解包、存档 / 音频 / 字体 / 设置绑定（Android library） |
| `render-canvas` | Android 自绘 View：命中区与绘制，消费 eraKcore 的显示列表 |
| `devtools/` | 出包体检脚本（`pack_release.py`）与调试工具 |
| `docs/` | 设计稿与运行手册 |

## 构建

需要 JDK 17+ 与 Android SDK（AGP 8.8.2）。命令均从仓库根目录执行。

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
（模板见 `local.report.properties.example`，已被 gitignore；不配置则仅关闭上报链，
出包不受影响）。

## 语料与版权

本仓不分发 EraTW 游戏本体（脚本、图片、音频、存档、配置），请自备合法获得的
游戏数据。出包时它们经变量进入产物，不进仓库。

## 状态与反馈

仍在活跃开发中，部分平台能力（音频焦点验收、边界机型冒烟）尚未收口。
issue 与 PR 均欢迎。报游戏行为问题时，请附上原版（Emuera / PC 版）的对照表现，
这样能更快判断问题出在壳层还是引擎。

## 致谢

- **eraKcore 的贡献者**——游戏语义的权威定义都在那边，本仓只是它的一个前端。
- **MinorShift** 与 **妊）|дﾟ)の中の人**——Emuera 的著作者。游戏行为的兼容性
  以 Emuera 的表现为参考。
- **まだ名前は無い人**，以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。
  本仓不包含游戏本体，其著作权与使用条件以原项目随附的说明和许可文件为准。
- era 社区的各位脚本作者、内容作者和工具维护者。

上述致谢不改变任何第三方材料的著作权与许可条件。

## 许可

[GPL-3.0](LICENSE)。引擎仓 eraKcore 以 MIT 发布（两者兼容，组合合法）；
Emuera 归因见其 [NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md)。
游戏本体的版权归其原作者所有，本仓与引擎仓均不包含、不代替。
