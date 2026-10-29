# EraTW Pocket — EraTW 安卓移植版

把 EraTW（era 系）移植到 Android 的玩家端壳层：Compose 界面、资源解包、存档、音频、设置，游戏语义引擎在姊妹仓 **[eraKcore](https://github.com/OWNER/eraKcore)**（Kotlin 重写的纯 JVM ERA 运行时）。

## 仓库结构

| 模块 | 说明 |
|---|---|
| `app` | Compose 壳层 + 装配 + 出包（`gradlew assembleRelease` 入口） |
| `platform` | 平台能力：资源解包、存档/音频/字体/设置绑定（Android library） |
| `render-canvas` | Android 自绘 View 绑定（命中区/绘制，消费 eraKcore 的显示列表） |
| `devtools/` | 出包体检脚本（`pack_release.py`）与调试工具 |
| `docs/` | 设计稿与运行手册 |

## 构建

要求：JDK 17+、Android SDK（AGP 8.8.2）。

**引擎仓必须与本仓同级检出**（composite build）：

```
parent/
├── eraKcore/    ← 引擎（core-vm / erbcc / render）
└── eratw-pocket/ ← 本仓
```

```bash
./gradlew :app:assembleDebug
```

随包游戏资源通过环境变量/属性提供（**本仓不分发任何游戏数据**）：

- `ERATW_GAMEDIR` / `-PeratwGameRoot`：游戏根目录（含 `ERB/`、图片、配置）——不设则出「只随 erbc」小包；
- `ERATW_ERBC_DIR` / `-PeratwErbcDir`：erbcc 编译产物目录（缺省回退找 `../eraKcore/tools/erbcc/build/erbc-out/erbc.v1`）；
- `-PeratwSkipAssets`：完全跳过资源随包（真机已解包过数据时用）。

正式出包请走唯一入口：

```bash
python devtools/pack_release.py          # 全量包 + 体检
python devtools/pack_release.py --skip-assets
```

报错上报端点/令牌放 `local.report.properties`（参考 `local.report.properties.example`，已被 gitignore）。

## 语料与版权声明

- 本仓库**不包含、不分发**游戏本体（ERB 脚本、图片、音频、存档、配置）。你需要自备合法获得的游戏数据，构建时用上述变量指向它。
- 游戏语义按原版逐条对拍复刻（含官方 bug 的原样保留），引擎侧语义规约见 eraKcore 的 `specs/`。

## 许可

TODO：LICENSE 待定（需先确认与 Emuera 原许可的兼容性）。在 LICENSE 落地前，本仓库内容按默认版权保留。
