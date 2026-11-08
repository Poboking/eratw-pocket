#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""国内镜像仓的**发布目录生成器**（自动更新通道的发布侧唯一入口）。

    python devtools/emit_update.py --mirror https://eratw-android.asia/updates/
    python devtools/emit_update.py --skip-assets --note "只换新引擎"
    python devtools/emit_update.py --verify-only build/update-dist
    python devtools/emit_update.py --upload   # 推产物到 R2（需要 wrangler 登录）
    python devtools/emit_update.py --upload --prune-dry   # 只看清理计划，一个对象都不删
    python devtools/emit_update.py --rollout-percentage 10 --skip-assets   # 灰度先放 10%
    python devtools/emit_update.py --selftest            # 只跑留存判式（不碰网络）

出包（`devtools/pack_release.py`）只管把 APK 打出来；这个脚本管**怎么让玩家的设备找得到它**。
产出是一个可以直接丢进任何静态目录（或 R2 桶）的树：

    build/update-dist/
        manifest.txt                       客户端读的那份清单
        eratw-0.2.4+6-abc1234-full-….apk   整包（从 build/release-artifacts 复制）
        eratw-assets-<N>.zip               全量数据包（从游戏根现编，可选）
        eratw-assets-delta-<N>.zip         差量数据包（可选，有索引时自动算）
        eratw-assets-blockdelta-<N>.zip    块级差量包（可选，见「块级差量」段）
        assets-index.json                  文件级索引（用于下次算差量）

## 为什么数据包要单独编一份 zip

随包 APK 里 743 MiB 有 680 MiB 是数据。只改了一条 ERB 脚本或几张立绘时，让玩家重下整个包没有道理。
差量包只包含变更/新增文件 + 删除列表（`update-delta.txt`），设备侧只替换列出的文件并删掉标记的。

## 差量（§1.2）

- `assets-full-*` 三件套：与旧 `assets-*` 同形状（全量）
- `assets-delta-*` 三件套：仅变更文件 + `update-delta.txt`（删除列表，一行一条相对路径）
- `assets-blockdelta-*` 三件套（P2-13）：块级差量，见下面那一段
- `assets-base-version`：差量基线（盘上 data-version 恰好等于它才允许应用差量；**两档差量共用它**）

索引文件 `assets-index.json` 记录每次全量包的条目（路径→{size,sha256}），下次差量对比靠它。
删除项写入 delta zip 根内条目 `update-delta.txt`。

## 块级差量（P2-13，U8）

文件级差量的粒度是**文件**：一张 4.67 MiB 的立绘改了中部一个字节，玩家还是要重下整张图。
这条把粒度降到**块**（4 KiB，实测选的档位）：`eratw-assets-blockdelta-<N>.zip` 里每条
`update-blocks/<相对路径>` 是一份补丁，只写「旧文件的第几块照用 + 这几字节是新的」，
设备侧按块号从**盘上那份旧文件**取字节拼回去（实现见 `platform/.../BlockDeltaAssetTree.kt`）。

- **要基线包的字节**：块号指的是 `eratw-assets-<base>.zip` 里那条的偏移。本地没有那张旧全量包
  就**不发**这个对象（清单里连这三行都不写），设备侧照旧走文件级差量 —— 新键只加不改。
- **每文件各自算账**：补丁不比「整条重发」省 10% 以上就改发整条，所以这个对象**恒不大于**
  文件级差量包；一张被整体重编码的图会自动落回整条那一路。
- **三道摘要闸**：补丁头部带 `base-sha256`（拼之前验盘上那份）与 `result-sha256`（拼之后验结果），
  整个对象再吃清单的 `assets-blockdelta-sha256`。任何一道不过都是抛 + 整单回落全量。
- `--no-block-delta` 是这条通道的逃生门（发新版时一键只发文件级差量）。
- 体检会**逐条重放**包里的补丁（本地留着基线包时），拼出来与全量包不等就拒绝发布。

## 版本单调闸（§1.6）

生成前拉线上 `https://eratw-android.asia/updates/manifest.txt`（`--manifest-url` 覆盖）。
线上 `apk-version-code >= 本次` 则非零退出。`assets-version` 同理。
拉不到就大字告警并按「无线上记录」放行。

## 上传（R2）

`--upload` 把 update-dist 推到 R2 桶 `eratw-updates`（wrangler r2 object put），推完回读 sha 核对。
只推**当前清单引用的对象**（+ 两份清单本身），不推目录里剩下的历史产物 —— 否则每发一版都把
旧 APK 重推一遍，免费额度就是这么被吃光的。不推也正常出目录（保持现有能力）。

**超过 300 MiB 的对象改走 R2 的 S3 兼容口**（`s3_multipart_put`，64 MiB 一片）：
`wrangler r2 object put` 对 >300 MiB 直接拒（2026-11-02 实测：整包 744 MiB、数据包 386 MiB
都被拒 ⇒ 这条发布路的正式尺寸此前只在测试小包上绿过）。S3 口要一把**桶级 API 令牌**
（`Object Read & Write`，控制台 R2 → Manage R2 API Tokens 建；wrangler 那把 OAuth 令牌能读桶
但没有建凭据的权限），三个键写进 `local.r2.properties`（已 gitignore，与端点令牌同一个待遇）。
任何一步失败都**不推清单**（见 `abort`），线上继续停在上一版形状。

## 留存与清理（免费额度靠这条，不是靠记得手动删）

R2 免费档 = 10 GB-month 存储 / 100 万次 Class A（写与删）/ 1000 万次 Class B（读），
**出口流量不计价**。一版整包 + 数据包 ≈ 1.4 GB，所以不清理的话免费额度只够堆 7 版。

- 每次发布把清单另存一份 `manifest-<apk-version-code>.txt` 一起推上去 —— 它是桶里唯一的
  「这一版引用了哪些对象」的账，比在本地记台账可靠（换机器发版也读得到）。
- 上传全部成功并回读核对之后才清理：保留**最近 `--prune-keep` 版（缺省 2 = 当前 + 上一版，
  留出退路）**引用到的对象，更旧版引用到的对象逐个 `wrangler r2 object delete` 并回读确认没了。
  被保留清单（含当前 `manifest.txt`）引用的对象**永不删**，清单本身（`manifest.txt` /
  `manifest-<code>.txt`，各百来字节）也不删 —— 删了就等于把账烧掉，下次算不出该删什么。
- `--prune-keep 0` 关闭清理；`--prune-dry` 只打印计划不删。
- 清理只看得见「有 `manifest-<code>.txt` 账」的版本。在这个机制之前手工推上去的对象是孤儿，
  脚本不猜名字、只会把它们列进告警，要清就手工删。

## 数据源闸（更新通道半边，2026-11-04）

出包闸（pack_release）管 APK 那半边，这里管 OTA 那半边，三道：

- [data_source_gate]：数据包的游戏根必须是**现行语料根**且与 `ERATW_CORPUS` 同源
  （判据单源 = `pack_release.retired_corpus_problem` / `pack_release.data_source_alignment`）。
  混装 = 「4.981 脚本 + 4.980 CSV」，即现网 ITEMNAME:1000 越界的形状（env.sh 的缺省
  `ERATW_GAMEDIR` 还指旧根，忘了覆盖就是这一炸）。
- [erd_gate]：zip 里的 ERD 表集合必须与游戏根 ERB 下的 .ERD 文件**逐条相等**，
  差哪几张点名到哪条相对路径。缺表 = 三段下标运行期 CodeEE（SEMEN_MATRIX 现网形状）。
- [erbc_pack_gate]：数据包**自带** erbc.v1 时，`manifest.txt` 的每条 `source` 与当前语料
  逐文件对哈希、`build-mode` 必须 full、且 `program.erbc` 与本次发布的 APK 内同名条目
  **同一次编译**（sha 相等）——OTA 一应用就覆盖设备引擎，两边不同版等于把刚发的引擎
  顶掉。内测刻意分开时 `--allow-stale-erbc` 放行；旧语料对拍包 `--allow-retired-corpus`。

## 体检是双向的

写完清单会立刻按清单回读一遍（文件在不在、字节数对不对、sha 算不算得一致）。
不合格非零退出。

KDoc 注意：正文不出现块注释定界符字符对（AGENTS.md 铁律 5）。
"""

from __future__ import annotations

import argparse
import base64
import binascii
import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

import apk_truth  # 同目录：APK 本体的真实版本与真实签名章
import pack_release  # 同目录：数据源闸的**单源判据**（退役语料 + 语料↔游戏根同源）
import update_manifest_check  # 同目录：设备端清单语义的单源判式（L2，见该文件类头）

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
# 公开分仓布局：core-vm / render 等引擎源码在姐妹仓 eraKcore；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(APP_ROOT), "eraKcore"))
ARTIFACT_DIR = os.path.join(APP_ROOT, "build", "release-artifacts")
DEFAULT_OUT = os.path.join(APP_ROOT, "build", "update-dist")
BUILD_GRADLE = os.path.join(APP_ROOT, "app", "build.gradle.kts")
LOCAL_PROPS = os.path.join(APP_ROOT, "local.report.properties")
APK_PATH = os.path.join(APP_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")

MANIFEST_NAME = "manifest.txt"
ASSETS_INDEX_NAME = "assets-index.json"
DELTA_LIST_NAME = "update-delta.txt"
# 灰度发布百分比（P1-5）：0..100；缺席该键 = 设备侧按 100 全量。
KEY_ROLLOUT_PERCENTAGE = "rollout-percentage"

# 默认镜像基址（R2 + Worker 自定义域，2026-10 波）
DEFAULT_MIRROR = "https://eratw-android.asia/updates/"
# 默认线上清单 URL（版本单调闸用）
DEFAULT_MANIFEST_URL = "https://eratw-android.asia/updates/manifest.txt"

# 与设备侧的常量**逐字同值**（改一边必须改另一边）。
ARTIFACT_DIR_NAME = "erbc.v1"
ARTIFACT_FILES = ("program.erbc", "program.meta", "program.stmt.idx", "manifest.txt")
DEFAULT_DATA_DIRS = ("CSV", "resources", "sound", "plugins")
BAD_DIR = "bad"
# 设备侧 `GameAssetInstaller.DATA_DIRS` 的第六类（2026-11-03 随包补 ERD 表之后）：`ERB/`。
# 这里**不能**把它并进 DEFAULT_DATA_DIRS 走通用 walk——游戏根 ERB/ 下还有几千个 ERB/ERH
# 源码（运行期不读，编译产物 erbc.v1 取代），整目录打包会把它们全带进数据包；只有 `.ERD`
# 数据表是运行期读的（`UserKeywordTables.discover`），缺表时用户变量三段下标运行期 CodeEE
# （`SEMEN_MATRIX:角色:x:子宫内射精量`，4.981 真机实证）。见 stage_assets_zip 的 ERD 段。
ERB_DIR = "ERB"
ERD_SUFFIX = ".ERD"

NEVER_BUNDLED = ("emuera.config", "sav", ".install.lock", "update")

# R2 桶名（wrangler r2 object put 用）
R2_BUCKET = "eratw-updates"

# 上传后回读核对的缺省基址：玩家设备实际访问的那条路（Worker 的 /updates/ 公开面）。
# 清单里有 `mirror` 行时优先用它 —— 必须校验「客户端会用的那个地址」，否则等于校验了另一条链。
DEFAULT_UPDATES_BASE = "https://eratw-android.asia/updates/"


def _force_utf8_streams() -> None:
    """把 stdout/stderr 换成 UTF-8，写不动的字符顶掉而不是让进程当场炸。

    Windows 控制台缺省 cp936，而这个脚本每一行进度都是中文：2026-11-02 实测 `--help` 里的
    一个「⇒」直接把进程打成 UnicodeEncodeError 回溯。更坏的形状是跑到上传中途才撞上那颗字符
    —— 半套产物已经推上镜像，而清单还没推，设备侧看到的就是一条指向不存在对象的更新提示。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


_force_utf8_streams()


def log(text: str) -> None:
    print(f"[emit] {text}")


def warn_big(text: str) -> None:
    print(f"[emit] ========== {text} ==========")


def die(text: str) -> None:
    print(f"[emit] **不合格**：{text}", file=sys.stderr)
    sys.exit(1)


def version_from_gradle() -> tuple:
    """从 app/build.gradle.kts 读 (versionName, versionCode)。"""
    if not os.path.isfile(BUILD_GRADLE):
        die(f"找不到 {BUILD_GRADLE}")
    with open(BUILD_GRADLE, "r", encoding="utf-8") as fh:
        text = fh.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        die("app/build.gradle.kts 里读不到 versionName / versionCode")
    return name.group(1), code.group(1)


ARCHIVE_NAME = re.compile(r"^eratw-(?P<name>[^+]+)\+(?P<code>\d+)-")


def version_from_apk_name(path: str) -> tuple:
    """从归档名里读出版本。"""
    matched = ARCHIVE_NAME.match(os.path.basename(path))
    if not matched:
        return None
    return matched.group("name"), matched.group("code")


def read_local_props(path: str = LOCAL_PROPS) -> dict:
    """读一个 `key=value` 的本地配置（缺省 local.report.properties；S3 凭据用 local.r2.properties）。"""
    if not os.path.isfile(path):
        return {}
    out = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip()
    return out


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_mirror(url: str) -> str:
    """镜像基址校验：必须 https、无空白、无查询串，末尾补斜杠。"""
    trimmed = url.strip()
    if not trimmed.startswith("https://"):
        die(f"镜像必须是 https（设备侧会直接拒收）：{trimmed}")
    if any(c.isspace() for c in trimmed):
        die(f"镜像地址含空白：{trimmed!r}")
    if "?" in trimmed or "#" in trimmed:
        die(f"镜像地址是目录基址，不许带查询串或片段：{trimmed}")
    host = trimmed[len("https://"):].split("/")[0]
    if "." not in host:
        die(f"镜像地址的主机名看着不完整：{trimmed}")
    return trimmed if trimmed.endswith("/") else trimmed + "/"


def newest_artifact_apk() -> str:
    """从 build/release-artifacts 里取最新的归档 APK。"""
    if not os.path.isdir(ARTIFACT_DIR):
        return ""
    names = [n for n in os.listdir(ARTIFACT_DIR) if n.endswith(".apk")]
    if not names:
        return ""
    newest = max(names, key=lambda n: os.path.getmtime(os.path.join(ARTIFACT_DIR, n)))
    return os.path.join(ARTIFACT_DIR, newest)


def resolve_apk(arg_path: str) -> str:
    if arg_path:
        if not os.path.isfile(arg_path):
            die(f"--apk 指向的文件不存在：{arg_path}")
        return arg_path
    archived = newest_artifact_apk()
    if archived:
        return archived
    if os.path.isfile(APK_PATH):
        return APK_PATH
    die(f"找不到 APK：先出包（devtools/pack_release.py），或用 --apk 指一个")


def resolve_mirrors(args) -> list:
    """镜像列表：--mirror > ERATW_UPDATE_MIRRORS > local.report.properties > 缺省 R2 地址。"""
    if args.mirrors:
        return list(args.mirrors)
    env = os.environ.get("ERATW_UPDATE_MIRRORS", "").strip()
    if env:
        return [m for m in re.split(r"[|,\s]+", env) if m]
    props = read_local_props()
    raw = props.get("mirrors") or props.get("update_mirrors") or ""
    if raw:
        return [m for m in re.split(r"[|,\s]+", raw) if m]
    # 缺省用 R2 地址
    return [DEFAULT_MIRROR]


# ---------------------------------------------------------------- 版本单调闸

def fetch_remote_manifest(url: str, quiet: bool = False) -> dict | None:
    """拉线上清单，返回解析后的键值对；拉不到返回 None。

    `quiet=True` 给「挨个探历史版本号」那种扫描用：那里 404 是**预期的正常答案**，
    每次都大字告警会把真信号淹掉。
    """
    req = urllib.request.Request(url, method="GET",
                                 headers={"User-Agent": "eratw-emit/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", "replace")
    except (urllib.error.URLError, urllib.error.HTTPError, OSError) as exc:
        if not quiet:
            warn_big(f"拉不到线上清单（{exc}）—— 按「无线上记录」放行")
        return None
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or " " not in line:
            continue
        key, value = line.split(" ", 1)
        if key != "mirror":
            values[key] = value
    return values


def version_gate(remote: dict | None, local_apk_code: str, local_assets_ver: str) -> None:
    """版本单调闸（§1.6）：线上 apk-version-code/assets-version >= 本次则非零退出。

    「拿到了 200 但不是清单」必须比「拉不到」更严重：`fetch_remote_manifest` 只在**网络层**
    失败时返回 None（那种情况按「无线上记录」放行，已经在上面大声告警过）。200 回来的东西
    却缺 `update-format` / `apk-version-code`，说明这个地址后面站着的根本不是我们的清单
    （反向代理的错误页、桶里被换了对象、路径写错落到了 index.html）—— 这时候**没有基线可比**，
    静默放行等于把「版本单调」这道闸整条关掉。首次发布/内网测试用 `--skip-version-gate` 显式跳过。
    """
    if remote is None:
        return  # 拉不到已经告警过了，放行
    online_apk = (remote.get("apk-version-code") or "").strip()
    if "update-format" not in remote or not online_apk.isdigit():
        warn_big(f"版本单调闸：线上 {DEFAULT_MANIFEST_URL} 回了 200 但内容不是清单"
                 f"（update-format={remote.get('update-format')!r}、"
                 f"apk-version-code={online_apk!r}）—— 拿不到可比对的基线。")
        die("版本单调闸拒绝放行：线上清单地址后面不是一份清单（反向代理错误页/桶里对象被换/"
            "路径写错）。先确认这个地址对，或首次发布/内网测试加 --skip-version-gate 显式跳过。")
    online_assets = (remote.get("assets-version") or "").strip()
    if online_apk and local_apk_code:
        if int(online_apk) >= int(local_apk_code):
            die(f"版本单调闸：线上 apk-version-code={online_apk} >= 本次 {local_apk_code}。"
                "先抬 app/build.gradle.kts 的 versionCode 再出包。")
    if online_assets and local_assets_ver:
        if not online_assets.isdigit():
            die(f"版本单调闸：线上清单的 assets-version={online_assets!r} 不是纯数字，"
                "这条读数不可比（先修线上清单，或 --skip-version-gate 显式跳过）")
        if int(online_assets) >= int(local_assets_ver):
            die(f"版本单调闸：线上 assets-version={online_assets} >= 本次 {local_assets_ver}。"
                "数据包版本号只增不减。")


# ---------------------------------------------------------------- 文件级索引

def load_index(out_dir: str) -> dict | None:
    """读上次的 assets-index.json；不存在返回 None。"""
    path = os.path.join(out_dir, ASSETS_INDEX_NAME)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as fh:
        try:
            return json.load(fh)
        except json.JSONDecodeError:
            log(f"警告：索引文件损坏，按无索引处理：{path}")
            return None


def save_index(out_dir: str, version: str, entries: dict) -> None:
    """写本次的文件级索引。"""
    path = os.path.join(out_dir, ASSETS_INDEX_NAME)
    data = {"version": version, "entries": entries,
            "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, sort_keys=True, indent=2)
    log(f"索引已写：{path}")


def compute_delta(new_entries: dict, old_index: dict) -> tuple:
    """算差量：返回 (changed_or_new_paths, deleted_paths)。

    new_entries: {path: {"size": N, "sha256": "hex"}}
    old_index:   {"version": "N", "entries": {path: {"size": N, "sha256": "hex"}}}
    """
    if not old_index or "entries" not in old_index:
        return None, None  # 无基线索引，不生成差量
    old_entries = old_index["entries"]
    changed = []
    for path, meta in new_entries.items():
        old = old_entries.get(path)
        if old is None or old.get("sha256") != meta["sha256"]:
            changed.append(path)
    deleted = []
    for path in old_entries:
        if path not in new_entries:
            deleted.append(path)
    return changed, deleted


def delta_payload_paths(out_dir: str, values: dict) -> tuple:
    """差量包实际携带的路径集合 + 删除表点名的路径集合（文件级与块级两档合并）。

    块级补丁的条目名带 `update-blocks/` 前缀（`BLOCK_DELTA_PREFIX`），去掉前缀才是被替换的
    数据文件路径 —— 判「index 里改过的路径，差量到底带没带」时两边要按同一个名字比。
    """
    shipped, deleted = set(), set()
    for key in ("assets-delta-file", "assets-blockdelta-file"):
        name = values.get(key)
        if not name:
            continue
        path = os.path.join(out_dir, name)
        if not os.path.isfile(path):
            continue
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                entry = info.filename
                if entry == DELTA_LIST_NAME:
                    raw = zf.read(entry).decode("utf-8", errors="replace")
                    for line in raw.splitlines():
                        text = line[:-1] if line.endswith("\r") else line
                        if text.strip():
                            deleted.add(text.strip())
                    continue
                if entry.startswith(BLOCK_DELTA_PREFIX):
                    shipped.add(entry[len(BLOCK_DELTA_PREFIX):])
                else:
                    shipped.add(entry)
    return shipped, deleted


def index_baseline_problems(out_dir: str, values: dict) -> list:
    """`assets-index.json` 与**本地基线全量包**逐条 sha 对账（L7）。

    为什么这条闸必须存在：文件级差量是按「上一版索引 → 这一版索引」算出来的
    （`compute_delta`），而设备侧应用差量的前提是「盘上那份字节 == 基线全量包」。差量包若
    漏带一条改过的文件、或删除表漏点名一条已删文件，设备装完得到的是**旧字节盖着新索引**
    的混合树 —— 不会报错，只会在运行期一路出怪事。发布目录里恰好还留着基线包
    `eratw-assets-<base-version>.zip` 时，把三份东西（基线包、index、差量包）逐条对一遍就能
    现在抓住：index 里 sha 变了的路径必须由差量携带（含块级补丁映射回原路径），基线里有而
    index 里没了的路径必须出现在删除表里。

    没有基线包时**不能假装对过**：`warn_big` 明说这一版没有基线可对拍（放行，但记在输出里）。
    索引文件缺席不算问题（老发布目录本来就没有它）；JSON 坏了由调用方的既有检查报。
    """
    problems = []
    index_path = os.path.join(out_dir, ASSETS_INDEX_NAME)
    if not os.path.isfile(index_path):
        return problems
    try:
        with open(index_path, "r", encoding="utf-8") as fh:
            index = json.load(fh)
    except json.JSONDecodeError:
        return problems
    entries = index.get("entries") or {}
    if not isinstance(entries, dict):
        return problems
    declared_version = (values.get("assets-version") or "").strip()
    index_version = str(index.get("version") or "").strip()
    if declared_version.isdigit() and index_version.isdigit() \
            and declared_version != index_version:
        problems.append(f"索引版本与清单不一致：assets-index version={index_version}、"
                        f"清单 assets-version={declared_version}（下一版差量会按错基线算）")
    has_delta = bool(values.get("assets-delta-file") or values.get("assets-blockdelta-file"))
    if not has_delta:
        return problems
    base_raw = (values.get("assets-base-version") or "").strip()
    if not base_raw.isdigit() or int(base_raw) < 1:
        return problems  # 缺基线这件事 validate_manifest 已经报过
    base_zip = os.path.join(out_dir, f"eratw-assets-{base_raw}.zip")
    if not os.path.isfile(base_zip):
        warn_big(f"本版差量没有基线可对拍：发布目录里没有 eratw-assets-{base_raw}.zip，"
                 "assets-index 与基线的逐条 sha 对账跳过。这类发布没法证明差量装完就是"
                 "全量包的内容（设备端只会在运行期出怪事）。要闭环就把上一版全量包与这一版"
                 "放在同一个目录里再跑 --verify-only。")
        return problems
    with zipfile.ZipFile(base_zip) as zf:
        base_entries = build_assets_index(zf)
    shipped, deleted = delta_payload_paths(out_dir, values)
    diffs = []
    for path, meta in entries.items():
        sha = (meta or {}).get("sha256") if isinstance(meta, dict) else None
        base = base_entries.get(path)
        if base is None:
            if path not in shipped:
                diffs.append(f"index 里的新条目 {path} 不在差量包里（差量装完设备仍缺这个文件）")
            continue
        if base.get("sha256") == sha:
            continue
        if path not in shipped:
            diffs.append(f"{path} 的 sha 与基线包不同，但差量包没带它（设备装完还是旧字节）")
    for path in base_entries:
        if path in entries:
            continue
        if path not in deleted:
            diffs.append(f"{path} 基线包里有、index 里没了，但差量包的删除表没点名它"
                         "（设备装完会留着一个已下架文件）")
    if diffs:
        problems.append(
            f"assets-index 与基线全量包 eratw-assets-{base_raw}.zip 逐条对账不符（共 {len(diffs)} 条）"
            + "：" + "；".join(diffs[:6])
            + (f"；…另有 {len(diffs) - 6} 条" if len(diffs) > 6 else ""))
    elif not problems:
        # 其他判式（版本不一致等）已经报过时不再说「一致」——那会读成「全过了」。
        log(f"索引对账：assets-index 与基线包 eratw-assets-{base_raw}.zip 逐条 sha 一致"
            f"（差量携带 {len(shipped)} 条、删除 {len(deleted)} 条）")
    return problems


# ---------------------------------------------------------------- zip 构建

def build_assets_index(zf: zipfile.ZipFile) -> dict:
    """从 zip 提取条目级索引：path -> {size, sha256}。"""
    entries = {}
    for info in zf.infolist():
        if info.is_dir():
            continue
        digest = hashlib.sha256()
        with zf.open(info) as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        entries[info.filename] = {"size": info.file_size, "sha256": digest.hexdigest()}
    return entries


def stage_assets_zip(out_dir: str, game_root: str, erbc_dir: str, version: str,
                     stored: bool, include_bad: bool) -> tuple:
    """把游戏根 + 产物目录编成 zip，返回 (zip_path, entries_index)。"""
    if not os.path.isdir(game_root):
        die(f"游戏根不是目录：{game_root}（用 --game-root 或导出 ERATW_GAMEDIR）")
    target = os.path.join(out_dir, f"eratw-assets-{version}.zip")
    os.makedirs(out_dir, exist_ok=True)
    method = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    comp = zipfile.ZipFile(target, "w", compression=method, allowZip64=True)
    entries = 0
    try:
        artifact_src = erbc_dir or os.path.join(game_root, ARTIFACT_DIR_NAME)
        if os.path.isdir(artifact_src):
            for name in ARTIFACT_FILES:
                src = os.path.join(artifact_src, name)
                if os.path.isfile(src):
                    comp.write(src, f"{ARTIFACT_DIR_NAME}/{name}")
                    entries += 1
        if entries == 0:
            log("警告：数据包里没有 erbc.v1 产物（只有语料/立绘）。改过 ERB 就必须带 --erbc-dir。")
        data_dirs = list(DEFAULT_DATA_DIRS) + ([BAD_DIR] if include_bad else [])
        for rel_dir in data_dirs:
            root_dir = os.path.join(game_root, rel_dir)
            if not os.path.isdir(root_dir):
                log(f"跳过不存在的目录：{rel_dir}")
                continue
            for dirpath, dirnames, filenames in os.walk(root_dir):
                dirnames[:] = [d for d in dirnames if d not in NEVER_BUNDLED]
                for fname in filenames:
                    if fname in NEVER_BUNDLED or fname.endswith((".tmp", ".bak", ".part")):
                        continue
                    full = os.path.join(dirpath, fname)
                    arc = os.path.relpath(full, game_root).replace(os.sep, "/")
                    comp.write(full, arc)
                    entries += 1
        if not include_bad and os.path.isdir(os.path.join(game_root, BAD_DIR)):
            # bad/ 是设备侧 DATA_DIRS 六类之一，但数据包默认不收（55 MB / 6,572 个帧文本）。
            # 经整包装出的设备已带有它、数据包也不删包外条目，所以不算缺；但语料换代时该目录
            # 内容会跟着变（4.980→4.981 实测 6,572 帧全部重编）—— 不随包的设备会一直用旧帧
            # （只影响 BADAPPLE 演示的观感，LOADTEXT 得空串时是空帧全速循环那档）。在这里
            # 大声说一句，发版的人至少知道自己在选什么；要不要默认随包属发布面政策，不擅自翻。
            log(f"提示：数据包不含 {BAD_DIR}/（BADAPPLE 帧文本，--include-bad 可带上）。"
                "整包装出的设备已有该目录、数据包不删包外条目；语料换代后它的内容若变过，"
                "经更新通道的设备会一直用旧帧。")
        # ERB/ 只带 .ERD 数据表（ERB/ERH 源码不随数据包，与随包 stageGameAssets 的 2b 段
        # 同口径）：设备侧解包面 DATA_DIRS 含 ERB，包里没有这一段 ⇒ 经更新通道安装的设备
        # 缺表，用户变量三段下标运行期 CodeEE（2026-11-03 真机实证的那条）。
        erb_root = os.path.join(game_root, ERB_DIR)
        if os.path.isdir(erb_root):
            for dirpath, dirnames, filenames in os.walk(erb_root):
                dirnames[:] = [d for d in dirnames if d not in NEVER_BUNDLED]
                for fname in filenames:
                    if not fname.upper().endswith(ERD_SUFFIX):
                        continue
                    if fname in NEVER_BUNDLED or fname.endswith((".tmp", ".bak", ".part")):
                        continue
                    full = os.path.join(dirpath, fname)
                    arc = os.path.relpath(full, game_root).replace(os.sep, "/")
                    comp.write(full, arc)
                    entries += 1
    finally:
        comp.close()
    written = os.path.getsize(target)
    log(f"全量数据包：{os.path.basename(target)}，{entries} 条目，"
        f"{written / 1024 / 1024:.1f} MiB（{'STORED' if stored else 'DEFLATE'}）")
    # 建索引
    with zipfile.ZipFile(target, "r") as zf:
        index = build_assets_index(zf)
    return target, index


def stage_delta_zip(out_dir: str, full_zip: str, changed: list, deleted: list,
                    base_version: str, delta_version: str) -> str | None:
    """从全量 zip 提取 changed 条目 + 写 update-delta.txt，输出差量 zip。

    返回 None 表示无实质变更（changed 和 deleted 都空），不生成差量。
    """
    if not changed and not deleted:
        log("差量：无变更，不生成 delta。")
        return None
    target = os.path.join(out_dir, f"eratw-assets-delta-{delta_version}.zip")
    method = zipfile.ZIP_DEFLATED
    comp = zipfile.ZipFile(target, "w", compression=method, allowZip64=True)
    try:
        with zipfile.ZipFile(full_zip, "r") as src:
            for path in sorted(changed):
                try:
                    info = src.getinfo(path)
                    comp.writestr(info, src.read(info))
                except KeyError:
                    log(f"警告：索引里有 {path} 但 zip 里找不到，跳过")
        # 写删除列表
        delete_content = "\n".join(sorted(deleted)) + "\n" if deleted else ""
        comp.writestr(DELTA_LIST_NAME, delete_content)
    finally:
        comp.close()
    written = os.path.getsize(target)
    log(f"差量数据包：{os.path.basename(target)}，变更 {len(changed)} 条、删除 {len(deleted)} 条，"
        f"{written / 1024 / 1024:.1f} MiB")
    return target


# ---------------------------------------------------------------- 块级差量（P2-13，U8）

#: 差量 zip 里**补丁条目**的固定前缀（相对归档根）。名字本身不是游戏文件，设备侧按前缀
#: 认出来、剥掉前缀才是目标相对路径 —— 与 `update-delta.txt` 同一条「控制条目不落盘」口径。
BLOCK_DELTA_PREFIX = "update-blocks/"
#: 补丁魔数（首行，一字不差）。设备侧 `BlockDeltaAssetTree.MAGIC` 同值，改一边必须改另一边。
BLOCK_PATCH_MAGIC = "EBPD1"
#: 补丁格式版本（第二行 `fmt`）：只增不改，改了设备侧整条拒装并回落全量。
BLOCK_PATCH_FORMAT = "1"
#: 分块大小：**4 KiB**。这是实测选的，不是拍的 —— 同一份 4.67 MiB webp「中部改 1 字节」
#: 的补丁，4 KiB 档 9,933 B、16 KiB 档 17,946 B、64 KiB 档 66,028 B（块越大，插入点之后
#: 要重发的「对齐损失」越大）；而最坏情况（整份重编码）4 KiB 档也只多到 269,607 B。
#: 4 KiB 在「常见小改」上最省、在「最坏情况」上兜底最高，所以取它。
BLOCK_SIZE_BYTES = 4096
#: 每条 op 的定长开销：1 字节标签 + 4 字节大端整数。
BLOCK_OP_OVERHEAD = 5
#: 头部的硬上限（字节）：正常头一百来字节，超了只能是有人往文本行里塞东西。
BLOCK_PATCH_HEADER_MAX = 4096
#: 单张补丁允许引用的最大块数 = 旧文件长度上限的保护：索引本身按 4 字节走，
#: 但「旧文件比发布机内存还大」不该把生成侧打死。32 MiB / 4 KiB = 8,192 块封顶。
BLOCK_PATCH_MAX_BLOCKS = 32 * 1024 * 1024 // BLOCK_SIZE_BYTES

BLOCK_DELTA_ZIP_PREFIX = "eratw-assets-blockdelta-"


def _deflated_size(data: bytes) -> int:
    """这条内容进 zip（DEFLATE、缺省档）后大约占多少字节 —— 与 `stage_delta_zip`
    用的 `zipfile.ZIP_DEFLATED` 同档（zlib 缺省 level 6、raw 窗口），用来判
    「发补丁」和「发整条」哪个划算。差个 zip 头的几十字节不在这里算，
    反正判据留了 `BLOCK_PATCH_WIN_MARGIN` 的余量。
    """
    co = zlib.compressobj(6, zlib.DEFLATED, -15)
    return len(co.compress(data) + co.flush())


#: 补丁必须**明显**小过整条才值得走补丁：省 3 个字节却引入一次全文件重读 + 拼接，
#: 不划算。留 10% 的门槛（并且门槛只让结果更保守，永远不会让差量变大）。
BLOCK_PATCH_WIN_MARGIN = 0.9


def find_block_matches(old: bytes, new: bytes, block: int) -> dict:
    """旧文件的每一块在 new 里**完整相等**的起始位置：`new 偏移 -> 旧块号`。

    做法是「拿旧块的头部 16 字节去 new 里 `find`」（CPython 的 `bytes.find` 是 C 速度的
    Boyer-Moore-Horspool），命中后再逐字节比整块确认 —— 于是既不用给两侧写一套滚动哈希
    （那是唯一真正麻烦的部分，也是 rsync 类算法最容易写歪的部分），也不需要设备侧懂
    任何匹配逻辑：**设备只按块号取字节**。同一个位置被多块认领时先到先得（块号小的先）。

    只做「起点精确对齐」的匹配 ⇒ 一次插入会让其后所有块错位，直到下一个块边界才重新
    咬合 —— 这就是上表里「16 KiB 档比 4 KiB 档贵」的来源，代价被块长**封顶**（每次编辑点
    至多重发一个块的字面量），所以块长取小。
    """
    matches = {}
    total = len(old)
    index = 0
    for start in range(0, total, block):
        chunk = old[start:start + block]
        key = chunk[:16]
        index += 1
        if not key:
            continue
        at = new.find(key)
        while at >= 0:
            if new[at:at + len(chunk)] == chunk:
                matches.setdefault(at, index - 1)
            at = new.find(key, at + 1)
    return matches


def build_block_patch(old: bytes, new: bytes, block: int = BLOCK_SIZE_BYTES) -> tuple:
    """把 new 编成「按块引用旧文件 + 字面量」的补丁字节流。

    返回 `(patch_bytes, stats)`；`patch_bytes` 为 None 表示**不值得发补丁**
    （整条发出去更省，或旧文件为空）。调用方据此改发整条文件。

    正确性不依赖任何「匹配得对不对」的运气：每个块号都经过逐字节确认，拼接结果与
    `new` 逐字节相等由本函数的自校验保证 —— 校验不过直接返回 None（宁可退回整条，
    也不发一份拼不出来的补丁）。设备侧还要再拿 `result-sha256` 验一遍，两侧任一
    算错都只会得到「拒绝应用 + 回落全量」，不会得到一份悄悄不对的语料。
    """
    if not old or not new:
        return None, None
    blocks = [old[i:i + block] for i in range(0, len(old), block)]
    if len(blocks) > BLOCK_PATCH_MAX_BLOCKS:
        return None, None
    matches = find_block_matches(old, new, block)
    ops = []
    pos = 0
    copied = 0
    literals = 0
    for at, index in sorted(matches.items()):
        if at < pos:
            continue  # 已被前一块覆盖
        if at > pos:
            run = new[pos:at]
            ops.append((b"R", run))
            literals += len(run)
        ops.append((b"C", index))
        copied += 1
        pos = at + len(blocks[index])
    if pos < len(new):
        run = new[pos:]
        ops.append((b"R", run))
        literals += len(run)
    body = bytearray()
    for tag, payload in ops:
        if tag == b"C":
            body += tag + payload.to_bytes(4, "big")
        else:
            body += tag + len(payload).to_bytes(4, "big") + payload
    header = (
        f"{BLOCK_PATCH_MAGIC}\n"
        f"fmt {BLOCK_PATCH_FORMAT}\n"
        f"block {block}\n"
        f"base-sha256 {hashlib.sha256(old).hexdigest()}\n"
        f"result-sha256 {hashlib.sha256(new).hexdigest()}\n"
        f"result-size {len(new)}\n"
        f"op-count {len(ops)}\n"
        "\n"
    ).encode("ascii")
    patch = header + bytes(body)
    if _reassemble_for_check(old, patch[len(header):], block) != new:
        # 双保险：上面那段贪心若写歪，这里就地判死，绝不发出拼不回来的补丁。
        return None, None
    stats = {"blocks": len(blocks), "copied_ops": copied, "literal_bytes": literals,
             "patch_bytes": len(patch), "old_bytes": len(old), "new_bytes": len(new)}
    if len(patch) >= int(_deflated_size(new) * BLOCK_PATCH_WIN_MARGIN):
        return None, stats
    return patch, stats


def _reassemble_for_check(old: bytes, body: bytes, block: int) -> bytes | None:
    """按 op 流把旧文件与新字节拼回去（生成侧的自校验，与设备侧同一条读法）。

    任何一条 op 读不出完整字段 = 补丁写歪了，返回 None 让调用方判死。
    """
    out = bytearray()
    pos = 0
    end = len(body)
    while pos < end:
        tag = body[pos:pos + 1]
        num = int.from_bytes(body[pos + 1:pos + 5], "big")
        pos += 5
        if tag == b"C":
            chunk = old[num * block:(num + 1) * block]
            if not chunk:
                return None
            out += chunk
        elif tag == b"R":
            if pos + num > end:
                return None
            out += body[pos:pos + num]
            pos += num
        else:
            return None
    return bytes(out)


def stage_block_delta_zip(out_dir: str, full_zip: str, base_zip: str, changed: list,
                          deleted: list, delta_version: str) -> tuple:
    """生成**块级差量**包：能按块拼的只发补丁，其余照发整条，外加同一张删除表。

    返回 `(zip_path, 统计)`；没有实质变更返回 `(None, None)`；
    基线包不在本地（换机发版 / 被清理掉）返回 `(None, "缺基线包")` —— 这时清单**不写**
    `assets-blockdelta-*`，客户端照旧走文件级差量，一条已发出去的包都不会被打死。

    与 `stage_delta_zip` 的关键区别：那个只需要新全量包 + 旧索引，这个要**旧全量包的字节**
    （块号指的是旧文件里的偏移）。旧包从 `out_dir/eratw-assets-<base>.zip` 取，取不到就不发。
    """
    if not changed and not deleted:
        return None, "无变更"
    if not os.path.isfile(base_zip):
        return None, f"缺基线包 {os.path.basename(base_zip)}"
    target = os.path.join(out_dir, f"{BLOCK_DELTA_ZIP_PREFIX}{delta_version}.zip")
    stats = {"patched": 0, "whole": 0, "patch_bytes": 0, "whole_bytes": 0}
    comp = zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True)
    try:
        with zipfile.ZipFile(full_zip, "r") as new_zf, zipfile.ZipFile(base_zip, "r") as old_zf:
            for path in sorted(changed):
                try:
                    info = new_zf.getinfo(path)
                except KeyError:
                    log(f"警告：索引里有 {path} 但新全量包找不到，块级差量里跳过")
                    continue
                new_data = new_zf.read(info)
                old_data = None
                try:
                    old_data = old_zf.read(old_zf.getinfo(path))
                except KeyError:
                    old_data = None
                patch = None
                if old_data is not None:
                    patch, _ = build_block_patch(old_data, new_data)
                if patch is not None:
                    comp.writestr(BLOCK_DELTA_PREFIX + path, patch)
                    stats["patched"] += 1
                    stats["patch_bytes"] += len(patch)
                else:
                    comp.writestr(info, new_data)
                    stats["whole"] += 1
                    stats["whole_bytes"] += _deflated_size(new_data)
        # 删除表与文件级差量**同一个条目名、同一份口径**：设备侧走哪条路都由
        # ZipAssetTree 的既有解析兑现，新路径不新增第二条删除语义。
        delete_content = "\n".join(sorted(deleted)) + "\n" if deleted else ""
        comp.writestr(DELTA_LIST_NAME, delete_content)
    finally:
        comp.close()
    written = os.path.getsize(target)
    log(f"块级差量包：{os.path.basename(target)}，补丁 {stats['patched']} 条、"
        f"整条 {stats['whole']} 条、删除 {len(deleted)} 条，{written / 1024 / 1024:.1f} MiB")
    return target, stats


def split_block_patch(data: bytes) -> tuple:
    """补丁字节 → `(字段字典, op 流)`；形状不对返回 `(None, 原因)`。

    头部是**行式 ASCII**（不是 DER、不是 pickle）：设备侧 `BlockDeltaAssetTree` 用同一套
    键名与同一个「空行结束」判据解析，两侧任何一侧写歪都只会得到「拒绝应用 + 回落全量」。
    """
    magic_line = (BLOCK_PATCH_MAGIC + "\n").encode("ascii")
    if not data.startswith(magic_line):
        return None, "首行不是补丁魔数"
    sep = data.find(b"\n\n", 0, BLOCK_PATCH_HEADER_MAX)
    if sep < 0:
        return None, f"头部在 {BLOCK_PATCH_HEADER_MAX} 字节内没收在空行上"
    try:
        head = data[len(magic_line):sep].decode("ascii")
    except UnicodeDecodeError:
        return None, "头部不是 ASCII"
    fields = {}
    for line in head.split("\n"):
        if not line:
            continue
        if " " not in line:
            return None, f"头部行没有键值分隔：{line!r}"
        key, value = line.split(" ", 1)
        fields[key] = value
    if fields.get("fmt") != BLOCK_PATCH_FORMAT:
        return None, f"fmt 不是 {BLOCK_PATCH_FORMAT}：{fields.get('fmt')!r}"
    try:
        block = int(fields["block"])
        result_size = int(fields["result-size"])
        op_count = int(fields["op-count"])
    except (KeyError, ValueError):
        return None, f"block/result-size/op-count 不是整数：{sorted(fields)}"
    if block < 1 or block > 1024 * 1024:
        return None, f"block 越界：{block}"
    if result_size < 0 or op_count < 0:
        return None, f"result-size/op-count 为负：{result_size}/{op_count}"
    for key in ("base-sha256", "result-sha256"):
        value = fields.get(key, "")
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            return None, f"{key} 不是 64 位小写十六进制：{value!r}"
    return fields, data[sep + 2:]


def reassemble_block_patch(old: bytes, body: bytes, block: int) -> tuple:
    """按 op 流把旧文件拼回新文件：`(字节, None)` 或 `(None, 原因)`。

    与设备侧 `BlockDeltaAssetTree` 同一条读法（tag `C` + 4 字节大端块号 /
    tag `R` + 4 字节大端长度 + 原文），所以发布侧这一遍过 = 设备侧那一遍也过。
    """
    out = bytearray()
    pos = 0
    end = len(body)
    ops = 0
    while pos < end:
        tag = body[pos:pos + 1]
        if end - pos < 5:
            return None, f"op 头被截断（偏移 {pos}）"
        num = int.from_bytes(body[pos + 1:pos + 5], "big")
        pos += 5
        ops += 1
        if tag == b"C":
            chunk = old[num * block:(num + 1) * block]
            if not chunk:
                return None, f"块号越界或落在旧文件末尾之外：{num}"
            out += chunk
        elif tag == b"R":
            if pos + num > end:
                return None, f"字面量段超出补丁长度（需要 {num}，只剩 {end - pos}）"
            out += body[pos:pos + num]
            pos += num
        else:
            return None, f"未知的 op 标签：{tag!r}"
    return bytes(out), None


def verify_block_patches(block_zip: str, full_zip: str, base_zip: str | None) -> list:
    """逐条复核块级差量包里的补丁；返回问题列表（空 = 全过）。

    三道，按手里有什么字节决定能查到第几道：
    1. **形状**（永远查）：魔数 / fmt / 两个 sha / block / result-size，任何一条歪了
       就点名是哪一条 —— 客户端碰到形状歪的补丁只会整包回落全量（屏上看不见原因），
       所以这一道必须在发布侧就报出来；
    2. **对账**（永远查）：补丁声明的 `result-sha256` / `result-size` 必须等于**全量包里
       同一条目**的那一份 —— 补丁要还原的不是全量包里那份字节，玩家拿到的就是两份不同的语料；
    3. **重放**（只有本地还留着基线包才查）：拿基线包按 op 拼一遍，逐字节必须等于全量包
       里的那条。这一道是真正的「设备侧会怎么做」，能查出来就必须查。
    """
    problems = []
    try:
        with zipfile.ZipFile(block_zip, "r") as zf:
            patches = {}
            for info in zf.infolist():
                if info.is_dir() or not info.filename.startswith(BLOCK_DELTA_PREFIX):
                    continue
                with zf.open(info) as fh:
                    patches[info.filename[len(BLOCK_DELTA_PREFIX):]] = fh.read()
    except (zipfile.BadZipFile, OSError) as exc:
        return [f"块级差量包打不开：{block_zip}（{exc}）"]
    if not patches:
        return problems
    old_of = {}
    if base_zip and os.path.isfile(base_zip):
        try:
            with zipfile.ZipFile(base_zip, "r") as zf:
                for rel in patches:
                    try:
                        old_of[rel] = zf.read(rel)
                    except KeyError:
                        old_of[rel] = None
        except (zipfile.BadZipFile, OSError) as exc:
            problems.append(f"基线包打不开，块级补丁只查形状不查重放：{exc}")
    elif base_zip:
        problems.append("块级差量包已发出但本地没有基线包，无法重放校验"
                        f"（把 {os.path.basename(base_zip)} 放回发布目录再体检一次）")
    try:
        with zipfile.ZipFile(full_zip, "r") as zf:
            expected = build_assets_index(zf)
    except (zipfile.BadZipFile, OSError) as exc:
        return problems + [f"全量包打不开：{full_zip}（{exc}）"]
    for rel, data in patches.items():
        where = f"{BLOCK_DELTA_PREFIX}{rel}"
        fields, body = split_block_patch(data)
        if fields is None:
            problems.append(f"块级补丁形状不合格：{where}（{body}）")
            continue
        meta = expected.get(rel)
        if meta is None:
            problems.append(f"块级补丁指向全量包里没有的条目：{rel}")
            continue
        if fields["result-sha256"] != meta["sha256"]:
            problems.append(f"块级补丁的 result-sha256 与全量包不符：{rel}")
            continue
        if int(fields["result-size"]) != meta["size"]:
            problems.append(f"块级补丁的 result-size 与全量包不符：{rel}")
            continue
        if rel not in old_of:
            continue  # 没有基线包：第 3 道查不了，上面已经把「不能查」说在明处
        old = old_of[rel]
        if old is None:
            problems.append(f"块级补丁引用的基线条目不在基线包里：{rel}")
            continue
        if hashlib.sha256(old).hexdigest() != fields["base-sha256"]:
            problems.append(f"块级补丁声明的基线摘要与基线包不符：{rel}")
            continue
        got, why = reassemble_block_patch(old, body, int(fields["block"]))
        if got is None:
            problems.append(f"块级补丁重放失败：{where}（{why}）")
        elif hashlib.sha256(got).hexdigest() != meta["sha256"] or len(got) != meta["size"]:
            # 拼得出来但拼出的不是全量包里那一份 —— 这一条是第 3 道闸的全部意义：
            # 前两道只证明「补丁自洽」，这一条才证明「补丁还原的就是玩家该拿到的字节」。
            problems.append(f"块级补丁重放出来的字节与全量包不等：{rel}")
    return problems


# ---------------------------------------------------------------- 清单本体签名（CONTRACT-UPD1-3LANE §2 U1 病①）

#: 清单里签名三行的键名与取值：签名行恒为**最后一行**，`sig-alg` / `sig-key-id`
#: 在被签字节**之内**（客户端只去掉最后一行，其余全部字节原样进验签）。
SIGNATURE_KEY = "manifest-signature"
SIG_ALG_KEY = "sig-alg"
SIG_ALG_VALUE = "rsa-sha256"
SIG_KEY_ID_KEY = "sig-key-id"
#: 现役键 id（与设备侧 `ManifestSignature.KEY_ID_K1` 逐字同值；轮换时先在客户端补
#: next 公钥，再切发布侧的 id —— 两边谁也不猜）。
DEFAULT_SIG_KEY_ID = "k1"
#: 私钥的落盘目录（不入库，.gitignore 挡住；正式键由发布负责人生成，worker 自测用临时键）。
SIGNING_KEY_DIR = "local.update-signing"
#: SHA-256 的 PKCS#1 v1.5 `DigestInfo` 前缀（DER 编码的 AlgorithmIdentifier + NULL 参数）。
#: 固定字节，RFC 8017 §9.2 注 1；黄金签名判式靠它钉住整条签名链。
SHA256_DIGEST_INFO_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def parse_pkcs8_der(der: bytes) -> tuple:
    """解析 PKCS#8 PrivateKeyInfo 的 DER，返回 RSA 三元组 (n, e, d)。

    DER 结构按 RFC 5958 手写读：PrivateKeyInfo(SEQUENCE){ version, algorithm,
    privateKey OCTET STRING -> RSAPrivateKey(SEQUENCE){ version, n, e, d, ... } }。
    标准库没有 ASN.1 库，这段 TLV 读取换掉的是「往工程里塞 cryptography」——
    与 S3 SigV4 那段同一条纪律：发布侧零第三方依赖。
    """

    def tlv(data: bytes, pos: int) -> tuple:
        tag = data[pos]
        length = data[pos + 1]
        pos += 2
        if length & 0x80:
            nbytes = length & 0x7F
            length = int.from_bytes(data[pos:pos + nbytes], "big")
            pos += nbytes
        return tag, data[pos:pos + length], pos + length

    tag, info, _ = tlv(der, 0)
    if tag != 0x30:
        die(f"PKCS#8 DER 首字节不是 SEQUENCE（0x{tag:02x}）")
    tag, _version, pos = tlv(info, 0)
    tag, _alg, pos = tlv(info, pos)
    tag, keybytes, pos = tlv(info, pos)
    if tag != 0x04:
        die(f"PKCS#8 的 privateKey 字段形状不对（tag 0x{tag:02x}）")
    tag, rk, _ = tlv(keybytes, 0)
    if tag != 0x30:
        die("PKCS#8 的 privateKey 里不是 RSAPrivateKey")
    tag, _rkver, pos = tlv(rk, 0)
    tag, n_b, pos = tlv(rk, pos)
    tag, e_b, pos = tlv(rk, pos)
    tag, d_b, pos = tlv(rk, pos)
    n = int.from_bytes(n_b, "big")
    e = int.from_bytes(e_b, "big")
    d = int.from_bytes(d_b, "big")
    if n <= 0 or e <= 0 or d <= 0:
        die("PKCS#8 读不出 RSA 三元组（n/e/d 有非正值）")
    return n, e, d


def load_pkcs8_key(pem_path: str) -> tuple:
    """解析 PEM 的 PKCS#8 RSA 私钥，返回 (n, e, d)。

    只支持 PEM 的 PKCS#8 RSA 私钥（非 PKCS#1）；DER/加密钥直接判不合格。
    """
    with open(pem_path, "r", encoding="utf-8") as fh:
        text = fh.read()
    body = "".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith("-----")
    )
    try:
        der = base64.b64decode(body, validate=True)
    except (binascii.Error, ValueError) as exc:
        die(f"--sign-key 不是可解析的 PEM Base64：{pem_path}（{exc}）")
    if "RSA PRIVATE KEY" in text:
        die(f"--sign-key 要 PKCS#8，来的是 PKCS#1：{pem_path}")
    return parse_pkcs8_der(der)


def rsa_sign_sha256(data: bytes, key: tuple) -> bytes:
    """PKCS#1 v1.5 + SHA-256 签名（RFC 8017 §9.2），与 Java 的 `SHA256withRSA` 同一形状。

    EM = 0x00 0x01 || FF…FF || 0x00 || DigestInfo(SHA-256) || digest，模长定长；
    签名 = EM 的 RSA 私钥运算（`pow(em, d, n)`，Python 原生大整数模幂）。
    v1.5 是**确定性**签名：同一输入永远得到同一签名 —— selftest 里拿 openssl
    产出的黄金值逐字比对靠的就是这个性质。
    """
    n, _e, d = key
    k = (n.bit_length() + 7) // 8
    t = SHA256_DIGEST_INFO_PREFIX + hashlib.sha256(data).digest()
    pad_len = k - 3 - len(t)
    if pad_len < 8:
        die(f"RSA 模长太小（{k} 字节），装不下 PKCS#1 v1.5 编码")
    em = b"\x00\x01" + b"\xff" * pad_len + b"\x00" + t
    return pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")


def rsa_verify_sha256(data: bytes, signature: bytes, key: tuple) -> bool:
    """PKCS#1 v1.5 + SHA-256 验签（公钥运算），与设备侧 `ManifestSignature.verifyWith` 同判式。"""
    n, e, _d = key
    k = (n.bit_length() + 7) // 8
    if len(signature) != k:
        return False
    em = pow(int.from_bytes(signature, "big"), e, n).to_bytes(k, "big")
    if not em.startswith(b"\x00\x01"):
        return False
    sep = em.find(b"\x00", 2)
    if sep < 10:  # 2 字节头 + 至少 8 个 0xFF（RFC 8017 对 pad 的下限）
        return False
    if any(b != 0xFF for b in em[2:sep]):
        return False
    return em[sep + 1:] == SHA256_DIGEST_INFO_PREFIX + hashlib.sha256(data).digest()


def _is_probable_prime(n: int, rounds: int = 24) -> bool:
    """Miller-Rabin 概率素性测试（只用于 selftest 临时键，不参与生产签名）。"""
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47,
              53, 59, 61, 67, 71, 73, 79, 83, 89, 97):
        if n % p == 0:
            return n == p
    d = n - 1
    s = 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for _ in range(rounds):
        a = secrets.randbelow(n - 3) + 2
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _gen_prime(bits: int) -> int:
    while True:
        cand = secrets.randbits(bits) | (1 << (bits - 1)) | 1
        if (cand - 1) % 65537 == 0:
            continue
        if _is_probable_prime(cand):
            return cand


def generate_rsa_key(bits: int = 2048) -> tuple:
    """运行时生成一把 RSA 键（只给 selftest 用，私钥不落源码）。"""
    if bits < 1024:
        raise ValueError("RSA 位数太小")
    e = 65537
    while True:
        p = _gen_prime(bits // 2)
        q = _gen_prime(bits - bits // 2)
        if p == q:
            continue
        n = p * q
        if n.bit_length() != bits:
            continue
        phi = (p - 1) * (q - 1)
        d = pow(e, -1, phi)
        return n, e, d


def _der_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def _der_tlv(tag: int, content: bytes) -> bytes:
    return bytes([tag]) + _der_len(len(content)) + content


def _der_int(v: int) -> bytes:
    raw = v.to_bytes(max(1, (v.bit_length() + 7) // 8), "big")
    if raw[0] & 0x80:
        raw = b"\x00" + raw
    return _der_tlv(0x02, raw)


def rsa_key_to_pkcs8_der(key: tuple) -> bytes:
    """把 (n,e,d) 编成 PKCS#8 PrivateKeyInfo DER（供 selftest 覆盖解析路径）。"""
    n, e, d = key
    rsa_private = _der_tlv(0x30, _der_int(0) + _der_int(n) + _der_int(e) + _der_int(d))
    alg = _der_tlv(0x30, bytes.fromhex("06092a864886f70d010101") + b"\x05\x00")
    return _der_tlv(0x30, _der_int(0) + alg + _der_tlv(0x04, rsa_private))


def split_signed_portion(manifest_text: str) -> tuple | None:
    """清单文本 → (被签字节, 签名 Base64)；没有签名行返回 None。

    与设备侧 `ManifestSignature.splitSignedPortion` 同一条规则：签名行 = 文本里
    **最后一行** `manifest-signature <base64>`；被签字节 = 从文件头到该行起点前的一切
    字节（含前一行的行尾换行），签名行自己的行尾换行不在内。**不做任何归一化** ——
    签的就是发出去的那份字节。
    """
    line_start = 0
    sig_start = -1
    sig_value = ""
    length = len(manifest_text)
    while line_start < length:
        line_end = manifest_text.find("\n", line_start)
        if line_end < 0:
            line_end = length
        line = manifest_text[line_start:line_end]
        if line.endswith("\r"):
            line = line[:-1]
        if line.startswith(SIGNATURE_KEY + " "):
            sig_start = line_start
            sig_value = line[len(SIGNATURE_KEY) + 1:].strip()
        line_start = line_end + 1
    if sig_start < 0 or not sig_value:
        return None
    return manifest_text[:sig_start], sig_value
# ---------------------------------------------------------------- 数据源闸（更新通道）

def data_source_gate(game_root: str, allow_retired: bool) -> list:
    """更新通道的**数据源同源**判据 —— 与 `pack_release.py` 的 `retired_corpus_problem` /
    `data_source_alignment` 同一把尺（判据单源在 pack_release，这里只接线，不复制语义）。

    为什么出包闸挡了这条、这里还要挡：`ERATW_GAMEDIR` 的缺省（`.tooling/env.sh`）至今指
    4.980 旧根，而正式 APK 的随包语料自 merge 76659754 起是 4.981。数据包若从另一个根编，
    设备 OTA 完拿到的是「新引擎 + 旧 VariableSize.csv」—— 2026-11-03 现网 ITEMNAME:1000
    越界（报告 1793712013906 / 1793712297107，上报端点 1793712013906）就是这个形状：
    4.980 的 CSV/VariableSize.csv:74 是 `ITEMNAME,1000`，4.981 同位置 `ITEMNAME,1200`，
    而 4.981 的 `ERB/魔改内容/qol/qol_item.ERB:113` 按 `FOR ITEM_ID, 1, 1200` 读
    `ITEMNAME`（`VariableSizeCouplingTest` 有真语料断言）。`pack_release` 自 13da05bd
    拦得住 APK 半边，更新通道半边此前**没有任何一道闸**——本函数补上。
    """
    if not game_root:
        # 要编数据包却没有根：stage_assets_zip 会先用「游戏根不是目录」的死法点名，
        # 这里没有判据可查，不替它抢话。
        return []
    problems = [] if allow_retired else pack_release.retired_corpus_problem(game_root)
    problems += pack_release.data_source_alignment(False, game_root)
    return problems


def erd_gate(game_root: str, index_entries: dict) -> list:
    """数据包里的 ERD 表集合必须与 `<游戏根>/ERB` 下的 `.ERD` 文件**逐条相等**。

    表是**运行期**数据：原版在 ERB 目录下递归扫 `*.erd` 建用户变量关键字表
    （spec: ErhLoader.cs:342-365 `PrepareERDFileNames`），本侧 `UserKeywordTables.discover`
    同口径（core-vm UserKeywordTables.kt:95）。缺表 ⇒ `SEMEN_MATRIX:角色:x:子宫内射精量`
    这类三段下标运行期报「不能用字符串指定（未找到 ERD 表 …）」直接 CodeEE
    （报告 1793688405639，2026-11-03 14:46，app 0.2.39）。随包半边由 de0017a9 的
    stageGameAssets 2b 段、数据包半边由 04270e46 的 ERD 段兜住 —— 任何一侧回退，
    这道对拍当场红：差**哪几张表**直接点名到相对路径。
    """
    expected = set()
    erb_root = os.path.join(game_root, ERB_DIR)
    for dirpath, dirnames, filenames in os.walk(erb_root):
        dirnames[:] = [d for d in dirnames if d not in NEVER_BUNDLED]
        for fname in filenames:
            if fname.upper().endswith(ERD_SUFFIX):
                full = os.path.join(dirpath, fname)
                expected.add(os.path.relpath(full, game_root).replace(os.sep, "/"))
    have = {p for p in index_entries
            if p.upper().endswith(ERD_SUFFIX) and p.split("/", 1)[0] == ERB_DIR}
    problems = []
    missing = sorted(expected - have)
    extra = sorted(have - expected)
    if missing:
        problems.append(
            f"数据包缺 {len(missing)} 张 ERD 表（游戏根有、zip 里没有；经本包的设备三段下标会 CodeEE）："
            + "; ".join(missing[:10]) + (" …" if len(missing) > 10 else ""))
    if extra:
        problems.append(
            f"数据包多出 {len(extra)} 张游戏根里已不存在的 ERD 条目（打包根与判据根不符？）："
            + "; ".join(extra[:10]))
    if not expected and os.path.isdir(erb_root):
        # 判据仍是上面的「缺/多」差额（expected=0 且 have=0 时差额为零，包与根自洽）；
        # 这一句只是拿表姿势的提醒：4.981 语料实形有 3 张表，零表根大概率是拿错目录。
        log(f"提示：游戏根 {erb_root} 下一张 .ERD 都没有；4.981 语料实形为 3 张"
            "（ERB/DLC/完全新增的DLC模板/Item.ERD、ERB/カラム機能/COLUMNDIV@2.ERD、"
            "ERB/魔改内容/SEMEN_MATRIX@2.ERD）。若这是换代后的空壳根，别发。")
    return problems


def erbc_pack_gate(game_root: str, erbc_dir: str, index_entries: dict,
                   apk_path: str, allow_stale: bool) -> list:
    """数据包**自带 erbc.v1** 时的新鲜度与同源对拍（stageGameAssets CMP-66 的更新通道版）。

    判据（口径对齐 erbcc `ArtifactFreshness.check`，行式 = ErbcArtifacts 写的
    `manifest.txt`）：
    1. 有 `manifest.txt`：没有就证明不了产物出自哪份语料（CMP-66 同判）；
    2. `build-mode full`：受限档（`--limit`）产物不得随包（stageGameAssets 同政策）；
    3. `source` 行逐条对 `<游戏根>/ERB` 下的**当前**文件重哈希（缺文件/字节/摘要任一
       不符 ⇒ 产物与语料不同版）；语料里新增的 ERB/ERH 未被登记 ⇒ 产物是旧的
       （排除口径与 erbcc `listCorpusFiles` 一致：`.erb`/`.erh` 不区分大小写、
       `GOLDEN_` 前缀的金标采集副本不算源）；
    4. `artifact` 行与产物目录磁盘字节自证；
    5. **与整包对拍**：`erbc.v1/program.erbc` 的 sha 必须等于本次发布 APK 里
       `assets/erbc.v1/program.erbc` 的 sha —— OTA 会用包内 erbc 覆盖设备侧解包目录，
       两边不是同一次编译时，玩家刚从 APK 拿到的引擎立刻被旧产物顶掉（报告
       1793688405639 的设备就是「包与 OTA 双源」形状）。整包不随 erbc（skip-assets
       APK）时无从对拍，只提示不判红。

    `allow_stale`（`--allow-stale-erbc`）把 3–5 降级为警告（内测）；1–2 是形状判据，
    任何档都不放行。
    """
    artifact_src = erbc_dir or os.path.join(game_root, ARTIFACT_DIR_NAME)
    in_zip = sorted(p for p in index_entries if p.split("/", 1)[0] == ARTIFACT_DIR_NAME)
    if not in_zip:
        return []  # 数据包不带产物：stage_assets_zip 已大声警告过，引擎面由 APK 独源
    shape = []   # 形状判据（1–2）：任何档不放行
    fresh = []   # 新鲜度/同源判据（3–5）：--allow-stale-erbc 可降级为警告
    warns = []
    manifest = os.path.join(artifact_src, "manifest.txt")
    if "erbc.v1/manifest.txt" not in in_zip or not os.path.isfile(manifest):
        shape.append(
            f"数据包带了 erbc.v1 却没有可读的 manifest.txt（{manifest}）：证明不了产物"
            "出自哪份语料，别发（先跑 ./gradlew :tools:erbcc:compileCorpus）")
    else:
        sources = []
        artifacts = []
        build_mode = None
        patches = []  # `corpus-patch <id> <rel> <坏版sha> <修后sha>` 留痕行（口径同 ErbcFreshness.kt:289）
        # 解析口径 = erbcc `ArtifactFreshness.parse`（ArtifactFreshness.kt:401-412）：
        # 相对路径可能含空格（语料目录名就有），从**右**往左切，末两段是字节数与摘要。
        for raw in open(manifest, encoding="utf-8", errors="replace"):
            line = raw.rstrip("\r\n")
            bucket = None
            if line.startswith("source "):
                bucket = sources
            elif line.startswith("artifact "):
                bucket = artifacts
            elif line.startswith("corpus-patch "):
                # 路径可能含空格 → 与 ErbcFreshness 同一个右切法：末段=修后sha、
                # 倒数第二段=坏版sha、首段=id，其余整段是路径；切不出四段的行丢弃。
                rest = line[len("corpus-patch "):]
                fixed_sep = rest.rfind(" ")
                bad_sep = rest.rfind(" ", 0, fixed_sep) if fixed_sep > 0 else -1
                id_sep = rest.find(" ")
                if 0 < id_sep < bad_sep < fixed_sep:
                    patches.append((rest[:id_sep], rest[id_sep + 1:bad_sep],
                                    rest[bad_sep + 1:fixed_sep], rest[fixed_sep + 1:]))
                continue
            elif line.startswith("build-mode "):
                build_mode = line[len("build-mode "):].strip()
                continue
            else:
                continue
            parts = line.split(maxsplit=1)[1].rsplit(maxsplit=2)
            if len(parts) != 3 or not parts[1].isdigit():
                continue
            bucket.append((parts[0], int(parts[1]), parts[2]))
        if build_mode != "full":
            shape.append(
                f"erbc 产物是受限档（build-mode={build_mode or '缺'}）：只有 full 档能随包"
                "（口径同 stageGameAssets 的 CMP-66 第 1 条）")
        corpus_root = os.path.join(game_root, "ERB")
        patch_by_rel = {}
        for pid, prel, bad_sha, fixed_sha in patches:
            patch_by_rel.setdefault(prel, (pid, bad_sha, fixed_sha))
        recorded = set()
        reconciled = []
        for rel, size, want in sources:
            recorded.add(rel)
            src = os.path.join(corpus_root, rel.replace("/", os.sep))
            if not os.path.isfile(src):
                fresh.append(f"清单登记的源文件在游戏根不存在：ERB/{rel}"
                             "（产物与随包语料不同版）")
                continue
            # 编译期语料补丁豁免口（唯一）：磁盘=坏版 sha 且清单=修后版 sha 且两侧都与
            # 留痕行一致 → 按已应用放行（真语料只读，补丁只在编译期 staging 副本生效，
            # 磁盘慢一步是预期形状；口径 = ErbcFreshness.kt:314-324 的 patchReconciled）。
            trace = patch_by_rel.get(rel)
            if (trace is not None and want.lower() == trace[2].lower()
                    and sha256_of(src).lower() == trace[1].lower()):
                reconciled.append(f"{rel}（{trace[0]}）")
                continue
            got_size = os.path.getsize(src)
            if got_size != size or sha256_of(src).lower() != want.lower():
                fresh.append(f"源文件已改动但产物未重编：ERB/{rel}"
                             f"（清单 {size} 字节，磁盘 {got_size} 字节或摘要不符）")
        if reconciled:
            log(f"语料补丁对账放行 {len(reconciled)} 个目标（磁盘=坏版、清单=修后，与留痕行一致）："
                + "; ".join(reconciled[:5]) + (" …" if len(reconciled) > 5 else ""))
        added = []
        for dirpath, dirnames, filenames in os.walk(corpus_root):
            for fname in filenames:
                up = fname.upper()
                if not (up.endswith(".ERB") or up.endswith(".ERH")):
                    continue
                if fname.upper().startswith("GOLDEN_"):
                    continue  # 金标采集副本不是编译输入（erbcc isCollectionCopy 同判）
                rel = os.path.relpath(os.path.join(dirpath, fname), corpus_root)
                rel = rel.replace(os.sep, "/")
                if rel not in recorded:
                    added.append(rel)
        if added:
            fresh.append(
                f"语料新增 {len(added)} 个 ERB/ERH 未编进产物（先重编再发数据包）："
                + "; ".join(sorted(added)[:10]) + (" …" if len(added) > 10 else ""))
        for rel, size, want in artifacts:
            f = os.path.join(artifact_src, rel.split("/", 1)[-1].replace("/", os.sep)
                             if rel.startswith(ARTIFACT_DIR_NAME + "/") else rel.replace("/", os.sep))
            if not os.path.isfile(f):
                fresh.append(f"清单登记的产物文件不在磁盘上：{rel}")
            elif os.path.getsize(f) != size or sha256_of(f).lower() != want.lower():
                fresh.append(f"产物文件与清单不符（半途被改过？）：{rel}")
        # 与整包对拍
        prog_arc = f"{ARTIFACT_DIR_NAME}/program.erbc"
        zip_prog = index_entries.get(prog_arc)
        if zip_prog and os.path.isfile(apk_path):
            try:
                with zipfile.ZipFile(apk_path) as zf:
                    apk_prog = hashlib.sha256(zf.read(f"assets/{prog_arc}")).hexdigest()
            except (KeyError, zipfile.BadZipFile):
                apk_prog = None
            if apk_prog is None:
                warns.append("整包里没有 assets/erbc.v1/program.erbc（skip-assets APK）："
                             "erbc 单源在数据包这一侧，本次无从对拍")
            elif apk_prog.lower() != zip_prog["sha256"].lower():
                fresh.append(
                    "数据包与整包的 program.erbc 不是同一次编译（包内 sha 与 APK 内不符）："
                    "OTA 一应用，设备上刚装的引擎就被另一份产物顶掉 —— 两边的 erbc 必须"
                    "出自同一次 compileCorpus（要刻意分开发内测再加 --allow-stale-erbc）")
    if fresh and allow_stale:
        for p in fresh:
            log(f"警告（--allow-stale-erbc 放行）：{p}")
        fresh = []
    for w in warns:
        log(f"提示：{w}")
    return shape + fresh


# ---------------------------------------------------------------- 清单

def write_manifest(out_dir: str, apk_name: str, apk_size: int, apk_sha: str,
                   version_name: str, version_code: str,
                   assets_full: tuple | None,
                   assets_delta: tuple | None,
                   assets_base_version: str,
                   mirrors: list, note: str, generated: str,
                   cert_sha256: str = "",
                   min_supported_version: str = "",
                   rollout_percentage: int | None = None,
                   sign_key: tuple | None = None,
                   assets_block_delta: tuple | None = None) -> str:
    """写清单文件，含 §1.2 的差量三件套与块级三行（如适用）、P1-5 的灰度百分比与 §2 U1 的签名三行（给了私钥时）。"""
    lines = [
        "update-format 1",
        f"generated {generated}",
        f"apk-version-code {version_code}",
        f"apk-version-name {version_name}",
        f"apk-file {apk_name}",
        f"apk-size {apk_size}",
        f"apk-sha256 {apk_sha}",
    ]
    if cert_sha256:
        # 章不一致 = 这台设备覆盖升级装不上，必须在下载前让它自己看出来；
        # 老客户端把这行当未知键，只进告警、不影响解析（update-format 的向前兼容口径）。
        lines.append(f"apk-cert-sha256 {cert_sha256}")
    if assets_full:
        name, size, sha, version = assets_full
        lines += [
            f"assets-version {version}",
            f"assets-file {name}",
            f"assets-size {size}",
            f"assets-sha256 {sha}",
            # 全量三件套（§1.2 新键）
            f"assets-full-file {name}",
            f"assets-full-size {size}",
            f"assets-full-sha256 {sha}",
        ]
    if assets_delta:
        d_name, d_size, d_sha = assets_delta
        lines += [
            f"assets-delta-file {d_name}",
            f"assets-delta-size {d_size}",
            f"assets-delta-sha256 {d_sha}",
        ]
    if assets_block_delta:
        # 块级差量三件套（P2-13，U8）：与 `assets-delta-*` **共用同一个 `assets-base-version`**
        # （两者都是「相对那一版语料的 diff」，基线判据不另开一套）。
        # 缺席 = 本条通道不存在，旧客户端与新客户端都照走文件级差量：新键只加不改，
        # 已经发出去的包一个都不打死。
        b_name, b_size, b_sha = assets_block_delta
        lines += [
            f"assets-blockdelta-file {b_name}",
            f"assets-blockdelta-size {b_size}",
            f"assets-blockdelta-sha256 {b_sha}",
        ]
    if assets_base_version:
        lines.append(f"assets-base-version {assets_base_version}")
    if min_supported_version:
        # 强制更新档（§2 U1 病②）：低于它的客户端必须更新；缺席 = 无强制档（老口径不变）。
        lines.append(f"min-supported-version {min_supported_version}")
    if note:
        lines.append(f"note {note}")
    if rollout_percentage is not None:
        lines.append(f"{KEY_ROLLOUT_PERCENTAGE} {rollout_percentage}")
    for mirror in mirrors:
        lines.append(f"mirror {mirror}")
    if sign_key is not None:
        # 签名三行的次序是契约形状：`sig-alg` / `sig-key-id` 在被签字节**之内**，
        # `manifest-signature` 恒为最后一行 —— 被签字节 = 到 key-id 行（含其行尾换行）为止
        # 的全部字节，与设备侧 `ManifestSignature.splitSignedPortion` 的切割逐字一致。
        lines.append(f"{SIG_ALG_KEY} {SIG_ALG_VALUE}")
        lines.append(f"{SIG_KEY_ID_KEY} {DEFAULT_SIG_KEY_ID}")
        signed_body = "\n".join(lines) + "\n"
        signature = rsa_sign_sha256(signed_body.encode("utf-8"), sign_key)
        lines.append(f"{SIGNATURE_KEY} {base64.b64encode(signature).decode('ascii')}")
    path = os.path.join(out_dir, MANIFEST_NAME)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def parse_manifest(path: str) -> dict:
    """读发布目录里的清单 → `{"values": …, "mirrors": …, "problems": …}`。

    行扫描与 `update-format` 的判式**单源**在 `update_manifest_check.parse_manifest`
    （与设备端 `UpdateManifest.parse` 同一份直译；L2 之前这里是另一套宽松读法：无空格行
    直接跳过、重复键后写覆盖前写）。`problems` 非空 = 设备端会判 Rejected；调用方若只要
    键值（如 `r2_upload` 点名对象）可以忽略它，但**体检**必须把它当不合格。
    """
    with open(path, "r", encoding="utf-8", newline="") as fh:
        text = fh.read()
    values, mirrors, problems = update_manifest_check.parse_manifest(text)
    return {"values": values, "mirrors": mirrors, "problems": problems}


def rollout_percentage_problem(raw: str | None) -> str | None:
    """复核清单里的 rollout-percentage：**发布侧严、消费侧宽**。

    键缺席（raw is None）= 合格：老清单不需要补这一行，设备侧按 100 全量。
    写了键就必须是 0..100 的纯整数；空值、非数、小数、越界一律挡下 —— 发布侧要把它
    拦在发版之前，别让一份写坏的灰度值上线：设备侧会宽容回落到 100，那等于发布者
    以为开了灰度、实际全量，白测一场。设备侧的宽容口径在 UpdateManifest.readRolloutPercentage。
    """
    if raw is None:
        return None
    text = raw.strip()
    if not re.fullmatch(r"\d+", text):
        return f"{KEY_ROLLOUT_PERCENTAGE} 不是 0..100 的整数：{raw!r}（缺席该键 = 设备侧全量 100）"
    value = int(text)
    if not 0 <= value <= 100:
        return f"{KEY_ROLLOUT_PERCENTAGE} 越界（0..100）：{raw!r}"
    return None


# ---------------------------------------------------------------- 体检

def verify(out_dir: str, sign_key: tuple | None = None) -> list:
    """按清单回读发布目录：清单语义、签名、文件在不在、字节数对不对、sha 一致不一致。

    **清单语义与签名走设备端同一份判式**（`update_manifest_check`）：
    - `parse_manifest` 保留设备端会拒收的形状（无空格行 / 重复键 / 坏 update-format）；
    - `validate_manifest` 是发布侧的严判式（缺键、坏 sha、文件名带空格、note 换行、
      min-supported 越界、镜像写法不归一 —— 设备侧对这些只降级或只告警）；
    - `check_signature` **无条件**用设备内置 K1 复核：体检手上不需要私钥，验签只需要包内
      公钥。签名行不是最后一个非空行、`sig-alg`/`sig-key-id` 与设备端不同判、假签名、
      被改过字节，全部算不合格。签名缺失不算不合格（老清单形态），但会大声提示。

    [sign_key] 保留给生成流程的调用方（那里刚从 `--sign-key` 装载、并已核对过它与设备内置
    k1 同钥）；体检本身只认设备端信任锚，不用它。

    L2 之前这里的解析器是另一套宽松读法：缺 apk-version-name / apk-sha256 / assets-sha256、
    重复键、文件名带空格、note 换行、坏 min-supported 全部 exit 0 —— 而设备端会判 Rejected。
    """
    path = os.path.join(out_dir, MANIFEST_NAME)
    if not os.path.isfile(path):
        return [f"没有清单：{path}"]
    with open(path, "rb") as fh:
        raw_bytes = fh.read()
    problems = []
    try:
        text = raw_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        # 设备端把字节按替换字符读，被签字节必然对不上；这种清单不可能验过。
        problems.append(f"清单不是合法 UTF-8（{exc}）：被签字节无法原样还原")
        text = raw_bytes.decode("utf-8", "replace")
    values, mirrors, parse_problems = update_manifest_check.parse_manifest(text)
    problems += parse_problems
    if not parse_problems:
        problems += update_manifest_check.validate_manifest(text, values, mirrors)
        try:
            signature_problems = update_manifest_check.check_signature(text, rsa_verify_sha256)
        except update_manifest_check.ManifestCheckError as exc:
            problems.append(str(exc))
        else:
            problems += signature_problems
            if not signature_problems and SIGNATURE_KEY not in values:
                log("提示：清单未签名（整包替换攻击对这份清单无从发现；正式发版应给 --sign-key，"
                    "并确认这把私钥的公钥就是设备内置的 k1）")
    # 灰度新键的复核（--verify-only 走这一条）：缺席放行，写坏就拦下。
    rollout_problem = rollout_percentage_problem(values.get(KEY_ROLLOUT_PERCENTAGE))
    if rollout_problem:
        problems.append(rollout_problem)
    # 镜像的存在性与归一口径在 validate_manifest 里逐条判；这里只补一条发布侧政策：
    # 一份谁都能改的清单不该成为唯一来源，改源必须重出包。
    if not mirrors:
        problems.append("清单里没有一条 mirror（设备只会用内置表，改源必须重出包）")

    # apk 组
    apk_name = values.get("apk-file")
    if apk_name:
        apk_path = os.path.join(out_dir, apk_name)
        if not os.path.isfile(apk_path):
            problems.append(f"整包文件不在发布目录里：{apk_name}")
        else:
            ds = values.get("apk-size")
            if ds != str(os.path.getsize(apk_path)):
                problems.append(f"整包字节数不符：声明 {ds}，实为 {os.path.getsize(apk_path)}")
            dsha = values.get("apk-sha256")
            if dsha and dsha.lower() != sha256_of(apk_path):
                problems.append(f"整包摘要不符")
    # assets 组（旧键）
    assets_name = values.get("assets-file")
    if assets_name:
        ap = os.path.join(out_dir, assets_name)
        if not os.path.isfile(ap):
            problems.append(f"数据包文件不在发布目录里：{assets_name}")
        else:
            ds = values.get("assets-size")
            if ds != str(os.path.getsize(ap)):
                problems.append(f"数据包字节数不符：声明 {ds}")
            dsha = values.get("assets-sha256")
            if dsha and dsha.lower() != sha256_of(ap):
                problems.append(f"数据包摘要不符")
    # 全量三件套（§1.2 新键）
    af_file = values.get("assets-full-file")
    if af_file:
        afp = os.path.join(out_dir, af_file)
        if not os.path.isfile(afp):
            problems.append(f"assets-full-file 不在发布目录：{af_file}")
        else:
            afs = values.get("assets-full-size")
            if afs != str(os.path.getsize(afp)):
                problems.append(f"assets-full-size 不符：声明 {afs}")
            afsha = values.get("assets-full-sha256")
            if afsha and afsha.lower() != sha256_of(afp):
                problems.append("assets-full-sha256 不符")

    # 差量三件套
    ad_file = values.get("assets-delta-file")
    if ad_file:
        adp = os.path.join(out_dir, ad_file)
        if not os.path.isfile(adp):
            problems.append(f"assets-delta-file 不在发布目录：{ad_file}")
        else:
            ads = values.get("assets-delta-size")
            if ads != str(os.path.getsize(adp)):
                problems.append(f"assets-delta-size 不符：声明 {ads}")
            adsha = values.get("assets-delta-sha256")
            if adsha and adsha.lower() != sha256_of(adp):
                problems.append("assets-delta-sha256 不符")
    # 块级差量三件套（P2-13，U8）：与文件级差量同一套体检口径，一条都不放松。
    ab_file = values.get("assets-blockdelta-file")
    if ab_file:
        abp = os.path.join(out_dir, ab_file)
        if not os.path.isfile(abp):
            problems.append(f"assets-blockdelta-file 不在发布目录：{ab_file}")
        else:
            abs_ = values.get("assets-blockdelta-size")
            if abs_ != str(os.path.getsize(abp)):
                problems.append(f"assets-blockdelta-size 不符：声明 {abs_}")
            absha = values.get("assets-blockdelta-sha256")
            if absha and absha.lower() != sha256_of(abp):
                problems.append("assets-blockdelta-sha256 不符")
            # 逐条复核补丁：每条 update-blocks/ 补丁自己拼回去，result-sha256 必须等于
            # 全量包里同一条目。**这是发布侧的最后一道闸** —— 拼错了的设备只会回落全量，
            # 但一张发出去的坏补丁会把这一版的块级通道白白废掉，且没人知道废在哪。
            full_name = values.get("assets-full-file") or values.get("assets-file") or ""
            base_ver = (values.get("assets-base-version") or "").strip()
            base_zip = os.path.join(out_dir, f"eratw-assets-{base_ver}.zip") if base_ver.isdigit() else None
            problems += verify_block_patches(abp, os.path.join(out_dir, full_name), base_zip)
    # 索引文件一致性（可选但建议有）
    index_path = os.path.join(out_dir, ASSETS_INDEX_NAME)
    if os.path.isfile(index_path):
        try:
            with open(index_path, "r", encoding="utf-8") as fh:
                idx = json.load(fh)
            if "version" not in idx or "entries" not in idx:
                problems.append(f"索引文件缺少 version 或 entries 键：{index_path}")
        except json.JSONDecodeError as exc:
            problems.append(f"索引文件 JSON 损坏：{exc}")
    # 有差量包时，index 必须与本地基线全量包逐条对得上（判据与理由见 [index_baseline_problems]）
    problems += index_baseline_problems(out_dir, values)

    # 版本账（留存清理的指南针）：缺了它这一版的对象永远删不掉，而且下次上传直接不合格
    code = (values.get("apk-version-code") or "").strip()
    if code.isdigit():
        ledger = f"{MANIFEST_HISTORY_PREFIX}{code}.txt"
        if not os.path.isfile(os.path.join(out_dir, ledger)):
            problems.append(f"缺版本账 {ledger}（清理旧版按它点名对象；由生成流程与清单同批写出）")

    return problems


# ---------------------------------------------------------------- 留存与清理

#: 每次发布另存一份带版本号的账（`manifest-<apk-version-code>.txt`）一起推上去：
#: 桶里「哪一版引用了哪些对象」这件事只能由桶自己回答，本地台账换台机器就没了。
MANIFEST_HISTORY_PREFIX = "manifest-"
#: 清单里指向桶内对象的键（同一份全量 zip 会同时进 assets-file 与 assets-full-file，集合天然去重）
#: `assets-blockdelta-file` 必须在账里：漏了它，块级差量包既不会被 `--upload` 推上去
#: （玩家拿到 404），也不会被清理认账（永远留在桶里吃免费额度）。
ARTIFACT_MANIFEST_KEYS = ("apk-file", "assets-file", "assets-full-file", "assets-delta-file",
                          "assets-blockdelta-file")
#: 缺省保留最近两版（当前 + 上一版）。一版整包 + 数据包 ≈1.4 GB，R2 免费档只有 10 GB-month，
#: 留三版以上就开始吃紧；只留一版则新包翻车时没有退路（版本单调闸不许把 code 改回去）。
DEFAULT_PRUNE_KEEP = 2
#: 往回探多少个版本号找账。连号是常态（每次发版 code +1），探到 24 版之外早已出保留窗。
PRUNE_SCAN_WINDOW = 24


def manifest_object_names(values: dict) -> set:
    """一份清单引用到的桶内对象名（不含清单本身）。"""
    return {v for k in ARTIFACT_MANIFEST_KEYS if (v := values.get(k))}


def manifest_object_sizes(values: dict) -> dict:
    """对象名 → 清单声明的字节数（只为「这次省了多少」的账面，取不到按 0 计）。"""
    pairs = (("apk-file", "apk-size"), ("assets-file", "assets-size"),
             ("assets-full-file", "assets-full-size"), ("assets-delta-file", "assets-delta-size"),
             ("assets-blockdelta-file", "assets-blockdelta-size"))
    out = {}
    for name_key, size_key in pairs:
        name = values.get(name_key)
        size = values.get(size_key)
        if name and size and name not in out:
            try:
                out[name] = int(size)
            except ValueError:
                pass
    return out


def upload_names(values: dict, code: str) -> list:
    """该推的对象与推送次序：产物 → 这一版的账 → `manifest.txt`（最后）。

    次序不是讲究，是失败形状：中途断掉时线上顶多是「新产物齐、清单还是旧的」（玩家下次再取），
    绝不能是「清单已指向不存在的东西」（玩家点更新拿到 404，屏上只说取不到清单）。
    """
    names = sorted(manifest_object_names(values))
    ledger = f"{MANIFEST_HISTORY_PREFIX}{code}.txt"
    if ledger not in names:
        names.append(ledger)
    if MANIFEST_NAME not in names:
        names.append(MANIFEST_NAME)
    return names


def plan_prune(current_code: int, history: dict, keep: int) -> tuple:
    """纯判式：给定「版本号 → 那一版的清单」与保留版数，算出保留哪些版、删哪些对象。

    返回 `(retained_codes, pruned_codes, object_names)`，口径三条：
    - `keep <= 0` ⇒ 不清理，三个空表；保留 = 当前版自己 + 比它旧的最新 `keep-1` 版。
    - 待删 = 出窗那些版引用到的对象，**再减去**任何仍在保留窗内的清单引用的同一对象
      （只换引擎不换数据的那一版会与上一版共用同一个数据包 zip）。
    - 清单本身永不出现在待删表里（它们不是 `ARTIFACT_MANIFEST_KEYS`，也没人该删账）。
    """
    if keep <= 0:
        return [], [], []
    older = sorted((c for c in history if c < current_code), reverse=True)
    retained = older[:keep - 1]
    pruned = older[keep - 1:]
    inside = set(manifest_object_names(history.get(current_code, {})))
    for code in retained:
        inside |= manifest_object_names(history[code])
    names = []
    for code in pruned:
        for name in sorted(manifest_object_names(history[code])):
            if name not in inside and name not in names:
                names.append(name)
    return retained, pruned, names


def remote_state(url: str) -> str:
    """对象在不在线：`present` / `absent` / `unknown`。

    只有 `absent`（404/410）才算「删掉了」。网络抖动、路由没答、Worker 不许 HEAD 一律记
    `unknown` —— 拿 unknown 当 absent 就是「脚本报清理完成而桶里还堆着 3 GB」。
    """
    import urllib.error
    import urllib.request

    def probe(method: str, headers: dict) -> str:
        req = urllib.request.Request(url, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return "present" if resp.status in (200, 206) else "unknown"
        except urllib.error.HTTPError as exc:
            return "absent" if exc.code in (404, 410) else "unknown"
        except (urllib.error.URLError, OSError):
            return "unknown"

    state = probe("HEAD", {"User-Agent": "eratw-emit/1.0"})
    if state == "unknown":
        # 这条路上的 Worker 未必放行 HEAD；带 Range 取一字节是玩家端已经验通的另一把钥匙
        state = probe("GET", {"User-Agent": "eratw-emit/1.0", "Range": "bytes=0-0"})
    return state


def r2_prune(base: str, key_prefix: str, keep: int, dry: bool) -> int:
    """按桶里的版本账删掉出保留窗的旧对象（只在上传全部成功之后由主流程调）。"""
    if keep <= 0:
        log("留存清理：--prune-keep 0，本次一步没动（旧版对象照旧堆在桶里吃额度）。")
        return 0
    live = fetch_remote_manifest(base + MANIFEST_NAME)
    if not live:
        warn_big("留存清理跳过：线上 manifest.txt 取不到。盲删等于蒙着眼删玩家正在下的东西，宁可不删。")
        return 0
    current = (live.get("apk-version-code") or "").strip()
    if not current.isdigit():
        warn_big(f"留存清理跳过：线上清单的 apk-version-code={current!r} 不是纯数字")
        return 0
    current_code = int(current)
    history = {current_code: live}
    for code in range(current_code - 1, current_code - 1 - PRUNE_SCAN_WINDOW, -1):
        values = fetch_remote_manifest(f"{base}{MANIFEST_HISTORY_PREFIX}{code}.txt", quiet=True)
        if values and values.get("apk-file"):
            history[code] = values
    retained, pruned, names = plan_prune(current_code, history, keep)
    log(f"留存清理：保留最近 {keep} 版（当前 {current_code}"
        + (f" + {'、'.join(str(c) for c in retained)}" if retained else "；桶里没有上一版")
        + f"）；出窗 {len(pruned)} 版" + (f"（{'、'.join(str(c) for c in pruned)}）" if pruned else ""))
    sizes = {}
    for code in pruned:
        sizes.update(manifest_object_sizes(history[code]))
    total = sum(sizes.get(n, 0) for n in names)
    if not names:
        log("  没有可删对象（桶里只剩保留窗内的东西，或更早的版本压根没有账）。")
        log("  注意：这套清理只认 `manifest-<code>.txt` 点名的对象；机制之前手工推上去的是孤儿，"
            f"它看不见也不会猜名字，要清就手工清 {R2_BUCKET}/{key_prefix}")
        return 0
    if dry:
        log(f"  --prune-dry：计划删 {len(names)} 个对象（约 {total / 1024 / 1024:.1f} MiB），本次一个都不删：")
        for name in names:
            log(f"    - {key_prefix}{name}")
        return 0
    failed = 0
    for name in names:
        cmd = [(shutil.which("npx") or "npx"), "wrangler", "r2", "object", "delete",
               f"{R2_BUCKET}/{key_prefix}{name}", "--remote", "--force"]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", cwd=APP_ROOT, timeout=300)
        except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
            print(f"  [失败] 删 {name}: {exc}", file=sys.stderr)
            failed += 1
            continue
        if proc.returncode != 0:
            print(f"  [失败] 删 {name}: {(proc.stderr or proc.stdout)[:200]}", file=sys.stderr)
            failed += 1
            continue
        state = remote_state(base + name)
        if state == "present":
            print(f"  [失败] 删 {name}: wrangler 报成功而线上还取得到（路由/缓存没对上）", file=sys.stderr)
            failed += 1
            continue
        if state == "unknown":
            print(f"  [失败] 删 {name}: 线上状态取不到，按「未确认」计，不当已删", file=sys.stderr)
            failed += 1
            continue
        log(f"  已删 {name}（{sizes.get(name, 0) / 1024 / 1024:.1f} MiB）· 线上确认已无")
    if failed:
        die(f"留存清理有 {failed} 项没确认删净：现在桶里的账与保留窗不一致，"
            f"下次发布前先手工核对 {R2_BUCKET}/{key_prefix}")
    log(f"留存清理完成：释放约 {total / 1024 / 1024:.1f} MiB（保留窗 {keep} 版）。")
    return 0


# ---------------------------------------------------------------- S3 传输（wrangler 之上那 300 MiB）

#: wrangler 对 `r2 object put` 的硬上限。2026-11-02 实测：整包 744 MiB 与数据包 386 MiB 都被
#: `Wrangler only supports uploading files up to 300 MiB in size` 拒掉 —— 也就是说 `--upload`
#: 这条路的**正式尺寸从来没被真包跑通过**，只在测试目录的小包上绿过。超过它必须走 S3 兼容面。
WRANGLER_PUT_LIMIT = 300 * 1024 * 1024
#: R2 的 SigV4 credential scope 里 region 固定是 `auto`（写真实地理区域会得到 SignatureDoesNotMatch）。
S3_REGION = "auto"
S3_SERVICE = "s3"
#: 分片：S3 规定除末片外最小 8 MiB。取 64 MiB ⇒ 744 MiB 整包 12 片，断线只重发一片而不是整包重传。
S3_PART_SIZE = 64 * 1024 * 1024
S3_PART_RETRY = 3
#: 上传后回读的重试（2026-11-03 实测：大对象分片 complete 后边缘可见性有分钟级延迟，
#: 单次立即回读把「还没传播到」误判成「路由没对上」，白白 abort 一轮已传 1 GiB 的上传）。
#: 30 s 起步线性退避，5 次（30+60+90+120 = 最多等 300 s）全空才判绑错。
READBACK_ATTEMPTS = 5
READBACK_RETRY_SECONDS = 30
#: 桶级 S3 凭据（不入库；`.gitignore` 里与 local.report.properties 同批挡住）
R2_CREDS_NAME = "local.r2.properties"


def load_r2_creds(path: str = "") -> dict:
    """读桶级 S3 凭据：`r2_access_key_id` / `r2_secret_access_key` / `r2_account_id`。

    取不到就**把去控制台哪几页点出来**，别让下一个人去猜：wrangler 那把 OAuth 令牌能读桶
    （`GET /accounts/<id>/r2/buckets` 200）但没有管凭据的权限（`/tokens` 403），
    所以这把令牌只能在 Cloudflare 控制台手工建。
    """
    props = read_local_props(path or os.path.join(APP_ROOT, R2_CREDS_NAME))
    need = ("r2_access_key_id", "r2_secret_access_key", "r2_account_id")
    missing = [k for k in need if not props.get(k)]
    if missing:
        die("整包与数据包都超过 wrangler 的 300 MiB 上限，必须走 R2 的 S3 兼容口，而桶级 S3 凭据没配。\n"
            f"  缺的键：{' '.join(missing)}（写进 {os.path.join(APP_ROOT, R2_CREDS_NAME)}，该文件已 gitignore）\n"
            "  建法（控制台，wrangler 令牌没有建凭据的权限）：Cloudflare Dashboard → R2 → Manage R2 API Tokens\n"
            "    → Create API Token → 选 Object Read & Write → 限定桶 eratw-updates → 创建\n"
            "    → 在令牌列表点 Get Access Key & Secret Key（选 eratw-updates）→ 密钥只显示一次\n"
            "  写好后重跑 --upload（幂等：已推上去的对象会被覆盖重写）")
    return props


def s3_uri_encode(value: str, keep_slash: bool = False) -> str:
    """AWS 的未保留字符集外一律百分号编码（`+` 必须变成 `%2B`，否则签名与线上对象名两回事）。"""
    out = []
    for ch in value:
        if ch.isalnum() or ch in "-._~" or (keep_slash and ch == "/"):
            out.append(ch)
        else:
            for byte in ch.encode("utf-8"):
                out.append(f"%{byte:02X}")
    return "".join(out)


def s3_sign(secret: str, date_stamp: str, payload: bytes) -> bytes:
    key = ("AWS4" + secret).encode("utf-8")
    for part in (date_stamp, S3_REGION, S3_SERVICE, "aws4_request"):
        key = hmac.new(key, part.encode("utf-8"), hashlib.sha256).digest()
    return hmac.new(key, payload, hashlib.sha256).digest()


def s3_canonical_path(bucket: str, key: str) -> str:
    """桶名 + 对象键**只编一次**的路径；签名的 canonical URI 与请求 URL 必须共用它。

    编码做两遍就是另一条 URI：已含 `%2B` 的键再编一次变成 `%252B`，R2 回
    `403 SignatureDoesNotMatch`（2026-11-02 正式上传实测死在 CreateMultipartUpload）。
    键里没有特殊字符时两种写法逐字相同 —— 所以合成名的冒烟绿，而带 `+` 的正式产物名当场死。
    """
    encoded_key = "/".join(s3_uri_encode(seg) for seg in key.split("/"))
    return f"/{s3_uri_encode(bucket)}/{encoded_key}"


def s3_request(creds: dict, method: str, key: str, query: str = "", body: bytes = b"",
               extra_headers: dict | None = None, timeout: int = 600):
    """签一个请求打到 `https://<account>.r2.cloudflarestorage.com/<bucket>/<key>`。

    只标准库：`hmac` + `hashlib` + `urllib`，不为此往工程里塞 boto3。
    返回 (状态码, 响应头, 响应体)；非 2xx 不抛，由调用方决定是重试还是中止 ——
    分片上传的失败面（`SignatureDoesNotMatch` / `InvalidPart` / 断线）要分开处置。
    """
    import urllib.error
    import urllib.request
    ak = creds["r2_access_key_id"]
    sk = creds["r2_secret_access_key"]
    account = creds["r2_account_id"]
    bucket = creds.get("r2_bucket") or R2_BUCKET
    # 键按段编码一次（段内 `+` → %2B，段间 `/` 留着）；下面签名与 URL 用的就是这一个形态
    path = s3_canonical_path(bucket, key)
    host = f"{account}.r2.cloudflarestorage.com"
    url = f"https://{host}{path}" + (f"?{query}" if query else "")
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    headers = {"host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": amz_date}
    headers.update(extra_headers or {})
    signed_names = sorted(headers)
    canonical_headers = "".join(f"{n}:{headers[n].strip()}\n" for n in signed_names)
    signed_headers = ";".join(signed_names)
    canonical_query = ""
    if query:
        pairs = [p.split("=", 1) for p in query.split("&") if p]
        canonical_query = "&".join(
            f"{s3_uri_encode(p[0])}={s3_uri_encode(p[1] if len(p) > 1 else '')}"
            for p in sorted(pairs)
        )
    canonical_request = "\n".join([
        method, path, canonical_query,
        canonical_headers, signed_headers, payload_hash,
    ])
    scope = f"{date_stamp}/{S3_REGION}/{S3_SERVICE}/aws4_request"
    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, scope,
        hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
    ])
    signature = s3_sign(sk, date_stamp, string_to_sign.encode("utf-8")).hex()
    headers["authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={ak}/{scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    req = urllib.request.Request(url, method=method, data=body or None,
                                headers={k: v for k, v in headers.items() if k != "host"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers or {}), exc.read()
    except (urllib.error.URLError, OSError) as exc:
        return 0, {}, str(exc).encode("utf-8")


def s3_multipart_put(creds: dict, key: str, fpath: str, part_size: int = S3_PART_SIZE) -> None:
    """分片把一个对象写进桶：Create → UploadPart×N → Complete，任一片三次都失败就 Abort 并抛。

    为什么不是整块单 PUT：`wrangler` 那条 300 MiB 墙挡的就是整块；而单 PUT 5 GiB 虽然 S3 允许，
    一条慢线路上 744 MiB 传到第 9 分钟断掉只能整包重来。分片还顺手给了进度。
    """
    total = os.path.getsize(fpath)
    status, _, body = s3_request(creds, "POST", key, query="uploads=")
    if status not in (200, 201):
        raise RuntimeError(f"CreateMultipartUpload 失败 {status}: {body[:220]!r}")
    upload_id = _xml_text(body, "UploadId")
    if not upload_id:
        raise RuntimeError(f"响应里没有 UploadId: {body[:220]!r}")
    parts = []
    index = 1
    try:
        with open(fpath, "rb") as fh:
            while True:
                chunk = fh.read(part_size)
                if not chunk:
                    break
                for attempt in range(1, S3_PART_RETRY + 1):
                    status, headers, resp = s3_request(
                        creds, "PUT", key,
                        query=f"partNumber={index}&uploadId={s3_uri_encode(upload_id)}",
                        body=chunk,
                        extra_headers={"content-length": str(len(chunk))},
                        timeout=1800,
                    )
                    if status in (200, 201) and headers.get("ETag"):
                        parts.append((index, headers["ETag"]))
                        log(f"    part {index} · {len(chunk) / 1024 / 1024:.1f} MiB 已写"
                            f"（累计 {min(total, index * part_size) / 1024 / 1024:.0f}/{total / 1024 / 1024:.0f} MiB）")
                        break
                    if attempt == S3_PART_RETRY:
                        raise RuntimeError(f"分片 {index} 三次都失败 {status}: {resp[:220]!r}")
                    log(f"    part {index} 第 {attempt} 次失败（{status}），退避后重试")
                    time.sleep(2 * attempt)
                index += 1
    except Exception as exc:
        s3_request(creds, "DELETE", key, query=f"uploadId={s3_uri_encode(upload_id)}")
        raise RuntimeError(f"{key} 分片上传中止：{exc}（UploadId 已 Abort，桶里不留半成品）") from exc
    xml = ["<CompleteMultipartUpload>"]
    for number, etag in parts:
        xml.append(f"<Part><PartNumber>{number}</PartNumber><ETag>{etag}</ETag></Part>")
    xml.append("</CompleteMultipartUpload>")
    payload = "".join(xml).encode("utf-8")
    status, _, resp = s3_request(creds, "POST", key, query=f"uploadId={s3_uri_encode(upload_id)}",
                                 body=payload, extra_headers={"content-length": str(len(payload))})
    if status not in (200, 201):
        raise RuntimeError(f"CompleteMultipartUpload 失败 {status}: {resp[:220]!r}")


def _xml_text(body: bytes, tag: str) -> str:
    """从响应里取一个标签的文本（S3 响应带命名空间，按本地名找最省事）。"""
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return ""
    for node in root.iter():
        if node.tag.rsplit("}", 1)[-1] == tag and (node.text or "").strip():
            return node.text.strip()
    return ""


def retention_selftest() -> int:
    """留存判式与 S3 签名的定向自测（不碰网络、不碰桶）。"""
    checks = 0

    def ok(label: str, cond: bool) -> None:
        nonlocal checks
        if not cond:
            raise AssertionError(f"retention selftest 失败：{label}")
        checks += 1

    def m(code: int, apk: str, zip_name: str = "", delta: str = "") -> dict:
        values = {"apk-version-code": str(code), "apk-file": apk}
        if zip_name:
            values["assets-file"] = zip_name
            values["assets-full-file"] = zip_name
        if delta:
            values["assets-delta-file"] = delta
        return values

    hist = {10: m(10, "a10.apk", "z10.zip"), 11: m(11, "a11.apk", "z10.zip"),
            12: m(12, "a12.apk", "z12.zip", "d12.zip")}
    kept, out, names = plan_prune(12, hist, keep=2)
    ok("保留窗含上一版", kept == [11] and out == [10])
    ok("出窗版的整包与独占数据包被删", names == ["a10.apk"])
    ok("与保留版共用的 zip 不删", "z10.zip" not in names)
    kept, out, names = plan_prune(12, hist, keep=1)
    ok("keep=1 只留当前版", kept == [] and sorted(names) == ["a10.apk", "a11.apk", "z10.zip"])
    kept, out, names = plan_prune(12, hist, keep=0)
    ok("keep=0 = 不清理", kept == [] and out == [] and names == [])
    kept, out, names = plan_prune(12, {12: hist[12], 9: m(9, "a9.apk")}, keep=2)
    ok("版本账有洞也不编造", kept == [9] and names == [])
    kept, out, names = plan_prune(12, {12: hist[12], 11: m(11, "a11.apk", "z11.zip"),
                                       10: m(10, "a10.apk", "z10.zip"),
                                       8: m(8, "a8.apk", "z8.zip")}, keep=3)
    ok("keep=3 留三版", kept == [11, 10] and names == ["a8.apk", "z8.zip"])
    ok("全量两把键认同一个对象", manifest_object_names(hist[12]) == {"a12.apk", "z12.zip", "d12.zip"})
    sizes = manifest_object_sizes({"apk-file": "a.apk", "apk-size": "743",
                                   "assets-file": "z.zip", "assets-size": "680",
                                   "assets-full-file": "z.zip", "assets-full-size": "680",
                                   "assets-delta-file": "d.zip", "assets-delta-size": "坏值"})
    ok("账面字节按对象归并且坏值不进账", sizes == {"a.apk": 743, "z.zip": 680})
    order = upload_names({"apk-file": "a.apk", "assets-full-file": "z.zip"}, "12")
    ok("manifest.txt 排最后（清单永远不指向不存在的对象）", order[-1] == MANIFEST_NAME)
    ok("版本账排在玩家清单之前", order[-2] == "manifest-12.txt")
    ok("产物名去重且排序", order[:-2] == ["a.apk", "z.zip"])
    # S3 那条路上最容易签错的就是键的编码：产物名里的 `+` 必须成 %2B，而前缀目录的 `/` 不能动
    ok("S3 键编码：+ 百分号化、未保留字符留着", s3_uri_encode("eratw-0.2.37+12-a~b.c")
       == "eratw-0.2.37%2B12-a~b.c")
    ok("S3 路径编码：段间斜杠按 keep_slash 决定", s3_uri_encode("smoke/a.zip") == "smoke%2Fa.zip"
       and "/".join(s3_uri_encode(s) for s in "smoke/a.zip".split("/")) == "smoke/a.zip"
       and s3_uri_encode("eratw-0.2.37+12-a", keep_slash=True) == "eratw-0.2.37%2B12-a")
    ok("wrangler 上限就是实测的那 300 MiB", WRANGLER_PUT_LIMIT == 300 * 1024 * 1024
       and S3_PART_SIZE >= 8 * 1024 * 1024)
    # 编码「做几遍」这件事必须钉在**组合口**上：单测 s3_uri_encode 认不出「编完再编」。
    # 2026-11-02 正式上传就是被这条漏掉的判式咬住 —— 产物名带 `+`，二次编码变 %252B。
    plus_key = "eratw-0.2.37+12-8276c6c-full-58b2f16e.apk"
    ok("签名路径只编一次：带 + 的产物名是 %2B 而不是 %252B",
       s3_canonical_path(R2_BUCKET, plus_key)
       == "/eratw-updates/eratw-0.2.37%2B12-8276c6c-full-58b2f16e.apk"
       and "%25" not in s3_canonical_path(R2_BUCKET, plus_key))
    ok("签名路径与请求 URL 同源：普通键（含冒烟前缀）逐字不变形",
       s3_canonical_path("eratw-updates", "smoke/r2smoke.bin") == "/eratw-updates/smoke/r2smoke.bin")
    # 签名链自己钉一发：2026-11-02 首跑冒烟就死在 `s3_sign(...).hexdigest()`（bytes 没这方法，
    # 该用 `.hex()`）。S3 这条路在拿到桶级凭据之前一行都没真跑过，判式不许只依赖线上冒烟。
    def spec_sig(secret: str, stamp: str, sts: str) -> str:
        """照 AWS SigV4 规范直译一遍四级派生（与 s3_sign 不同写法，用来互证）。"""
        k = ("AWS4" + secret).encode("utf-8")
        for part in (stamp, S3_REGION, S3_SERVICE, "aws4_request"):
            k = hmac.new(k, part.encode("utf-8"), hashlib.sha256).digest()
        return hmac.new(k, sts.encode("utf-8"), hashlib.sha256).hexdigest()

    probe_sts = ("AWS4-HMAC-SHA256\n20261102T000000Z\n"
                 f"20261102/{S3_REGION}/{S3_SERVICE}/aws4_request\n" + "0" * 64)
    probe_sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    ok("S3 签名链与规范直译同值（且取的是 .hex() 不是 .hexdigest()）",
       s3_sign(probe_sk, "20261102", probe_sts.encode("utf-8")).hex()
       == spec_sig(probe_sk, "20261102", probe_sts))
    # 灰度百分比（P1-5）：--verify-only 与生成路径共用同一个复核判式，这里钉住它的边界。
    ok("灰度百分比键缺席 = 设备侧全量", rollout_percentage_problem(None) is None)
    ok("灰度百分比 0/1/50/99/100 合格",
       all(rollout_percentage_problem(str(n)) is None for n in (0, 1, 50, 99, 100)))
    ok("灰度百分比坏值全挡",
       all(rollout_percentage_problem(v) is not None
           for v in ("", "  ", "-1", "101", "abc", "50.5", "+5", "1 2")))
    ok("灰度百分比前导零按整数收", rollout_percentage_problem("050") is None)

    # ---------------------------------------------------------------
    # 块级差量（P2-13，U8）：拼接正确性 + 体积收益 + 形状契约 + 篡改必失败。
    # 判式全部吃**合成**样本，不吃语料、不吃环境变量 —— `--selftest` 在没导出
    # ERATW_GAMEDIR 的机器上也必须一样绿（真语料的体积读数记在交付报告里，不在这里）。
    # ---------------------------------------------------------------
    import random as _random
    import tempfile as _tempfile
    rnd = _random.Random(20261105)

    def _similar_base(size: int) -> bytes:
        """半结构化的一份「旧文件」：约三分之一的块重复出现，模拟一张会被局部改动的图/表。"""
        piece = rnd.randbytes(blk)
        out = bytearray()
        while len(out) < size:
            out += piece if rnd.random() < 0.35 else rnd.randbytes(blk)
        return bytes(out[:size])

    blk = BLOCK_SIZE_BYTES
    old_file = _similar_base(2 * 1024 * 1024)
    mid = len(old_file) // 2
    new_insert = old_file[:mid] + b"META-CHUNK-ADDED" + old_file[mid:]

    def _roundtrip(old: bytes, new: bytes) -> bytes | None:
        """发一次补丁再按设备侧那条读法拼回去；不值得发或拼不回都返回 None。"""
        made = build_block_patch(old, new)[0]
        if made is None:
            return None
        return reassemble_block_patch(old, split_block_patch(made)[1], BLOCK_SIZE_BYTES)[0]

    ok("块级补丁：中部插入一处也能拼回逐字节相等的新文件", _roundtrip(old_file, new_insert) == new_insert)
    whole_size = _deflated_size(new_insert)
    patch_bytes_len = len(build_block_patch(old_file, new_insert)[0])
    ok("块级补丁明显小过「整条重发」", patch_bytes_len < whole_size)
    print(f"[blockdelta] 2 MiB 样本中部插入 16 字节：整条重发 {whole_size} B → 补丁 "
          f"{patch_bytes_len} B（省 {whole_size - patch_bytes_len} B，"
          f"{(whole_size - patch_bytes_len) * 100 // whole_size}%）")
    # 边界：空旧文件 / 空新文件 / 恰好一个整块 / 尾部不足一块
    ok("旧文件为空不发补丁（没东西可引用）", build_block_patch(b"", new_insert)[0] is None)
    ok("新文件为空不发补丁（走整条更省）", build_block_patch(old_file, b"")[0] is None)
    ok("新文件恰为一整块旧数据时逐字节还原", _roundtrip(old_file, old_file[:blk]) == old_file[:blk])
    tail_short = old_file[:blk * 3 + 11]
    ok("尾部不足一块也逐字节还原", _roundtrip(old_file, tail_short) == tail_short)
    ok("旧文件只有一块、新文件与它相同时补丁极小",
       (lambda p: p is not None and len(p) < blk // 2)(build_block_patch(old_file[:blk], old_file[:blk])[0]))
    # 完全不相似 ⇒ 补丁不划算 ⇒ 发整条（这条是「不会比现状更差」的唯一硬保证）
    ok("两份毫无共同块的文件不发补丁（自动退回整条，块级永远不比文件级大）",
       build_block_patch(old_file, rnd.randbytes(len(old_file)))[0] is None)
    # 形状契约（设备侧读的就是这几行）：魔数 / fmt / 两个 sha / block
    patch = build_block_patch(old_file, new_insert)[0]
    fields, body = split_block_patch(patch)
    body_at = len(patch) - len(body)
    ok("补丁头部键齐全且 base/result 摘要与内容一致",
       fields is not None and fields["base-sha256"] == hashlib.sha256(old_file).hexdigest()
       and fields["result-sha256"] == hashlib.sha256(new_insert).hexdigest()
       and int(fields["result-size"]) == len(new_insert) and int(fields["block"]) == blk)
    ok("魔数被改 → 形状判定当场拒绝（不是拼出错字节）",
       split_block_patch(b"XBPD1\n" + patch[6:])[0] is None)
    ok("fmt 不是 1 → 形状判定拒绝（设备侧同一条判据）",
       split_block_patch(patch.replace(b"fmt 1\n", b"fmt 2\n"))[0] is None)
    ok("头部没有结束空行 → 拒绝（不拿尾部字节当头部）",
       split_block_patch(patch.replace(b"\n\n", b"\n", 1))[0] is None)
    tampered = bytearray(patch)
    tampered[-1] ^= 0x01
    ok("补丁末字节改一个 → 重放结果与 result-sha256 不符（设备侧据此回落全量）",
       (lambda got: got is None or hashlib.sha256(got).hexdigest() != fields["result-sha256"])(
           reassemble_block_patch(old_file, bytes(tampered[body_at:]), blk)[0]))
    bad_index = bytearray(patch)
    bad_index[body_at + 2] = 0x7F  # 第一条 op 的块号改成越界值
    ok("块号越界 → 重放判死而不是静默截短",
       reassemble_block_patch(old_file[:blk], bytes(bad_index[body_at:]), blk)[0] is None)
    # 清单新键：写了要能读回，没写必须**一个都不出现**（旧客户端的向后兼容靠这条）
    with _tempfile.TemporaryDirectory() as tmp:
        mf = write_manifest(tmp, "a.apk", 1, "0" * 64, "0.2.4", "7", None, None, "",
                            ["https://x.example/updates/"], "n", "2026-11-05T00:00:00Z",
                            assets_block_delta=("eratw-assets-blockdelta-8.zip", 42, "1" * 64))
        got = parse_manifest(mf)["values"]
        ok("清单写出块级三件套且键名与设备侧 BlockDeltaManifest 常量同值",
           got.get("assets-blockdelta-file") == "eratw-assets-blockdelta-8.zip"
           and got.get("assets-blockdelta-size") == "42"
           and got.get("assets-blockdelta-sha256") == "1" * 64
           and sum(1 for k in got if k.startswith("assets-blockdelta-")) == 3)
        mf2 = write_manifest(tmp, "a.apk", 1, "0" * 64, "0.2.4", "7", None, None, "",
                             ["https://x.example/updates/"], "n", "2026-11-05T00:00:00Z")
        ok("不发块级时清单里这三行一句都不出现（旧客户端形状逐字不变）",
           "assets-blockdelta" not in open(mf2, encoding="utf-8").read())
        ok("块级对象进留存账（不推进去 = 玩家 404；不清 = 白占额度）",
           "eratw-assets-blockdelta-8.zip" in manifest_object_names(
               {"assets-blockdelta-file": "eratw-assets-blockdelta-8.zip"})
           and manifest_object_sizes({"assets-blockdelta-file": "b.zip",
                                      "assets-blockdelta-size": "4321"}) == {"b.zip": 4321})
    # 端到端一小段：两份全量包 → 块级差量包 → 按发布侧的复核判式再走一遍。
    # 这一段盯的是「发出去的对象 + 那张删除表」这一整条形状，不是单个函数的算法。
    import zipfile as _zipfile
    with _tempfile.TemporaryDirectory() as tmp2:
        base_zip = os.path.join(tmp2, "eratw-assets-7.zip")
        new_zip = os.path.join(tmp2, "eratw-assets-8.zip")
        old_big = _similar_base(200 * 1024)
        new_big = old_big[:60 * 1024] + b"INSERTED" + old_big[60 * 1024:]
        kept = _similar_base(32 * 1024)
        with _zipfile.ZipFile(base_zip, "w", _zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("resources/a.bin", old_big)
            zf.writestr("resources/kept.bin", kept)
            zf.writestr("resources/gone.bin", b"old frame")
        with _zipfile.ZipFile(new_zip, "w", _zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("resources/a.bin", new_big)
            zf.writestr("resources/kept.bin", kept)
        made, st = stage_block_delta_zip(tmp2, new_zip, base_zip, ["resources/a.bin"],
                                         ["resources/gone.bin"], "8")
        ok("端到端：块级差量包生成出来了", made is not None)
        with _zipfile.ZipFile(made, "r") as zf:
            names = set(zf.namelist())
            ok("端到端：改过的那条走补丁而不是整条重发",
               BLOCK_DELTA_PREFIX + "resources/a.bin" in names
               and "resources/a.bin" not in names and st["patched"] == 1)
            ok("端到端：删除表条目名与文件级差量同一个（update-delta.txt）",
               DELTA_LIST_NAME in names)
            ok("端到端：删除表内容与传进来的 deleted 逐行一致",
               zf.read(DELTA_LIST_NAME).decode("utf-8").strip() == "resources/gone.bin")
        ok("端到端：生成侧复核全过（空问题表）",
           verify_block_patches(made, new_zip, base_zip) == [])
        # 复核必须抓得住三种坏法：基线包换了、补丁被改一个字节、条目名指向包外
        with _zipfile.ZipFile(made, "r") as zf:
            one_patch = zf.read(BLOCK_DELTA_PREFIX + "resources/a.bin")
        with _tempfile.TemporaryDirectory() as tmp3:
            tampered_zip = os.path.join(tmp3, "tampered.zip")
            with _zipfile.ZipFile(tampered_zip, "w", _zipfile.ZIP_DEFLATED) as zf:
                zf.writestr(BLOCK_DELTA_PREFIX + "resources/a.bin",
                            one_patch[:-1] + bytes([one_patch[-1] ^ 1]))
                zf.writestr(DELTA_LIST_NAME, "")
            found = verify_block_patches(tampered_zip, new_zip, base_zip)
            ok("复核抓得住被改一个字节的重放（重放与全量包不等）",
               any("result-sha256" in p or "重放" in p for p in found))
            wrong_index = os.path.join(tmp3, "wrong-index.zip")
            with _zipfile.ZipFile(wrong_index, "w", _zipfile.ZIP_DEFLATED) as zf:
                zf.writestr("resources/a.bin", _similar_base(200 * 1024))
            ok("复核抓得住补丁指向与全量包不符的条目",
               verify_block_patches(made, wrong_index, base_zip) != [])
            other_base = os.path.join(tmp3, "other-base.zip")
            with _zipfile.ZipFile(other_base, "w", _zipfile.ZIP_DEFLATED) as zf:
                zf.writestr("resources/a.bin", _similar_base(200 * 1024))
            ok("复核抓得住基线包换了人（base-sha256 对不上）",
               any("基线" in p for p in verify_block_patches(made, new_zip, other_base)))
        ok("基线包不在本地时 stage 拒绝发块级差量（不发新键，旧通道照走）",
           stage_block_delta_zip(tmp2, new_zip, os.path.join(tmp2, "eratw-assets-404.zip"),
                                 ["resources/a.bin"], [], "8")[0] is None)
    print(f"block-delta selftest ok ({checks} checks)")

    # ---------------------------------------------------------------
    # 清单本体签名（CONTRACT-UPD1-3LANE §2 U1 病①）：签→验闭环 + 篡改必败。
    # 黄金签名由 openssl 产出（`openssl dgst -sha256 -sign`），只保留对应**公钥**
    # （n/e 是公开信息）来验证 Python 验签链与 OpenSSL 互通；签名私钥不落源码，
    # 每次 selftest 运行时用纯 Python 临时生成一把 RSA-2048，只活在内存里。
    # ---------------------------------------------------------------
    gold_input = b"manifest selftest fixed input 2026-11-04"
    gold_sig_b64 = (
        "I204l6JhfbgXbtD47zcSEA4LwBHNysMz/ZQotrXH3yDj+kiNRME7+XVwIywN4WXcomvHZG8p6pXb2VZuXOt21pVsqazZ8RXCxxZ8NzRA3Xl4yS2TNbwEFEfl4K/IjEkuGCRx6OxdF/a1n2SbS1skfmChvEYIVfa3rogFsY1pWBHOSzdsP5vPXs9iednEe4jxM4QndHsn5nkcO68rJ/mgYihlwc+TJ3QaAQer4owy8nYXEdChB07UrNN/TnDmbMZGTTrCK59UxqA91jDMooqviEP4JyU+cDe/6ZEMUcy8SIB2ZQNja08yU+9ww9SlN+vlFmIQc2fSqDZUi/fHzCY6DQ=="
    )
    gold_sig = base64.b64decode(gold_sig_b64)
    gold_pubkey = (
        int(
            "c25540f8a7bdeca27c81e68fed4db691da082f92e48ce3b67557447c7ba15dae03bb4fbe2288b4ebee32f20df3e3c441c271308ae03756725c5ac66f5ab7aefd7e57937869a5e2fafa887b3841dd763571211365e75ad2cf85ba035c391cd0a6082e62ebcd9480af40a0857a341dda0e32871a55ec45522945067f2213f98de34af4a7ec33e08b57033405b19febe916f8678a5970dff4eb65cff098079942d56855dfb84067c233d53df30129695e5b3f2d53d40d398108d4b6327aab7104d123da2a2f1affc39b9c9ce966f6855e2cf1339e6c39c01ad694709e24bce273c6e0a43e1d0c7463f0bd982f40087e476b95617442cc5ac6c297575d0cc82ae283",
            16,
        ),
        65537,
        0,
    )
    ok("黄金签名（openssl 产出）用内置公钥验得过：DigestInfo/padding/模幂与 OpenSSL 互证",
       rsa_verify_sha256(gold_input, gold_sig, gold_pubkey))
    tampered_gold_sig = bytearray(gold_sig)
    tampered_gold_sig[100] ^= 0x01
    ok("黄金签名改一个字节验签必败（签名面）",
       not rsa_verify_sha256(gold_input, bytes(tampered_gold_sig), gold_pubkey))

    # 运行时临时生成一把 RSA-2048：覆盖 PKCS#8 DER 解析 + 签→验闭环 + 篡改必败。
    key = generate_rsa_key(2048)
    parsed = parse_pkcs8_der(rsa_key_to_pkcs8_der(key))
    ok("运行时生成的 PKCS#8 DER 解析回同一把 RSA 键且是 2048 位",
       parsed == key and parsed[0].bit_length() == 2048 and parsed[1] == 65537)
    ok("k1 现役键 id 与设备侧常量同值", DEFAULT_SIG_KEY_ID == "k1" and SIG_ALG_VALUE == "rsa-sha256")
    ours = rsa_sign_sha256(gold_input, key)
    ok("签→验闭环：运行时临时键签名可被同键公钥验过",
       rsa_verify_sha256(gold_input, ours, key))
    ok("PKCS#1 v1.5 确定性：同输入两次签名逐字同值",
       rsa_sign_sha256(gold_input, key) == ours)
    tampered_input = gold_input[:-1] + ("5" if gold_input[-1:] != b"5" else "6").encode("ascii")
    ok("清单改一个字节验签必败（输入面）", not rsa_verify_sha256(tampered_input, ours, key))
    tampered_sig = bytearray(ours)
    tampered_sig[100] ^= 0x01
    ok("签名改一个字节验签必败（签名面）", not rsa_verify_sha256(gold_input, bytes(tampered_sig), key))
    # 切割规则与设备侧同一条：签名行去掉后其余全部字节 = 签名时的输入
    sample_manifest = (
        "update-format 1\n"
        "apk-version-code 16\n"
        "sig-alg rsa-sha256\n"
        "sig-key-id k1\n"
    )
    sample_signed_body = sample_manifest  # 上面的主体（含 key-id 行）就是被签字节
    sample_sig = base64.b64encode(rsa_sign_sha256(sample_signed_body.encode("utf-8"), key)).decode("ascii")
    sample_full = sample_signed_body + f"{SIGNATURE_KEY} {sample_sig}\n"
    split = split_signed_portion(sample_full)
    ok("签名行切割：被签字节含 sig-alg/sig-key-id、不含签名行及其行尾换行",
       split is not None and split[0] == sample_signed_body and split[1] == sample_sig)
    ok("切割后验签闭环", rsa_verify_sha256(split[0].encode("utf-8"), base64.b64decode(split[1]), key))
    # 改整份清单里的一个字节（note 里最常见：镜像地址/说明被顺手改一个字符）必败
    tweaked = sample_full.replace("apk-version-code 16", "apk-version-code 17")
    split_bad = split_signed_portion(tweaked)
    ok("清单正文改一个字节 → 切割后验签必败",
       split_bad is not None and not rsa_verify_sha256(split_bad[0].encode("utf-8"),
                                                       base64.b64decode(split_bad[1]), key))
    ok("没有签名行的清单判「未签名」而不是崩", split_signed_portion(sample_signed_body) is None)
    print(f"signature selftest ok ({checks} checks total)")

    # ---------------------------------------------------------------
    # 清单闸（L2）：设备端判式的坏清单族 + 验签信任锚（K1 黄金向量）。
    # 判式单源在 devtools/update_manifest_check.py；这里只断言「坏形状必被点名」，
    # 不碰网络、不碰本机私钥（K1 黄金签名对读自 ManifestSignatureTest 的常量）。
    # ---------------------------------------------------------------
    check = update_manifest_check
    k1_keys = check.load_device_builtin_keys()
    ok("设备端内置公钥表读得出且就是 k1（2048 位 / e=65537）",
       bool(k1_keys) and k1_keys[0][0] == DEFAULT_SIG_KEY_ID
       and k1_keys[0][1][0].bit_length() == 2048 and k1_keys[0][1][1] == 65537)
    core_update = os.path.join(
        KCORE_ROOT, "core-vm", "src", "main", "kotlin", "com", "eratw", "port", "core", "update")

    def kotlin_int_const(source: str, name: str) -> int:
        """Kotlin 数字常量表达式 → int（去 `L` 后缀与下划线；只认数字四则，口径同 drill:151-163）。"""
        match = re.search(r"const val %s(?:: (?:Int|Long))?\s*=\s*([^\n]+)" % re.escape(name),
                          source)
        if match is None:
            raise AssertionError(f"设备端源里读不到常量 {name}")
        expr = re.sub(r"\b(\d[\d_]*)L\b", r"\1", match.group(1)).replace("_", "").strip()
        if not re.fullmatch(r"[0-9+\-*/()\s]+", expr):
            raise AssertionError(f"设备端常量 {name} 的表达式没法安全求值：{expr!r}")
        return int(eval(expr, {"__builtins__": {}}, {}))  # noqa: S307 —— 输入限定为数字四则

    with open(os.path.join(core_update, "UpdatePolicy.kt"), "r", encoding="utf-8") as fh:
        policy_src = fh.read()
    with open(os.path.join(core_update, "UpdateManifest.kt"), "r", encoding="utf-8") as fh:
        manifest_src = fh.read()
    ok("镜像条数上限与设备端 UpdatePolicy.MAX_MIRRORS 同值",
       check.MAX_MIRRORS == kotlin_int_const(policy_src, "MAX_MIRRORS"))
    ok("镜像长度上限与设备端 UpdatePolicy.MAX_MIRROR_CHARS 同值",
       check.MAX_MIRROR_CHARS == kotlin_int_const(policy_src, "MAX_MIRROR_CHARS"))
    ok("清单格式上限与设备端 UpdateManifest.FORMAT_SUPPORTED 同值",
       check.FORMAT_SUPPORTED == kotlin_int_const(manifest_src, "FORMAT_SUPPORTED"))
    ok("单件字节上限与设备端 UpdateManifest.MAX_ARTIFACT_BYTES 同值",
       check.MAX_ARTIFACT_BYTES == kotlin_int_const(manifest_src, "MAX_ARTIFACT_BYTES"))
    ok("note 上限与设备端 UpdateManifest.MAX_NOTE_CHARS 同值",
       check.MAX_NOTE_CHARS == kotlin_int_const(manifest_src, "MAX_NOTE_CHARS"))

    legal = "\n".join([
        "update-format 1",
        "generated 2026-11-05T00:00:00Z",
        "apk-version-code 21",
        "apk-version-name 0.2.44",
        "apk-file eratw-0.2.44+21-probe.apk",
        "apk-size 1234",
        "apk-sha256 " + "b" * 64,
        "assets-version 21",
        "assets-file eratw-assets-21.zip",
        "assets-size 4321",
        "assets-sha256 " + "c" * 64,
        "min-supported-version 20",
        "note 合法清单",
        "mirror https://probe.invalid/updates/",
    ]) + "\n"
    values, mirrors, parse_problems = check.parse_manifest(legal)
    ok("合法清单：设备端解析零问题",
       parse_problems == [] and mirrors == ["https://probe.invalid/updates/"])
    ok("合法清单：发布侧判式零问题", check.validate_manifest(legal, values, mirrors) == [])
    ok("合法未签名清单：体检不因缺签名判不合格（老清单形态放行）",
       check.check_signature(legal, rsa_verify_sha256) == [])

    def _parse(text: str) -> list:
        return check.parse_manifest(text)[2]

    def _validate(text: str) -> list:
        got_values, got_mirrors, got_problems = check.parse_manifest(text)
        if got_problems:
            return got_problems
        return check.validate_manifest(text, got_values, got_mirrors)

    ok("缺 apk-version-name ⇒ 不合格", any("apk-version-name" in item for item in
       _validate(legal.replace("apk-version-name 0.2.44\n", ""))))
    ok("缺 apk-sha256 ⇒ 不合格", any("apk-sha256" in item for item in
       _validate(legal.replace("apk-sha256 " + "b" * 64 + "\n", ""))))
    ok("缺 assets-sha256 ⇒ 不合格", any("assets-sha256" in item for item in
       _validate(legal.replace("assets-sha256 " + "c" * 64 + "\n", ""))))
    ok("重复键 ⇒ 不合格（不是后写覆盖前写）",
       any("键名重复" in item for item in
           _parse(legal.replace("note 合法清单", "apk-version-name 0.2.45"))))
    ok("note 里带回车 ⇒ 不合格（换行会把说明截成两半）",
       any("note 里带换行" in item for item in
           _validate(legal.replace("note 合法清单", "note 前半句\r后半句"))))
    ok("文件名带空格 ⇒ 不合格（设备端只许 A-Za-z0-9._+-）",
       any("不是合法文件名" in item for item in
           _validate(legal.replace("eratw-0.2.44+21-probe.apk", "eratw 0.2.44.apk"))))
    ok("文件名带 .. ⇒ 不合格（不许带路径）",
       any("不是合法文件名" in item for item in
           _validate(legal.replace("eratw-0.2.44+21-probe.apk", "..apk"))))
    ok("min-supported-version 越界（> apk-version-code）⇒ 不合格",
       any("越界" in item for item in
           _validate(legal.replace("min-supported-version 20", "min-supported-version 99"))))
    ok("min-supported-version 非纯数字 ⇒ 不合格",
       any("纯数字" in item for item in
           _validate(legal.replace("min-supported-version 20", "min-supported-version 1.5"))))
    ok("min-supported-version 为 0 ⇒ 不合格",
       any("必须 ≥1" in item for item in
           _validate(legal.replace("min-supported-version 20", "min-supported-version 0"))))
    ok("mirror 不是 https ⇒ 不合格（设备端会直接丢弃这条源）",
       any("mirror 不合法" in item for item in
           _validate(legal.replace("https://probe.invalid/", "http://probe.invalid/"))))
    ok("mirror 缺尾斜杠 ⇒ 不合格（发布侧写设备端归一后的形态）",
       any("归一后的形态不一致" in item for item in
           _validate(legal.replace("https://probe.invalid/updates/",
                                   "https://probe.invalid/updates"))))
    ok("缺 update-format ⇒ 不合格（不是 eratw 更新清单）",
       any("update-format" in item for item in _parse(legal.replace("update-format 1\n", ""))))
    ok("update-format 高于支持值 ⇒ 不合格",
       any("只支持到" in item for item in _parse(legal.replace("update-format 1", "update-format 2"))))
    ok("无空格行 ⇒ 不合格（设备端 Rejected；旧判式是静默跳过）",
       any("不是「键 值」" in item for item in _parse(legal + "这一行没有空格\n")))
    ok("apk 组整组缺席 ⇒ 不合格（发布目录必须有整包）",
       any("没有 apk 组" in item for item in _validate(
           "\n".join(line for line in legal.split("\n") if not line.startswith("apk-")) + "\n")))

    # —— 验签：信任锚 = 设备端 K1；签名字节用 ManifestSignatureTest 的黄金向量（真 k1 签的）
    signature_test = os.path.join(
        KCORE_ROOT, "core-vm", "src", "test", "kotlin", "com", "eratw", "port", "core",
        "update", "ManifestSignatureTest.kt")
    with open(signature_test, "r", encoding="utf-8") as fh:
        test_src = fh.read()
    body_block = re.search(r"GOLDEN_BODY: String = listOf\((.*?)\n\s*\)\.joinToString",
                           test_src, re.S)
    sig_match = re.search(r"GOLDEN_SIGNATURE: String =\s*\n?\s*\"([^\"]+)\"", test_src)
    if body_block is None or sig_match is None:
        raise AssertionError("ManifestSignatureTest 的黄金向量读不出（改形状时同步改本断言）")
    raw_body = re.sub(r'\$\{"a"\.repeat\(64\)\}', "a" * 64, body_block.group(1))
    # `listOf(…, "")` 末尾那个空串就是「最后一行后面还有换行」；拼接后统一成恰好一个收尾换行。
    golden_body = "\n".join(re.findall(r'"((?:[^"\\]|\\.)*)"', raw_body)).rstrip("\n") + "\n"
    golden_sig = sig_match.group(1)
    golden_text = golden_body + f"{SIGNATURE_KEY} {golden_sig}\n"
    ok("k1 黄金签名对验得过：体检的信任锚就是设备端那把内置公钥",
       check.check_signature(golden_text, rsa_verify_sha256) == [])
    ok("k1 黄金签名对：正文改一个字节 ⇒ 验签不过",
       any("验签不过" in item for item in check.check_signature(
           golden_text.replace("apk-size 12345", "apk-size 12346"), rsa_verify_sha256)))
    ok("签名行后追加非空行 ⇒ 必须点名（设备侧的被签字节止于签名行）",
       any("最后一个非空行" in item for item in check.check_signature(
           golden_text + "note 签名后追加\n", rsa_verify_sha256)))
    ok("签名行后只追加空行 ⇒ 不算不合格",
       check.check_signature(golden_text + "\n\n", rsa_verify_sha256) == [])
    ok("假签名（全零签名）⇒ 验签不过",
       any("验签不过" in item for item in check.check_signature(
           golden_body + f"{SIGNATURE_KEY} " + base64.b64encode(b"\x00" * 256).decode() + "\n",
           rsa_verify_sha256)))
    ok("签名不是合法 Base64 ⇒ 点名",
       any("Base64" in item for item in check.check_signature(
           golden_body + f"{SIGNATURE_KEY} 不是base64!!\n", rsa_verify_sha256)))
    ok("sig-alg 不是 rsa-sha256 ⇒ 点名（设备端判「不过」）",
       any(SIG_ALG_KEY in item for item in check.check_signature(
           golden_text.replace(f"{SIG_ALG_KEY} rsa-sha256", f"{SIG_ALG_KEY} ed25519"),
           rsa_verify_sha256)))
    ok("sig-key-id 指向包里没有的键 ⇒ 点名",
       any(SIG_KEY_ID_KEY in item for item in check.check_signature(
           golden_text.replace(f"{SIG_KEY_ID_KEY} k1", f"{SIG_KEY_ID_KEY} next"),
           rsa_verify_sha256)))

    # —— 版本单调闸（L2）：200 但不是清单必须 die；正常清单照旧比大小。
    # die 会往 stderr 打大字报，selftest 里把它吞掉（断言看的是 SystemExit 而不是文案）。
    import contextlib as _contextlib
    import io as _io

    def _gate_dies(remote) -> bool:
        with _contextlib.redirect_stdout(_io.StringIO()), \
                _contextlib.redirect_stderr(_io.StringIO()):
            try:
                version_gate(remote, "21", "21")
            except SystemExit:
                return True
        return False

    ok("版本单调闸：200 但不是清单（缺 update-format/apk-version-code）⇒ die",
       _gate_dies({"note": "反向代理的错误页", "server": "nginx"}))
    ok("版本单调闸：线上 apk-version-code 更高 ⇒ die",
       _gate_dies({"update-format": "1", "apk-version-code": "22"}))
    ok("版本单调闸：线上 assets-version 更高 ⇒ die",
       _gate_dies({"update-format": "1", "apk-version-code": "20", "assets-version": "22"}))
    version_gate({"update-format": "1", "apk-version-code": "20", "assets-version": "20"},
                 "21", "21")
    version_gate(None, "21", "21")
    ok("版本单调闸：线上更低 / 拉不到（None）⇒ 放行", True)
    print(f"manifest-gate selftest ok ({checks} checks total)")

    # ---------------------------------------------------------------
    # 索引对账（L7）：assets-index 与本地基线全量包逐条 sha 对账。
    # 三份合成 zip 覆盖三个判据：差量覆盖全部改动 ⇒ 绿；index 的 sha 与基线不符且差量没带
    # ⇒ 红；基线包缺席 ⇒ 只 warn_big 不判红（明说「没有基线可对拍」）。
    # ---------------------------------------------------------------
    with tempfile.TemporaryDirectory() as index_tmp:
        def _mk_zip(name: str, entries: dict) -> str:
            path = os.path.join(index_tmp, name)
            with zipfile.ZipFile(path, "w") as zf:
                for arc, data in entries.items():
                    zf.writestr(arc, data)
            return path

        base_zip = _mk_zip("eratw-assets-20.zip",
                           {"a.txt": b"old-a", "b.txt": b"same-b", "gone.txt": b"gone"})
        new_zip = _mk_zip("eratw-assets-21.zip",
                          {"a.txt": b"new-a", "b.txt": b"same-b", "c.txt": b"new-c"})
        _mk_zip("eratw-assets-delta-21.zip",
                {"a.txt": b"new-a", "c.txt": b"new-c", DELTA_LIST_NAME: b"gone.txt\n"})
        with zipfile.ZipFile(new_zip) as zf:
            new_entries = build_assets_index(zf)

        def _write_index(entries: dict, version: str = "21") -> None:
            with open(os.path.join(index_tmp, ASSETS_INDEX_NAME), "w",
                      encoding="utf-8", newline="\n") as fh:
                json.dump({"version": version, "entries": entries}, fh,
                          ensure_ascii=False, sort_keys=True, indent=2)

        index_values = {"assets-version": "21", "assets-base-version": "20",
                        "assets-full-file": "eratw-assets-21.zip",
                        "assets-delta-file": "eratw-assets-delta-21.zip"}
        _write_index(new_entries)
        index_log = _io.StringIO()
        with _contextlib.redirect_stdout(index_log):
            index_ok = index_baseline_problems(index_tmp, index_values)
        ok("索引对账：差量覆盖了全部改动 ⇒ 零问题", index_ok == [])
        ok("索引对账：零问题时点名「逐条 sha 一致」", "逐条 sha 一致" in index_log.getvalue())

        tampered_entries = json.loads(json.dumps(new_entries))
        tampered_entries["b.txt"]["sha256"] = "f" * 64
        _write_index(tampered_entries)
        ok("索引对账：index 的 sha 与基线不同、差量又没带 ⇒ 点名（设备装完是旧字节）",
           any("逐条对账不符" in item
               for item in index_baseline_problems(index_tmp, index_values)))

        _write_index(new_entries, version="20")
        ok("索引对账：index version 与清单 assets-version 不一致 ⇒ 点名（下版差量按错基线算）",
           any("索引版本与清单不一致" in item
               for item in index_baseline_problems(index_tmp, index_values)))

        _write_index(new_entries)
        os.remove(base_zip)
        no_base_log = _io.StringIO()
        with _contextlib.redirect_stdout(no_base_log):
            no_base_problems = index_baseline_problems(index_tmp, index_values)
        ok("索引对账：无基线包只 warn_big 不判红，且明说「没有基线可对拍」",
           no_base_problems == [] and "没有基线可对拍" in no_base_log.getvalue())
    print(f"index-reconcile selftest ok ({checks} checks total)")

    print(f"retention selftest ok ({checks} checks)")
    return 0


# ---------------------------------------------------------------- R2 上传

def updates_base_from_manifest(manifest_path):
    """取清单里第一条 mirror 作为回读基址；没有就用缺省更新面。"""
    try:
        with open(manifest_path, "r", encoding="utf-8") as fh:
            for line in fh:
                parts = line.strip().split(" ", 1)
                if len(parts) == 2 and parts[0] == "mirror":
                    value = parts[1].strip()
                    if value.startswith("https://"):
                        return value if value.endswith("/") else value + "/"
    except OSError:
        pass
    return DEFAULT_UPDATES_BASE


def fetch_remote(url):
    """流式拉一个线上对象，返回 (sha256 小写, 字节数)；取不到返回 None。

    走 HTTP 而不再问一次 wrangler 有两个理由：这条才是玩家用的路（Worker 路由、
    R2 绑定、Content-Length 一起验）；大文件用 wrangler 取回还要落盘，几百 MiB 白占地方。
    """
    import hashlib
    import urllib.error
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": "eratw-emit/1.0"})
    digest = hashlib.sha256()
    total = 0
    try:
        with urllib.request.urlopen(req, timeout=900) as resp:
            for chunk in iter(lambda: resp.read(1024 * 1024), b""):
                digest.update(chunk)
                total += len(chunk)
    except (urllib.error.HTTPError, urllib.error.URLError, OSError):
        return None
    return digest.hexdigest(), total


def probe_range(url):
    """线上面是否真支持 Range 续传（要 206 + Content-Range + 恰好 1 字节）。"""
    import urllib.error
    import urllib.request
    req = urllib.request.Request(
        url, headers={"User-Agent": "eratw-emit/1.0", "Range": "bytes=0-0"}
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status == 206 and len(resp.read()) == 1 and bool(resp.headers.get("Content-Range"))
    except (urllib.error.HTTPError, urllib.error.URLError, OSError):
        return False


def r2_key_prefix_from_base(base):
    """把镜像基址换成 R2 对象键前缀（Worker 的 `/updates/` 就是桶根）。

    为什么必须跟着基址走：上传过去把对象**一律推到桶根**，与 `--mirror` 声明的路径无关。
    于是用测试前缀（`/updates/smoke/`）试跑一次发版，就会把测试清单写成玩家真正在读的那份
    `manifest.txt` —— 一个内测动作覆盖了正式更新源。前缀跟着基址推之后，
    「推到哪」与「玩家从哪取」是同一件事，测试包只能落在测试目录里。
    """
    path = urllib.parse.urlparse(base).path
    if path.startswith("/updates/"):
        prefix = path[len("/updates/"):]
    else:
        prefix = path.strip("/")
    if prefix and not prefix.endswith("/"):
        prefix += "/"
    return prefix


def r2_upload(out_dir: str) -> int:
    """把 update-dist 里**当前清单引用的**文件推到 R2 桶 eratw-updates。"""
    manifest_path = os.path.join(out_dir, MANIFEST_NAME)
    if not os.path.isfile(manifest_path):
        die(f"找不到清单：{out_dir} 不像发布目录")

    files_to_upload = []
    read = parse_manifest(manifest_path)
    values = read["values"]
    code = (values.get("apk-version-code") or "").strip()
    if not code.isdigit():
        die(f"清单的 apk-version-code 不是纯数字（{code!r}）：留存账按版本号命名（manifest-<code>.txt），"
            "写不出版本号就没法安全清理旧版")
    # 只推清单引用的对象，且 manifest.txt 排最后（次序的含义见 upload_names）。
    # 以前是把整个目录倒进桶里：update-dist 不清空，于是发第 N 版时把 N-1、N-2 的整包
    # 又重推一遍（每版 1.4 GB）—— 免费额度就是这么吃光的。
    names = upload_names(values, code)
    for fname in names:
        fpath = os.path.join(out_dir, fname)
        if not os.path.isfile(fpath):
            die(f"清单或版本账引用 {fname}，但发布目录里没有它：先跑 --verify-only {out_dir}"
                f"（带版本号的那份账由主流程与 manifest.txt 同批写出）")
        files_to_upload.append((fname, fpath))
    stale = [f for f in sorted(os.listdir(out_dir))
             if os.path.isfile(os.path.join(out_dir, f))
             and f not in names and f != ASSETS_INDEX_NAME
             and os.path.getsize(os.path.join(out_dir, f)) > 8 * 1024 * 1024]
    if stale:
        warn_big(f"发布目录里有 {len(stale)} 个当前清单不引用的大文件（本次不推，"
                 f"也别手工丢进镜像）：{' '.join(stale)}")

    verify_base = updates_base_from_manifest(manifest_path)
    key_prefix = r2_key_prefix_from_base(verify_base)
    # `--upload` 的桶根就是 Worker 的 `/updates/`（[r2_key_prefix_from_base] 的注）。基址的 path
    # 不在 /updates/ 下面时，推上去的对象与玩家真正会去取的路径**不是同一条链** —— 脚本照报
    # 「上传完成并回读核对」，而回读的是另一个地址。所以在任何一个 put 之前拦下（L2）。
    upload_path = urllib.parse.urlparse(verify_base).path
    if not upload_path.startswith("/updates/"):
        die(f"--upload 只服务 Worker 的 /updates/ 面（桶根 = 该前缀下的对象），本次回读基址的 "
            f"path 是 {upload_path!r}：推上去的东西与玩家读的清单不是同一条链。"
            "要么把 --mirror 换成 https://eratw-android.asia/updates/（或带前缀的测试目录），"
            "要么去掉 --upload 手动分发。")
    # `--upload` 只服务自有域名那条路（Worker 的 /updates/ = 桶根）。基址指向别处却硬往
    # R2 推，等于把文件放进一个没人读的地方，而脚本照样报「上传完成」。
    host = urllib.parse.urlparse(verify_base).hostname or ""
    if host != "eratw-android.asia" and not host.endswith(".workers.dev"):
        die(f"--upload 只服务自有域名的更新源（Worker 的 /updates/ = 桶根），本次镜像基址的主机名是 {host!r}：别把文件推到一个没人读的地方。要么去掉 --upload 手动分发，要么把 --mirror 换成 https://eratw-android.asia/updates/（或带前缀的测试目录）")
    log(f"R2 上传：{len(files_to_upload)} 个文件 -> {R2_BUCKET}/{key_prefix}"
        f"（回读基址 {verify_base}）")
    if key_prefix:
        log(f"  注意：本次是**带前缀的发布**（测试目录/备用路径），正式源 {R2_BUCKET}/manifest.txt 不动")
    uploaded = 0
    probe_range_ok = None
    # 只有真出现超限对象时才要凭据（小包内测不该被「没配 S3 令牌」卡住）。
    oversize = [n for n, p in files_to_upload if os.path.getsize(p) > WRANGLER_PUT_LIMIT]
    r2_creds = load_r2_creds() if oversize else {}
    if oversize:
        log(f"  其中 {len(oversize)} 个超过 wrangler 的 {WRANGLER_PUT_LIMIT // 1024 // 1024} MiB 上限"
            f"，走 S3 分片口：{' '.join(oversize)}")
    for idx, (fname, fpath) in enumerate(files_to_upload):
        size_mb = os.path.getsize(fpath) / 1024 / 1024
        log(f"  put {fname} ({size_mb:.1f} MiB)...")
        rest = [n for n, _ in files_to_upload[idx + 1:]]

        def abort(why: str) -> None:
            # 任何一步没成，玩家在读的 `manifest.txt` 与版本账都不推：线上继续停在上一版的形状，
            # 玩家顶多是「还没有新包」，不会出现「清单指向不存在的东西」。
            # （2026-11-02 实测踩过：wrangler 对 >300 MiB 直接报错，而脚本照样把清单推了上去。）
            die(f"{why}\n  已推成功 {uploaded} 个；**清单未推**，线上仍是上一版形状。\n"
                f"  这一轮没推上去的还有：{' '.join([fname] + rest)}\n"
                f"  修好链路后重跑 --upload（同对象会被覆盖重写，重跑不冲突）")

        # wrangler 对 `r2 object put` 有 300 MiB 硬墙（见 WRANGLER_PUT_LIMIT 的实测注），
        # 整包与数据包都撞得上限 ⇒ 超过它的对象走 S3 分片口，其余照旧走 wrangler 那条熟路。
        # 两条路下来都交给同一段回读核对 —— put 报成功不等于线上有。
        if os.path.getsize(fpath) > WRANGLER_PUT_LIMIT:
            log(f"  S3 put {fname} ({size_mb:.1f} MiB · {S3_PART_SIZE // 1024 // 1024} MiB 一片)...")
            try:
                s3_multipart_put(r2_creds, f"{key_prefix}{fname}", fpath)
            except RuntimeError as exc:
                abort(f"  [失败] S3 put {fname}: {exc}")
        else:
            # wrangler r2 object put <bucket>/<key> --file=<path>
            log(f"  put {fname} ({size_mb:.1f} MiB)...")
            cmd = [
                (shutil.which("npx") or "npx"), "wrangler", "r2", "object", "put",
                f"{R2_BUCKET}/{key_prefix}{fname}",
                f"--file={fpath}",
                # `--remote` 必须显式带：2026-11-01 实测不带它 wrangler 也照样打印
                # "Upload complete."，而对象落在本地持久化里、**真桶没有**，线上取就是 404。
                # 这正是「脚本全绿、玩家什么都拿不到」的形状，所以不给缺省。
                "--remote",
            ]
            try:
                proc = subprocess.run(cmd, capture_output=True, text=True,
                                      encoding="utf-8", errors="replace", cwd=APP_ROOT,
                                      # 慢线路上 600 s 是「小包才够」的旧值：还在传的 put 会被
                                      # 判成超时失败，而对象其实已经推进去了一半。
                                      timeout=3600)
                if proc.returncode != 0:
                    abort(f"  [失败] put {fname}: {proc.stderr[:300]}")
            except FileNotFoundError:
                die("wrangler 不可用（npx wrangler 找不到）：先确认 npm/npx 在 PATH")
            except subprocess.TimeoutExpired:
                abort(f"  [超时] put {fname}：3600 s 内没传完")

        # 回读核对：从线上把刚推的对象**真读回来**，比 sha 与字节数。
        # 只算本地 sha 不叫校验 —— 那是「put 报错也算成功」的另一个名字。
        # R2 自定义域的对象可见性有边缘传播延迟（2026-11-03 实测：S3 分片 complete 后
        # 立即回读 404、abort；同一 URL 二十分钟后 206，桶里对象一直在 —— 是边缘没
        # 拿到新对象，不是路由没对上）。所以「取不到」按退避重试，全空才算真绑错；
        # sha/字节不符不重试，那是内容问题，重读一万次也变不出另一个值。
        remote = None
        for attempt in range(READBACK_ATTEMPTS):
            remote = fetch_remote(verify_base + fname)
            if remote is not None:
                break
            if attempt + 1 < READBACK_ATTEMPTS:
                wait = READBACK_RETRY_SECONDS * (attempt + 1)
                log(f"    回读取不到（第 {attempt + 1} 次），{wait} s 后重试 —— "
                    "边缘可见性延迟是已知形态，全空才算绑错")
                time.sleep(wait)
        local_sha = sha256_of(fpath)
        local_size = os.path.getsize(fpath)
        if remote is None:
            abort(f"  [失败] {fname}: 线上取不到（{verify_base + fname}，重试 "
                  f"{READBACK_ATTEMPTS} 次全空）—— put 报成功而线上没有 = 路由或绑定没对上")
        remote_sha, remote_size = remote
        if remote_sha != local_sha or remote_size != local_size:
            abort(f"  [失败] {fname}: 线上与本地不符（本地 {local_sha[:12]}.../{local_size:,} B，"
                  f"线上 {remote_sha[:12]}.../{remote_size:,} B）")
        uploaded += 1
        log(f"  OK {fname} · 线上 sha {remote_sha[:12]}... · {remote_size:,} B")
        if probe_range_ok is None and local_size > 1:
            probe_range_ok = probe_range(verify_base + fname)
            log("  Range 续传面：可用（206 + Content-Range）" if probe_range_ok else
                "  Range 续传面：**不可用**（客户端断线只能整段重下）")

    if probe_range_ok is False:
        warn_big("Range 面没验通：玩家断线后只能从头重下整包。先查 Worker 的 /updates/ 路由有没有部署")
    log(f"R2 上传完成（{uploaded} 个文件都已从线上读回核对），现在才轮到清理旧版。")
    return 0


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="生成国内镜像仓的更新发布目录（manifest + 整包 + 数据包 + 差量包）并体检",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--out", default=DEFAULT_OUT, help=f"发布目录（缺省 {DEFAULT_OUT}）")
    parser.add_argument("--apk", default="", help="要发布的 APK")
    parser.add_argument("--game-root", default=os.environ.get("ERATW_GAMEDIR", ""),
                        help="游戏根（缺省取环境变量 ERATW_GAMEDIR）")
    parser.add_argument("--erbc-dir", default=os.environ.get("ERATW_ERBC_DIR", ""),
                        help="erbc 产物目录（缺省取游戏根下的 erbc.v1）")
    parser.add_argument("--mirror", action="append", default=[], dest="mirrors",
                        help="镜像目录基址（可重复；缺省 https://eratw-android.asia/updates/）")
    parser.add_argument("--assets-version", default="", help="数据包版本号")
    parser.add_argument("--apk-version-code", default="", help="覆盖 apk-version-code")
    parser.add_argument("--apk-version-name", default="", help="覆盖 apk-version-name")
    parser.add_argument("--skip-assets", action="store_true", help="只发整包，不编数据包")
    parser.add_argument("--no-block-delta", action="store_true",
                        help="不发块级差量包（清单里就不写 assets-blockdelta-* 三行；"
                             "设备侧照旧走文件级差量。块级这条翻车时的唯一逃生门）")
    parser.add_argument("--allow-retired-corpus", action="store_true",
                        help=f"放行「数据包按已退役 4.980 语料编」（回归对拍内测专用；"
                             f"正式通道数据根必须是 {pack_release.CURRENT_CORPUS_DIR}，"
                             "判据与 pack_release 同源自 [data_source_gate]）")
    parser.add_argument("--allow-stale-erbc", action="store_true",
                        help="放行「数据包自带的 erbc 不是当前语料产物 / 与整包不是同一次编译」"
                             "（内测专用；正式通道两边必须同次 compileCorpus）")
    parser.add_argument("--include-bad", action="store_true", help=f"把 {BAD_DIR}/ 也打进数据包")
    parser.add_argument("--stored", action="store_true", help="数据包用 STORED")
    parser.add_argument("--note", default="", help="一行说明")
    parser.add_argument("--rollout-percentage", type=int, default=None, metavar="N",
                        help="灰度发布百分比 0..100（缺省不写该键 = 设备侧全量 100；"
                             "写坏值在生成后由 --verify-only 自检挡下）")
    parser.add_argument("--sign-key", default="",
                        help=f"清单签名用的 PKCS#8 私钥 PEM（放 {SIGNING_KEY_DIR}/，不入库）；"
                             f"不给就出未签名清单，必须同时显式 --unsigned-explicit")
    parser.add_argument("--unsigned-explicit", action="store_true",
                        help="显式出**未签名**清单（没给 --sign-key 时的唯一出路；"
                             "老客户端照常读，但整包替换攻击对这份清单无从发现）")
    parser.add_argument("--min-supported-version", default="",
                        help="强制更新档：低于该 versionCode 的客户端必须更新（不给 = 无强制档）")
    parser.add_argument("--allow-unverified-apk-version", action="store_true",
                        help="放行「读不出 APK 本体 versionCode」（aapt2 缺失/坏包；内测逃生门，"
                             "正式发版据此写出的清单可能与包内不符，别用）")
    parser.add_argument("--verify-only", metavar="DIR", nargs="?", const="", default=None,
                        help="不生成，只体检现成的发布目录（不给参数时体检默认目录）")
    parser.add_argument("--manifest-url", default=DEFAULT_MANIFEST_URL,
                        help=f"线上清单 URL（版本单调闸用，缺省 {DEFAULT_MANIFEST_URL}）")
    parser.add_argument("--skip-version-gate", action="store_true",
                        help="跳过版本单调闸（首次发布或内网测试时用）")
    parser.add_argument("--upload", action="store_true",
                        help="生成完后推产物到 R2 桶（需要 wrangler 已登录）")
    parser.add_argument("--prune-keep", type=int, default=DEFAULT_PRUNE_KEEP,
                        help=f"上传成功后桶里保留最近几版（含当前），出窗版本的产物逐个删掉；"
                             f"0 = 不清理（缺省 {DEFAULT_PRUNE_KEEP}）")
    parser.add_argument("--prune-dry", action="store_true",
                        help="只打印清理计划，一个对象都不删")
    parser.add_argument("--selftest", action="store_true",
                        help="只跑留存判式（不碰网络、不碰桶）")
    args = parser.parse_args(argv)

    if args.rollout_percentage is not None:
        rollout_problem = rollout_percentage_problem(str(args.rollout_percentage))
        if rollout_problem:
            die(rollout_problem)

    if args.selftest:
        return retention_selftest()

    # 清单签名闸（§2 U1）：配了私钥就签；没配又没显式认领「未签名」就停下来 ——
    # 未签名清单对整包替换攻击不设防，这个缺口要发版人睁着眼选，不能靠忘。
    sign_key = None
    if args.sign_key:
        if not os.path.isfile(args.sign_key):
            die(f"--sign-key 指向的私钥不存在：{args.sign_key}")
        sign_key = load_pkcs8_key(args.sign_key)
        # 私钥与设备端信任锚对账（L2）：清单写的是 `sig-key-id k1`，设备端只会用编进包里的
        # k1 公钥验。装载的是另一把钥匙时，签出来的清单在设备端逐镜像判 INVALID ——
        # 整条更新通道对所有人哑掉，而发布侧从生成到体检全绿。这一格必须在装载后立刻核对 n/e。
        device_keys = update_manifest_check.load_device_builtin_keys()
        device_key = next((pair for key_id, pair in device_keys
                           if key_id == DEFAULT_SIG_KEY_ID), None)
        if device_key is None or (sign_key[0], sign_key[1]) != device_key:
            die(f"--sign-key 对应的公钥不是设备内置的 {DEFAULT_SIG_KEY_ID}：这把私钥的公钥"
                f"（{sign_key[0].bit_length()} 位）与 "
                f"{os.path.basename(update_manifest_check.MANIFEST_SIGNATURE_KT)} 里的 "
                f"{DEFAULT_SIG_KEY_ID} 对不上，用它签出的清单在设备端会全部判「不过」。"
                "要么换回设备内置那把私钥，要么先按轮换流程把新公钥编进客户端。")
        log(f"清单签名：已装载私钥 {args.sign_key}（{sign_key[0].bit_length()} 位，"
            f"key-id {DEFAULT_SIG_KEY_ID}，与设备内置公钥同钥）")
    if sign_key is None and args.unsigned_explicit:
        # 认领语义在两条路都成立：生成时放行未签名清单，体检时让发版人明确知道手上这份
        # 没有签名（verify-only 自己**不**会被下面的未签名闸拦住，见它上面的注）。
        warn_big("已按 --unsigned-explicit 认领：这次经手的清单**不带签名**。")

    # --verify-only 模式：**排在未签名闸之前**。它只读不写（不生成、不上传、不碰桶），
    # 而「手上有没有签名私钥」与「体检一份现成目录」是两件事 —— 压在签名闸后面时
    # `--verify-only <dir>` 连目录都没打开就 exit 1（L2 审查实测；本任务修的正是这条）。
    # 签名复核不依赖私钥：verify 用**设备端内置 k1 公钥**无条件复核（见 [verify]）。
    if args.verify_only is not None:
        check_dir = args.verify_only or args.out
        problems = verify(check_dir)
        if problems:
            print("[emit] 发布目录不合格：", file=sys.stderr)
            for item in problems:
                print(f"  - {item}", file=sys.stderr)
            return 3
        log(f"发布目录合格：{check_dir}")
        return 0

    # 未签名闸（只约束「生成」这条路）：配了私钥就签；没配又没显式认领就停下来 ——
    # 未签名清单对整包替换攻击不设防，这个缺口要发版人睁着眼选，不能靠忘。
    if sign_key is None:
        warn_big("没有给 --sign-key：这份清单将**不带签名**（镜像上 manifest+APK+zip 整包被换时，"
                 "客户端无从发现）。要发未签名清单必须再加 --unsigned-explicit 重跑。")
        if not args.unsigned_explicit:
            die("未签名清单被拦下：要么给 --sign-key <pem>，要么显式 --unsigned-explicit 认领这一风险")
    if args.min_supported_version and not args.min_supported_version.isdigit():
        die(f"--min-supported-version 必须是纯数字（versionCode）：{args.min_supported_version}")

    version_name, version_code = version_from_gradle()
    apk_src_for_name = resolve_apk(args.apk)
    from_apk = version_from_apk_name(apk_src_for_name)
    if from_apk:
        apk_name_v, apk_code_v = from_apk
        if (apk_name_v, apk_code_v) != (version_name, version_code):
            log(f"注意：清单按 APK 自己的版本写（{apk_name_v}+{apk_code_v}），"
                f"源码树现在是 {version_name}+{version_code}。")
        version_name, version_code = apk_name_v, apk_code_v
    else:
        log(f"警告：APK 名认不出版本，清单按源码树 {version_name}+{version_code} 写。")
    # 上面两套来源（源码树 + APK 文件名）都可能与包内实际值不符；**包内值才是判据**，
    # 因为客户端拿它判「要不要再下一遍」。读不出时大声说，不静默回落。
    binary_ver = apk_truth.version_from_apk_binary(apk_src_for_name)
    if binary_ver:
        if binary_ver[0] != str(version_code) or binary_ver[1] != version_name:
            log(f"以 APK 本体为准：包里是 {binary_ver[1]}+{binary_ver[0]}，"
                f"源码树/文件名是 {version_name}+{version_code} —— 清单按包写。")
        version_name, version_code = binary_ver[1], binary_ver[0]
    elif not args.allow_unverified_apk_version:
        # 从 warn 升成硬闸（L2）：清单版本只能从文件名/源码树猜时，二者与包内值对不上就是
        # 「每次检查都有新版 → 下几百 MiB → 装上还是老码 → 下次又催」的死循环，而屏上没痕迹。
        # 内测/离线环境确实读不出 aapt2 时才走 --allow-unverified-apk-version 显式认领。
        die("读不出 APK 本体的 versionCode（aapt2 没找到或 dump 失败）：清单版本只能按文件名/"
            "源码树猜，对不上就是玩家反复重下整包的死循环。装好 build-tools（ANDROID_HOME / "
            "ANDROID_SDK_ROOT）再发；内测确要跳过就显式加 --allow-unverified-apk-version。")
    else:
        warn_big("--allow-unverified-apk-version：读不出 APK 本体的 versionCode（aapt2 没找到或"
                 " dump 失败），清单版本按文件名/源码树写 —— 仅限内测，正式发版必须先修好 aapt2。")
    if args.apk_version_code:
        if not args.apk_version_code.isdigit():
            die(f"--apk-version-code 必须是纯数字：{args.apk_version_code}")
        if binary_ver and args.apk_version_code != binary_ver[0]:
            die(f"--apk-version-code={args.apk_version_code} 与 APK 本体实际的 "
                f"{binary_ver[0]} 不符：这样发出的清单会让玩家无限重下整包。"
                "要发新版请先真出那个 versionCode 的包。")
        version_code = args.apk_version_code
    if args.apk_version_name:
        version_name = args.apk_version_name

    mirrors = resolve_mirrors(args)
    mirrors = [normalize_mirror(m) for m in mirrors]

    # 版本单调闸（§1.6）：生成前检查
    effective_assets_version = ""
    if not args.skip_assets:
        effective_assets_version = args.assets_version or version_code
        if not effective_assets_version.isdigit():
            die(f"--assets-version 必须是纯数字：{effective_assets_version}")

    if not args.skip_version_gate:
        remote = fetch_remote_manifest(args.manifest_url)
        version_gate(remote, version_code, effective_assets_version)

    # 数据源闸（更新通道半边，口径单源见 [data_source_gate]）：根不对，后面全白编。
    if not args.skip_assets:
        src_problems = data_source_gate(args.game_root, args.allow_retired_corpus)
        if src_problems:
            print("[emit] 数据源不合格，拒绝发布（混装 = 现网 4.981 脚本 + 4.980 数据的"
                  " ITEMNAME 越界形状）：", file=sys.stderr)
            for item in src_problems:
                print(f"  - {item}", file=sys.stderr)
            print(f"\n  修法：--game-root 指现行语料根（或导出 ERATW_GAMEDIR/ERATW_CORPUS 同源）；"
                  "确要旧语料的对拍包加 --allow-retired-corpus。", file=sys.stderr)
            return 2

    os.makedirs(args.out, exist_ok=True)

    # APK
    apk_src = resolve_apk(args.apk)
    apk_name = os.path.basename(apk_src)
    apk_dst = os.path.join(args.out, apk_name)
    if os.path.abspath(apk_src) != os.path.abspath(apk_dst):
        shutil.copy2(apk_src, apk_dst)
    apk_sha = sha256_of(apk_dst)
    log(f"整包：{apk_name} {os.path.getsize(apk_dst) / 1024 / 1024:.1f} MiB sha {apk_sha[:12]}...")

    # 数据包 + 差量
    assets_full = None
    assets_delta = None
    assets_block_delta = None
    assets_base_version = ""

    if not args.skip_assets:
        assets_version = effective_assets_version
        zip_path, new_index = stage_assets_zip(args.out, args.game_root, args.erbc_dir,
                                               assets_version, args.stored, args.include_bad)
        # 数据包内容闸：ERD 表对拍 +（随包带 erbc 时）语料新鲜度与整包对拍。
        # 判据与失败形状见 [erd_gate] / [erbc_pack_gate]。不合格留着 zip 让人取证，但别传。
        pack_problems = erd_gate(args.game_root, new_index)
        pack_problems += erbc_pack_gate(args.game_root, args.erbc_dir, new_index,
                                        apk_src, args.allow_stale_erbc)
        if pack_problems:
            print("[emit] 数据包不合格（**别上传**）：", file=sys.stderr)
            for item in pack_problems:
                print(f"  - {item}", file=sys.stderr)
            return 3
        assets_full = (os.path.basename(zip_path), os.path.getsize(zip_path),
                       sha256_of(zip_path), assets_version)

        # 差量：对比上一次索引
        old_index = load_index(args.out)
        if old_index and old_index.get("entries"):
            changed, deleted = compute_delta(new_index, old_index)
            if changed is not None:
                base_ver = old_index.get("version", "")
                delta_zip = stage_delta_zip(args.out, zip_path, changed or [],
                                           deleted or [], base_ver, assets_version)
                if delta_zip:
                    d_name = os.path.basename(delta_zip)
                    d_size = os.path.getsize(delta_zip)
                    d_sha = sha256_of(delta_zip)
                    assets_delta = (d_name, d_size, d_sha)
                    assets_base_version = base_ver
                else:
                    assets_base_version = base_ver
                # 块级差量（P2-13）：文件级差量治的是「改一条脚本重下整包」，
                # 治不了「改一张立绘重下整张图」。这条只**加**一个新对象、一组新键：
                # 不发（缺基线包 / --no-block-delta / 无变更）时清单里没有这三行，
                # 客户端照旧走文件级差量 —— 已经发出去的包一个都不打死。
                if not args.no_block_delta and changed:
                    base_zip = os.path.join(args.out, f"eratw-assets-{base_ver}.zip")
                    block_zip, block_stats = stage_block_delta_zip(
                        args.out, zip_path, base_zip, changed or [], deleted or [],
                        assets_version)
                    if block_zip:
                        assets_block_delta = (os.path.basename(block_zip),
                                              os.path.getsize(block_zip),
                                              sha256_of(block_zip))
                        if assets_delta:
                            saved = assets_delta[1] - assets_block_delta[1]
                            log(f"块级 vs 文件级差量：{assets_delta[1]} B → "
                                f"{assets_block_delta[1]} B（省 {saved} B，"
                                f"{saved * 100 // max(1, assets_delta[1])}%）")
                    else:
                        log(f"块级差量本次不发：{block_stats}")
        else:
            log("无旧索引，本次不生成差量（下次发布才有基线可比）。")

        # 保存新索引
        save_index(args.out, assets_version, new_index)

    elif args.assets_version:
        die("--assets-version 与 --skip-assets 同时给了")

    generated = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cert_sha = apk_truth.apk_cert_sha256(apk_dst)
    log(f"签名章：{cert_sha[:12]}… —— {apk_truth.cert_label(cert_sha)}")
    if cert_sha == apk_truth.PINNED_DEBUG_SHA256:
        warn_big("这个包是 debug 章签的：装过发布包的设备装不上它，反过来也顶不掉老包。")
    manifest = write_manifest(args.out, apk_name, os.path.getsize(apk_dst), apk_sha,
                              version_name, version_code, assets_full, assets_delta,
                              assets_base_version, mirrors, args.note, generated,
                              cert_sha,
                              min_supported_version=args.min_supported_version,
                              rollout_percentage=args.rollout_percentage,
                              sign_key=sign_key,
                              assets_block_delta=assets_block_delta)
    log(f"清单：{manifest}" + ("（已签名）" if sign_key is not None else "（未签名！）"))
    # 版本账：与 manifest.txt 同内容、按版本号命名，一起推上去。桶里的清理靠读这些账
    # 才知道「上一版引用过哪些对象」，本地台账换台机器就断了，所以账必须跟着产物住在一起。
    ledger = os.path.join(args.out, f"{MANIFEST_HISTORY_PREFIX}{version_code}.txt")
    shutil.copyfile(manifest, ledger)
    log(f"版本账：{os.path.basename(ledger)}（清理旧版时由它点名对象）")

    # 自检（给了私钥时顺带复核刚写下的签名）
    problems = verify(args.out, sign_key=sign_key)
    if problems:
        print("[emit] 刚生成的发布目录不合格（**别上传**）：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 3
    log("体检通过。")

    # R2 上传
    if args.upload:
        rc = r2_upload(args.out)
        if rc != 0:
            return rc
        # 清理只在「这次的东西确认都在线上」之后动 —— 先补后退，玩家永远拿得到清单指向的字节。
        base = updates_base_from_manifest(manifest)
        return r2_prune(base, r2_key_prefix_from_base(base), args.prune_keep, args.prune_dry)

    log(f"上传整个目录到镜像：{' '.join(mirrors)}")
    log("或用 --upload 推到 R2（wrangler r2 object put）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
