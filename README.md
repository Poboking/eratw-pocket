<div align="center">

# EraTW Pocket

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)
![players](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2FPoboking%2Feratw-pocket%2Fmain%2Fbadges%2Fplayers.json)
![active today](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2FPoboking%2Feratw-pocket%2Fmain%2Fbadges%2Factive-today.json)
![downloads](https://img.shields.io/github/downloads/Poboking/eratw-pocket/total)

**EraTW 的安卓原生移植版。**

在手机上原生运行 EraTW，不借助模拟器。这个仓库只做壳层的事：Compose 界面、
资源解包、存档、音频、设置；游戏语义全部交给
[Kotera](https://github.com/Poboking/Kotera)，两个仓库之间没有语义
层面的耦合，引擎可以在桌面端独立验证。

</div>

---

## 仓库结构

| 路径 | 说明 |
|---|---|
| `app` | Compose 壳层 + 装配 + 出包；含设置页、输入条、覆盖层、错误上报与更新 UI |
| `app/src/test/.../sentinel/` | 审**壳层真源**的源码哨兵（主语是壳层的闸归本仓，理由见 [跨仓契约](https://github.com/Poboking/Kotera/blob/main/specs/cross-repo-contract.md)） |
| `platform` | 平台能力：资源解包 / 素材导入、存档导入导出、音频焦点、字体、设置存储（Android library） |
| `render-canvas` | Android 自绘 View：命中区与绘制，消费 Kotera 的显示列表 |
| `buildSrc` | 出包期任务与纯函数：随包资产 staging、语料补丁、erbc 新鲜度、ART profile 转换 |
| `devtools/` | 出包体检、更新清单 / 差量、错误报告分诊、性能与真机探针等开发工具 |
| `tools/apkpack/` | APK 内容自检：清单 / 分片 / STORED / 压缩口径逐类对账 |
| `tools/report-endpoint/` | 错误上报 Cloudflare Worker 模板（端点与 KV id 均为占位） |
| `docs/` | 设计稿、golden 采集手册与可交互原型；索引见 [docs/README.md](docs/README.md) |
| `badges/` | 玩家数 / 活跃数徽章的回退快照（README 现在直连 Worker 的 shields 端点，刷新延迟只有几分钟；这份 JSON 由 CI 定时刷新，端点故障或迁移时可把徽章两行改回 raw.githubusercontent） |
| `gradle/libs.versions.toml` | 全仓依赖坐标与 Android / Kotlin 版本的单源 |

## 构建

需要 JDK 17+ 与 Android SDK（AGP 8.8.2）。命令均从仓库根目录执行。

**引擎仓必须与本仓同级检出**（Gradle composite build）：

```
parent/
├── Kotera/   ← 引擎（core-vm / render / sqlite-adapter / mod-* / tools/erbcc）
└── eratw-pocket/  ← 本仓
```

日常小包（不随游戏数据，CI 口径）：

```bash
./gradlew :app:assembleDebug -PeratwSkipAssets
```

装机即玩全量包（需要游戏根与 erbc 产物）：

```bash
./gradlew :app:assembleDebug -PeratwRequireComplete=1
```

单测：

```bash
./gradlew :app:testDebugUnitTest :platform:testDebugUnitTest
```

CI 等价命令为：

```bash
./gradlew --no-daemon \
  :app:assembleDebug -PeratwSkipAssets \
  :app:testDebugUnitTest :platform:testDebugUnitTest
```

CI 另跑三道**源码闸**（纯 Python，不依赖 Gradle / Android SDK / 网络）：

```bash
python3 devtools/test_source_gates.py    # 数据源 / 业务词 / 语料专名
python3 devtools/test_shell_profile.py   # 壳层 profile 口径
python3 devtools/test_game_versions.py   # 游戏版本台账
```

CI 还会把姐妹仓 [Kotera](https://github.com/Poboking/Kotera) **同级检出**（读对方
`main`）：上面的语料专名闸要扫引擎主源，引擎侧的入库资产闸要读本仓的随包资产 ——
两道跨仓守卫都靠这道互检才真跑。合并次序与冻结夹具口径见
[跨仓契约](https://github.com/Poboking/Kotera/blob/main/specs/cross-repo-contract.md)。

本仓不分发游戏数据；随包资源、产物与更新通道均可通过变量 / 属性指定：

| 来源 | 作用 |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | 游戏根目录（`ERB/`、`CSV/`、`resources/`、`sound/`、配置）；不设则只出 erbc 小包 |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc 产物目录；缺省回退探测 `../Kotera/tools/erbcc/build/erbc-out/erbc.v1` |
| `-PeratwSkipAssets` | 完全跳过资源随包（真机已解包过数据 / CI） |
| `-PeratwRequireComplete` | 正式包闸：任一类随包条目缺失 / 产物不新鲜即失败 |
| `-PeratwErbcChunkMb` | 把 `program.erbc` 切成 `program.partNNN.erbc` + `program.parts` 清单 |
| `-PeratwErbcCompress` | 允许程序条目 DEFLATE（缺省 STORED，体积与解包口径见出包报告） |
| `-PeratwAbi` | 目标 ABI 白名单（缺省 `arm64-v8a,armeabi-v7a`） |
| `ERATW_REPORT_ENDPOINT` / `ERATW_REPORT_TOKEN` / `-PeratwReportEndpoint` | 错误上报端点与令牌；出包脚本读环境变量，直接 Gradle 构建用 `-PeratwReportEndpoint` / `-PeratwReportToken`；也可放 `local.report.properties` |
| `ERATW_UPDATE_MIRRORS` / `-PeratwUpdateMirrors` | 更新镜像基址列表；公开仓缺省为空（自动更新通道关闭），需显式配置 |
| `ERATW_KCORE_ROOT` | devtools 定位姐妹引擎仓（缺省 `../Kotera`） |
| `ERATW_T63_PROF_DIR` / `ERATW_T63_DIAG_DIR` | `t63_*` 真机 profile / 诊断脚本的输出目录 |



**权威语料根 = Kotera 根**（`eratw-画蛇添足版eratw4.980`，NOW_VERSION 20260326）。
本仓与引擎仓共用同一冻结口径：配置往返闸（`emuera.config` 逐字节 sha256）等语料读数
都按这棵根冻结。换根跑会与冻结值对不上，那是**语料换代**不是回归。

## 出包与工具链

正式发布走唯一入口：

```bash
python devtools/pack_release.py            # 全量包 + 出包体检
python devtools/pack_release.py --skip-assets
```

它会回读 APK 内的真实字符串与配置逐项核对（上报端点 / 令牌 / 语料包完整性 /
manifest 与分片 / STORED 口径），不合格即非零退出。

| 工具 | 用途 |
|---|---|
| `devtools/pack_release.py` | 发布包唯一入口与出包体检 |
| `devtools/emit_update.py` | 生成更新清单、签名与块级差量（`--selftest` 可离线自检） |
| `devtools/update_drill.py` | 更新演练 / mock mirror / 断点续传验证 |
| `devtools/triage_reports.py` | 拉取并分诊玩家错误报告（令牌只从本地配置读取） |
| `devtools/boot2play.py` / `apk_truth.py` / `hawkeye.py` / `soak.py` | 真机启动、APK 真值、性能与浸泡探针 |
| `devtools/corpus-patches/` | 语料内容补丁（CB-xxx）与 manifest 留痕 |
| `tools/apkpack/inspect_apk.py` | 清单 / parts / 实包三方对账 |
| `tools/report-endpoint/` | 上报 Worker 模板（部署时填自己的域名与 KV id） |

## 架构与设计取舍

**对游戏语义零知识。** 壳层不解析脚本、不知道任何内建函数的行为，只消费引擎的
显示列表与回调。这保证上游语义修正在本仓零成本同步——没有需要跟改的游戏逻辑。
如果一个问题看起来非改引擎不可，请去
[Kotera](https://github.com/Poboking/Kotera) 提 issue；壳层里的绕过补丁，
往往就是下一个 bug。

**显示列表渲染边界。** `render-canvas` 把 Kotera 的显示列表画到 Android
Canvas 上；命中区、滚动、输入预填都来自引擎侧纯 JVM 的 `render` 模型。桌面
预览与真机跑的是同一套布局计算，UI 在两端的差异只可能来自适配器本身。

**composite build 接线 + 构建期版本对账。** Kotera 以 `includeBuild` 接入，
依赖替换之后，APK 里的引擎永远是同级检出的那份源码编出来的；引擎侧的
sqlite-adapter（SQLite 真实引擎的平台接线）与 RT2 官方 Mod 也走同一依赖替换进入
APK——宿主只依赖 `mod-registry`（载体注册表，构建期扫各 `mod-*` 的 `@ModCarrier`
生成唯一清单），不再逐个点名载体模块。erbcc 版本与编译器
源码指纹在 app 构建时校验，发现漂移即构建失败——「引擎升了、字节码没重编」的
错配窗口不存在。

**素材导入与 Mod 落点。** 资源包 / Mod 的识别、安全闸、落位与三语报告都在壳层
完成；引擎只看最终的 `resources/`、`CSV/` 与 `.erbc`。素材来源名与下载单
`label` 是两套语义，源码守卫会拦住把旧语言标签带进玩家面的写法。

**可观测的更新与上报。** 更新通道有检查态 / 下载进度 / 失败重试 / 镜像回退的
可读文案；错误上报有本地队列、剪枝、限流与手动导出。两者都只通过显式配置的
端点工作，公开仓默认关闭。

**出包自检。** `pack_release.py` 与 `tools/apkpack/inspect_apk.py` 在出包阶段
回读真实产物对账，静默失效（上报链、旧 erbc、缺资源、分片错位）会在这里被拦下，
而不是等玩家先崩溃。

## 语料与版权

本仓不分发 EraTW 游戏本体（脚本、图片、音频、存档、配置），请自备合法获得的
游戏数据。出包时它们经变量进入产物，不进仓库。

## 状态与反馈

仍在活跃开发中，部分平台能力（音频焦点验收、边界机型冒烟）尚未收口。
issue 与 PR 均欢迎。报游戏行为问题时，请附上原版（Emuera / PC 版）的对照表现，
这样能更快判断问题出在壳层还是引擎。

## 致谢

- **Kotera 的贡献者**——游戏语义的权威定义都在那边，本仓只是它的一个前端。
- **MinorShift** 与 **妊）|дﾟ)の中の人**——Emuera 的著作者。游戏行为的兼容性
  以 Emuera 的表现为参考。
- **まだ名前は無い人**，以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。
  本仓不包含游戏本体，其著作权与使用条件以原项目随附的说明和许可文件为准。
- era 社区的各位脚本作者、内容作者和工具维护者。

上述致谢不改变任何第三方材料的著作权与许可条件。

## 许可

[GPL-3.0](LICENSE)。引擎仓 Kotera 以 MIT 发布（两者兼容，组合合法）；
Emuera 归因见其 [NOTICE.md](https://github.com/Poboking/Kotera/NOTICE.md)。
游戏本体的版权归其原作者所有，本仓与引擎仓均不包含、不代替。
