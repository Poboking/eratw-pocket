#!/usr/bin/env python3
"""出包唯一入口 —— release APK，且**默认就把报错上报打开**。

## 为什么要有这个脚本

上报开关由两个 gradle 参数决定（`app/build.gradle.kts` 的 `resValue`）：

```text
-PeratwReportEndpoint=https://<worker>/report
-PeratwReportToken=<令牌>
```

**漏带 = APK 静默回到「不上报」**：gradle 不报错、编译全绿、包照样出，只是玩家崩了
没人知道。这个失败模式没有屏上痕迹，只能靠流程堵 —— 所以出包收敛到本脚本：
参数由它带，出完再**回读 APK 里的真实字符串**确认上报真的开着，读不到就非零退出。

## 用法

```bash
python devtools/pack_release.py                  # 全量包（带资源），上报开，更新源需显式配置（公开仓缺省为空）
python devtools/pack_release.py --skip-assets    # 小包（复用设备上已解包的数据）
python devtools/pack_release.py --offline        # 明确出「不上报」的包（会大声警告）
python devtools/pack_release.py --no-update      # 不内置更新源（开发/内测包）
python devtools/pack_release.py --verify-only    # 只体检已有 APK，不构建
python devtools/pack_release.py --dry-run        # 只打印 gradle 命令（不配令牌也放行）
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
   端点一份也收不到，玩家侧零痕迹。

1-2 不过 = 这个包不会上报；3 不过 = 包的形状与预期不符；4 不过 = 章不对（与钉值不符或 debug 章），
老包不能覆盖升级；5 回 403 = 令牌错，别发；6 不过 = 装上去也发不出来。

出完把 APK 复制一份到 `build/release-artifacts/eratw-<versionName>+<versionCode>-<git7>-
<full|noassets|skipassets>-<sha前8>.apk` 并配 `.sha256.txt`（gradle 输出目录会被下次构建
覆盖，归档名里带版本与产物指纹才认得出哪个包是哪个提交出的）。`--no-archive` 跳过复制。

本脚本只做「带参数 + 体检 + 归档」，不碰构建逻辑、不改任何 gradle 配置。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

# ---------------------------------------------------------------- 路径与环境

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)                 # <worktree>/eratw-android
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import update_manifest_check  # noqa: E402 同目录：设备端清单/镜像判式的单源（L2 起）

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
# 透传给 gradle 的额外参数（按 shlex.split 解析；调用方可追加 --profile 生成 task 级耗时报告）。
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

# 上报端点缺省值：**不在代码里写死**，从 local.report.properties 或环境变量取
# （ERATW_REPORT_ENDPOINT / ERATW_REPORT_TOKEN，或 local.report.properties 的
# endpoint / token 两键）。两者都缺时出包照常、上报链关闭并大声警告。
DEFAULT_ENDPOINT = ""

# 更新源缺省值：公开仓不内置任何真实更新域。需要内置更新源的出包请用
# --mirror / ERATW_UPDATE_MIRRORS / local.report.properties 显式提供。
DEFAULT_MIRRORS = ""

# ---------------------------------------------------------------- 出包自述：语料补丁独立列
# cb001 接线①：随包 manifest.txt 里 buildSrc（CorpusPatches.kt:326）写的是逐条
# `corpus-patch <id> <target> <bad-sha> <fixed-sha>` 留痕行；产物侧要能**不翻构建日志**
# 直接回答「这个包套了哪几个语料补丁」，故在 package.report 自述面另立一个独立列，
# 不把信息塞回留痕行的文本里。列值由脚本回读 manifest.txt 的真实留痕行逐项生成，
# 出包期与产物声明值逐项对齐（缺列或值不符非零退出，照回读核对纪律）。
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

# ---------------------------------------------------------------- 语料根（4.981 换代）

# 随包语料的**现行**根（merge 76659754 起随包语料 4.980 → 4.981）与**已退役**的旧根。
# `ERATW_GAMEDIR` 缺省回落时现行根优先；`.tooling/env.sh` 的缺省导出仍指旧根（共享基建，
# 不随本次换代改），所以「显式设了但指旧根」也要大声点出来 —— 否则出的是内部自洽的
# 4.980 数据包，却按 4.981 的版本号发出去（体检全绿，无一处拦得住）。
CURRENT_CORPUS_DIR = "eraTW4.981-261017-EAGLOV3"
RETIRED_CORPUS_DIR = "eratw-画蛇添足版eratw4.980"


def fallback_game_root(root: str) -> str:
    """`ERATW_GAMEDIR` 未设时的回落语料根：现行 4.981 优先，旧 4.980 只作机器缺新根时的兜底。"""
    for name in (CURRENT_CORPUS_DIR, RETIRED_CORPUS_DIR):
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
    """游戏根指向**已退役**的 4.980 语料且本机有 4.981 新根 ⇒ 拒出包（可显式放行）。

    4.981 起（merge 76659754）随包语料已换代；`.tooling/env.sh` 的缺省 `ERATW_GAMEDIR`
    仍指 4.980 —— source 过它再出包，得到的是「4.980 语料 + 当前版本号」的包：erbc 按同一
    语料编出、freshness 自洽、章也对，**没有任何一道既有体检拦得住**。这属于「漏了不报错」
    家族，所以做成硬闸；确要 4.980 数据的包（回归对拍之类）走 `--allow-retired-corpus`。
    """
    if not game_root or not os.path.isdir(game_root):
        return []
    root = eratw_root()
    if not root or not os.path.isdir(os.path.join(root, CURRENT_CORPUS_DIR)):
        return []                      # 本机没有新语料根：无从换代，按老规矩出
    if not same_dir(game_root, os.path.join(root, RETIRED_CORPUS_DIR)):
        return []
    return [
        f"游戏根指向已退役的 4.980 语料：{game_root}。随包语料自 4.981（merge 76659754）起"
        f"应为 {CURRENT_CORPUS_DIR} —— env.sh 的缺省 ERATW_GAMEDIR 还是旧根，source 过它会"
        "静默出「旧语料 + 新版本号」的包。修法：--game-root 或导出 ERATW_GAMEDIR 指向新根；"
        "确要 4.980 数据的包（回归对拍）加 --allow-retired-corpus。",
    ]


def eratw_root() -> str:
    """解析本地工具链/语料的共享根。

    优先读 `ERATW_ROOT` 环境变量；未设置时返回空串，由调用方判断是否需要该根。
    公开仓不内置任何个人机器路径。
    """
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

    缺省取 `DEFAULT_MIRRORS`（公开仓为空）。如果用户配了 `--no-update` 则强制清空。
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
    """Windows 下 .bat 必须经 cmd 拉起；其余平台直接跑 wrapper（统一 ./gradlew）。"""
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


def run_build(endpoint: str, token: str, skip_assets: bool, dry_run: bool = False,
              game_root: str = "", mirrors: str = "") -> int:
    tasks = ["--configure-on-demand", ":app:assembleRelease"]
    # 优化开关再给一次命令行写法：GRADLE_OPTS 里的 -Dorg.gradle.parallel/caching 取决于
    # 调用方是否 source 过优化环境，出包入口自带这两个开关谁调都一样；8.10.2 下实测与
    # --configure-on-demand 可共存。
    tasks += ["--parallel", "--build-cache"]
    # 构建标识：把「这个 APK 出自哪个提交」编进 BuildConfig，错误报告头部会带出来。
    # 2026-10-28 的教训：一小时内三个 0.2.0 包（缺权限的 / 缺重启修复的 / 都带上的）
    # versionName 完全一样，玩家报来的错根本分不清是哪个包 —— 只能靠人回忆。
    tasks.append(f"-PeratwBuildStamp={git_head()}")
    if skip_assets:
        tasks.append("-PeratwSkipAssets=1")
    elif game_root:
        tasks.append(f"-PeratwGameRoot={game_root}")
    if endpoint:
        tasks.append(f"-PeratwReportEndpoint={endpoint}")
    if token:
        tasks.append(f"-PeratwReportToken={token}")
    # 透传 ERATW_GRADLE_EXTRA_ARGS：放在任务名之后（Gradle 允许全局选项在任务名后），
    # 让调用方能追加 --profile 生成 task 级耗时报告；空值/未设置完全不追加。
    tasks += parse_gradle_extra_args()
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
    proc = subprocess.run(cmd, cwd=APP_ROOT, env=env)
    print(f"[pack] 构建耗时 {time.time() - started:.0f}s，退出码 {proc.returncode}")
    return proc.returncode


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
    if "com.eratw.port.android.updatefiles" not in text:
        problems.append("合并后的 manifest 没有 UpdateFileProvider —— APK 交不给系统安装器")
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
    """回读核对：package.report 的 `applied_corpus_patches` 独立列必须与
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
    只有确知降级来自「不许改源的第三方口上」时才加 `--allow-degraded-corpus`。
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
            "     如果确实需要换章（极罕见的泄露场景），须同步更新此处常量并在团队内公告。"
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
            return [f"端点回 {exc.code}（token 不符）—— 包里的令牌与服务端配置不配对，"
                    "装上去上报会全部 403。核对 local.report.properties 与服务端配置"]
        if exc.code == 500:
            return ["端点回 500（服务端未配置好）—— 先检查上报服务端的部署与配置"]
        print(f"[pack] 端点活体没验成：HTTP {exc.code} {detail[:80]!r} —— "
              "端点异常或请求被前置网关拦截，令牌配对状态未知；"
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


def archive_apk(kind: str, out_dir: str = "") -> str:
    """把 APK 复制成带版本/提交/包型/指纹的名字，落 `build/release-artifacts/`。

    gradle 的 `app-release.apk` 每次构建原地覆盖 —— 只留那儿等于「上周那个包」找不回来。
    名字里带 git7 与产物 sha8：需要确认「这个包是哪个提交出的」时不用回来翻日志。
    """
    name_v, code_v = version_from_gradle()
    dst_dir = out_dir or ARTIFACT_DIR
    os.makedirs(dst_dir, exist_ok=True)
    # 归档名里含 sha 前 8 位，先写临时文件，拿到 sha 后才 os.replace 成正式名。
    # 临时名带 pid：两个出包进程并发落同一目录时不会互相踩掉对方的半截文件。
    tmp = os.path.join(dst_dir, f".eratw-archive-{os.getpid()}.tmp")
    try:
        sha = digest_and_copy(APK_PATH, tmp)
        base = f"eratw-{name_v}+{code_v}-{git_head()}-{kind}-{sha[:8]}"
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
    parser.add_argument("--game-root", default="",
                        help="覆盖游戏根（缺省取 ERATW_GAMEDIR；缺了它包会静默退化成不随资源）")
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
                        help=f"放行「游戏根指向已退役 4.980 语料」的包（回归对拍内测专用；"
                             f"正式包语料必须是 {CURRENT_CORPUS_DIR}）")
    parser.add_argument("--no-archive", action="store_true",
                        help="不把 APK 复制进 build/release-artifacts/（省一份 743 MiB 磁盘）")
    parser.add_argument("--out", default="", help="覆盖归档目录（缺省 build/release-artifacts）")
    args = parser.parse_args(argv)

    endpoint, token, origin = resolve_report_config(args)
    mirrors, mirrors_origin = resolve_update_mirrors(args)
    if args.no_update:
        mirrors = ""

    # 语料换代闸（4.981 起）：出包游戏根还在指旧 4.980 语料就拒 —— env.sh 的缺省 ERATW_GAMEDIR
    # 仍是旧根，source 过它再出包会得到内部自洽、体检全绿的「旧语料 + 新版本号」包，无一处
    # 拦得住（见 [retired_corpus_problem]）。判读在这一拍做完；--verify-only 不构建不拦，
    # --dry-run 不拦但大声提示（两种模式的调用方要的是读数与命令形状，不是一次真出包）。
    era_problems = [] if args.allow_retired_corpus else retired_corpus_problem(effective_game_root(args.game_root))
    # 同一条洞的另一半：erbc 由 `ERATW_CORPUS` 编译、CSV/resources 由游戏根随包，两条指到
    # **不同**游戏根时同样内部自洽、体检全绿（见 [data_source_alignment] 的现网实证）。
    # `--verify-only` 只体检现成 APK、不产出新包，数据源怎么配都不影响这次判定。
    source_problems = [] if args.verify_only else data_source_alignment(args.skip_assets, args.game_root)

    if args.dry_run:
        # dry-run 不出包，所以不逼它配齐令牌：配不齐也要能看见命令长什么样。
        for item in era_problems + source_problems:
            print(f"[pack] 注意（dry-run）：{item}", file=sys.stderr)
        run_build(endpoint, token, args.skip_assets, True, args.game_root, mirrors)
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

    if not args.verify_only:
        code = run_build(endpoint, token, args.skip_assets, False, args.game_root, mirrors)
        if code != 0:
            print("[pack] 构建失败，没有可发的包。", file=sys.stderr)
            return 1

    kind_problems = report_package_kind(args.skip_assets, args.allow_stale_erbc)
    kind_problems += report_degraded_corpus(args.allow_degraded_corpus)
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
        archive_apk(kind, args.out)

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
