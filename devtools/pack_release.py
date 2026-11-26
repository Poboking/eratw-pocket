#!/usr/bin/env python3
"""出包唯一入口 —— release APK，且**默认就把报错上报打开**。

## 为什么要有这个脚本

上报开关由两个 gradle 参数决定（`app/build.gradle.kts` 的 `resValue`）：

```text
-PeratwReportEndpoint=https://<worker>/report
-PeratwReportToken=<wrangler secret 同值>
```

**漏带 = APK 静默回到「不上报」**：gradle 不报错、编译全绿、包照样出，只是玩家崩了
没人知道。这个失败模式没有屏上痕迹，只能靠流程堵 —— 所以出包收敛到本脚本：
参数由它带，出完再**回读 APK 里的真实字符串**确认上报真的开着，读不到就非零退出。

## 用法

```bash
python devtools/pack_release.py                  # 全量包（带资源），上报开，更新源取缺省 R2 地址
python devtools/pack_release.py --skip-assets    # 小包（复用设备上已解包的数据）
python devtools/pack_release.py --offline        # 明确出「不上报」的包（会大声警告）
python devtools/pack_release.py --no-update      # 不内置更新源（开发/内测包）
python devtools/pack_release.py --verify-only    # 只体检已有 APK，不构建
python devtools/pack_release.py --dry-run        # 只打印 gradle 命令（不配令牌也放行）
python devtools/pack_release.py --print-corpus-root-name  # 只打印现行语料根名（pack_one 取名单源，不构建）
python devtools/pack_release.py --no-require-seed-qol-db  # dev 试包：缺预置库只告警不拒（正式档缺省即强制）
```

退出码：`0` 全绿（包已归档）· `1` 构建失败 · `2` 配置不合格（没端点/没令牌，拒出包）·
`3` 体检不通过（包已生成但**不该发**：不上报 / 令牌与端点不配对 / 章不对 / 包型不对）。

发布章的四个 `-PeratwRelease*` 本脚本**不往命令行带**（口令会留在 shell 历史与构建日志里），
写在 `GRADLE_USER_HOME/gradle.properties`；脚本只负责验包上的章确实是它。

端点与令牌的取值顺序：命令行 > 环境变量 > `local.report.properties`（不入库，见 .gitignore）。
令牌是共享口令，**不进仓库、不进提交信息**。

## 出包后验什么

1. `app/build/generated/res/resValues/release/values/gradleResValues.xml` —— AGP 把
   `resValue` 落成的中间产物，与传入参数逐字比对（这是「参数确实生效」的直接证据）；
2. APK 里的 `resources.arsc` —— 端点与令牌两个串是否真的进了包（这是「真的进包」的
   直接证据）；
3. APK 里的 `assets/erbc.v1/package.report` —— 包型（随资源 / 只随 erbc）。`ERATW_GAMEDIR`
   缺了不会报错，只会静默出一个 376 MiB 的「不随资源」包（完整包 743 MiB），所以这一项
   也要点名；`--skip-assets` 是**第三种形状**（零随包条目，按设计没有 package.report 可读），
   认下来并打印，不当退化；
4. **签名章（§1.7 钉指纹）** —— 回读 APK 签名证书的 SHA-256 指纹，与脚本常量 `PINNED_RELEASE_SHA256`
   比对。不匹配就 exit 3（意味着换章了，老包将永久无法覆盖升级）。debug 章的包按
   `PINNED_DEBUG_SHA256` 识别，需显式 `--allow-debug-sign` 放行。
5. **端点活体** —— 拿包里的令牌去 `GET /reports`：回 403 = 令牌与端点不符（这种包上报
   等于关着，前两步却全绿），回 200 = 这条链真通；连不上（无网/没代理）只警告不阻断；
6. **INTERNET 权限** —— 配了端点就必须能在合并后的 manifest 里看到
   `android.permission.INTERNET`。2026-10-27 首个真机包就是栽在这：串都进包了、五步全绿，
   但没这条权限，真机上一上报就 SecurityException（被 runCatching 吞成一行 logcat），
   端点一份也收不到，玩家侧零痕迹；
7. **预置 qol_data.db 自证** —— 缺库时本脚本先自动跑 `devtools/gen_qol_seeddb.py`（真引擎、
   约 105s）再按 `build/seeddb/qol_data.db` + `seed.json` 两个 sha256 对账，带
   `-PeratwSeedQolDb` 让 staging 整份覆盖 `assets/plugins/qol_data.db`（玩家首启不再跑
   36,418 行建图），出包后回读 `package.report` 的 `seed-qol-db <sha256> <bytes>` 行与生成物
   比对。**正式出包缺省强制**：缺失/指纹不符/自动生成失败/包里没有自证 ⇒ 拒绝出包；
   dev 试包用 `--no-require-seed-qol-db` 放宽成告警。

1-2 不过 = 这个包不会上报；3 不过 = 包的形状与预期不符；4 不过 = 章不对（与钉值不符或 debug 章），
老包不能覆盖升级；5 回 403 = 令牌错，别发；6 不过 = 装上去也发不出来。

出完把 APK 复制一份到 `build/release-artifacts/<game>-<versionName>+<versionCode>-<git7>-
<full|noassets|skipassets>-<sha前8>.apk` 并配 `.sha256.txt`（gradle 输出目录会被下次构建
覆盖，归档名里带版本与产物指纹才认得出哪个包是哪个提交出的）。`<game>` 跟随随包语料：
给了 `--shell-profile` 用其 gameName，否则用 `--game-root` 目录名，两者皆无才是 `eratw`。
`--no-archive` 跳过复制。

本脚本只做「带参数 + 体检 + 归档」，不碰构建逻辑、不改任何 gradle 配置。
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import hashlib
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- 路径与环境

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)                 # <worktree>/eratw-android
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import update_manifest_check  # noqa: E402 同目录：设备端清单/镜像判式的单源（L2 起）
import engine_compat  # noqa: E402 同目录：引擎配置/印章单源（ENGC-6 / B6-PACK）
import engine_compat_report  # noqa: E402 同目录：引擎兼容报告渲染（ENGC-6 / B6-PACK）
import shell_profile  # noqa: E402 同目录：壳层品牌配置单源（SHELL-PROFILE）
import game_versions  # noqa: E402 同目录：每款游戏各自的版本账本（GAME-VERSION）

APK_PATH = os.path.join(APP_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
RES_VALUES = os.path.join(
    APP_ROOT, "app", "build", "generated", "res", "resValues", "release", "values",
    "gradleResValues.xml",
)
BUILD_GRADLE = os.path.join(APP_ROOT, "app", "build.gradle.kts")
# AGP 合并后的 release manifest（权限的最终形状，含库合并进来的条目）。
MERGED_MANIFEST = os.path.join(
    APP_ROOT, "app", "build", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)
ARTIFACT_DIR = os.path.join(APP_ROOT, "build", "release-artifacts")
LOCAL_PROPS = os.path.join(APP_ROOT, "local.report.properties")

ENV_ENDPOINT = "ERATW_REPORT_ENDPOINT"
ENV_TOKEN = "ERATW_REPORT_TOKEN"
# 国内镜像仓的更新源（一条或多条，`|` 分隔；写进 resValue 供 UpdateManager 读）。
ENV_MIRRORS = "ERATW_UPDATE_MIRRORS"
# 透传给 gradle 的额外参数（按 shlex.split 解析；让主人能追加 --profile 生成 task 级耗时报告）。
ENV_GRADLE_EXTRA_ARGS = "ERATW_GRADLE_EXTRA_ARGS"

# 发布章的四个键（与 app/build.gradle.kts 的 signingConfigs.releaseLocal 同名）。
# 取值顺序与 gradle 一致：命令行 -P > GRADLE_USER_HOME/gradle.properties > 工程 gradle.properties。
SIGN_KEYS = ("eratwReleaseStoreFile", "eratwReleaseStorePassword",
             "eratwReleaseKeyAlias", "eratwReleaseKeyPassword")

# §1.7 签名章钉指纹：发布章证书的 SHA-256 指纹（非敏感，是证书公开信息）。
# 换章 = 所有老包永久无法覆盖升级，所以把它钉成常量；体检不符就非零退出。
# 指纹由发布 keystore 的 `keytool -list -v` 的 SHA-256 行取得；更换发布章须同步更新此常量。
PINNED_RELEASE_SHA256 = (
    "e2fad8c6342ca97a54826ad8beb45b1f4f0ef0ba"
    "b5aad7bb8ac398efd645a181"
)
# debug keystore 的 SHA-256 指纹（用于 --allow-debug-sign 时明确标注）。
PINNED_DEBUG_SHA256 = (
    "76b0aa436970ce200f05b97d3c268872aa1740da"
    "3c0c863f6190f0078fab4085"
)

# 部署好的端点（tools/report-endpoint/ 的 worker，2026-10-27 上线）。
# 只作缺省值：换端点改这里或 local.report.properties 或环境变量。
# 上报端点缺省值：**不在代码里写死**，从 local.report.properties 或环境变量取
# （ERATW_REPORT_ENDPOINT / ERATW_REPORT_TOKEN，或 local.report.properties 的
# endpoint / token 两键）。两者都缺时出包照常、上报链关闭并大声警告。
DEFAULT_ENDPOINT = ""

# 国内镜像仓更新源的缺省基址（R2 + 自定义域，2026-10 波）。
# 命令行 > 环境变量 > local.report.properties > 此处缺省。
# 更新源缺省值：公开仓不内置任何真实更新域。需要内置更新源的出包请用
# --mirror / ERATW_UPDATE_MIRRORS / local.report.properties 显式提供。
DEFAULT_MIRRORS = ""

# ---------------------------------------------------------------- 出包自述：语料补丁独立列
# cb001 接线①：随包 manifest.txt 里 buildSrc（CorpusPatches.kt:326）写的是逐条
# `corpus-patch <id> <target> <bad-sha> <fixed-sha>` 留痕行；产物侧要能**不翻构建日志**
# 直接回答「这个包套了哪几个语料补丁」，故在 package.report 自述面另立一个独立列，
# 不把信息塞回留痕行的文本里。列值由脚本回读 manifest.txt 的真实留痕行逐项生成，
# 出包期与产物声明值逐项对齐（缺列或值不符非零退出，照 AGENTS §8 的回读核对纪律）。
APPLIED_CORPUS_PATCHES_KEY = "applied_corpus_patches"
# manifest.txt 里一条编译期留痕行的前缀（与 buildSrc CorpusPatches.kt 同字面前缀）。
CORPUS_PATCH_MARKER = "corpus-patch "
# 本次没套任何补丁时该列的「空形状」：列必须存在，值取这个占位，不许缺列
# （对应测试用例「没套补丁的包该列为空形状而不是缺列」）。
APPLIED_CORPUS_PATCHES_EMPTY = "-"

# ---------------------------------------------------------------- 严格闸（D8 缺口闭环，2026-11-08）
# 过渡期开关（不是默认行为）：D8 刚合入时产物侧 buildSrc 还不会在 package.report 写这一列，
# 立刻判红会破坏「不改出包默认行为」（CONTRACT-ENGFIX 的 D8 设计张力裁决）。那段过渡期可把
# 本常量置 False，让 apply_corpus_patches_column 暂时代生成列，保默认绿。
# 一旦 buildSrc 侧 StageGameAssetsTask 在 package.report 自述面自己写出该列（eng2-d8b 任务 A），
# 产物缺列就必然是「产物侧接线坏了」；脚本再兜就永远把这个信号遮住，让「脚本代生成」永久化，
# 缺列判红分支在端到端路径永远不可达 ⇒ 因此接线完成后必须置 True：
# 产物缺 applied_corpus_patches 列直接进 kind_problems → exit 3，产物自己证明自己。
# 本路任务 A 已落地，故默认 True（严格档）；过渡档仅留作接线完成前的临时形态，勿长期开启。
APPLIED_CORPUS_PATCHES_STRICT = True

# ---------------------------------------------------------------- 预置 qol_data.db（跳过首次建图）
# 链路：devtools/gen_qol_seeddb.py 在出包期用真引擎把 261111 语料的建图结果落成 SQLite 文件
# （12 表、GRAPH_DISTANCE 36,418 行）→ staging 期整份覆盖 assets/plugins/qol_data.db →
# 玩家首启读到的 _meta.graph_schema_version 就等于 NOW_VERSION，语料不再跑 GRAPH_DB_REBUILD
# （语料 qol_graph_init.ERB:49-51 的判据只比这个版本号；桌面实测 TITLE 相位建图 wall ≈ 6.8s）。
# 本脚本负责把住「这个库是不是当前 erbc 产物跑出来的」这道门：seed.json 声明值与实测 sha 对不上
# 就不把 -PeratwSeedQolDb 交给 gradle —— 否则会出一个「引擎/语料换了、图还是旧的」的包，
# 而语料只认 _meta 版本号、不会自愈，玩家侧表现为寻路/距离错到某一版为止。
SEED_QOL_DB_DIR_REL = os.path.join("build", "seeddb")
SEED_QOL_DB_BASENAME = "qol_data.db"
SEED_QOL_DB_META_BASENAME = "seed.json"
# staging 后的随包条目（与 buildSrc StageGameAssetsTask.applySeedQolDbOverlay 同字面）。
SEED_QOL_DB_ASSET_ENTRY = "assets/plugins/qol_data.db"
# package.report 自述键：`seed-qol-db <sha256> <bytes>`；来源路径另起一列便于事后追。
SEED_QOL_DB_REPORT_KEY = "seed-qol-db"
SEED_QOL_DB_SOURCE_KEY = "seed-qol-db-source"
# 出包期现场生成预置库的入口（真引擎跑 TITLE 建图）；与文档/告警文案同源，不另记路径。
SEED_QOL_DB_GENERATOR_REL = os.path.join("devtools", "gen_qol_seeddb.py")
ENV_ERBC_DIR = "ERATW_ERBC_DIR"

# ---------------------------------------------------------------- 语料根（4.981 换代）

# 随包语料的**现行**根与**已退役**的旧根都**不写在这份脚本里**（CONTRACT-ENGCPL E7）：
# 唯一出处是手写的 `specs/engine-profiles.input.yaml`，由 `devtools/gen_engine_profiles.py`
# 投影成机器可读的 `specs/engine-profiles.yaml`，本脚本用 PyYAML 直读它的 `corpora:` 段。
# `ERATW_GAMEDIR` 缺省回落时现行根优先；`.tooling/env.sh` 的缺省导出仍指旧根（共享基建，
# 不随换代改），所以「显式设了但指旧根」也要大声点出来 —— 否则出的是内部自洽的
# 4.980 数据包，却按 4.981 的版本号发出去（体检全绿，无一处拦得住）。
SPEC_CORPORA_YAML = Path(__file__).resolve().parent.parent / "specs" / "engine-profiles.yaml"


def load_corpora(path: str = "") -> dict:
    """读生成物语料身份表的 `corpora:` 段 → `{id: {"root_dir", "marker_file", "retired"}}`。

    缺 PyYAML / 表缺失 / 段缺失一律硬失败（`SystemExit`）：本脚本不再内联语料根名字面量，
    静默回落等于又造一份事实。顺序 = 表内书写顺序。
    """
    try:
        import yaml  # type: ignore
    except ImportError as exc:  # pragma: no cover - 环境缺依赖
        raise SystemExit(
            "读语料身份表需要 PyYAML：先 pip install pyyaml"
            "（devtools/pack_release.py 不再内联语料根名字面量）") from exc
    spec = path or str(SPEC_CORPORA_YAML)
    if not os.path.isfile(spec):
        raise SystemExit(
            f"语料身份表不存在：{spec}（它是 devtools/gen_engine_profiles.py 的生成物，"
            "源 = specs/engine-profiles.input.yaml；先重生成再出包）")
    with open(spec, "r", encoding="utf-8") as fh:
        try:
            doc = yaml.safe_load(fh)
        except yaml.YAMLError as exc:
            raise SystemExit(f"语料身份表 {spec} 不是合法 YAML：{exc}") from exc
    corpora = doc.get("corpora") if isinstance(doc, dict) else None
    if not isinstance(corpora, dict) or not corpora:
        raise SystemExit(f"语料身份表 {spec} 缺 corpora 段或为空")
    rows = {}
    for cid, entry in corpora.items():
        if not isinstance(entry, dict):
            raise SystemExit(f"语料身份表 {spec} 的 corpora.{cid} 不是映射")
        root_dir, retired = entry.get("root_dir"), entry.get("retired")
        if not isinstance(root_dir, str) or not root_dir.strip():
            raise SystemExit(f"语料身份表 {spec} 的 corpora.{cid} 缺 root_dir")
        if not isinstance(retired, bool):
            raise SystemExit(f"语料身份表 {spec} 的 corpora.{cid} 的 retired 必须是 true/false")
        rows[cid] = {"root_dir": root_dir.strip(), "marker_file": entry.get("marker_file"),
                     "retired": retired}
    return rows


CORPORA = load_corpora()

# 现行语料 = 唯一 `retired: false` 的那条（0 条或多条 = 表坏了，出包期先炸，不许挑一个）。
_ACTIVE_CORPORA = [row for row in CORPORA.values() if not row["retired"]]
if len(_ACTIVE_CORPORA) != 1:
    raise SystemExit(
        f"{SPEC_CORPORA_YAML} 的 corpora 段必须恰有一条 retired=false 的语料，"
        f"实际 {len(_ACTIVE_CORPORA)} 条（{sorted(k for k, v in CORPORA.items() if not v['retired'])}）")
CURRENT_CORPUS_DIR = _ACTIVE_CORPORA[0]["root_dir"]

# 退役语料根（全部 `retired: true`；顺序 = 表内书写顺序）。出包闸按**集合**判，不再只认一个名字。
RETIRED_CORPUS_DIRS = tuple(row["root_dir"] for row in CORPORA.values() if row["retired"])
# 兼容既有单名读点（`emit_update.py` / `test_source_gates.py` 按模块属性取）；新代码用集合。
RETIRED_CORPUS_DIR = RETIRED_CORPUS_DIRS[0] if RETIRED_CORPUS_DIRS else ""


def fallback_game_root(root: str) -> str:
    """`ERATW_GAMEDIR` 未设时的回落语料根：现行语料优先，退役语料只作机器缺新根时的兜底。"""
    for name in (CURRENT_CORPUS_DIR, *RETIRED_CORPUS_DIRS):
        cand = os.path.join(root, name)
        if os.path.isdir(cand):
            return cand
    return ""


def effective_game_root(cli_value: str) -> str:
    """出包实际会用的游戏根：命令行 > 环境变量 > 语料父目录 > 共享根回落（与 toolchain_env 同序）。"""
    if cli_value:
        return cli_value
    env = (os.environ.get("ERATW_GAMEDIR") or "").strip()
    if env:
        return env
    corpus = (os.environ.get("ERATW_CORPUS") or "").strip()
    if corpus:
        derived = os.path.dirname(os.path.abspath(corpus))
        if os.path.isdir(derived):
            return derived
    root = eratw_root()
    return fallback_game_root(root) if root else ""


def same_dir(a: str, b: str) -> bool:
    """两路是否同一目录（大小写/分隔符/结尾斜杠不敏感；本机不支持 samefile 时退字符串比）。"""
    if not a or not b:
        return False
    try:
        return os.path.samefile(a, b)
    except OSError:
        na, nb = os.path.normcase(os.path.abspath(a)), os.path.normcase(os.path.abspath(b))
        return na == nb


def retired_corpus_problem(game_root: str) -> list:
    """游戏根指向**任一已退役**语料且本机有现行语料根 ⇒ 拒出包（可显式放行）。

    4.981 起随包语料已换代；`.tooling/env.sh` 的缺省 `ERATW_GAMEDIR` 仍指退役根 ——
    source 过它再出包，得到的是「旧语料 + 当前版本号」的包：erbc 按同一语料编出、
    freshness 自洽、章也对，**没有任何一道既有体检拦得住**。这属于「漏了不报错」
    家族，所以做成硬闸；确要旧语料数据的包（回归对拍之类）走 `--allow-retired-corpus`。

    退役名单来自生成物 `specs/engine-profiles.yaml` 的 `corpora.*.retired: true`（集合判，
    不是单名硬判）：多一份旧语料登记进表就自动多一条拦法。
    """
    if not game_root or not os.path.isdir(game_root):
        return []
    root = eratw_root()
    if not root or not os.path.isdir(os.path.join(root, CURRENT_CORPUS_DIR)):
        return []                      # 本机没有现行语料根：无从换代，按老规矩出
    hit = [name for name in RETIRED_CORPUS_DIRS
           if same_dir(game_root, os.path.join(root, name))]
    if not hit:
        return []
    return [
        f"游戏根指向已退役的语料（{hit[0]}）：{game_root}。随包语料自 4.981（merge 76659754）起"
        f"应为 {CURRENT_CORPUS_DIR} —— env.sh 的缺省 ERATW_GAMEDIR 还是旧根，source 过它会"
        "静默出「旧语料 + 新版本号」的包。修法：--game-root 或导出 ERATW_GAMEDIR 指向新根；"
        "确要旧语料数据的包（回归对拍）加 --allow-retired-corpus。",
    ]


def eratw_root() -> str:
    """按主机名解析共享根（AGENTS.md §1）；两机之外的输出直接判错，不猜路径。"""
    # 公开仓不内置任何个人机器路径：只认 ERATW_ROOT；未设置返回空串，由调用方判断。
    root = (os.environ.get("ERATW_ROOT") or "").strip()
    return root if root and os.path.isdir(root) else ""


def toolchain_env() -> dict:
    """补 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / ERATW_GAMEDIR —— 交互 shell 里 source 过 env.sh 就不动它。

    `ERATW_GAMEDIR` 不是可选项：`app/build.gradle.kts` 用「`-PeratwGameRoot` 或环境变量
    ERATW_GAMEDIR」决定**要不要把游戏资源搬进包**。缺了它不报错，只是静默退化成
    「只随包 erbc.v1」的小包（376 MiB vs 完整包 743 MiB）—— 2026-10-27 实测踩过。

    **`ERATW_GAMEDIR` 还必须与 `ERATW_CORPUS` 同源**（见 [data_source_alignment]）：erbc 由
    语料编译，CSV/resources 由游戏根随包。缺省值原先写死 4.980，于是「导出 4.981 的
    ERATW_CORPUS 出包、ERATW_GAMEDIR 没设」会静默出成 4.981 脚本 + 4.980 数据
    （真机 ITEMNAME 只有 1000 槽，4.981 的 QOL 脚本按 1..1200 读 ⇒ 越界）。
    """
    env = dict(os.environ)
    root = eratw_root()
    tooling = os.path.join(root, ".tooling") if root else ""
    if tooling and os.path.isdir(tooling):
        if not env.get("JAVA_HOME"):
            for name in sorted(os.listdir(tooling)):
                if name.lower().startswith("jdk-"):
                    env["JAVA_HOME"] = os.path.join(tooling, name)
                    break
        if not env.get("GRADLE_USER_HOME"):
            gh = os.path.join(tooling, "gradle-home")
            if os.path.isdir(gh):
                env["GRADLE_USER_HOME"] = gh
        if not env.get("ANDROID_HOME"):
            sdk = os.path.join(tooling, "android-sdk")
            if os.path.isdir(sdk):
                env["ANDROID_HOME"] = sdk
                env.setdefault("ANDROID_SDK_ROOT", sdk)
    # 数据源：语料（编译 erbc）与游戏根（随包 CSV/resources）必须同一份，顺序为
    # 「显式 ERATW_GAMEDIR > ERATW_CORPUS 的父目录 > 缺省语料根（[fallback_game_root]，
    # 现行 4.981 优先）」。语料父目录这一档是为了让两条路径**恒同源**：只导出了
    # ERATW_CORPUS 时若仍回落缺省根，就会出成「4.981 脚本 + 4.980 数据」（见
    # [data_source_alignment] 的现网实证）。
    corpus = env.get("ERATW_CORPUS", "").strip()
    game = env.get("ERATW_GAMEDIR", "").strip()
    if root and not game:
        derived = os.path.dirname(os.path.abspath(corpus)) if corpus else ""
        if derived and os.path.isdir(derived):
            game = derived
            print(f"[pack] ERATW_GAMEDIR 未设，跟语料同源：{game}")
        else:
            game = fallback_game_root(root)
            if game:
                print(f"[pack] ERATW_GAMEDIR 未设，回落语料根：{game}")
        if game:
            env["ERATW_GAMEDIR"] = game
    if game and not corpus:
        env["ERATW_CORPUS"] = os.path.join(game, "ERB")
    # 子进程（gradlew / keytool）的输出一律 UTF-8：Windows 控制台缺省 cp936，中文体检信息
    # 落进日志就是乱码（2026-10-27 实测：`构建耗时 206s` 存成 `?????? 206s`），
    # 真出事时读不到证据。
    jt = (env.get("JAVA_TOOL_OPTIONS") or "").strip()
    if "stdout.encoding" not in jt:
        env["JAVA_TOOL_OPTIONS"] = (jt + " " if jt else "") + (
            "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
        )
    return env


# ---------------------------------------------------------------- 上报配置

def read_local_props() -> dict:
    """读 local.report.properties（key=value，`#` 注释）。文件不存在 = 空。"""
    props: dict = {}
    if not os.path.isfile(LOCAL_PROPS):
        return props
    with open(LOCAL_PROPS, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def resolve_report_config(args) -> tuple:
    """返回 (endpoint, token, 来源说明)。命令行 > 环境变量 > local.report.properties。"""
    props = read_local_props()
    endpoint = (args.endpoint
                or os.environ.get(ENV_ENDPOINT)
                or props.get("endpoint")
                or DEFAULT_ENDPOINT)
    token = (args.token
             or os.environ.get(ENV_TOKEN)
             or props.get("token")
             or "")
    origin = []
    origin.append("命令行" if args.endpoint else
                  ("环境变量" if os.environ.get(ENV_ENDPOINT) else
                   ("local.report.properties" if props.get("endpoint") else "脚本缺省")))
    origin.append("命令行" if args.token else
                  ("环境变量" if os.environ.get(ENV_TOKEN) else
                   ("local.report.properties" if props.get("token") else "缺失")))
    return endpoint.strip(), token.strip(), " / ".join(origin)


def check_report_config(endpoint: str, token: str) -> list:
    """与 core 层 ErrorReportUpload.isHttpsEndpoint 同口径（https、无空白），外加令牌非空。"""
    problems = []
    if not endpoint:
        problems.append("端点为空")
    elif not endpoint.startswith("https://"):
        problems.append(f"端点必须是 https（core 层会拒明文 http）：{endpoint}")
    elif any(ch.isspace() for ch in endpoint):
        problems.append("端点含空白字符")
    if not token:
        problems.append(
            "令牌为空 —— 端点有了但没令牌，APK 里上报仍会整体关闭。"
            f" 填 {LOCAL_PROPS}（key: token）或导出 {ENV_TOKEN}"
        )
    return problems


# ---------------------------------------------------------------- 更新通道（国内镜像仓）

def resolve_update_mirrors(args) -> tuple:
    """返回 (镜像串, 来源说明)。命令行 `--mirror`（可重复）> 环境变量 > local.report.properties > 缺省。

    缺省取 `DEFAULT_MIRRORS`（R2 基址）。如果用户配了 `--no-update` 则强制清空。
    """
    props = read_local_props()
    cli = list(getattr(args, "mirrors", None) or [])
    env = os.environ.get(ENV_MIRRORS, "").strip()
    file_raw = props.get("mirrors") or props.get("update_mirrors") or ""
    # `re.split` 对空串返回 `['']`（真值），会把「脚本缺省 DEFAULT_MIRRORS」短路掉 ——
    # 先滤空再选档，否则「没配 mirrors」会静默退化成「内置零条源」。
    file_list = [s for s in re.split(r"[|,\s]+", file_raw) if s.strip()]
    raw_list = cli or ([env] if env else []) or file_list or [DEFAULT_MIRRORS]
    cleaned = []
    for item in raw_list:
        url = (item or "").strip()
        if not url:
            continue
        cleaned.append(url)
    origin = ("命令行" if cli else
              ("环境变量" if env else
               ("local.report.properties" if file_raw else "脚本缺省（DEFAULT_MIRRORS）")))
    return "|".join(cleaned), origin


def _mirror_problem(url: str) -> str | None:
    """把一条镜像地址的**第一条**违规翻成发布侧看得懂的话（判定本体不在这里复写）。

    顺序与 `UpdatePolicy.normalizeMirror` 一致（https → 长度 → 空白/控制字符 → 主机名 →
    查询串/片段），只是为了让报错能指出「错在哪一条规则」；`normalize_mirror_value` 才是
    判据单源（L7）。
    """
    if not url.strip().startswith("https://"):
        return f"镜像必须是 https（设备侧直接拒收明文）：{url}"
    if len(url.strip()) > update_manifest_check.MAX_MIRROR_CHARS:
        return (f"镜像地址过长（设备侧上限 {update_manifest_check.MAX_MIRROR_CHARS} 字符）："
                f"{url[:80]}…（共 {len(url.strip())} 字符）")
    if any(ch.isspace() or ord(ch) < 0x21 or ord(ch) == 0x7F for ch in url.strip()):
        return f"镜像地址含空白或控制字符：{url!r}"
    without_scheme = url.strip()[len("https://"):]
    if not without_scheme or without_scheme[0] == "/":
        return f"镜像地址 `https://` 之后缺主机名：{url}"
    if "?" in url or "#" in url:
        return f"镜像地址是目录基址，不许带查询串或片段（拼接下载名会错位）：{url}"
    host = without_scheme.split("/", 1)[0]
    if "." not in host:
        return f"镜像地址的主机名看着不完整：{url}"
    return None


def check_update_mirrors(mirrors: str) -> tuple:
    """与 core 层 `UpdatePolicy.normalizeMirror` **同一套判式**（L7 起判据单源）。

    判据本体复用 `update_manifest_check.normalize_mirror_value`（L2 建的设备端直译，逐条
    对照 UpdatePolicy.kt:88-101：https、≤512、无空白/控制字符、`https://` 后不许以 `/` 开头、
    主机名含点、无 `?`/`#`、末尾补斜杠）。所以「发布侧放行而设备侧丢掉」这条缝隙在构造上
    就不存在 —— 而发布侧拒掉设备侧能用的地址只是在为难自己，同样不写。
    唯一一条**发布侧额外**的拒绝是 userinfo：设备端只判「`https://` 后第一段含点」，所以
    `https://user:pass@host/…` 在设备侧能过；但这条 URL 会被原样编进 APK 的 resValue，
    等于把凭据发给所有玩家 —— 这不是「设备能不能用」的问题，是发布纪律，故单独拦。

    返回 (问题列表, 可用条目数)。
    """
    problems = []
    usable = 0
    for url in [u for u in mirrors.split("|") if u]:
        normalized = update_manifest_check.normalize_mirror_value(url)
        if normalized is None:
            problems.append(_mirror_problem(url) or f"镜像不合法（设备侧会丢弃）：{url}")
            continue
        host_segment = url.strip()[len("https://"):].split("/", 1)[0]
        if "@" in host_segment:
            problems.append(f"镜像地址带 userinfo（发布侧额外拦：凭据会被编进 APK 资源发给所有"
                            f"玩家；设备端 normalizeMirror 本身不拒这种形状）：{url}")
            continue
        usable += 1
    return problems, usable


# ---------------------------------------------------------------- 构建

def data_source_alignment(skip_assets: bool, game_root: str) -> list:
    """出包前的**数据源同源**判据：erbc 的编译语料与随包数据的游戏根必须是同一份。

    这两条路径各有归属：`ERATW_CORPUS` 决定 erbc 编译读哪份 ERB/CSV，
    `ERATW_GAMEDIR`（或 `-PeratwGameRoot`）决定 CSV/resources 随哪份包。指到不同版本
    的游戏根，产出的就是一个「自洽却与数据版本不符」的包 —— 编译全绿、体检全绿
    （上报/签名/包型都不看这一项），真机上才炸。

    现网实证（上报端点 1793712013906 / 1793715910544，app 0.2.39）：APK 的 erbc 来自 4.981
    语料、随包 CSV 来自 4.980 游戏根 ⇒ `VariableSize.csv` 没有 `ITEMNAME,1200`，
    `ITEMNAME` 只有 1000 槽（4.981 那份 :74 才有 `ITEMNAME,1200`，见
    `VariableSizeCouplingTest` 的真实语料断言），而 4.981 的 `qol_item.ERB` 按
    `FOR ITEM_ID, 1, 1200` 读 `ITEMNAME:I_ID` ⇒ 新局即报「下标超出范围 ITEMNAME:1000」。

    `--skip-assets` 不随数据，这条无从谈起，直接放行。
    """
    if skip_assets:
        return []
    env = toolchain_env()
    effective = game_root.strip() or env.get("ERATW_GAMEDIR", "").strip()
    corpus = env.get("ERATW_CORPUS", "").strip()
    if not effective:
        return [
            "找不到游戏根（-PeratwGameRoot / ERATW_GAMEDIR 都为空）：这个包不会随数据，"
            "与「完整包」预期不符（要只随 erbc 请显式加 --skip-assets）"
        ]
    if not corpus:
        return []
    corpus_root = os.path.dirname(os.path.abspath(corpus))
    if os.path.normcase(os.path.abspath(effective)) != os.path.normcase(os.path.abspath(corpus_root)):
        return [
            f"语料与随包数据不同源：ERATW_CORPUS={corpus}（erbc 由它编译）"
            f" vs 游戏根={effective}（CSV/resources 由它随包）——"
            f" 两边的 VariableSize/Item.csv 版本会打架（旧数据只有 1000 个 ITEMNAME 槽，"
            f"新脚本按 1..1200 读 ⇒ 真机「下标超出范围 ITEMNAME:1000」）"
        ]
    return []


def gradle_cmd(tasks: list) -> list:
    """Windows 下 .bat 必须经 cmd 拉起；其余平台直接跑 wrapper（AGENTS §5：统一 ./gradlew）。"""
    wrapper = os.path.join(APP_ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    if os.name == "nt":
        return ["cmd", "/c", wrapper] + tasks
    return [wrapper] + tasks


_BS_SENTINEL = "\ue000"


def parse_gradle_extra_args() -> list:
    """把 ERATW_GRADLE_EXTRA_ARGS 解析成额外 gradle 参数；空值/未设置返回 []。

    Windows 下 gradlew 必须经 cmd /c 拉起，token 一旦含 | & < > ^ % 就会被 cmd 当成
    管道/重定向/转义/变量展开拆开（更新源那条正是因此改走 ERATW_UPDATE_MIRRORS 的），
    所以只放行不含这些元字符的简单 token；解析或校验失败直接报错出包，不静默吞掉。
    """
    raw = os.environ.get(ENV_GRADLE_EXTRA_ARGS, "")
    if not raw.strip():
        return []
    # shlex 的 POSIX 模式把反斜杠当转义符：-Pfoo=C:\path\file 会被静默改成 C:pathfile。
    # 先换成私用区哨兵再 split，取回后还原，Windows 路径才不会被吞掉。
    if _BS_SENTINEL in raw:
        print(f"[pack] {ENV_GRADLE_EXTRA_ARGS} 含保留字符，拒绝解析；原值={raw!r}",
              file=sys.stderr)
        raise SystemExit(2)
    try:
        tokens = shlex.split(raw.replace("\\", _BS_SENTINEL))
    except ValueError as exc:
        print(f"[pack] {ENV_GRADLE_EXTRA_ARGS} 解析失败（引号要成对）：{exc}；"
              f"原值={raw!r}", file=sys.stderr)
        raise SystemExit(2)
    tokens = [t.replace(_BS_SENTINEL, "\\") for t in tokens]
    bad = [t for t in tokens if any(ch in t for ch in "|&<>^%")]
    if bad:
        print(f"[pack] {ENV_GRADLE_EXTRA_ARGS} 含 cmd 元字符（| & < > ^ %）的 token：{bad}；"
              "经 cmd /c 会被拆开、重定向或做变量展开。去掉元字符，或把这类值改用专门的环境变量传入"
              f"（如更新源用 {ENV_MIRRORS}）；原值={raw!r}", file=sys.stderr)
        raise SystemExit(2)
    return tokens


def gradle_worker_count() -> int:
    """Gradle 并行 worker 数：优先 ERATW_GRADLE_MAX_WORKERS，否则按物理核口径（本机 44）。

    **2026-11-11 的 44 vs 12 三轮 A/B 证伪了「降 worker 能提速」，别再改小。**
    同一独立 Gradle home、每轮强制清 I/O 链输出重跑、都带 --profile：
      A 44 冷 213s / B 12 热 161s / C 44 热 188s；
      Task Execution 167 / 151 / **147s**（热态 44 反而快 4s），
      compressReleaseAssets 112 / 124 / **106s**（12 workers 慢 12~18s）。
    B 轮总时长看着最短，只是它复用了 A 的 daemon（Startup 3s / 配置 5s，
    且省掉 74s Artifact Transforms）；C 轮换了 worker 数重新起 daemon 就回到
    17s / 13s —— 全是冷启动噪声，不是 worker 效应。日志在 pack-20261111/16~18。
    Gradle 缺省取逻辑核（本机 88）会让 JVM/GC 线程互相踩，按「逻辑核/2」最稳；
    保留 ERATW_GRADLE_MAX_WORKERS 给主人按机器或按场景手调。
    """
    raw = os.environ.get("ERATW_GRADLE_MAX_WORKERS", "").strip()
    if raw.isdigit() and int(raw) > 0:
        return int(raw)
    logical = os.cpu_count() or 4
    return max(4, min(64, logical // 2 if logical > 8 else logical))


class SeedQolDb:
    """一个已通过指纹对账、可以交给 gradle 的预置 qol_data.db。

    刻意用普通类而不是 `@dataclass`：`devtools/test_engine_compat.py` 的反向证明会把本文件
    变异后按 file-location 载入（模块不进 `sys.modules`），dataclass 在那种载入方式下会在
    `sys.modules.get(cls.__module__)` 上崩，反向证明就变成了与变异无关的假红。
    """
    __slots__ = ("path", "db_sha256", "db_bytes", "program_sha256", "origin")

    def __init__(self, path: str, db_sha256: str, db_bytes: int,
                 program_sha256: str, origin: str) -> None:
        self.path = path                # 绝对路径（gradle 侧要求绝对路径）
        self.db_sha256 = db_sha256
        self.db_bytes = db_bytes
        self.program_sha256 = program_sha256   # 生成它的那份 program.erbc 的 sha256
        self.origin = origin            # "auto"（缺省 build/seeddb 自动发现）/ "cli"（显式）


class SeedQolDbDiscovery:
    """[discover_seed_qol_db] 的结果：要么拿到可用的 seed，要么给出为什么没有。"""
    __slots__ = ("seed", "problems", "warnings", "skipped")

    def __init__(self) -> None:
        self.seed = None
        self.problems: list[str] = []   # 判红项（require / 显式路径 / 指纹不符）
        self.warnings: list[str] = []   # 只忽略不判红的说明
        self.skipped = ""               # 本次根本不适用（skip-assets / 第三方语料…）


def sha256_file(path: str) -> str:
    """流式算文件 sha256（预置库 1.4 MB、program.erbc 数十 MB 都不进内存）。"""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def erbc_program_candidates() -> list[str]:
    """当前 erbc 产物的 `program.erbc` 候选（顺序即优先级，去重后取第一个存在的）。

    与 [preflight_erbc_digest] 同口径：`ERATW_ERBC_DIR` 优先，缺省是
    `devtools/erbc_freshness_probe.py` 的 `MANIFEST_REL` 所在目录；不新造第三条路径猜测。
    """
    dirs: list[str] = []
    env = os.environ.get(ENV_ERBC_DIR, "").strip()
    if env:
        dirs.append(env)
    try:
        import erbc_freshness_probe  # noqa: F811 同目录探针：产物目录的单源
        dirs.append(os.path.join(APP_ROOT, os.path.dirname(str(erbc_freshness_probe.MANIFEST_REL))))
    except Exception:  # 探针缺失不该让出包脚本在这里就崩，退回公开目录
        dirs.append(os.path.join(APP_ROOT, "tools", "erbcc", "build", "erbc-out", "erbc.v1"))
    seen: set[str] = set()
    out: list[str] = []
    for d in dirs:
        key = os.path.normcase(os.path.abspath(d))
        if key in seen:
            continue
        seen.add(key)
        out.append(os.path.join(os.path.abspath(d), "program.erbc"))
    return out


def resolve_erbc_program() -> str:
    """第一个真实存在的 program.erbc；一个都没有返回空串。"""
    for cand in erbc_program_candidates():
        if os.path.isfile(cand):
            return cand
    return ""


def discover_seed_qol_db(*, explicit: str = "", require: bool = False,
                         skip_assets: bool = False, foreign_corpus: bool = False,
                         engine_config: bool = False, disabled: bool = False,
                         db_path: str = "", meta_path: str = "",
                         erbc_program: str | None = None) -> SeedQolDbDiscovery:
    """找预置 qol_data.db 并核对「它确实是当前 erbc 产物跑出来的」。

    判定矩阵（`--require-seed-qol-db` 把「本来只警告」升级成判红；显式 `--seed-qol-db`
    同样按判红处理 —— 调用方已经点名要它了，静默退回等于骗人）：

    | 情形 | 结果 |
    | --- | --- |
    | `--no-seed-qol-db` / `--skip-assets` / `--foreign-corpus` / 第三方引擎模式 | skipped，不发声 |
    | 缺库 / 空库 / 缺 seed.json / JSON 坏 / 缺字段 | 缺省 warning 并跳过；require 或显式路径 ⇒ problem |
    | db 的 sha256 或字节数与 seed.json 不符 | 同上（指纹不符同样按「不可信」处理） |
    | 找不到当前 program.erbc，或它的 sha256 与 seed.json 不符 | 同上 |
    | 全部对上 | seed 填好，出包带 `-PeratwSeedQolDb=<path>` |

    `db_path` / `meta_path` / `erbc_program` 只给单测注入用；生产路径不提，走默认。
    """
    found = SeedQolDbDiscovery()
    if disabled:
        found.skipped = "显式 --no-seed-qol-db"
        return found
    if skip_assets:
        found.skipped = "--skip-assets 小包不随资源，预置库无从生效"
        return found
    if foreign_corpus:
        found.skipped = "--foreign-corpus 第三方语料自带自己的图，不用 eraTW 预置库"
        return found
    if engine_config:
        found.skipped = "第三方引擎构建模式（--engine-config），不用 eraTW 预置库"
        return found

    explicit = (explicit or "").strip()
    db = os.path.abspath(db_path or explicit
                         or os.path.join(APP_ROOT, SEED_QOL_DB_DIR_REL,
                                         SEED_QOL_DB_BASENAME))
    meta = os.path.abspath(meta_path or os.path.join(os.path.dirname(db),
                                                     SEED_QOL_DB_META_BASENAME))
    # origin 只看「调用方有没有点名」：db_path/meta_path 是单测注入口，不代表用户意图。
    origin = "cli" if explicit else "auto"

    def reject(msg: str) -> SeedQolDbDiscovery:
        if require or origin == "cli":
            found.problems.append(msg)
        else:
            found.warnings.append(msg + "：本次不带预置库")
        return found

    if not os.path.isfile(db):
        return reject(f"预置库不存在：{db}（先跑 devtools/gen_qol_seeddb.py 生成）")
    size = os.path.getsize(db)
    if size <= 0:
        return reject(f"预置库是空文件：{db}（生成器中途失败？重跑 devtools/gen_qol_seeddb.py）")
    if not os.path.isfile(meta):
        return reject(f"缺 seed.json：{meta}（没有它无法核对预置库与当前 erbc 产物是否同源）")
    try:
        with open(meta, "r", encoding="utf-8") as fh:
            info = json.load(fh)
    except (OSError, ValueError) as exc:
        return reject(f"seed.json 读不出来：{meta}（{exc}）")
    if not isinstance(info, dict):
        return reject(f"seed.json 不是 JSON 对象：{meta}")
    declared_sha = str(info.get("db-sha256", "")).strip().lower()
    declared_bytes = info.get("db-bytes")
    declared_program = str(info.get("program-sha256", "")).strip().lower()
    if not declared_sha or not declared_program or not isinstance(declared_bytes, int):
        return reject(f"seed.json 缺 db-sha256 / db-bytes / program-sha256：{meta}"
                      "（生成器版本过旧？重跑 devtools/gen_qol_seeddb.py）")
    actual_sha = sha256_file(db)
    if declared_sha != actual_sha or declared_bytes != size:
        return reject(f"预置库指纹与 seed.json 不符：实测 {actual_sha[:16]}… / {size} B，"
                      f"声明 {declared_sha[:16]}… / {declared_bytes} B"
                      "（库被换过或生成器中途失败）")
    program = erbc_program if erbc_program is not None else resolve_erbc_program()
    if not program or not os.path.isfile(program):
        return reject(f"找不到当前 erbc 产物 program.erbc（查 {ENV_ERBC_DIR} 或 "
                      f"{os.path.join(APP_ROOT, 'tools', 'erbcc', 'build', 'erbc-out', 'erbc.v1')}）："
                      "无法核对预置库对应的编译产物")
    actual_program = sha256_file(program)
    if declared_program != actual_program:
        return reject(f"预置库对应的 erbc 产物不符：seed.json program-sha256={declared_program[:16]}…，"
                      f"当前 {program}={actual_program[:16]}…"
                      "（引擎/语料变了 ⇒ 图要重建，重跑 devtools/gen_qol_seeddb.py）")
    found.seed = SeedQolDb(path=db, db_sha256=actual_sha, db_bytes=size,
                           program_sha256=actual_program, origin=origin)
    return found


def seed_qol_db_gradle_args(seed: SeedQolDb | None) -> list[str]:
    """把发现到的预置库翻成 gradle 参数；None ⇒ []（参数序列与引入前逐字一致）。"""
    return [f"-PeratwSeedQolDb={seed.path}"] if seed is not None else []


def seed_qol_db_report_problems(seed: SeedQolDb | None, require: bool = False,
                                report: dict | None = None) -> list[str]:
    """出包后回读 package.report 的 `seed-qol-db <sha256> <bytes>` 做自证。

    「staging 真的吃到了预置库」只有产物能证明：参数带了但 staging 因为
    `-PeratwSkipAssets` / 无游戏根 / 第三方语料把 overlay 忽略掉时，gradle 只 warn，
    report 里不会有这一行 —— 这种包在玩家侧仍然要自己建图，必须在出包期挡下来。
    """
    pkg = read_package_report() if report is None else report
    line = str(pkg.get(SEED_QOL_DB_REPORT_KEY, "") or "").strip()
    if seed is None:
        if require and not line:
            return [f"package.report 缺 {SEED_QOL_DB_REPORT_KEY} 行（--require-seed-qol-db）"]
        if line and not require:
            print(f"[pack] 注意：包里带 {SEED_QOL_DB_REPORT_KEY} 行（{line}），"
                  "本次脚本没有指定预置库")
        return []
    if not line:
        msg = (f"package.report 缺 {SEED_QOL_DB_REPORT_KEY} 行：-PeratwSeedQolDb 已给却没有自证 —— "
               "staging 没吃到预置库（--skip-assets / 无游戏根 / 第三方语料）")
        if require:
            return [msg + "（--require-seed-qol-db）"]
        print(f"[pack] 警告：{msg}")
        return []
    fields = line.split()
    try:
        actual_sha = fields[0].strip().lower()
        actual_bytes = int(fields[1])
    except (IndexError, ValueError):
        return [f"package.report 的 {SEED_QOL_DB_REPORT_KEY} 行格式不对：{line!r}"
                "（应为 <sha256> <bytes>）"]
    problems: list[str] = []
    if actual_sha != seed.db_sha256:
        problems.append(f"包里预置库 sha256 与生成物不符：包 {actual_sha[:16]}… / "
                        f"生成 {seed.db_sha256[:16]}…")
    if actual_bytes != seed.db_bytes:
        problems.append(f"包里预置库字节数不符：包 {actual_bytes} / 生成 {seed.db_bytes}")
    if not problems:
        print(f"[pack] 预置库自证通过：{SEED_QOL_DB_ASSET_ENTRY} sha256={seed.db_sha256[:16]}… / "
              f"{seed.db_bytes} B（来源 {pkg.get(SEED_QOL_DB_SOURCE_KEY, '?')}）")
    return problems


def seed_qol_db_generator_cmd(*, game_root: str = "", erbc_dir: str = "",
                              compare: str = "") -> list[str]:
    """出包期补跑生成器的命令行（路径与 [SEED_QOL_DB_GENERATOR_REL] 同源）。"""
    cmd = [sys.executable, os.path.join(APP_ROOT, SEED_QOL_DB_GENERATOR_REL)]
    if game_root:
        cmd += ["--game-root", game_root]
    if erbc_dir:
        cmd += ["--erbc-dir", erbc_dir]
    if compare:
        cmd += ["--compare", compare]
    return cmd


def seed_qol_db_generator_skip_reason(*, disabled: bool, explicit: str, skip_assets: bool,
                                      foreign_corpus: bool, engine_config: bool,
                                      verify_only: bool) -> str:
    """空串 ⇒ 缺库时该自动生成；非空 ⇒ 跳过生成的原因（进日志，便于解释「为什么没跑引擎」）。

    `--no-seed-qol-db` 是显式关掉；显式路径由调用方自己负责；`--skip-assets` /
    `--foreign-corpus` / 引擎配置模式（第三方引擎档）下预置库本来就不进包或口径不同；
    `--verify-only` 只体检现成 APK，不在这一步跑引擎。
    """
    if disabled:
        return "--no-seed-qol-db"
    if explicit:
        return "已显式指定 --seed-qol-db"
    if skip_assets:
        return "--skip-assets"
    if foreign_corpus:
        return "--foreign-corpus"
    if engine_config:
        return "引擎配置模式（第三方引擎档）"
    if verify_only:
        return "--verify-only"
    return ""


def run_seed_qol_db_generator(*, game_root: str = "", erbc_dir: str = "",
                              compare: str = "") -> int:
    """现场生成预置库；返回子进程退出码（0 = `build/seeddb/qol_data.db` 已就位）。

    生成器自己保证「两次跑同 sha + 结构校验 + 旧库对拍」，这里只负责在缺库时把它叫起来，
    并把失败原样带出去 —— 失败后是否判红由调用方按 `--require-seed-qol-db`/`--no-require-...`
    决定（缺省 release 形态是判红：宁可不发，也不发一个把建图成本退回给玩家的包）。
    """
    cmd = seed_qol_db_generator_cmd(game_root=game_root, erbc_dir=erbc_dir, compare=compare)
    if not os.path.isfile(cmd[1]):
        print(f"[pack] 预置库生成器不存在：{cmd[1]}（源码树不完整？）", file=sys.stderr)
        return 2
    print("[pack] 预置库缺失：自动生成（暖跑约 105s；首次含 :tools:vmdbg 编译约 4-5min）")
    print(f"[pack]   {' '.join(cmd)}")
    try:
        proc = subprocess.run(cmd, cwd=APP_ROOT)
    except OSError as exc:
        print(f"[pack] 预置库生成器启动失败：{exc}", file=sys.stderr)
        return 2
    if proc.returncode != 0:
        print(f"[pack] 预置库生成器失败：exit {proc.returncode}", file=sys.stderr)
    return proc.returncode


FORK_KILLED_MARK = "268435466"


def run_build_streaming(cmd: list[str], env: dict) -> tuple[int, str]:
    """跑构建并**边跑边转发**两端输出，同时攒一条尾带（最近 400 行）。

    为什么不用 subprocess.run：共享 gradle daemon 被污染时，Gradle 只用一行
    `Process ... finished with non-zero exit value 268435466` 说明 fork 的子 JVM 被宿主杀掉
    （0x1000000A；2026-11-26 实测同一 daemon 连挂两次、--no-daemon 一次过），进程退出码
    统一是 1 —— 不先抓文本，就没法判断该不该换 --no-daemon 重跑一轮，只能等人肉看日志。
    stdout/stderr 各一根线程抽干（单管道会死锁），转发保持既有观感（进度行照常实时刷）；
    两端分开转发是为了不让 stderr 混进进度窗的 stdout 解析面。
    """
    proc = subprocess.Popen(
        cmd, cwd=APP_ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    tail: collections.deque[str] = collections.deque(maxlen=400)
    lock = threading.Lock()

    def pump(pipe, sink) -> None:
        for line in pipe:
            sink.write(line)
            sink.flush()
            with lock:
                tail.append(line)
        pipe.close()

    threads = [
        threading.Thread(target=pump, args=(proc.stdout, sys.stdout), daemon=True),
        threading.Thread(target=pump, args=(proc.stderr, sys.stderr), daemon=True),
    ]
    for t in threads:
        t.start()
    code = proc.wait()
    for t in threads:
        t.join()
    with lock:
        return code, "".join(tail)


def run_build(endpoint: str, token: str, skip_assets: bool, dry_run: bool = False,
              game_root: str = "", mirrors: str = "", erbc_stored: bool = False,
              engine_config=None, foreign_corpus: bool = False,
              seed_qol_db: str = "", shell_config=None) -> int:
    tasks = ["--configure-on-demand", ":app:assembleRelease"]
    # 优化开关再给一次命令行写法：GRADLE_OPTS 里的 -Dorg.gradle.parallel/caching 取决于
    # 调用方是否 source 过优化环境，出包入口自带这两个开关谁调都一样；8.10.2 下实测与
    # --configure-on-demand 可共存。
    tasks += ["--parallel", "--build-cache"]
    # 并行度固化：出包缺省按物理核口径钉 worker 数（本机 44；44 vs 12 的 A/B 证伪了
    # 「降并行」，取证见 gradle_worker_count 注释），不靠调用方记得透传
    # ERATW_GRADLE_EXTRA_ARGS。显式给了 --max-workers 的以显式为准。
    # 出包期间同机并行跑别的 Gradle 构建会让 1 GiB 资源链与全量编译抢磁盘
    # （实测 8m → 20m41s）；这里只提醒、绝不自动 --stop —— 项目里被别的 lane 的
    # gradlew --stop 打断过三次，停共享 daemon 是会伤到别人构建的破坏性动作。
    print("[pack] 提示：出包期间请勿同机并行 Gradle 构建；"
          "1 GiB 资源链与全量编译争抢会把 8 分钟拖到 20 分钟。", file=sys.stderr)
    extra_args = parse_gradle_extra_args()
    if not any(a.startswith("--max-workers") for a in extra_args):
        tasks.append(f"--max-workers={gradle_worker_count()}")
    # 构建标识：把「这个 APK 出自哪个提交」编进 BuildConfig，错误报告头部会带出来。
    # 2026-10-28 的教训：一小时内三个 0.2.0 包（缺权限的 / 缺重启修复的 / 都带上的）
    # versionName 完全一样，玩家报来的错根本分不清是哪个包 —— 只能靠人回忆。
    tasks.append(f"-PeratwBuildStamp={git_head()}")
    # 程序条目压缩口径（STORAGE-1）：缺省带 -PeratwErbcCompress（erbc/meta 走 DEFLATE，
    # APK 瘦 ~366 MiB；解包产物逐字节不变——判重拷回落与版本漂移重拷见 GameAssetInstaller）。
    # --erbc-stored 回到旧口径（STORED；给真机 A/B 与事故回退留的路）。
    if not erbc_stored and not skip_assets:
        tasks.append("-PeratwErbcCompress")
    if skip_assets:
        tasks.append("-PeratwSkipAssets=1")
    elif game_root:
        tasks.append(f"-PeratwGameRoot={game_root}")
    if endpoint:
        tasks.append(f"-PeratwReportEndpoint={endpoint}")
    if token:
        tasks.append(f"-PeratwReportToken={token}")
    # 引擎配置模式（ENGC-6 / B6-PACK）：零干扰早退收拢在 engine_gradle_args 内——
    # engine_config 为 None（无 --engine-config）时它返回 []，gradle 参数序列与改动前
    # 逐字一致（I7 零干扰，基线对拍见 devtools/test_engine_compat.py）；
    # 删掉那道早退守卫 ⇒ 无配置路径当场崩（反向证明①钉住此行为）。
    # 第三方语料根（ENGC-6 构建期通道）：显式声明这批随包语料不是 devtools/corpus-patches 的
    # eraTW 根 ⇒ 编译面与 staging 面整表不适用（不套补丁，也不因「游戏根缺 CSV/_Rename.csv」
    # 这类 eraTW 专属目标缺席而判红）。缺省 False ⇒ 不追加，参数序列与引入本开关前逐字一致（I7）。
    if foreign_corpus:
        tasks.append("-PeratwForeignCorpus=1")
    # 预置 qol_data.db（跳过首次建图）：只在脚本侧指纹对账通过后传入；空串 ⇒ 参数序列与
    # 引入本功能前逐字一致（I7 零干扰）。必须绝对路径：gradle 侧按各 cwd 解释相对路径会分叉。
    if seed_qol_db:
        tasks.append(f"-PeratwSeedQolDb={seed_qol_db}")
    tasks += engine_gradle_args(engine_config)
    # 壳层品牌配置（SHELL-PROFILE）：无 --shell-profile 时 shell_config=None ⇒ 投影空表，
    # gradle 参数序列与改动前逐字相同；有配置时把 app label / 加载页 / 教程模式烙进资源。
    tasks += shell_profile.gradle_args(shell_config)
    # 透传 ERATW_GRADLE_EXTRA_ARGS：放在任务名之后（Gradle 允许全局选项在任务名后），
    # 让主人能追加 --profile 生成 task 级耗时报告；空值/未设置完全不追加。
    tasks += extra_args
    # 更新源**不走 -P 参数**：分隔符 `|` 在 cmd 里是管道符，而 Windows 下 gradlew 必须经
    # `cmd /c` 拉起（实测同一个参数被拆成「管道交给 https:」，退出码 255）。
    # 所以这条走环境变量，gradle 侧读 ERATW_UPDATE_MIRRORS（-P 仍可用，只是不该由脚本在 cmd 下带）。
    env = toolchain_env()
    if mirrors:
        env[ENV_MIRRORS] = mirrors
    cmd = gradle_cmd(tasks)
    printable = " ".join(cmd).replace(token, "***") if token else " ".join(cmd)
    print(f"[pack] {printable}")
    if mirrors:
        print(f"[pack] （更新源经环境变量 {ENV_MIRRORS} 传入：{mirrors}）")
    if dry_run:
        print("[pack] --dry-run：只打印命令，不构建、不体检。")
        return 0
    started = time.time()
    code, tail = run_build_streaming(cmd, env)
    if code != 0 and FORK_KILLED_MARK in tail and "--no-daemon" not in cmd:
        # 机器级现象，不是本工程的编译错误：共享 daemon 的子进程被宿主杀（见函数 KDoc）。
        # 自动换 --no-daemon 重跑，别让出包方对着「零输出的非零退出」猜半小时。
        print("[pack] 检测到 fork 子进程被宿主杀掉（Gradle 报 exit value 268435466 = 0x1000000A，"
              "子进程零输出）：共享 gradle daemon 环境已污染 —— 本机实测同一 daemon 的孩子必死、"
              "--no-daemon 正常。自动以 --no-daemon 重跑一轮（本轮不算失败）。", file=sys.stderr)
        code, tail = run_build_streaming(cmd + ["--no-daemon"], env)
    print(f"[pack] 构建耗时 {time.time() - started:.0f}s，退出码 {code}")
    return code


def preflight_erbc_digest() -> list[str]:
    """构建前 CMP-66 预检：把「编译器或语料补丁变了但 erbc 没重编」在半秒内挡下来。

    旧流程只有 Gradle 跑到 `stageGameAssets` 才会把 compiler-digest 与随包清单对账，
    撞上就是一整轮构建白跑、重编 `:tools:erbcc:compileCorpus`、再出一次包。
    探针与 `ErbcFreshness.kt` / `ErbcCompilerDigest.kt` 同口径，算法单源在
    `devtools/erbc_freshness_probe.py`；读不出判据时 fail-closed，不静默放行。
    """
    try:
        import erbc_freshness_probe
    except Exception as exc:  # 探针缺失/导入失败：宁可挡下来，也不出一个可能被硬闸拒的包
        return [f"erbc 新鲜度探针不可用：{exc}"]

    erbc_dir = os.environ.get("ERATW_ERBC_DIR", "").strip()
    manifest = (Path(erbc_dir) / "manifest.txt" if erbc_dir
                else Path(APP_ROOT) / erbc_freshness_probe.MANIFEST_REL)
    if erbc_dir and not manifest.is_file():
        manifest = Path(APP_ROOT) / erbc_freshness_probe.MANIFEST_REL
    # 校验对象写进每一条结论：`ERATW_ERBC_DIR` 会盖掉缺省目录（app/build.gradle.kts:127-139 同序：
    # -PeratwErbcDir > ERATW_ERBC_DIR > 兜底），本机实测该变量曾被别的 lane 留在环境里指向别处产物，
    # 不打印出来就分不清「校验的是我要出的这只包」还是「别人的产物」。
    src = f"校验对象 {manifest}"

    r = erbc_freshness_probe.probe(Path(APP_ROOT), manifest)
    if not r["ok"]:
        return [f"读不出 erbc 新鲜度判据：{r['error']}"]
    # 两个维度分开判、分开给处置：合成一条只会说「不符」，出包方分不清是编译器变了还是补丁变了。
    if not r.get("compiler_match", r["match"]):
        return [
            "随包 erbc 不是当前编译器源码的产物（compiler-digest 不符）："
            f"当前 {r['current'][:16]}… / 清单 {(r['manifest_digest'] or '')[:16]}…（{src}）",
            "先跑 ./gradlew --configure-on-demand :tools:erbcc:compileCorpus "
            "-PcompileMode=compat -PallowParseFailures=devtools/erbc-degraded-allowlist.tsv "
            "再出包；或直接用 devtools/pack_one/eratw-pack.exe（自动判重编）。",
        ]
    # 补丁资产指纹（2026-11-20 事故的根因闸）：只改 devtools/corpus-patches 的提交既不动 .kt
    # 树、也不动语料 ERB mtime，旧复用判据会判「新鲜」而补丁根本不进 erbc；这里与 gradle 侧
    # ErbcFreshness（1c 段）同口径，构建前 0.5 秒挡下来，不让整轮构建白跑。
    if not r.get("patches_match", True):
        man = r.get("patches_manifest_digest")
        man_disp = (man[:16] + "…") if man else "（manifest 无该留痕行：旧编译器产物）"
        return [
            "随包 erbc 不是当前语料补丁资产的产物（corpus-patches-digest 不符）："
            f"当前 {(r.get('patches_current') or '')[:16]}… / 清单 {man_disp}（{src}）",
            "devtools/corpus-patches 下任一 .patch / index.yaml 改动后必须重编 "
            ":tools:erbcc:compileCorpus，否则补丁不会进 erbc（0.2.48x 的料理补丁就是这样静默失效的）。",
        ]
    print(f"[pack] 构建前 erbc 新鲜度预检通过：compiler-digest {r['current'][:16]}… / "
          f"corpus-patches {r['patches_current'][:16]}…（{r['sources']} 个 .kt / "
          f"{r['patches_files']} 个补丁资产 / {r['elapsed_ms']} ms；{src}）")
    return []


# ---------------------------------------------------------------- 体检

# APK 条目的模块级缓存：key 含 (条目名, st_mtime_ns, st_size)，同一次出包里重复读同一条目
# 不再重开 zip；下次构建覆盖 APK 后 mtime/size 一变键就失效，不会读到旧包的内容。
# 不用 lru_cache：APK 会被原地覆盖，纯函数式缓存不会自动失效。
# namelist 单独一张表：早先与条目共用一张表、只靠一个魔法字符串键区分，万一 APK 里真有
# 同名条目，read_apk_entry 会拿到 list 而不是 bytes。
_APK_ENTRY_CACHE: dict = {}
_APK_NAMELIST_CACHE: dict = {}


def apk_entry_cache_key() -> tuple | None:
    """缓存键 = (APK st_mtime_ns, APK st_size)；stat 不到 APK 时返回 None（不缓存）。"""
    try:
        st = os.stat(APK_PATH)
    except OSError:
        return None
    return (st.st_mtime_ns, st.st_size)


def read_apk_entry(name: str) -> bytes:
    """读 APK 内条目并缓存 bytes；KeyError / BadZipFile 照常抛，交给调用方按原样兜底。"""
    stamp = apk_entry_cache_key()
    key = (name, stamp) if stamp is not None else None
    if key is not None and key in _APK_ENTRY_CACHE:
        return _APK_ENTRY_CACHE[key]
    with zipfile.ZipFile(APK_PATH) as zf:
        data = zf.read(name)
    if key is not None:
        _APK_ENTRY_CACHE[key] = data
    return data


def read_apk_namelist() -> list:
    """读 APK 的 namelist，按同一套 (mtime_ns, size) 键缓存；BadZipFile 照常抛。"""
    key = apk_entry_cache_key()
    if key is not None and key in _APK_NAMELIST_CACHE:
        return _APK_NAMELIST_CACHE[key]
    with zipfile.ZipFile(APK_PATH) as zf:
        names = zf.namelist()
    if key is not None:
        _APK_NAMELIST_CACHE[key] = names
    return names


def verify_res_values(endpoint: str, token: str, mirrors: str = "") -> list:
    """AGP 中间产物：resValue 是否按传入值落盘（参数生效的直接证据）。"""
    if not os.path.isfile(RES_VALUES):
        return [f"找不到 resValues 中间产物：{RES_VALUES}（构建没跑到 generateReleaseResValues？）"]
    with open(RES_VALUES, "r", encoding="utf-8") as fh:
        text = fh.read()

    def pick(name: str) -> str:
        m = re.search(rf'<string name="{name}"[^>]*>(.*?)</string>', text, re.S)
        return m.group(1) if m else ""

    problems = []
    got_ep, got_tok = pick("eratw_report_endpoint"), pick("eratw_report_token")
    if not got_ep:
        problems.append("resValues 里 eratw_report_endpoint 为空（出包时没带 -PeratwReportEndpoint）")
    elif got_ep != endpoint:
        problems.append(f"resValues 端点与预期不符：{got_ep!r} != {endpoint!r}")
    if not got_tok:
        problems.append("resValues 里 eratw_report_token 为空（出包时没带 -PeratwReportToken）")
    elif got_tok != token:
        problems.append("resValues 令牌与预期不符（换过令牌？还是构建用了旧缓存）")
    # 更新源：空串在这里是**合法值**（刻意的不内置源），只有「传了却没落盘」才算不合格。
    got_mirrors = pick("eratw_update_mirrors")
    if mirrors and got_mirrors != mirrors:
        problems.append(f"resValues 更新源与预期不符：{got_mirrors!r} != {mirrors!r}"
                        "（没吃到 -PeratwUpdateMirrors？）")
    if not mirrors and got_mirrors:
        problems.append(f"resValues 里有镜像但本次没传 --mirror：{got_mirrors!r}（构建用了旧缓存？）")
    return problems


def verify_apk(endpoint: str, token: str, mirrors: str = "") -> list:
    """APK 内的 resources.arsc：几个串真的进了包（进包的直接证据）。"""
    if not os.path.isfile(APK_PATH):
        return [f"找不到 APK：{APK_PATH}"]
    try:
        arsc = read_apk_entry("resources.arsc")
    except (KeyError, zipfile.BadZipFile) as exc:
        return [f"读不出 APK 里的 resources.arsc：{exc}"]
    problems = []
    if endpoint and endpoint.encode("utf-8") not in arsc:
        problems.append(f"APK 的 resources.arsc 里找不到端点串：{endpoint}")
    if token and token.encode("utf-8") not in arsc:
        problems.append("APK 的 resources.arsc 里找不到令牌串（上报会整体关闭）")
    # 逐条查：`|` 拼成一条字符串存进 arsc，但任一条地址被截断都会让那个镜像哑掉且无人知晓。
    for url in [u for u in mirrors.split("|") if u]:
        if url.encode("utf-8") not in arsc:
            problems.append(f"APK 的 resources.arsc 里找不到镜像串：{url}（这个包不会走该源更新）")
    return problems


# ---------------------------------------------------------------- APK 结构完整性（安装解析面）
#
# 「解析安装包错误」是 PackageManager 在 PackageParser 阶段的**笼统兜底**文案：Manifest 读不出、
# ZIP 中央目录缺失（下载截断）、v2/v3 签名方案缺失、resources.arsc 被压缩或未 4 字节对齐，
# 都只报这一句，屏上拿不到失败码（真机要看 adb install 的 INSTALL_PARSE_FAILED_* 或 logcat）。
#
# 本工程的正式包约 685 MiB、走网盘/聊天工具手工分发，玩家侧副本被截断或改后缀是高频诱因。
# 判读要点：**同一个 APK 在另一台设备上能装能跑，就证明 minSdk / targetSdk / 签名证书 /
# ABI / manifest 属性这些包级属性全部合法** —— 它们对每台设备一视同仁；剩下的差异只可能在
# 设备侧那一份字节（或该设备的安装来源、存储位置、剩余空间）。所以发布侧必须给出可供玩家
# 逐字比对的「字节数 + sha256」，并在出包时先自证这个包有被解析的资格。
#
# 只做结构与头部检查，不解压 685 MiB 的随包条目（全量 CRC 会让每次出包多花几十秒）。

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIG_V2_ID = 0x7109871A
APK_SIG_V3_ID = 0xF05368C0
APK_SIG_V31_ID = 0x1B93AD61
EOCD_SIGNATURE = b"PK\x05\x06"
LOCAL_HEADER_SIGNATURE = b"PK\x03\x04"


def _eocd_central_dir_offset(path: str, size: int) -> int | None:
    """读 EOCD 拿中央目录偏移；尾部不是 EOCD 或 ZIP64 形态返回 None，由调用方判读。"""
    if size < 22:
        return None
    with open(path, "rb") as fh:
        fh.seek(size - 22)
        eocd = fh.read(22)
    if len(eocd) != 22 or eocd[:4] != EOCD_SIGNATURE:
        return None
    cd_offset = struct.unpack_from("<I", eocd, 16)[0]
    # 0xFFFFFFFF = ZIP64 占位；本工程 <4 GiB 且条目数远小于 65535，不该出现，
    # 出现了就别拿一个错误偏移去猜签名块（当作「检查不了」而不是「没问题」）。
    return None if cd_offset == 0xFFFFFFFF else cd_offset


def _signing_scheme_ids(path: str, cd_offset: int) -> set[int] | None:
    """从中央目录前找 APK Signing Block，返回其中的方案 ID 集合；找不到块返回 None。

    块形（ApkSigningBlockUtils）：uint64 size + N × (uint64 pairLen + uint32 id + value)
    + uint64 size + 16B magic；紧贴在中央目录之前。
    """
    if cd_offset < 32:
        return None
    with open(path, "rb") as fh:
        fh.seek(cd_offset - 24)
        footer = fh.read(24)
        if len(footer) != 24 or footer[8:24] != APK_SIG_BLOCK_MAGIC:
            return None
        block_size = struct.unpack_from("<Q", footer, 0)[0]
        block_start = cd_offset - block_size - 8
        if block_start < 0:
            return None
        fh.seek(block_start)
        if fh.read(8) != struct.pack("<Q", block_size):
            return None
        ids: set[int] = set()
        pos = block_start + 8
        end = cd_offset - 24
        # 每个 ID-value 对的头是 uint64 pairLen + uint32 id = 12 字节；
        # pairLen 记的是「4 字节 id + value」的长度，不含这 8 字节的 pairLen 自身。
        while pos + 12 <= end:
            fh.seek(pos)
            pair_head = fh.read(12)
            if len(pair_head) != 12:
                break
            pair_len, pair_id = struct.unpack_from("<QI", pair_head, 0)
            if pair_len < 4 or pos + 8 + pair_len > end:
                break
            ids.add(pair_id)
            pos += 8 + pair_len
        return ids


def verify_apk_integrity() -> list:
    """第 3.5 步：APK 自己有没有「被 Android 解析」的资格 + 给玩家的对账读数。

    查五件事，全部只靠 ZIP 头与签名块（不解压随包条目）：
      1. 文件尾 EOCD 在不在（截断的包一眼可见）；
      2. 中央目录可读、关键条目齐（AndroidManifest.xml / resources.arsc / classes*.dex）；
      3. 每个条目的本地头签名与偏移有效（拼接错位 / 二次打包改坏）；
      4. `resources.arsc` STORED 且数据起点 4 字节对齐（targetSdk≥30 的硬要求）；
      5. APK Signing Block 里有 v2/v3 方案（Android 11+ 不再接受纯 v1 JAR 签名）。

    无论是否 ``--offline`` 都跑：这与上报/更新通道无关，是包本身的事。
    """
    if not os.path.isfile(APK_PATH):
        return [f"找不到 APK：{APK_PATH}"]
    size = os.path.getsize(APK_PATH)
    # 手工分发的玩家侧副本要能逐字对账：这两项就是「同一份字节」的判据。
    print(f"[pack] APK 字节数 {size:,} B · 计算 sha256 …")
    print(f"[pack]       sha256 {apk_digest()}")

    cd_offset = _eocd_central_dir_offset(APK_PATH, size)
    if cd_offset is None:
        return [
            "APK 尾部找不到合法 EOCD（ZIP 中央目录记录缺失或 ZIP64 形态）——"
            "典型是下载/传输被截断，系统安装器只会含糊地报「解析安装包错误」。"
            "请重新取包，并让玩家比对上面的字节数与 sha256。",
        ]

    try:
        with zipfile.ZipFile(APK_PATH) as zf:
            infos = zf.infolist()
    except (zipfile.BadZipFile, OSError) as exc:
        return [f"APK 的 ZIP 中央目录读不出来：{exc}（包被截断或被二次打包改坏）"]

    problems: list = []
    names = {info.filename for info in infos}
    for required in ("AndroidManifest.xml", "resources.arsc"):
        if required not in names:
            problems.append(f"APK 缺 {required} —— PackageParser 必然报「解析安装包错误」")
    if not any(n.startswith("classes") and n.endswith(".dex") for n in names):
        problems.append("APK 里没有任何 classes*.dex")

    with open(APK_PATH, "rb") as fh:
        for info in infos:
            if info.header_offset < 0 or info.header_offset + 30 > size:
                problems.append(
                    f"条目 {info.filename} 的本地头偏移越出文件尾（包被截断或中央目录与数据错位）"
                )
                break
            fh.seek(info.header_offset)
            head = fh.read(30)
            if len(head) != 30 or head[:4] != LOCAL_HEADER_SIGNATURE:
                problems.append(f"条目 {info.filename} 的本地头签名不对（包被改写/拼接错位）")
                break
            if info.filename == "resources.arsc":
                name_len, extra_len = struct.unpack_from("<HH", head, 26)
                data_start = info.header_offset + 30 + name_len + extra_len
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(
                        "resources.arsc 被压缩：targetSdk≥30 的 APK 要求它 STORED 且 4 字节对齐，"
                        "否则 Android 11+ 解析阶段直接失败"
                    )
                elif data_start % 4 != 0:
                    problems.append(
                        f"resources.arsc 数据起点 {data_start} 未 4 字节对齐（Android 11+ 解析失败）"
                    )

    scheme_ids = _signing_scheme_ids(APK_PATH, cd_offset)
    if scheme_ids is None:
        problems.append(
            "APK 没有 APK Signing Block（v2/v3 签名缺失）：Android 11+ 对 targetSdk≥30 的包"
            "不再接受纯 v1 JAR 签名，会报 INSTALL_PARSE_FAILED_NO_CERTIFICATES"
        )
    elif not scheme_ids & {APK_SIG_V2_ID, APK_SIG_V3_ID, APK_SIG_V31_ID}:
        problems.append(
            "APK Signing Block 里没有 v2/v3 签名方案（只有 "
            f"{sorted(hex(i) for i in scheme_ids)}），高版本 Android 会拒装"
        )

    if not problems:
        schemes = "v2" if scheme_ids and scheme_ids & {APK_SIG_V2_ID} else "v3+"
        print(
            f"[pack] APK 结构完整：{len(infos)} 条目 · 本地头全有效 · "
            f"resources.arsc STORED+4B 对齐 · {schemes} 签名块在位"
        )
        print(
            "[pack] 玩家报「解析安装包错误」时先对账上面两项：字节数 / sha256 不一致 = "
            "设备侧副本被截断或改名，与包内 minSdk/targetSdk/签名/ABI 无关（另一台设备能装即已证明）"
        )
    return problems


def verify_update_permission(mirrors: str) -> list:
    """内置了镜像就必须有拉起安装器的权限：缺它时整包更新在真机上只能手动装。

    与 `verify_internet_permission` 同一条教训 —— manifest 少一行，功能在真机上静默失效，
    而编译与 arsc 体检全绿。
    """
    if not mirrors:
        return []
    if not os.path.isfile(MERGED_MANIFEST):
        return [f"找不到合并后的 manifest：{MERGED_MANIFEST}（构建没跑到 processReleaseMainManifest？）"]
    with open(MERGED_MANIFEST, "r", encoding="utf-8") as fh:
        text = fh.read()
    problems = []
    if "android.permission.REQUEST_INSTALL_PACKAGES" not in text:
        problems.append("合并后的 manifest 没有 REQUEST_INSTALL_PACKAGES —— 整包更新装不上")
    if "android.permission.ACCESS_NETWORK_STATE" not in text:
        # 2026-11-03 玩家报「连 Wi-Fi 也说不是 Wi-Fi」：判网络类型那两个调用官方 apidoc 都写明
        # Requires ACCESS_NETWORK_STATE，缺它 = 真机上永远取不到默认网络 = Wi-Fi 玩家被当成蜂窝。
        problems.append("合并后的 manifest 没有 ACCESS_NETWORK_STATE —— 判不出 Wi-Fi，"
                        "连着无线的玩家也会被提示「当前不是 Wi-Fi」")
    # authority 跟随 applicationId（第三方包有自己的包名），从合并清单里读实际 package，
    # 不再钉 eraTW 的字面量：钉死会让第三方包在「包名已分开」后误判为缺 Provider。
    pkg_match = re.search(r'package="([^"]+)"', text)
    pkg = pkg_match.group(1) if pkg_match else ""
    if not pkg or f'android:authorities="{pkg}.updatefiles"' not in text:
        problems.append(f"合并后的 manifest 没有 {pkg or '<package>'}.updatefiles "
                        "UpdateFileProvider —— APK 交不给系统安装器")
    return problems


def read_package_report() -> dict:
    """读 APK 里 assets/erbc.v1/package.report（出包自述），返回 key→value。"""
    if not os.path.isfile(APK_PATH):
        return {}
    try:
        raw = read_apk_entry("assets/erbc.v1/package.report").decode("utf-8", "replace")
    except (KeyError, zipfile.BadZipFile):
        return {}
    out = {}
    for line in raw.splitlines():
        if " " in line:
            key, value = line.split(" ", 1)
            out[key.strip()] = value.strip()
    return out


def read_apk_asset(name: str):
    """读 APK 内 assets 下的文本条目；缺失/坏包返回 None。"""
    if not os.path.isfile(APK_PATH):
        return None
    try:
        return read_apk_entry(name).decode("utf-8", "replace")
    except (KeyError, zipfile.BadZipFile):
        return None


# ---------------------------------------------------------------- 出包自述：语料补丁独立列
# cb001 接线①。随包 manifest.txt 里 buildSrc 写的是逐条 `corpus-patch` 留痕行；这里在
# package.report 自述面另立一个独立列，值从那些真实留痕行逐项生成（id + 病灶/修后 sha +
# 留痕来源），出包期再回读与产物声明值逐项对齐。形状照脚本既有 manifest 列风格：单行
# `key value`，多项用 ` | ` 分隔，每项与留痕行同字段，但不塞回留痕行的文本里。

def _parse_patch_fields(raw: str) -> dict:
    """把一条 `<id> <target> <bad-sha> <fixed-sha>` 拆成逐项记录。

    真语料 target 含空格（如 `051 Nitori [にとり]/...`），故按「头一个 id + 尾两个
    64 位小写 hex sha + 中间全当 target」拆，不按空格数硬切。坏形状 raise ValueError
    （窄 schema 宁红不猜，照 devtools/corpus-patches/index.yaml 的口径）。
    """
    parts = raw.split()
    if len(parts) < 4:
        raise ValueError(f"语料补丁留痕字段不足 4 段：{raw!r}")
    patch_id, bad_sha, fixed_sha = parts[0], parts[-2], parts[-1]
    target = " ".join(parts[1:-2])
    if not re.fullmatch(r"[0-9a-f]{64}", bad_sha) or not re.fullmatch(r"[0-9a-f]{64}", fixed_sha):
        raise ValueError(f"语料补丁留痕的病灶/修后 sha 不是 64 位小写 hex：{raw!r}")
    return {
        "id": patch_id,
        "target": target,
        "bad_sha256": bad_sha,
        "fixed_sha256": fixed_sha,
        "source": CORPUS_PATCH_MARKER + raw,  # 留痕来源：原留痕行文本
    }


def parse_corpus_patch_markers(manifest_text: str) -> list:
    """回读 APK 内 assets/erbc.v1/manifest.txt 的真实字符串：逐条 `corpus-patch` 留痕行
    → 本次实际套用的补丁记录列表（buildSrc CorpusPatches.kt:326 编译期写）。

    非留痕行（source / artifact / degraded 等）一律跳过；坏形状的留痕行 raise ValueError，
    由 applied_corpus_patches_problems 转成非零退出的问题，绝不静默丢一个补丁。
    """
    records = []
    for line in (manifest_text or "").splitlines():
        if line.startswith(CORPUS_PATCH_MARKER):
            records.append(_parse_patch_fields(line[len(CORPUS_PATCH_MARKER):]))
    return records


def format_applied_corpus_patches(records: list) -> str:
    """生成面：逐项补丁记录 → `applied_corpus_patches` 独立列的规范值。

    形状照脚本既有 manifest 列风格：单行值、多项用 ` | ` 分隔，每项
    `<id> <target> <bad-sha> <fixed-sha>`（与留痕行同字段，作为独立列不混进其文本）。
    空 records → 返回 APPLIED_CORPUS_PATCHES_EMPTY（空形状，不是缺列）。
    """
    if not records:
        return APPLIED_CORPUS_PATCHES_EMPTY
    return " | ".join(
        f"{r['id']} {r['target']} {r['bad_sha256']} {r['fixed_sha256']}"
        for r in records
    )


def applied_corpus_patches_column(manifest_text: str) -> str:
    """生成面一步式：APK 内真实留痕文本 → 独立列规范值。"""
    return format_applied_corpus_patches(parse_corpus_patch_markers(manifest_text))


def parse_applied_corpus_patches_value(value: str) -> list:
    """列值 → 逐项记录（format_applied_corpus_patches 的逆），空形状 → 空列表；坏形状
    raise ValueError。"""
    if not value or value == APPLIED_CORPUS_PATCHES_EMPTY:
        return []
    return [_parse_patch_fields(item) for item in value.split(" | ")]


def apply_corpus_patches_column(rep: dict, manifest_text: str) -> dict:
    """出包自述视图：严格档下**只**保留产物自带的 `applied_corpus_patches` 列（返回副本）。

    产物自带该列 → 原样保留产物声明值，交由 applied_corpus_patches_problems 与 APK 内
    manifest.txt 的真实留痕逐项对齐（值不符即判红）。
    产物缺该列 → 由 APPLIED_CORPUS_PATCHES_STRICT 决定：
      * 严格档（默认，接线完成后）：不代生成，原样返回缺列视图 ⇒ 核对面的缺列分支
        在 report_package_kind 端到端路径真正可达 → kind_problems → exit 3。
      * 过渡档（False，仅接线完成前的临时形态）：脚本按真实留痕行代生成列保默认绿，
        见模块顶部该常量的注释；接线完成后必须翻回 True，防止脚本代生成永久化。
    """
    out = dict(rep)
    if APPLIED_CORPUS_PATCHES_KEY in out:
        return out
    if APPLIED_CORPUS_PATCHES_STRICT:
        return out
    try:
        out[APPLIED_CORPUS_PATCHES_KEY] = applied_corpus_patches_column(manifest_text)
    except ValueError:
        # 留痕行形状坏了不在这里猜：给个空形状占位，由 applied_corpus_patches_problems
        # 回读真实字符串时判红（宁红不猜，且保证生成面不崩，问题仍走非零退出）。
        out[APPLIED_CORPUS_PATCHES_KEY] = APPLIED_CORPUS_PATCHES_EMPTY
    return out


def applied_corpus_patches_problems(rep: dict, manifest_text: str) -> list:
    """回读核对（AGENTS §8）：package.report 的 `applied_corpus_patches` 独立列必须与
    APK 内 manifest.txt 的真实 `corpus-patch` 留痕行逐项对齐。

    逐项回读真实字符串算出期望记录，再解析列值得声明记录，按 id 逐项比对病灶/修后 sha
    与目标路径。缺列、值不符（多项 / 少项 / sha 脱节 / 坏形状）一律返回问题 → 调用方汇总
    进 kind_problems → exit 3（照「出完回读核对、不合格非零退出」纪律）。
    """
    try:
        expected = parse_corpus_patch_markers(manifest_text)
    except ValueError as exc:
        return [f"APK 内 manifest.txt 的语料补丁留痕行读不出：{exc}"]
    if APPLIED_CORPUS_PATCHES_KEY not in rep:
        return [f"package.report 缺 {APPLIED_CORPUS_PATCHES_KEY} 独立列："
                "产物侧无法直读本次套用的语料补丁（cb001 接线① 未完成？）"]
    declared_raw = rep[APPLIED_CORPUS_PATCHES_KEY]
    try:
        declared = parse_applied_corpus_patches_value(declared_raw)
    except ValueError as exc:
        return [f"{APPLIED_CORPUS_PATCHES_KEY} 列值形状不合法：{exc}（值={declared_raw!r}）"]
    want = {r["id"]: r for r in expected}
    got = {r["id"]: r for r in declared}
    fields = ("id", "target", "bad_sha256", "fixed_sha256")
    problems = []
    for patch_id in sorted(want.keys() - got.keys()):
        problems.append(f"{APPLIED_CORPUS_PATCHES_KEY} 列漏了实际套用的补丁 {patch_id}")
    for patch_id in sorted(got.keys() - want.keys()):
        problems.append(f"{APPLIED_CORPUS_PATCHES_KEY} 列多报了未套用的补丁 {patch_id}")
    for patch_id in sorted(want.keys() & got.keys()):
        if any(want[patch_id][k] != got[patch_id][k] for k in fields):
            problems.append(
                f"{APPLIED_CORPUS_PATCHES_KEY} 列里 {patch_id} 的病灶/修后 sha 或目标路径"
                f"与 manifest.txt 真实留痕不符：{got[patch_id]} != {want[patch_id]}"
            )
    return problems


def report_degraded_corpus(allow: bool = False) -> list:
    """读随包 manifest 的 `degraded-slices` 段：兼容档编译出的包里有「死函数」。

    这些函数在兼容档里被编成入口 `THROW` 桩：真机上被调用时立刻抛「函数装载出错」
    （可被 TRYC 捕获），不会静默返回默认值。但正常玩法路径上仍是「死函数」，屏上只在
    错误屏/日志里可见。所以清单跟着 APK 走，出包最后一步把数量喊出来；默认**拒绝出包**，
    只有降级清单在册、且确系「上游无法修」的第三方口上失败时才加 `--allow-degraded-corpus`（2026-11-14 起在册 0 条，正常出包不带此开关）。
    """
    text = read_apk_asset("assets/erbc.v1/manifest.txt")
    if text is None:
        # 没有随包 manifest 的包（--skip-assets）由 report_package_kind 负责，这里不重复报。
        return []
    count = 0
    details = []
    for line in text.splitlines():
        if line.startswith("degraded-slices "):
            try:
                count = int(line.split()[1])
            except (IndexError, ValueError):
                count = -1
        elif line.startswith("degraded "):
            details.append(line[len("degraded "):])
    if count == 0:
        return []
    print(f"[pack] 随包语料含降级切片（兼容档编译），清单见 assets/erbc.v1/manifest.txt")
    for d in details[:10]:
        print(f"[pack]   - {d}")
    if len(details) > 10:
        print(f"[pack]   … 其余 {len(details) - 10} 条见 manifest")
    if allow:
        return []
    return [
        f"随包 erbc 有 {max(count, len(details))} 条降级切片：对应函数被编成入口 THROW 桩，"
        "调用时抛「函数装载出错」（不会静默返回）。先修源文件并回到严格档重编；确知来自"
        "不许改源的第三方口上，才可加 --allow-degraded-corpus。",
    ]


def has_bundled_erbc_entries() -> bool:
    """APK 里有没有 `assets/erbc.v1/` 条目（`--skip-assets` 的包按设计一条都没有）。"""
    if not os.path.isfile(APK_PATH):
        return False
    try:
        return any(name.startswith("assets/erbc.v1/") for name in read_apk_namelist())
    except zipfile.BadZipFile:
        return False


def erd_bundling_problems(rep: dict) -> list:
    """随包 ERD 表体检：`package.report` 的 entries-ERB 必须与游戏根 ERB 下的 .ERD 实际条数一致。

    表是运行期数据（原版在 ERB 目录递归扫 .ERD 建用户变量关键字表，spec: ErhLoader.cs:342-365
    `PrepareERDFileNames`；本侧 `UserKeywordTables.discover`）。缺表 ⇒
    `SEMEN_MATRIX:角色:x:子宫内射精量` 这类三段下标运行期报「不能用字符串指定
    （未找到 ERD 表 …）」直接 CodeEE（报告 1793688405639，2026-11-03 14:46，app 0.2.39）。
    de0017a9 给 stageGameAssets 补了 2b 段（只随包 .ERD），但**出包侧没人回读它**——
    2b 段一旦回退（改 task、换根、ERB 目录没命中），包照样全绿出厂。这一条把
    「报告数 vs 磁盘数」钉成硬体检；游戏根为空（不随资源 / --skip-assets）由
    [report_package_kind] 既有判据负责，这里不重复。
    """
    root = rep.get("game-root", "-")
    if not root or root == "-":
        return []
    erb_dir = os.path.join(root, "ERB")
    expected = 0
    if os.path.isdir(erb_dir):
        for dirpath, dirnames, filenames in os.walk(erb_dir):
            expected += sum(1 for f in filenames if f.upper().endswith(".ERD"))
    raw = rep.get("entries-ERB")
    if raw is None:
        return [
            f"package.report 没有 entries-ERB 行：stageGameAssets 的 2b 段（ERB 的 .ERD 表随包）"
            f"没跑到或未落报表。检查 {erb_dir} 是否存在、任务是否回退（缺表 = 三段下标运行期 CodeEE）。"
        ]
    try:
        staged = int(raw)
    except ValueError:
        return [f"package.report 的 entries-ERB 读不出数字：{raw!r}"]
    if staged != expected:
        return [
            f"随包 ERD 表 {staged} 条 vs 游戏根 {erb_dir} 实际 {expected} 条不一致"
            "（表没进全 = 经本包的设备用户变量三段下标运行期 CodeEE；4.981 语料实形 3 张："
            "Item / COLUMNDIV@2 / SEMEN_MATRIX@2）。",
        ]
    if expected == 0:
        # 差额判据已保证「包 == 根」；零表根自洽但姿势可疑，大声提醒不判红
        # （4.981 起随包根必有表，见 emit_update.py 的 erd_gate 同款提示）。
        print(f"[pack] 提示：游戏根 {erb_dir} 下一张 .ERD 都没有（4.981 语料实形为 3 张）——"
              "确认这不是换代后的空壳根。")
    return []


# ---------------------------------------------------------------- RT2 内置 mod manifest 出包体检
# RT2-B③：内置 Kotlin 载体的 manifest 是 app 模块 assets 下的静态资产，解包器把它投递到
# 游戏根的 mods/rt2 后，ModRuntimeAssembly 才装得上。缺档 / 装了旧字节 / 包名对不上注册表
# 都是「开闸也装 0 个 mod」的同一种静默失败，出包期必须当场判红，不能等真机日志。
RT2_BUILTIN_ASSET_PREFIX = "assets/mods/rt2/"
# manifest 线格式版本阶梯（权威锚 = core-vm patch/ManifestCodec.kt 的 encode/decode
# hasGrants 阶梯：/3 基础形，/4 meta 5 值，/5 settings，/6 min-api，/7
# declaration-positions，/8 write-grants；/1 /2 解码首行即拒载）。出包体检与
# 运行期解码器接受同一个集合，不单独钉死某一个版本。
RT2_MANIFEST_MAGICS = tuple(f"RT2MANIFEST/{n}" for n in range(3, 9))
RT2_MANIFEST_SUFFIX = ".manifest"
# RT2-ERBM：ERB 模块容器（.erbm）体检面。头部布局 = ErbModuleContainer.encode：
# magic 5B `ERTM1` + version u16 + flags u16（DataOutputStream 口径，全部 big-endian）
# + 记录区；尾 32B = SHA-256(body)。档位位 0x1 = allowSuperCalls（Mod 包编译档）。
RT2_ERBM_SUFFIX = ".erbm"
RT2_ERBM_MAGIC = b"ERTM1"
RT2_ERBM_VERSION = 1
RT2_ERBM_HEADER_FIXED_BYTES = 9
RT2_ERBM_DIGEST_BYTES = 32
RT2_ERBM_KNOWN_FLAGS = (0, 1)
DEMO_MODULE_BUILD_FILE = os.path.join(
    APP_ROOT, "tools", "modgen", "src", "main", "kotlin", "com", "eratw", "port",
    "modgen", "DemoModuleBuild.kt",
)
FAMILY_DECLARATIONS_FILE = os.path.join(
    APP_ROOT, "mod-family", "src", "main", "kotlin", "com", "eratw", "port",
    "modfamily", "FamilyDeclarations.kt",
)
MEMORY_DECLARATIONS_FILE = os.path.join(
    APP_ROOT, "mod-memory", "src", "main", "kotlin", "com", "eratw", "port",
    "modmemory", "MemoryDeclarations.kt",
)
# M10-1：Living World Demo 四包声明真源（eratw.lw.*，全部在 :mod-livingworld 一个模块里）。
LW_DECLARATIONS_DIR = os.path.join(
    APP_ROOT, "mod-livingworld", "src", "main", "kotlin", "com", "eratw", "port",
    "modlivingworld",
)
LW_CHARACTER_DECLARATIONS_FILE = os.path.join(LW_DECLARATIONS_DIR, "CharacterDeclarations.kt")
LW_RELATIONSHIP_DECLARATIONS_FILE = os.path.join(LW_DECLARATIONS_DIR, "RelationshipDeclarations.kt")
LW_MEMORY_DECLARATIONS_FILE = os.path.join(LW_DECLARATIONS_DIR, "MemoryDeclarations.kt")
LW_EVENT_DECLARATIONS_FILE = os.path.join(LW_DECLARATIONS_DIR, "EventDeclarations.kt")
# 载体注册表引用对账表：声明真源文件 → ModCarrierRegistry 里必须出现的引用符号。
CARRIER_DECLARATION_SOURCES = (
    (FAMILY_DECLARATIONS_FILE, "FamilyDeclarations.PACKAGE_ID"),
    (MEMORY_DECLARATIONS_FILE, "MemoryDeclarations.PACKAGE_ID"),
    (LW_CHARACTER_DECLARATIONS_FILE, "CharacterDeclarations.PACKAGE_ID"),
    (LW_RELATIONSHIP_DECLARATIONS_FILE, "RelationshipDeclarations.PACKAGE_ID"),
    (LW_MEMORY_DECLARATIONS_FILE, "LwMemoryDeclarations.PACKAGE_ID"),
    (LW_EVENT_DECLARATIONS_FILE, "EventDeclarations.PACKAGE_ID"),
)
MOD_CARRIER_REGISTRY_FILE = os.path.join(
    APP_ROOT, "app", "src", "main", "kotlin", "com", "eratw", "port", "android",
    "ModCarrierRegistry.kt",
)
BUILTIN_ASSET_DIR = os.path.join(APP_ROOT, "app", "src", "main", "assets", "mods", "rt2")


def declared_rt2_package_ids() -> tuple:
    """读声明真源的 PACKAGE_ID，并确认载体注册表仍引用它；返回 (包名表, 问题表)。

    对账链分四段：注册表（源码引用）→ 声明真源（常量值）→ 仓库静态资产（modgen 资产闸
    保证与声明逐字节一致）→ APK 内资产（本函数之外的逐字节核对）。装了旧字节也躲不过。

    RT2-ERBM 起随包三件：family 载体（eratw.family）、memory 载体（eratw.memory，
    M9-1 起第二只 Kotlin 载体）+ demo 模块（eratw.demo，包 id 从 DemoModuleBuild.kt 的
    同名常量 marker 解析——真源常量改名会当场在体检暴露）。
    M10-1 起再压 eratw.lw 四载体（character / relationship / memory / event，
    全部在 :mod-livingworld 一个 Gradle 模块里，各自独立 packageId）。
    注册表核对压全部载体（CARRIER_DECLARATION_SOURCES）：demo 不是载体（无
    ModCarrierRegistry 声明事务），它走 .erbm 容器链，对账面是 rt2_erbm_problems。
    """
    problems = []
    try:
        with open(FAMILY_DECLARATIONS_FILE, encoding="utf-8") as fh:
            source = fh.read()
    except OSError as exc:
        return [], [f"读不到 RT2 载体声明真源 {FAMILY_DECLARATIONS_FILE}：{exc}"]
    marker = 'const val PACKAGE_ID: String = "'
    at = source.find(marker)
    if at < 0:
        return [], ["RT2 载体声明真源里找不到 PACKAGE_ID 常量 —— 出包体检无法对账包名"]
    package_id = source[at + len(marker):].split('"', 1)[0]
    if not package_id:
        return [], ["RT2 载体声明真源的 PACKAGE_ID 是空串"]
    try:
        with open(DEMO_MODULE_BUILD_FILE, encoding="utf-8") as fh:
            demo_source = fh.read()
    except OSError as exc:
        return [package_id], [f"读不到 demo 模块真源 {DEMO_MODULE_BUILD_FILE}：{exc}"]
    at = demo_source.find(marker)
    if at < 0:
        return [package_id], ["demo 模块真源里找不到 PACKAGE_ID 常量 —— 出包体检无法对账包名"]
    demo_id = demo_source[at + len(marker):].split('"', 1)[0]
    if not demo_id:
        return [package_id], ["demo 模块真源的 PACKAGE_ID 是空串"]
    if demo_id == package_id:
        return [package_id], ["demo 模块包名与载体包名撞车（两件套会互相顶替）"]
    # 其余载体（memory + M10-1 的 eratw.lw 四包）照 family 同一对账面逐只走一遍；
    # 任意一只读不到/解析不出即带已收齐的包名表提前返回（fail-closed，不猜）。
    carrier_ids = []
    for source_path, registry_symbol in CARRIER_DECLARATION_SOURCES[1:]:
        try:
            with open(source_path, encoding="utf-8") as fh:
                carrier_source = fh.read()
        except OSError as exc:
            return [package_id, demo_id] + carrier_ids, [f"读不到 RT2 载体声明真源 {source_path}：{exc}"]
        at = carrier_source.find(marker)
        if at < 0:
            return (
                [package_id, demo_id] + carrier_ids,
                [f"载体声明真源里找不到 PACKAGE_ID 常量：{source_path}"],
            )
        carrier_id = carrier_source[at + len(marker):].split('"', 1)[0]
        if not carrier_id:
            return [package_id, demo_id] + carrier_ids, [f"载体声明真源的 PACKAGE_ID 是空串：{source_path}"]
        if carrier_id in (package_id, demo_id) or carrier_id in carrier_ids:
            return (
                [package_id, demo_id] + carrier_ids,
                [f"载体包名 {carrier_id} 与既有包撞车（注册/体检面会互相顶替）：{source_path}"],
            )
        carrier_ids.append(carrier_id)
    try:
        with open(MOD_CARRIER_REGISTRY_FILE, encoding="utf-8") as fh:
            registry = fh.read()
    except OSError as exc:
        return [package_id, demo_id] + carrier_ids, [f"读不到载体注册表 {MOD_CARRIER_REGISTRY_FILE}：{exc}"]
    # 2026-11-16 起登记面收成一处：声明 object 打 @ModCarrier，构建期 :mod-registry 扫
    # mod-* 模块生成 ModRegistry.carriers；ModCarrierRegistry 只消费它、不再点名任何载体
    # （它自己的 KDoc「名单从哪来」节）。体检面因此改对两件事：
    # ① 每个声明真源仍有 @ModCarrier 注解（漏打 = 构建期注册表不收录 = 静默不生效，
    #   正是本段要挡的失败）；② 设备侧装配仍消费 ModRegistry.carriers。
    if "ModRegistry.carriers" not in registry:
        problems.append(
            "ModCarrierRegistry 不再消费 ModRegistry.carriers —— "
            "设备侧装配名单与构建期注册表可能已经分叉，包里的 manifest 会逐 mod fail-closed"
        )
    for source_path, _ in CARRIER_DECLARATION_SOURCES:
        try:
            with open(source_path, encoding="utf-8") as fh:
                carrier_source = fh.read()
        except OSError as exc:
            problems.append(f"读不到 RT2 载体声明真源 {source_path}：{exc}")
            continue
        if "@ModCarrier" not in carrier_source:
            problems.append(
                f"{os.path.basename(source_path)} 的声明 object 没有 @ModCarrier 注解 —— "
                "构建期注册表不会收录它，包里的 manifest 会静默不生效"
            )
    return [package_id] + carrier_ids + [demo_id], problems


def parse_rt2_manifest_package_id(data: bytes) -> tuple:
    """解 RT2 manifest 的 @meta 包名；返回 (包名, 错误文本)，二选一非空。

    首行必须是版本阶梯（RT2MANIFEST_MAGICS，/1 /2 即坏档——设备侧解码器同样拒载）；
    @meta 段首个 S 行恒为 packageId（/3 一值、/4 起五值，首个恒是包名，
    锚 = ManifestCodec.encode 的 w.head("meta") 段序）。坏档不猜、不兼容旧版。
    """
    try:
        lines = data.decode("utf-8").split("\n")
    except UnicodeDecodeError as exc:
        return None, f"manifest 不是合法 UTF-8：{exc}"
    if not lines or lines[0] not in RT2_MANIFEST_MAGICS:
        return None, "首行不是 RT2MANIFEST/3..8 之一（实际「%s」）" % (lines[0] if lines else "")
    for i, line in enumerate(lines):
        if line.strip() != "@meta":
            continue
        if i + 1 >= len(lines):
            return None, "@meta 段没有包名行"
        token = lines[i + 1].strip()
        if not token.startswith("S") or len(token) < 2:
            return None, f"@meta 包名行不是 S 加十六进制：「{token}」"
        raw = token[1:]
        if len(raw) % 2 != 0:
            return None, f"@meta 包名十六进制长度不成对：「{raw}」"
        try:
            return bytes.fromhex(raw).decode("utf-8"), None
        except (ValueError, UnicodeDecodeError) as exc:
            return None, f"@meta 包名解不出 UTF-8：{exc}"
    return None, "manifest 里没有 @meta 段"


def rt2_manifest_problems() -> list:
    """APK 内 assets/mods/rt2 的点 manifest 与仓库静态资产、声明真源逐项对账。"""
    declared, problems = declared_rt2_package_ids()
    try:
        names = read_apk_namelist()
    except (OSError, zipfile.BadZipFile) as exc:
        return problems + [f"读不出 APK 条目清单，RT2 内置 manifest 无法对账：{exc}"]
    entries = sorted(
        n for n in names
        if n.startswith(RT2_BUILTIN_ASSET_PREFIX) and n.endswith(RT2_MANIFEST_SUFFIX)
    )
    if not entries:
        return problems + [
            "APK 里没有 assets/mods/rt2 下的点 manifest —— 开闸也装 0 个 mod"
            "（RT2-B③ 资产未投递；app 模块 assets 静态资产没进包？）"
        ]
    apk_names = {n[len(RT2_BUILTIN_ASSET_PREFIX):] for n in entries}
    disk_names = set()
    if os.path.isdir(BUILTIN_ASSET_DIR):
        disk_names = {n for n in os.listdir(BUILTIN_ASSET_DIR) if n.endswith(RT2_MANIFEST_SUFFIX)}
    else:
        problems.append(f"仓库内置资产目录不存在：{BUILTIN_ASSET_DIR}")
    for name in sorted(disk_names - apk_names):
        problems.append(f"APK 缺内置 RT2 manifest：{name}（包不完整）")
    for name in sorted(apk_names - disk_names):
        problems.append(f"APK 多出来源不明的内置 RT2 manifest：{name}")
    seen = set()
    for entry in entries:
        short = entry[len(RT2_BUILTIN_ASSET_PREFIX):]
        try:
            data = read_apk_entry(entry)
        except (KeyError, zipfile.BadZipFile) as exc:
            problems.append(f"读不出 APK 内 {entry}：{exc}")
            continue
        disk = os.path.join(BUILTIN_ASSET_DIR, short)
        if os.path.isfile(disk):
            with open(disk, "rb") as fh:
                if fh.read() != data:
                    problems.append(f"APK 内 {short} 与仓库静态资产字节不符（装了旧声明？重跑 generateBuiltinRt2Manifest）")
        package_id, err = parse_rt2_manifest_package_id(data)
        if err is not None:
            problems.append(f"APK 内 {short} 是坏档：{err}")
            continue
        if short != package_id + RT2_MANIFEST_SUFFIX:
            problems.append(f"APK 内 {short} 的文件名与 @meta 包名 {package_id} 不符")
        if package_id in seen:
            problems.append(f"APK 内内置 RT2 manifest 包名重复：{package_id}")
        seen.add(package_id)
    if declared and seen != set(declared):
        problems.append(
            f"APK 内内置 manifest 包名 {sorted(seen)} 与声明真源 {declared} 不一致"
            "（载体注册表会逐 mod fail-closed）"
        )
    return problems


def parse_rt2_manifest_artifact_sha256(data: bytes) -> tuple:
    """解 RT2MANIFEST/3 的 @artifact-sha256 段；返回 (摘要串, 错误文本)，二选一非空。

    空串 = 未钉（family 载体如此）；demo manifest 必钉 .erbm 摘要（裁定②）。
    段缺失按未钉处理：段格式本该由运行期 ManifestCodec.decode 严格拒载，
    体检面只对账「钉了的值必须与实物相等」这一个方向。
    """
    try:
        lines = data.decode("utf-8").split("\n")
    except UnicodeDecodeError as exc:
        return "", f"manifest 不是合法 UTF-8：{exc}"
    for i, line in enumerate(lines):
        if line.strip() != "@artifact-sha256":
            continue
        if i + 1 >= len(lines):
            return None, "@artifact-sha256 段没有摘要行"
        token = lines[i + 1].strip()
        if not token.startswith("S"):
            return None, f"@artifact-sha256 摘要行不是 S 加十六进制：「{token}」"
        raw = token[1:]
        if not raw:
            return "", None
        try:
            return bytes.fromhex(raw).decode("utf-8"), None
        except (ValueError, UnicodeDecodeError) as exc:
            return None, f"@artifact-sha256 摘要解不出 UTF-8：{exc}"
    return "", None


def parse_rt2_erbm(data: bytes) -> tuple:
    """解 .erbm 容器头；返回 (package_id, 错误文本)，二选一非空。

    只核出包体检需要的字段（ErbModuleContainer.encode 布局，见 RT2_ERBM_* 常量）：
    尾 32B 摘要复算 / magic / version / 档位位 / packageId。其余记录区字段交给
    运行期 decode 的 fail-closed 全套校验，这里不重造。
    """
    if len(data) < RT2_ERBM_HEADER_FIXED_BYTES + RT2_ERBM_DIGEST_BYTES:
        return None, f".erbm 容器长度不足（{len(data)} 字节）"
    if data[: len(RT2_ERBM_MAGIC)] != RT2_ERBM_MAGIC:
        return None, ".erbm magic 不符（期望 ERTM1；注意 legacy `ERBM` 属 M2-4 转码面）"
    body = data[:-RT2_ERBM_DIGEST_BYTES]
    digest = data[-RT2_ERBM_DIGEST_BYTES:]
    if hashlib.sha256(body).digest() != digest:
        return None, ".erbm 尾部 SHA-256 摘要与正文复算不符（传输损坏 / 被单独替换过）"
    version = struct.unpack(">H", data[5:7])[0]
    if version != RT2_ERBM_VERSION:
        return None, f".erbm 版本不符（期望 {RT2_ERBM_VERSION}，实为 {version}）"
    flags = struct.unpack(">H", data[7:9])[0]
    if flags not in RT2_ERBM_KNOWN_FLAGS:
        return None, f".erbm 含未知档位位 0x{flags:x}（合法集 {RT2_ERBM_KNOWN_FLAGS}）"
    pkg_len = struct.unpack(">i", data[9:13])[0]
    if pkg_len < 0 or 13 + pkg_len > len(body):
        return None, f".erbm packageId 长度越界（{pkg_len}）"
    try:
        package_id = data[13:13 + pkg_len].decode("utf-8")
    except UnicodeDecodeError as exc:
        return None, f".erbm packageId 解不出 UTF-8：{exc}"
    return package_id, None


def rt2_erbm_problems() -> list:
    """APK 内 assets/mods/rt2 的 .erbm 容器与仓库静态资产、点 manifest 逐项对账。

    判据（CONTRACT-RT2-ERBM §1 第 7 条）：ERTM1 前缀 / 尾 32B=SHA-256(body) 复算 /
    version u16=1 / 档位位属于 {0,1} / packageId 与文件名及 manifest 包名集互洽
    （每容器恰配一 manifest，孤儿容器判红——装配侧「每容器必有同包名 manifest」
    的 fail-closed 会在真机整包拒，出包期先挡）/ manifest 钉了 artifact-sha256
    则必须与 APK 内 .erbm 实际摘要相等。
    """
    problems = []
    try:
        names = read_apk_namelist()
    except (OSError, zipfile.BadZipFile) as exc:
        return [f"读不出 APK 条目清单，RT2 .erbm 容器无法对账：{exc}"]
    prefix = RT2_BUILTIN_ASSET_PREFIX
    entries = sorted(n for n in names if n.startswith(prefix) and n.endswith(RT2_ERBM_SUFFIX))
    apk_names = {n[len(prefix):] for n in entries}
    disk_names = set()
    if os.path.isdir(BUILTIN_ASSET_DIR):
        disk_names = {n for n in os.listdir(BUILTIN_ASSET_DIR) if n.endswith(RT2_ERBM_SUFFIX)}
    else:
        problems.append(f"仓库内置资产目录不存在：{BUILTIN_ASSET_DIR}")
    for name in sorted(disk_names - apk_names):
        problems.append(f"APK 缺内置 RT2 容器：{name}（两件套不齐，开闸装配整包拒）")
    for name in sorted(apk_names - disk_names):
        problems.append(f"APK 多出来源不明的内置 RT2 容器：{name}")
    # 配对的另一侧 = 同目录点 manifest 的包名表（复用 manifest 体检的既有解析，不重造）。
    manifest_pkg_by_name = {}
    manifest_data_by_name = {}
    for name in sorted(
        n for n in names if n.startswith(prefix) and n.endswith(RT2_MANIFEST_SUFFIX)
    ):
        try:
            data = read_apk_entry(name)
        except (KeyError, zipfile.BadZipFile) as exc:
            problems.append(f"读不出 APK 内 {name}：{exc}")
            continue
        package_id, err = parse_rt2_manifest_package_id(data)
        if err is not None:
            continue  # 坏档由 rt2_manifest_problems 点名，这里不重复报
        manifest_pkg_by_name[name[len(prefix):]] = package_id
        manifest_data_by_name[name[len(prefix):]] = data
    seen_containers = set()
    for entry in entries:
        short = entry[len(prefix):]
        try:
            data = read_apk_entry(entry)
        except (KeyError, zipfile.BadZipFile) as exc:
            problems.append(f"读不出 APK 内 {entry}：{exc}")
            continue
        disk = os.path.join(BUILTIN_ASSET_DIR, short)
        if os.path.isfile(disk):
            with open(disk, "rb") as fh:
                if fh.read() != data:
                    problems.append(
                        f"APK 内 {short} 与仓库静态资产字节不符"
                        "（装了旧模块？重跑 generateDemoErbModule 后两件一起提交）"
                    )
        package_id, err = parse_rt2_erbm(data)
        if err is not None:
            problems.append(f"APK 内 {short} 是坏档：{err}")
            continue
        if package_id in seen_containers:
            problems.append(f"APK 内内置 RT2 容器包名重复：{package_id}")
        seen_containers.add(package_id)
        if short != package_id + RT2_ERBM_SUFFIX:
            problems.append(f"APK 内 {short} 的文件名与容器 packageId {package_id} 不符")
        manifest_name = package_id + RT2_MANIFEST_SUFFIX
        if manifest_name not in manifest_pkg_by_name:
            problems.append(
                f"APK 内 {short} 是孤儿容器：找不到同包名 manifest {manifest_name}"
                "（装配侧 fail-closed 整包拒）"
            )
            continue
        declared_pkg = manifest_pkg_by_name[manifest_name]
        if declared_pkg != package_id:
            problems.append(
                f"APK 内 {short} 与 {manifest_name} 包名分叉："
                f"容器 {package_id} / manifest {declared_pkg}"
            )
            continue
        pinned, perr = parse_rt2_manifest_artifact_sha256(manifest_data_by_name[manifest_name])
        if perr is not None:
            problems.append(f"APK 内 {manifest_name} 的 artifact-sha256 段解不出：{perr}")
            continue
        if pinned and pinned.lower() != hashlib.sha256(data).hexdigest():
            problems.append(
                f"APK 内 {manifest_name} 钉的 artifact-sha256 与 {short} 实际摘要不符"
                "（容器被单独替换过？重跑 generateDemoErbModule）"
            )
    return problems


def report_package_kind(skip_assets: bool, allow_stale_erbc: bool = False) -> list:
    """打印包型（随资源 / 只随 erbc），返回需要提醒的问题。

    `-PeratwSkipAssets` 与「找不到游戏根」都能让包变小，但**语义不同**：
    前者是刻意的日常小包，后者是静默退化。这里把两者区分开，后者一律提示。

    ## 为什么把 `erbc-freshness` 从「警告」升级成「失败」（2026-10-29）

    真机实测踩过一次：仓库里的 `tools/erbcc/build/erbc-out` 是 6 天前的旧产物，
    `stageGameAssets` 在「兜底目录」档只 warn、`package.report` 写 `unverified`，
    包照出、装上也照启动 —— 结果是标题页 `SYSTEM_TITLE → RM_タイトル画像用意` 撞
    2000 万步闸（旧字节码 + 新引擎），而日志里只有一行「erbc-freshness=unverified」。
    这种失败模式会**安静地毁掉任何基于该包的真机结论**，所以默认拒绝，只能显式
    `--allow-stale-erbc` 放行（内测）。
    """
    rep = read_package_report()
    if not rep:
        # `-PeratwSkipAssets=1` 的包**按设计**一个随包条目都不搬（连 erbc.v1 一起跳，APK 回到
        # ~13 MB，装机复用设备上已解包的数据）—— 这种包根本没有 package.report 可读，不是退化。
        # 真正的静默退化长的是下面那条：没带 --skip-assets 却读不出自述 / game-root 为空。
        if skip_assets:
            if has_bundled_erbc_entries():
                return ["传了 --skip-assets，包里却有 assets/erbc.v1/ 条目而读不出 package.report"
                        "（打包任务半途失败？）"]
            print("[pack] 包型 = 零随包条目（--skip-assets：不搬 erbc 与语料，装机复用设备上已解包的数据）")
            return []
        return ["读不出 assets/erbc.v1/package.report（包不完整？）"]
    # 出包自述独立列：从 APK 内 manifest.txt 的真实留痕行生成 applied_corpus_patches
    # （cb001 接线①）。--skip-assets 的零条目包在上面已按既有形状 early return，不抢话。
    manifest_text = read_apk_asset("assets/erbc.v1/manifest.txt")
    rep = apply_corpus_patches_column(rep, manifest_text)
    game_root = rep.get("game-root", "-")
    print(f"[pack] 包型 game-root={game_root} · erbc-source={rep.get('erbc-source', '?')}")
    freshness = rep.get("erbc-freshness", "?")
    print(f"[pack]      staged-entries={rep.get('staged-entries', '?')} · "
          f"staged-bytes={rep.get('staged-bytes', '?')} · "
          f"erbc-freshness={freshness.split(',')[0]}")
    # 严格档下产物缺列时 rep 里没有该键（apply 不再代生成）；用 get 打印占位而不 KeyError，
    # 缺列判红交给下面的 applied_corpus_patches_problems → kind_problems → exit 3。
    print(f"[pack]      {APPLIED_CORPUS_PATCHES_KEY}="
          f"{rep.get(APPLIED_CORPUS_PATCHES_KEY, '(产物缺列)')}")
    problems = []
    if not freshness.startswith("verified") and not allow_stale_erbc:
        problems.append(
            f"随包 erbc 不是当前编译器/当前语料的产物（erbc-freshness={freshness.split(',')[0]}）——"
            "旧字节码 + 新引擎可能让脚本行为跑偏（实测过一次标题页撞 2000 万步闸）。"
            "先跑 ./gradlew :tools:erbcc:compileCorpus 再出包；确需内测可加 --allow-stale-erbc。"
        )
    if not skip_assets and game_root == "-":
        problems.append(
            "本次是「不随游戏资源」的包（package.report 的 game-root 为空）——"
            "ERATW_GAMEDIR 没生效。要完整包就设 ERATW_GAMEDIR 或传 --game-root 重出。"
        )
    if skip_assets and game_root != "-":
        problems.append("传了 --skip-assets，但包里仍有随资源条目（game-root 非空），口径不符。")
    problems += erd_bundling_problems(rep)
    problems += applied_corpus_patches_problems(rep, manifest_text)
    return problems


def report_apk(will_archive: bool = True) -> None:
    if not os.path.isfile(APK_PATH):
        return
    size = os.path.getsize(APK_PATH)
    mtime = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(APK_PATH)))
    print(f"[pack] APK  {APK_PATH}")
    print(f"[pack] 体积 {size:,} B（{size / 1024 / 1024:.0f} MiB） · mtime {mtime}")
    # 归档那一步会在同一次流式读里既算 sha 又复制。这里再独立算一次，等于把 1.1GB
    # 多读一遍、把「一次读盘」的优化整个抵消 —— 会归档时交给 archive_apk 打印 sha。
    if will_archive:
        print("[pack] sha256 见归档步骤（与复制合并为一次读盘）")
    else:
        print(f"[pack] sha256 {apk_digest()}")


# ---------------------------------------------------------------- 签名章体检

def gradle_props() -> dict:
    """合并 `GRADLE_USER_HOME/gradle.properties` 与工程 `gradle.properties`（前者覆盖后者）。

    与 gradle 自己的取值顺序一致：命令行 `-P` > GRADLE_USER_HOME > 工程根。这里读不到命令行，
    所以出包时若把四个 `-PeratwRelease*` 敲在命令行上，本体检会按文件里的值（可能没有）判断 ——
    把口令写进 `GRADLE_USER_HOME/gradle.properties` 才是既有口令不进 shell 历史、又被体检覆盖的接法。
    """
    guh = os.environ.get("GRADLE_USER_HOME") or os.path.join(
        os.path.expanduser("~"), ".gradle")
    out: dict = {}
    for path in (os.path.join(APP_ROOT, "gradle.properties"), os.path.join(guh, "gradle.properties")):
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                out[key.strip()] = value.strip()
    return out


def keytool(env: dict, args: list) -> tuple:
    """跑 JDK 的 keytool（不依赖 SDK 的 apksigner，也不猜 build-tools 版本）。"""
    exe = os.path.join(env.get("JAVA_HOME", ""), "bin",
                       "keytool.exe" if os.name == "nt" else "keytool")
    if not os.path.isfile(exe):
        exe = "keytool"
    proc = subprocess.run([exe] + args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=env, cwd=APP_ROOT)
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def first_sha256(text: str) -> str:
    """从 keytool 输出里抓第一个 SHA-256 指纹（32 组冒分十六进制），归一成小写无分隔。

    只认指纹的形状不认标签：标签随 JDK 语言环境变（`证书指纹` / `Certificate fingerprints`），
    指纹本身是 locale 无关的。
    """
    m = re.search(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}", text)
    return m.group(0).replace(":", "").lower() if m else ""


def apk_signer(env: dict) -> tuple:
    """APK 签名证书的 (sha256, DN)。v1（JAR）签名缺失时拿不到，返回 ("", "")。"""
    code, out = keytool(env, ["-printcert", "-jarfile", APK_PATH])
    if code != 0:
        return "", ""
    dn = re.search(r"CN=[^\n,]*", out)
    return first_sha256(out), (dn.group(0) if dn else "")


def keystore_signer(env: dict, store: str, alias: str) -> tuple:
    """配置的那把 keystore 的证书 (sha256, DN)。口令走 `JKS_PASS` 环境变量，不上命令行。"""
    props = gradle_props()
    env = dict(env)
    env["JKS_PASS"] = props.get("eratwReleaseStorePassword", "")
    args = ["-list", "-v", "-keystore", store, "-storepass:env", "JKS_PASS"]
    if alias:
        args += ["-alias", alias]
    code, out = keytool(env, args)
    if code != 0:
        return "", ""
    dn = re.search(r"CN=[^\n,]*", out)
    return first_sha256(out), (dn.group(0) if dn else "")


def verify_signing(allow_debug_sign: bool) -> list:
    """第 4 步（§1.7 升级）：APK 的章 == 钉死的发布章指纹。

    `signingConfigs.releaseLocal` 没配四个 `-PeratwRelease*` 时**静默**退回
    `~/.android/debug.keystore`（与上报开关同一个失败形状：编译全绿、包照出、装照跑）。
    章错了的代价是事后才知道的：换章 ⇒ 老包不能覆盖升级，只能卸载，存档跟着没。

    本波把发布章 SHA-256 指纹钉成常量 `PINNED_RELEASE_SHA256`（非敏感，证书公钥的哈希）。
    体检不符就 exit 3——不依赖 keystore 配置文件是否存在（那种情况下人可能换了 keystore
    但忘了告诉其他人）。`--allow-debug-sign` 放行 debug 章（内测用）。
    """
    env = toolchain_env()
    props = gradle_props()
    store = props.get("eratwReleaseStoreFile", "")
    alias = props.get("eratwReleaseKeyAlias", "")

    apk_fp, apk_dn = apk_signer(env)
    if not apk_fp:
        return ["读不出 APK 的签名证书（keytool -printcert -jarfile 失败：包没 v1 签名？）"]
    print(f"[pack] 签名章 {apk_dn or '(无 DN)'} · SHA-256 {apk_fp[:16]}…")

    # 先看是不是 debug 章（DN 含 "Android Debug" 或指纹匹配 PINNED_DEBUG）
    is_debug = (apk_fp == PINNED_DEBUG_SHA256 or
                "Android Debug" in (apk_dn or ""))

    if is_debug:
        if allow_debug_sign:
            print("[pack] 注意：本次签的是 debug keystore（--allow-debug-sign 放行），"
                  "这种包 **不许** 发给外部玩家——正式包与它不兼容。")
            return []
        return [
            "APK 签的是 debug keystore（SHA-256 匹配已知 debug 指纹或 DN 含 'Android Debug'）。"
            "修法：确认四个 -PeratwRelease* 写在 GRADLE_USER_HOME/gradle.properties；"
            "确实要 debug 章内测包加 --allow-debug-sign。"
        ]

    # §1.7 核心：与钉死的发布章指纹比对
    if apk_fp != PINNED_RELEASE_SHA256:
        return [
            f"APK 签名指纹与钉值不符！\n"
            f"     包上：{apk_fp}\n"
            f"     钉值：{PINNED_RELEASE_SHA256}\n"
            "     意味着用了不同的 keystore 签名，老包将无法覆盖升级。"
            "     如果确实需要换章（极罕见的泄露场景），须同步更新此处常量并在 BOARD 公告。"
        ]
    print(f"[pack]      与钉值一致（PINNED_RELEASE_SHA256 前 16 位匹配）")

    # 额外：如果 gradle.properties 配了 keystore，也做一次文件对账（双保险）
    if store:
        ks_fp, ks_dn = keystore_signer(env, store, alias)
        if ks_fp and ks_fp != apk_fp:
            return [f"APK 章（{apk_fp[:16]}…）与 gradle.properties 里配置的 keystore"
                    f"（{ks_fp[:16]}…，{ks_dn}）不一致——构建可能没吃到 release 签名参数"]

    return []


def verify_internet_permission() -> list:
    """第 6 步：配了上报端点，包就必须真的拿到 INTERNET 权限。

    2026-10-27 首个真机包的实证事故：端点与令牌都进了包、第 1-5 步全绿，但 manifest 里
    **从来没有** `<uses-permission android:name="android.permission.INTERNET"/>`
    （方案①是「零服务器」的本地报告，不需要它；方案③加了 HTTP 却没人补权限）。
    后果是 `conn.outputStream` 抛 SecurityException，被 uploader 的 runCatching 吞成一行
    logcat，端点一份也收不到 —— 玩家那边零痕迹，我们这边以为在收。
    字符串体检抓不到它，只有读合并后的 manifest 抓得到。
    """
    if not os.path.isfile(MERGED_MANIFEST):
        return [f"读不出合并后的 release manifest：{MERGED_MANIFEST}（构建没跑到 processReleaseManifest？）"]
    with open(MERGED_MANIFEST, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    if 'android.permission.INTERNET' in text:
        return []
    return ["包里配了上报端点，但 APK 没有 android.permission.INTERNET —— "
            "真机上整条上报链会静默哑火（SecurityException 被 runCatching 吞掉，端点一份收不到）。"
            " 修法：app/src/main/AndroidManifest.xml 加 <uses-permission android:name=\"android.permission.INTERNET\"/>"]


# ---------------------------------------------------------------- 端点活体

def probe_endpoint(endpoint: str, token: str) -> list:
    """第 5 步：拿包里的令牌真打一次只读面 `GET /reports`。

    前两步只证明「端点与令牌进了包」，证明不了「这俩在一台机器上配对」。
    令牌抄错一位 ⇒ 前三步全绿，而玩家上报全部 403 —— 只有活体能抓到。
    连不上（无网 / 没配代理）或被 Cloudflare 挡在 Worker 之前只警告：出包不该被网络卡住。
    """
    base = endpoint[: -len("/report")] if endpoint.endswith("/report") else endpoint.rstrip("/")
    url = base + "/reports?token=" + urllib.parse.quote(token, safe="")
    # UA 必须显式给：urllib 的缺省 `Python-urllib/3.x` 会被 Cloudflare 按浏览器指纹挡在
    # Worker 之前（实测 403 + `error code: 1010`），那既不是端点坏也不是令牌错。
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": "eratw-pack/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = resp.read(200).decode("utf-8", "replace")
        print(f"[pack] 端点活体 200 · 收件箱 {body.strip()[:60]}")
        return []
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read(240).decode("utf-8", "replace")
        except Exception:                                          # noqa: BLE001 读不出就算了
            pass
        if exc.code in (401, 403) and "token" in detail.lower():
            return [f"端点回 {exc.code}（token 不符）—— 包里的令牌与 Worker 的 REPORT_TOKEN 不配对，"
                    "装上去上报会全部 403。核对 local.report.properties 与部署时 secret 同值"]
        if exc.code == 500:
            return ["端点回 500（REPORT_TOKEN 未配置或 KV 未绑定）—— Worker 没部署好，先 wrangler 侧修"]
        print(f"[pack] 端点活体没验成：HTTP {exc.code} {detail[:80]!r} —— "
              "被 Cloudflare 挡在 Worker 之前（1010）或端点异常，令牌配对状态未知；"
              f"要人工确认就 curl '{base}/reports?token=<令牌>'")
        return []
    except Exception as exc:                                   # noqa: BLE001 网络面异常一律不阻断出包
        print(f"[pack] 端点活体没做成（{type(exc).__name__}: {exc}）—— 只警告，不阻断；"
              "离线出包或没配代理时会走到这里")
        return []


# ---------------------------------------------------------------- 归档

def apk_digest() -> str:
    digest = hashlib.sha256()
    with open(APK_PATH, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def digest_and_copy(src: str, tmp_path: str) -> str:
    """一遍流式读：同一 1MB 块循环里既算 sha256 又写临时文件，返回 hexdigest。

    归档名要带 sha 前 8 位，所以必须先落临时文件、拿到 sha 才定得了最终名再 os.replace；
    把原来「apk_digest 全量读一遍 + shutil.copy2 全量复制一遍」合成一次读盘。
    """
    digest = hashlib.sha256()
    with open(src, "rb") as rfh, open(tmp_path, "wb") as wfh:
        for chunk in iter(lambda: rfh.read(1 << 20), b""):
            digest.update(chunk)
            wfh.write(chunk)
    return digest.hexdigest()


def version_from_gradle() -> tuple:
    """从 app/build.gradle.kts 读 versionName / versionCode（归档名要用，别手抄）。"""
    if not os.path.isfile(BUILD_GRADLE):
        return "unknown", "0"
    with open(BUILD_GRADLE, "r", encoding="utf-8") as fh:
        text = fh.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (name.group(1) if name else "unknown", code.group(1) if code else "0")


def git_head() -> str:
    proc = subprocess.run(["git", "rev-parse", "--short=7", "HEAD"],
                          capture_output=True, text=True, cwd=APP_ROOT)
    if proc.returncode != 0:
        return "nogit"
    sha = proc.stdout.strip()
    # 工作树脏 ⇒ 包内容对不上这个 sha，名字里必须标出来：2026-10-28 实测出过一次
    # 「归档名叫 b903ab0，实际带着两个没提交的修复」，事后按名字找提交就是假账。
    st = subprocess.run(["git", "status", "--porcelain"],
                        capture_output=True, text=True, cwd=APP_ROOT)
    return sha + ("-dirty" if st.stdout.strip() else "")


def archive_apk(kind: str, out_dir: str = "",
                stem: str = shell_profile.ARTIFACT_STEM_FALLBACK,
                version: tuple | None = None) -> str:
    """把 APK 复制成带游戏名/版本/提交/包型/指纹的名字，落 `build/release-artifacts/`。

    gradle 的 `app-release.apk` 每次构建原地覆盖 —— 只留那儿等于「上周那个包」找不回来。
    名字里带 git7 与产物 sha8：主人问「这个包是哪个提交出的」时不用回来翻日志。
    `stem` 跟随随包语料（见 `shell_profile.artifact_stem`），第三方包不再顶着 `eratw-` 前缀。
    `version` 由版本账本给出（第三方包各自的 1.0.x 线）；None ⇒ 读 build.gradle.kts 的 eraTW 取值。
    """
    name_v, code_v = version if version else version_from_gradle()
    dst_dir = out_dir or ARTIFACT_DIR
    os.makedirs(dst_dir, exist_ok=True)
    # 归档名里含 sha 前 8 位，先写临时文件，拿到 sha 后才 os.replace 成正式名。
    # 临时名带 pid：两个出包进程并发落同一目录时不会互相踩掉对方的半截文件。
    tmp = os.path.join(dst_dir, f".pack-archive-{os.getpid()}.tmp")
    try:
        sha = digest_and_copy(APK_PATH, tmp)
        base = f"{stem}-{name_v}+{code_v}-{git_head()}-{kind}-{sha[:8]}"
        dst = os.path.join(dst_dir, base + ".apk")
        os.replace(tmp, dst)
    finally:
        # digest_and_copy / os.replace 任一环节抛错都不留半截临时文件；成功时 tmp 已被
        # os.replace 消费，exists 为 False，不会误删正式归档。
        if os.path.exists(tmp):
            os.remove(tmp)
    with open(os.path.join(dst_dir, base + ".apk.sha256.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"{sha}  {base}.apk\n")
    print(f"[pack] sha256 {sha}")
    print(f"[pack] 归档 {dst}")
    return dst



# ---------------------------------------------------------------- 引擎配置模式（ENGC-6 / B6-PACK）
# 第三方引擎出包通道（契约 CONTRACT-ENGCOMPAT §8.0.5）：`--engine-config <file>` 声明
# 目标引擎档（engine + version），据此生成印章文件、注入两个 gradle 属性
# （`-PeratwEngineProfile` / `-PeratwEngineStampFile`，由 Lane C 的 gradle 侧透传给
# erbcc / staging）、跳过 eraTW 语料根闸（保留「语料与游戏根同源」判据）、编译后渲染
# 兼容性报告。所有新分支都以「无 --engine-config」早退：无配置时出包路径与今天逐字一致
# （I7 零干扰，对拍见 devtools/test_engine_compat.py）。配置文件本身不进 APK。
ENGINE_STAMP_ASSET = engine_compat.STAMP_ASSET_PATH      # APK 内印章条目名
ENGINE_COMPAT_DIR = os.path.join(APP_ROOT, "build", "engine-compat")
# 印章文件（pack 侧生成，经 -PeratwEngineStampFile 交给 staging 任务按字节拷进 assets）。
ENGINE_REPORT_JSON_CANDIDATES = (
    # 报告 JSON 落点由 erbcc/gradle 侧（Lane A/C）决定，这里按候选路径发现；
    # 接口对齐记录在「需组长接线」。
    os.path.join(ENGINE_COMPAT_DIR, "engine-report.json"),
    os.path.join(APP_ROOT, "tools", "erbcc", "build", "engine-report.json"),
)


def engine_stamp_path() -> str:
    """印章文件落点（配置模式生成；-PeratwEngineStampFile 的值就是这个绝对路径）。"""
    return os.path.join(ENGINE_COMPAT_DIR, "engine-profile.json")


def engine_config_gate(config_path: str, game_root: str, skip_assets: bool = False) -> list:
    """配置模式的门槛判据：第三方游戏根必须**显式**给，不许回落缺省 eraTW 语料根。

    eraTW 的 4.980/4.981 语料根不是目标游戏的数据；回落 = 「第三方脚本 + eraTW 数据」
    的静默错包，与 [retired_corpus_problem] 拦的是同一类失败，所以这里前置硬拦。
    """
    problems = []
    if not config_path:
        problems.append("--engine-config 需要配置文件路径")
    if not game_root:
        problems.append(
            "配置模式要求显式 --game-root：第三方游戏不许回落缺省 eraTW 语料根"
            f"（{' / '.join((CURRENT_CORPUS_DIR, *RETIRED_CORPUS_DIRS))} 都不是目标游戏的数据）"
        )
    if skip_assets:
        # 组长裁定（B6 验收期，2026-11-12）：印章 assets/engine-profile.json 只能经
        # StageGameAssetsTask 落进包（D10 印章是唯一档位载体）；--skip-assets 的包
        # 没有可烙章面 = 运行期恒回落缺省档的假兼容包，静默比报错危险，前置硬拦。
        problems.append(
            "配置模式不支持 --skip-assets：印章只能经随包资产落进包（D10 印章是唯一"
            "档位载体），跳过随包资产的包无法烙章，请去掉 --skip-assets 出完整包"
        )
    return problems


def prepare_engine_stamp(engine_cfg: dict, path: str = "") -> str:
    """配置模式：把印章写到 build/engine-compat/engine-profile.json，返回 abs path。

    印章三串（profile/engine/version）逐字取自 engine_compat.TARGETS 同步表；
    写死成固定档会让 --verify-only 回读判红（反向证明②钉住）。
    """
    return os.path.abspath(engine_compat.write_stamp_file(engine_cfg, path or engine_stamp_path()))


def engine_gradle_args(engine_cfg) -> list:
    """引擎配置模式注入的两个 gradle 参数（契约 §8.0.4）。

    `engine_cfg` 为 None（无 --engine-config）时返回 [] —— 这就是零干扰早退本体；
    删掉它，无配置路径就会带上引擎参数（反向证明①用例钉住此行为）。
    """
    if engine_cfg is None:
        return []
    return [
        f"-PeratwEngineProfile={engine_cfg['profile']}",
        f"-PeratwEngineStampFile={engine_stamp_path()}",
    ]


def engine_stamp_problems(engine_cfg: dict) -> list:
    """配置模式回读（--verify-only 与出包后共用）：APK 内印章与配置逐字段比对。

    缺印章 = 配置模式却没烙章（-PeratwEngineStampFile 没生效 / staging 没写进包）⇒ 判红；
    字段不符（schema/profile/engine/version 任一）⇒ 判红并给出期望 vs 实际。
    无 --engine-config 时本函数不会被调用（I7）。
    """
    raw = read_apk_asset(ENGINE_STAMP_ASSET)
    if raw is None:
        return [f"配置模式的包里缺印章 {ENGINE_STAMP_ASSET}"
                "（-PeratwEngineStampFile 没生效或 staging 没把印章写进包）"]
    return [f"APK 内印章 {ENGINE_STAMP_ASSET} 与配置不符：{p}"
            for p in engine_compat.verify_stamp_text(raw, engine_cfg)]


def engine_report_problems(engine_cfg: dict, candidates=None, out_dir: str = "") -> list:
    """配置模式：erbcc `--engine-report` 的 JSON → 渲染 `build/engine-compat/report.md`。

    报告是出包前产物且**必填**（D11）：找不到 JSON / 形状不符（契约 §8.0.3）/
    目标档与配置不符都判红；渲染结果落在 `out_dir`（缺省 ENGINE_COMPAT_DIR）。
    无 --engine-config 时本函数不会被调用（I7）。
    """
    paths = list(candidates) if candidates is not None else list(ENGINE_REPORT_JSON_CANDIDATES)
    found = next((p for p in paths if os.path.isfile(p)), None)
    if not found:
        return [
            "配置模式编译后找不到 --engine-report 的 JSON，兼容性报告缺产物（D11 必填）；"
            f"候选路径：{paths}。检查 erbcc/gradle 侧 --engine-report 接线。"
        ]
    try:
        report = engine_compat_report.load_report(found)
    except engine_compat_report.EngineReportError as exc:
        return [f"引擎报告 JSON 不合格：{exc}"]
    if report.get("profile") != engine_cfg["profile"]:
        return [f"引擎报告的目标档与配置不符：报告 profile={report.get('profile')!r} "
                f"vs 配置 profile={engine_cfg['profile']!r}（{found}）"]
    out_path = engine_compat_report.render_file(
        report, os.path.join(out_dir or ENGINE_COMPAT_DIR, "report.md"))
    print(f"[pack] 引擎兼容性报告：{out_path}")
    return []


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="eratw-android 出包唯一入口（默认打开报错上报，并体检它真的开着）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--offline", "--no-report", dest="offline", action="store_true",
                        help="出「不上报」的包（内测/离线场景；会大声警告）")
    parser.add_argument("--skip-assets", action="store_true",
                        help="带 -PeratwSkipAssets=1（小包，复用设备上已解包的数据）")
    parser.add_argument("--erbc-stored", dest="erbc_stored", action="store_true",
                        help="随包 erbc/meta 保持 STORED 不压缩（缺省带 -PeratwErbcCompress 走 DEFLATE："
                             "APK 瘦 ~366 MiB，解包产物逐字节不变；STORAGE-1）")
    parser.add_argument("--game-root", default="",
                        help="覆盖游戏根（缺省取 ERATW_GAMEDIR；缺了它包会静默退化成不随资源）")
    parser.add_argument("--seed-qol-db", dest="seed_qol_db", default="", metavar="PATH",
                        help="预置 qol_data.db（「跳过首次建图」包）的路径；缺省自动发现 "
                             "build/seeddb/qol_data.db，须有同名 seed.json 且 db/当前 erbc 产物"
                             "两个 sha256 都对上才生效（对不上宁可不带，也不带一张旧图）")
    parser.add_argument("--no-seed-qol-db", dest="no_seed_qol_db", action="store_true",
                        help="不启用预置库（即使 build/seeddb 有产物）")
    parser.add_argument("--require-seed-qol-db", dest="require_seed_qol_db", action="store_true",
                        help="保留兼容：正式出包缺省已强制预置库（缺失/指纹不符/自动生成失败即拒）；"
                             "dev 试包要放宽用 --no-require-seed-qol-db")
    parser.add_argument("--no-require-seed-qol-db", dest="no_require_seed_qol_db",
                        action="store_true",
                        help="dev 档放宽：预置库缺失/自动生成失败只告警不拒包"
                             "（该包会让玩家首装自己跑 GRAPH_DB_REBUILD）")
    parser.add_argument("--engine-config", dest="engine_config", default="",
                        help="第三方引擎构建模式：engine.config.json 路径（schema v1 = "
                             "{schema,engine,version}；生成印章 build/engine-compat/engine-profile.json，"
                             "注入 -PeratwEngineProfile/-PeratwEngineStampFile，跳过 eraTW 语料根闸，"
                             "要求显式 --game-root，编译后渲染兼容性报告）。"
                             "不带此参数 = eraTW 出包路径与今天逐字一致")
    parser.add_argument("--shell-profile", dest="shell_profile", default="", metavar="PATH",
                        help="壳层品牌配置（SHELL-PROFILE schema v1）：指定游戏名 / app 名称 / "
                             "加载页文案 / 教程模式 / applicationId / 版本号；不带此参数 = eraTW 壳逐字不变")
    parser.add_argument("--no-bump-version", dest="no_bump_version", action="store_true",
                        help="第三方包出包成功后不抬版本账本（连续试包时用；正式发版别带）")
    parser.add_argument("--foreign-corpus", dest="foreign_corpus", action="store_true",
                        help="声明本次随包的语料/游戏根不是 devtools/corpus-patches 的 eraTW 根："
                             "编译面与 staging 面整表跳过语料内容补丁（否则第三方游戏根会因为"
                             "缺 CSV/_Rename.csv 这类 eraTW 专属目标被判红）。必须配显式 --game-root；"
                             "不带此参数 = 补丁表照常严格适用（缺省，护栏原样保留）")
    parser.add_argument("--endpoint", default="", help="覆盖端点（缺省取 local.report.properties）")
    parser.add_argument("--token", default="", help="覆盖令牌（不建议：会留在 shell 历史里）")
    parser.add_argument("--mirror", action="append", default=[], dest="mirrors",
                        help="国内镜像仓的更新源目录基址（可重复；缺省取 local.report.properties 的 mirrors）")
    parser.add_argument("--no-update", dest="no_update", action="store_true",
                        help="刻意不给这个包内置更新源（开发包/内测包；缺省会大声警告）")
    parser.add_argument("--verify-only", action="store_true", help="不构建，只体检已有 APK")
    parser.add_argument("--dry-run", action="store_true", help="只打印将要执行的 gradle 命令")
    parser.add_argument("--allow-debug-sign", action="store_true",
                        help="放行「没配发布章 ⇒ 签成 debug keystore」的包（内测专用，不许外发）")
    parser.add_argument("--allow-stale-erbc", action="store_true",
                        help="放行「随包 erbc 不是当前编译器/当前语料产物」的包（内测专用）")
    parser.add_argument("--allow-degraded-corpus", action="store_true",
                        help="放行「随包 erbc 含降级切片（死函数）」的包（仅限不许改源的第三方口上）")
    parser.add_argument("--allow-retired-corpus", action="store_true",
                        help=f"放行「游戏根指向已退役语料（{' / '.join(RETIRED_CORPUS_DIRS)}）」的包"
                             f"（回归对拍内测专用；正式包语料必须是 {CURRENT_CORPUS_DIR}）")
    parser.add_argument("--no-archive", action="store_true",
                        help="不把 APK 复制进 build/release-artifacts/（省一份 743 MiB 磁盘）")
    parser.add_argument("--out", default="", help="覆盖归档目录（缺省 build/release-artifacts）")
    parser.add_argument("--print-corpus-root-name", dest="print_corpus_root_name",
                        action="store_true",
                        help=f"只打印现行语料的 root_dir（{CURRENT_CORPUS_DIR}）后退出 0："
                             "devtools/pack_one 取语料根名的单源通道（不构建、不写盘）")
    args = parser.parse_args(argv)

    # 语料根名单源读口（devtools/pack_one 的唯一用例）：**在任何构建/写盘之前**早退，
    # 只吐一行 root_dir，使「工具与出包脚本各记一份 4.981 目录名」这条暗通道没有存在余地。
    if args.print_corpus_root_name:
        print(CURRENT_CORPUS_DIR)
        return 0

    # 引擎配置模式（ENGC-6 / B6-PACK）：无 --engine-config 时 engine_cfg 恒 None，
    # 后续所有新分支都以它早退，出包路径与今天逐字一致（I7 零干扰）。
    engine_cfg = None
    # --foreign-corpus 撤的是「eraTW 语料补丁严格适用」这道护栏，所以只认显式给定的游戏根：
    # 从 ERATW_GAMEDIR 继承一个根再来撤护栏，等于对着真 eraTW 根关掉补丁而不自知（CB-001/005/006
    # 会静默回归成玩家可见崩溃）。要关就把根写在脸上。
    if args.foreign_corpus and not args.game_root:
        print("[pack] --foreign-corpus 必须配显式 --game-root（缺了就等于对着 ERATW_GAMEDIR 的 "
              "eraTW 根关掉语料补丁）", file=sys.stderr)
        return 2
    if args.engine_config:
        gate = engine_config_gate(args.engine_config, args.game_root, args.skip_assets)
        if gate:
            print("[pack] 引擎配置不合格，拒绝出包：", file=sys.stderr)
            for item in gate:
                print(f"  - {item}", file=sys.stderr)
            return 2
        try:
            engine_cfg = engine_compat.load_engine_config(args.engine_config)
        except engine_compat.EngineConfigError as exc:
            print(f"[pack] 引擎配置不合格，拒绝出包：\n{exc}", file=sys.stderr)
            return 2
        print(f"[pack] 引擎配置模式：profile={engine_cfg['profile']} "
              f"(engine={engine_cfg['engine']} version={engine_cfg['version']})")
        if not args.verify_only:
            print(f"[pack] 印章文件：{prepare_engine_stamp(engine_cfg)}")

    # 壳层品牌配置（SHELL-PROFILE）：独立通道，可与 --engine-config 组合，也可单独给
    # （只换壳不换引擎）。配置文件不进 APK，值经 -P 属性烙进 resources.arsc。
    shell_cfg = None
    shell_version = None
    if args.shell_profile:
        try:
            shell_cfg = shell_profile.load_profile(args.shell_profile)
        except shell_profile.ShellProfileError as exc:
            print(f"[pack] 壳层配置不合格，拒绝出包：\n{exc}", file=sys.stderr)
            return 2
        # 版本线（GAME-VERSION）：第三方目标各自从 1.0.0+1 起算，不继承 eraTW 的号段。
        # 显式配置 > 账本 > 首次默认；出包成功后才抬号，失败不记假账。
        try:
            ledger = game_versions.load()
            shell_version = game_versions.resolve(
                ledger, shell_cfg["gameName"],
                shell_cfg.get("versionName"), shell_cfg.get("versionCode"))
        except game_versions.VersionLedgerError as exc:
            print(f"[pack] 版本账本不合格，拒绝出包：\n{exc}", file=sys.stderr)
            return 2
        shell_cfg = {**shell_cfg,
                     "versionName": shell_version[0], "versionCode": shell_version[1]}
        print(f"[pack] 壳层配置模式：{shell_profile.summary(shell_cfg)} "
              f"applicationId={shell_cfg['applicationId']} "
              f"version={shell_version[0]}+{shell_version[1]}")

    # 预置 qol_data.db（跳过首次建图）：在启动 Gradle 之前就把「库在不在、是不是这份引擎/语料
    # 跑出来的」判完。判据见 [discover_seed_qol_db]；require/显式路径下任一不符 ⇒ exit 2，
    # 不把「参数带了但 staging 忽略」这种静默退化留给玩家侧。
    if args.no_seed_qol_db and (args.seed_qol_db or args.require_seed_qol_db):
        print("[pack] --no-seed-qol-db 与 --seed-qol-db / --require-seed-qol-db 互斥",
              file=sys.stderr)
        return 2
    if args.no_require_seed_qol_db and args.require_seed_qol_db:
        print("[pack] --no-require-seed-qol-db 与 --require-seed-qol-db 互斥", file=sys.stderr)
        return 2
    # 正式出包缺省强制：预置库缺失/生成失败 ⇒ 拒绝出包（否则这个包会静默把 36k 行建图
    # 成本退回给玩家）。dev 档只想试包时用 --no-require-seed-qol-db 放宽。
    # 但「预置库不适用」的形态（dev 的 --skip-assets、第三方语料、第三方引擎档）本就不带库，
    # 这里不能拿缺行判红 —— 否则 dev 小包会被自己挡死。
    seed_applicable = not (args.skip_assets or args.foreign_corpus or engine_cfg is not None)
    require_seed_qol_db = not args.no_require_seed_qol_db and seed_applicable
    seed_skip = seed_qol_db_generator_skip_reason(
        disabled=args.no_seed_qol_db,
        explicit=args.seed_qol_db,
        skip_assets=args.skip_assets,
        foreign_corpus=args.foreign_corpus,
        engine_config=engine_cfg is not None,
        verify_only=args.verify_only,
    )
    if args.verify_only and not args.seed_qol_db:
        seed_discovery = SeedQolDbDiscovery()
        seed_discovery.skipped = "--verify-only 只体检现成 APK，不做预置库发现"
    else:
        # 第一趟只看「现成库能不能用」；不能用且该由出包期补跑时就现场生成再判。
        probe = discover_seed_qol_db(
            explicit=args.seed_qol_db,
            require=False,
            skip_assets=args.skip_assets,
            foreign_corpus=args.foreign_corpus,
            engine_config=engine_cfg is not None,
            disabled=args.no_seed_qol_db,
        )
        if not seed_skip and probe.seed is None and not probe.problems:
            if args.dry_run:
                print("[pack] 注意（dry-run）：未找到可用预置库；真出包会先自动跑 "
                      f"{SEED_QOL_DB_GENERATOR_REL}（暖跑约 105s）", file=sys.stderr)
                # dry-run 的用途是看命令形状，不在这里跑引擎；缺省强制也放宽成告警，
                # 只有显式 --require-seed-qol-db 才保持判红（调用方要的是形状，不是一次真出包）。
                require_seed_qol_db = bool(args.require_seed_qol_db)
            else:
                rc = run_seed_qol_db_generator(
                    game_root=effective_game_root(args.game_root),
                    erbc_dir=os.environ.get(ENV_ERBC_DIR, ""),
                )
                if rc != 0:
                    if require_seed_qol_db:
                        print("[pack] 预置库自动生成失败，拒绝出包（本包会让玩家首装自己跑 "
                              "GRAPH_DB_REBUILD；确认要放宽用 --no-require-seed-qol-db）",
                              file=sys.stderr)
                        return 2
                    print("[pack] 警告：预置库自动生成失败，按非强制继续 —— 本包会把建图成本"
                          "退回给玩家", file=sys.stderr)
        elif seed_skip:
            print(f"[pack] 预置库：不自动生成（{seed_skip}）")
        seed_discovery = discover_seed_qol_db(
            explicit=args.seed_qol_db,
            require=require_seed_qol_db,
            skip_assets=args.skip_assets,
            foreign_corpus=args.foreign_corpus,
            engine_config=engine_cfg is not None,
            disabled=args.no_seed_qol_db,
        )
    for item in seed_discovery.warnings:
        print(f"[pack] 预置库：{item}")
    if seed_discovery.problems:
        print("[pack] 预置库不可用，拒绝出包（否则这个包会静默把建图成本退回给玩家）：",
              file=sys.stderr)
        for item in seed_discovery.problems:
            print(f"  - {item}", file=sys.stderr)
        return 2
    seed_qol_db = seed_discovery.seed
    if seed_discovery.skipped:
        print(f"[pack] 预置库：跳过（{seed_discovery.skipped}）")
    elif seed_qol_db is not None:
        print(f"[pack] 预置库：{seed_qol_db.path}（sha256 {seed_qol_db.db_sha256[:16]}… / "
              f"{seed_qol_db.db_bytes} B，erbc program-sha256 对账通过，来源={seed_qol_db.origin}）")
    seed_qol_db_arg = seed_qol_db.path if seed_qol_db is not None else ""

    endpoint, token, origin = resolve_report_config(args)
    mirrors, mirrors_origin = resolve_update_mirrors(args)
    if args.no_update:
        mirrors = ""
    # 第三方包不许继承 eraTW 的更新源：那份 manifest 写的是 eraTW 的版本线，
    # 会把「eratw 0.2.48」当成自家更新推给 eratohoReverse 玩家（装上去就是另一款游戏）。
    # 只有「脚本缺省」这一档才清 —— 调用方显式给 --mirror/env 时按显式来（自建源场景）。
    if shell_cfg is not None and mirrors and mirrors_origin.startswith("脚本缺省"):
        print("[pack] 第三方包不内置 eraTW 更新源（避免串台）：如需更新通道，"
              "请显式 --mirror 指向本游戏自己的清单。")
        mirrors = ""
        mirrors_origin = "第三方包不内置"

    # 语料换代闸（4.981 起）：出包游戏根还在指旧 4.980 语料就拒 —— env.sh 的缺省 ERATW_GAMEDIR
    # 仍是旧根，source 过它再出包会得到内部自洽、体检全绿的「旧语料 + 新版本号」包，无一处
    # 拦得住（见 [retired_corpus_problem]）。判读在这一拍做完；--verify-only 不构建不拦，
    # --dry-run 不拦但大声提示（两种模式的调用方要的是读数与命令形状，不是一次真出包）。
    # 引擎配置模式（契约 §8.0.5）跳过这道 eraTW 专属闸（第三方游戏根没有 eraTW 语料目录），
    # 保留下面「语料与游戏根同源」判据；无 --engine-config 时本表达式取值与改动前逐字相同。
    era_problems = ([] if args.allow_retired_corpus or engine_cfg is not None
                    else retired_corpus_problem(effective_game_root(args.game_root)))
    # 同一条洞的另一半：erbc 由 `ERATW_CORPUS` 编译、CSV/resources 由游戏根随包，两条指到
    # **不同**游戏根时同样内部自洽、体检全绿（见 [data_source_alignment] 的现网实证）。
    # `--verify-only` 只体检现成 APK、不产出新包，数据源怎么配都不影响这次判定。
    source_problems = [] if args.verify_only else data_source_alignment(args.skip_assets, args.game_root)

    if args.dry_run:
        # dry-run 不出包，所以不逼它配齐令牌：配不齐也要能看见命令长什么样。
        for item in era_problems + source_problems:
            print(f"[pack] 注意（dry-run）：{item}", file=sys.stderr)
        run_build(endpoint, token, args.skip_assets, True, args.game_root, mirrors,
                  erbc_stored=args.erbc_stored, engine_config=engine_cfg,
                  foreign_corpus=args.foreign_corpus, seed_qol_db=seed_qol_db_arg,
                  shell_config=shell_cfg)
        if not token and not args.offline:
            print(f"[pack] 注：令牌来源={origin.split(' / ')[-1]}，真出包会在这一步 exit 2（dry-run 放行）")
        return 0

    if args.offline:
        print("[pack] ===== 本次出包：报错上报 **关闭**（--offline）=====")
        print("[pack] 玩家崩溃不会上报。发布给外部玩家请去掉 --offline。")
        endpoint, token = "", ""
    else:
        problems = check_report_config(endpoint, token)
        if problems:
            print("[pack] 配置不合格，拒绝出包（否则会得到一个「静默不上报」的包）：", file=sys.stderr)
            for item in problems:
                print(f"  - {item}", file=sys.stderr)
            print(f"\n  端点：{endpoint or '(空)'}\n  令牌来源：{origin.split(' / ')[-1]}", file=sys.stderr)
            print(f"  修法：把 endpoint / token 写进 {LOCAL_PROPS}（已在 .gitignore 里）", file=sys.stderr)
            return 2

    # 镜像地址的形状：写错一条 = 那个源在设备上被 UpdatePolicy 静默丢掉（其余源照用），
    # 所以坏条目只警告不挡出包；但**一条可用地址都没有**时必须挡下来 —— 那种包看着是
    # 「发了源的包」，实际整条更新通道是空的，与「漏带上报参数」同一类静默失效。
    mirror_problems, mirror_usable = check_update_mirrors(mirrors)
    if mirror_problems:
        print("[pack] 镜像地址有问题（设备侧 UpdatePolicy 会把不合规格的条目丢掉）：", file=sys.stderr)
        for item in mirror_problems:
            print(f"  - {item}", file=sys.stderr)
    if mirrors and not mirror_usable:
        print("[pack] 一条可用镜像都没有，拒绝出一个「以为会更新其实不会」的包。", file=sys.stderr)
        return 2

    if not args.verify_only and (era_problems or source_problems):
        print("[pack] 语料根/数据源不对，拒绝出包（会得到一个「脚本与数据版本打架」的包）：",
              file=sys.stderr)
        for item in era_problems + source_problems:
            print(f"  - {item}", file=sys.stderr)
        if source_problems:
            print("  修法：让 ERATW_CORPUS 与 ERATW_GAMEDIR（或 --game-root）指向同一个游戏根。",
                  file=sys.stderr)
        return 2

    # CMP-66 前置闸（2026-11-14）：启动 Gradle 之前先用 0.5 秒的内容指纹对账，
    # 挡掉「编译器源码变了却拿旧 erbc 出包」——这类包必然在 6 分钟后被
    # stageGameAssets 判红，旧流程等于白跑一整轮 + 重编 + 重出。
    # 只对真正要构建的完整包生效：verify-only / dry-run / 小包 / 第三方引擎模式
    # 与显式 --allow-stale-erbc 内测放行，行为与改动前逐字一致。
    if not (args.verify_only or args.dry_run or args.allow_stale_erbc
            or args.skip_assets or engine_cfg is not None):
        pre = preflight_erbc_digest()
        if pre:
            print("[pack] 随包 erbc 新鲜度预检不通过，拒绝启动 Gradle（否则整轮构建必白跑）：",
                  file=sys.stderr)
            for item in pre:
                print(f"  - {item}", file=sys.stderr)
            return 2

    if not args.verify_only:
        code = run_build(endpoint, token, args.skip_assets, False, args.game_root, mirrors,
                         erbc_stored=args.erbc_stored, engine_config=engine_cfg,
                         foreign_corpus=args.foreign_corpus, seed_qol_db=seed_qol_db_arg,
                         shell_config=shell_cfg)
        if code != 0:
            print("[pack] 构建失败，没有可发的包。", file=sys.stderr)
            return 1

    kind_problems = report_package_kind(args.skip_assets, args.allow_stale_erbc)
    kind_problems += report_degraded_corpus(args.allow_degraded_corpus)
    # RT2-B③：内置 manifest 是 app 模块 assets 的静态资产，任何包型（含 --skip-assets 小包）
    # 都该带着它；缺档 / 旧字节 / 包名分叉一律挡在出包期，不留到真机日志里才看出来。
    kind_problems += rt2_manifest_problems()
    # RT2-ERBM：.erbm 容器同面体检（magic/摘要/档位/包名互洽/孤儿容器/artifact-sha256 对账）。
    kind_problems += rt2_erbm_problems()
    # 引擎配置模式（ENGC-6 / B6-PACK）：APK 内印章回读比对 + 兼容性报告渲染（D11）；
    # 无 --engine-config 时整段跳过，--verify-only 行为与今天逐字一致（I7）。
    if engine_cfg is not None:
        kind_problems += engine_stamp_problems(engine_cfg)
        kind_problems += engine_report_problems(engine_cfg)
    # 预置库自证：只看产物（package.report 的 seed-qol-db 行）—— 参数带了不等于 staging 吃了。
    kind_problems += seed_qol_db_report_problems(seed_qol_db,
                                                 require_seed_qol_db and not args.verify_only)
    report_apk(not args.no_archive)

    # 包本身有没有「被 Android 解析」的资格：与上报/更新通道无关，--offline 也要查；
    # --verify-only 走的就是这里（现成 APK 事后体检的唯一入口）。
    integrity_problems = verify_apk_integrity()
    if integrity_problems:
        print("\n[pack] APK 结构体检不通过 —— **这个包装不上，别发出去**：", file=sys.stderr)
        for item in integrity_problems:
            print(f"  - {item}", file=sys.stderr)
        print(
            "\n  玩家侧「解析安装包错误」排查顺序：① 比对上面打印的字节数与 sha256"
            "（同一份包在另一台设备能装，就已证明包级属性全合法）；"
            "② 确认扩展名还是 .apk（聊天工具常改成 .apk.1）；"
            "③ 换内部存储/文件管理器，或 adb install -r 拿 INSTALL_PARSE_FAILED_* 失败码。",
            file=sys.stderr,
        )
        return 3

    problems = [] if args.offline else (
        verify_res_values(endpoint, token, mirrors) + verify_apk(endpoint, token, mirrors) +
        verify_internet_permission()
    )
    # 更新源的权限体检只在「真的内置了源」时才做：没源的包本来就不该有那条权限的期望。
    problems += verify_update_permission(mirrors)
    if problems:
        print("\n[pack] 体检不通过 —— **这个包不会上报，别发出去**：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 3

    # 端点活体（网络 I/O）与签名章（keytool 子进程）互不依赖、不共享可变状态，用两个线程
    # 并行发起；结果取回后仍严格按原顺序判定与输出：先端点活体 return 3，再签名 return 3。
    # 两个 .result() 的异常原样冒出，不 try/except 静默吞掉；--offline 时不提交端点活体。
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        live_future = (pool.submit(probe_endpoint, endpoint, token)
                       if not args.offline else None)
        sign_future = pool.submit(verify_signing, args.allow_debug_sign)
        live = live_future.result() if live_future is not None else []
        sign_problems = sign_future.result()

    # 端点活体：包里那一对「端点 + 令牌」真配上，上报才叫通（前两步只证明串进了包）。
    if not args.offline and live:
        print("\n[pack] 端点活体检不通过 —— 装上去上报会全灭，这个包别发：", file=sys.stderr)
        for item in live:
            print(f"  - {item}", file=sys.stderr)
        return 3

    if sign_problems:
        print("\n[pack] 签名体检不通过 —— 章不对，这个包别发：", file=sys.stderr)
        for item in sign_problems:
            print(f"  - {item}", file=sys.stderr)
        return 3

    if kind_problems:
        print("\n[pack] 包型与预期不符（上报与章都是好的，但包的形状不对）：", file=sys.stderr)
        for item in kind_problems:
            print(f"  - {item}", file=sys.stderr)
        return 3

    if args.offline:
        print("[pack] 跳过上报体检（本次刻意不上报）—— 这种包只许内测，不许发给外部玩家。")
    else:
        print("[pack] 上报体检通过：端点与令牌都已进包且配对，安装后崩溃/失败会自动上报。")
        print(f"[pack] 端点 {endpoint}")

    if not args.no_archive:
        game_root = read_package_report().get("game-root", "-")
        kind = "skipassets" if args.skip_assets else ("full" if game_root != "-" else "erbconly")
        # 归档名前缀跟随随包语料：壳层配置 gameName > 游戏根目录名 > eratw。
        # 未给 --game-root 时回落到包内自证的那条路径，保证第三方包不会落成 eratw-*。
        stem = shell_profile.artifact_stem(
            shell_cfg, args.game_root or ("" if game_root == "-" else game_root))
        archive_apk(kind, args.out, stem, shell_version)

    # 出包成功才记账（GAME-VERSION）：把该游戏抬到下一条，下次出包自动 +1。
    # 构建/体检任一处提前 return 都走不到这里 —— 失败绝不写假版本号。
    if (shell_cfg is not None and shell_version is not None
            and not args.no_bump_version and not args.verify_only):
        try:
            nxt = game_versions.record(
                game_versions.LEDGER_FILE, shell_cfg["gameName"],
                shell_version[0], shell_version[1])
            print(f"[pack] 版本账本已抬号：{shell_cfg['gameName']} "
                  f"{shell_version[0]}+{shell_version[1]} → 下一次 {nxt[0]}+{nxt[1]}"
                  f"（{os.path.relpath(game_versions.LEDGER_FILE, APP_ROOT)}）")
        except (game_versions.VersionLedgerError, OSError) as exc:
            # 记账失败不回滚已出的包：包本身是好的，只是下次要手工确认版本号。
            print(f"[pack] 警告：版本账本抬号失败，下次出包前请手工确认：{exc}",
                  file=sys.stderr)

    # 更新通道的最后一句：包里有没有源、下一步该跑什么。缺了源不会编译失败、也不会体检红，
    # 玩家那边的表现只是「永远等不到更新」—— 与上报开关同一种静默失效，所以必须说出来。
    if mirrors:
        print(f"[pack] 更新源已内置（来源={mirrors_origin}）：{mirrors.replace('|', ' ')}")
        print(
            "[pack] 下一步发这批文件：python devtools/emit_update.py --note \"…\" "
            "--sign-key local.update-signing/k1-private.pk8.pem"
        )
        print("[pack]   （它会把 manifest 与数据包写进 build/update-dist/，按镜像地址逐条上传）")
        print("[pack]   （不给 --sign-key 就发不出清单：emit_update 会拦未签名，除非显式 "
              "--unsigned-explicit 认领「整包被替换发现不了」这个风险）")
        print("[pack]   （先发一小批观察上报漏斗：加 --rollout-percentage 10；"
              "致命修复要给老版本兜底就加 --min-supported-version <versionCode>）")
    elif args.no_update:
        print("[pack] 本次刻意不内置更新源（--no-update）：这个包永远等不到自动更新。")
    elif shell_cfg is not None:
        print("[pack] 第三方包按设计不内置 eraTW 更新源（避免把 eraTW 的新版推给本游戏）。")
        print("[pack] 要给自己留更新通道：--mirror https://<本游戏自己的清单目录>/，"
              "清单里的 versionCode 必须与本游戏账本一致。")
    else:
        print("[pack] ===== 警告：这个包**没有内置更新源** =====")
        print("[pack] 玩家装了它之后不会自动收到任何修复，而且屏上没有任何地方看得出来。")
        print("[pack] 要发外部包请给镜像地址：--mirror https://<你的国内目录>/ 写进 "
              f"{LOCAL_PROPS}（key: mirrors），或导出 {ENV_MIRRORS}")
        print(f"[pack] 确定这次不要更新通道请加 --no-update（开发包/内测包就该这样）")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
