# -*- coding: utf-8 -*-
"""设备端清单语义的**单源判式**（L2 发版端闸门与设备端契约对齐）。

## 这个文件为什么存在

`emit_update.py --verify-only` 过去自带一份**宽松解析器**（`parse_manifest` 里 `if " " not in
line: continue`、后写覆盖前写），与设备端 `UpdateManifest.parse` 的实际口径背离。实测形状：
缺 apk-version-name / 缺 apk-sha256 / 缺 assets-sha256 的清单、重复键、文件名带空格、note 换行、
坏 min-supported 全部 exit 0；而设备端拿到同一份清单会判 `Rejected`（更新通道对那台设备整条哑掉）。
发版端比消费端宽 = 发布者永远看不到这类事故，所以这里把设备端的判式搬成一份可 import 的模块，
发布侧体检（`emit_update.verify`）与演练侧直译（`update_drill.parse_manifest`）**共用同一份**。

判式来源（逐条带出处，语义单源在 Kotlin，不在本文件）：

- 解析层：[UpdateManifest.kt:251-301](../../core-vm/src/main/kotlin/com/eratw/port/core/update/UpdateManifest.kt)
  —— 行扫描、`#` 注释、空行、无空格行、重复键、`update-format` 必需且 ≤ [FORMAT_SUPPORTED]、
  apk 组五键 / assets 组四键「要么齐全、要么整组缺席」。
- 策略层：[UpdatePolicy.kt:88-147](../../core-vm/src/main/kotlin/com/eratw/port/core/update/UpdatePolicy.kt)
  —— `normalizeMirror` / `isArtifactFileName` / `normalizeSha`；常量 `MAX_MIRRORS`（:53）、
  `MAX_MIRROR_CHARS`（:68）与本文件逐字同值，改一边必须改另一边。
- 验签层：[ManifestSignature.kt:117-164](../../core-vm/src/main/kotlin/com/eratw/port/core/update/ManifestSignature.kt)
  —— 三态（VERIFIED / INVALID / UNSIGNED）、签名行恒为最后一行、被签字节 = 文件头到签名行起点。
- 直译参考：`update_drill.py` 里那份设备端直译（L2 起反过来 import 本模块，判式只留一份）。

## 「发布侧严、消费侧宽」在这两个函数里的分工

- [parse_manifest] 只保留**设备侧会拒收**的判式（判成 Rejected = 那台设备的更新通道哑掉）。
  设备侧宽容的东西（未知键、坏镜像、可选组半组、note 超长、坏 rollout）一律不进 problems。
- [validate_manifest] 承载**发布侧**应当现在红的判式：设备侧只降级/只告警、但一旦上线就白烧
  玩家流量的形状（note 带换行、min-supported 越界、文件名带空格、缺 sha、镜像写法不归一）。
  `emit_update.verify` 的组合是「parse 的问题 + validate 的问题 + 验签问题」，任一条非零即 exit 3。

## 签名（契约 CONTRACT-UPD1-3LANE §2 U1 病①）

`--verify-only` 手上没有私钥，但验签只需要**包内公钥**（设备端 K1 就编在 `ManifestSignature.kt`
的 `K1_BASE64` 里）。所以体检**无条件**复核签名：签名行必须在最后、`sig-alg`/`sig-key-id` 与
设备端同判、被签字节按同一条切割规则还原、用 K1 验。假签名清单在发版前就被挡下，而不是等
设备端逐镜像回退（那等于这条镜像对玩家永久失效）。

密码学不在本文件重写：[check_signature] 收一个 `verify_rsa` 回调，调用方传
`emit_update.rsa_verify_sha256`（PKCS#1 v1.5 + SHA-256，已由 `emit_update.py --selftest` 的
openssl 黄金签名与 Java 侧 `SHA256withRSA` 互证）。
"""
from __future__ import annotations

import base64
import binascii
import os
import re

#: 本客户端认识的清单格式版本（`UpdateManifest.FORMAT_SUPPORTED`）。
FORMAT_SUPPORTED = 1
#: 单个下载文件的字节数上限（`UpdateManifest.MAX_ARTIFACT_BYTES`，4 GiB）。
MAX_ARTIFACT_BYTES = 4 * 1024 * 1024 * 1024
#: note 的显示长度上限（`UpdateManifest.MAX_NOTE_CHARS`）。
MAX_NOTE_CHARS = 500
#: 参与回退的镜像上限（`UpdatePolicy.MAX_MIRRORS`）。
MAX_MIRRORS = 8
#: 单条镜像地址长度上限（`UpdatePolicy.MAX_MIRROR_CHARS`）。
MAX_MIRROR_CHARS = 512

#: 下载文件名允许的字符集（`UpdatePolicy.isArtifactFileName` 的 allowlist）。
FILE_NAME_CHARS = frozenset(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._+-")
#: 文件名长度上限（`UpdatePolicy.isArtifactFileName`）。
MAX_FILE_NAME_CHARS = 200

SIGNATURE_KEY = "manifest-signature"
SIG_ALG_KEY = "sig-alg"
SIG_ALG_VALUE = "rsa-sha256"
SIG_KEY_ID_KEY = "sig-key-id"
#: 现役键 id（`ManifestSignature.KEY_ID_K1`）。
DEFAULT_KEY_ID = "k1"
#: 轮换预留键 id（`ManifestSignature.KEY_ID_NEXT`）。
NEXT_KEY_ID = "next"

APK_KEYS = ("apk-file", "apk-size", "apk-sha256", "apk-version-code", "apk-version-name")
ASSETS_KEYS = ("assets-file", "assets-size", "assets-sha256", "assets-version")
#: 三组可选三件套（整组缺席 = 这条通道不存在；缺半组/形状不对 = warning 后当缺席）。
OPTIONAL_GROUPS = ("assets-full", "assets-delta", "assets-blockdelta")

#: 与设备侧逐字同值的键名（写死在这里而不是抄全表：本模块只判「发布侧必须管」的那些）。
KEY_ASSETS_BASE_VERSION = "assets-base-version"
KEY_MIN_SUPPORTED_VERSION = "min-supported-version"
KEY_APK_CERT_SHA256 = "apk-cert-sha256"

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
# 公开分仓布局：core-vm 在姐妹仓 eraKcore；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(APP_ROOT), "eraKcore"))
CORE_UPDATE_DIR = os.path.join(
    KCORE_ROOT, "core-vm", "src", "main", "kotlin", "com", "eratw", "port", "core", "update")
MANIFEST_SIGNATURE_KT = os.path.join(CORE_UPDATE_DIR, "ManifestSignature.kt")


class ManifestCheckError(RuntimeError):
    """判式本身的前提缺失（读不出设备端公钥/常量）——不能静默放行，也不能假装验过。"""


# ---------------------------------------------------------------- 行扫描与形状（设备端直译）


def parse_manifest(text: str) -> tuple:
    """清单文本 → `(values, mirrors, problems)`，problems 非空即设备侧 Rejected。

    判式逐行对照 `UpdateManifest.parse`（:251-301）：
    - 跳过空行与 `#` 开头的注释行（:260）；
    - `line.trim()` 之后 `find(" ")`，`<= 0` ⇒ problem「不是「键 值」」（:261-265）；
    - `mirror` 行可以重复、进 mirrors（:268-271）；
    - 其余键重复 ⇒ problem「键名重复」（:272-275，**不是后写覆盖前写**）；
    - `update-format` 必需、必须是整数、`> FORMAT_SUPPORTED` 或 `< 1` ⇒ Rejected（:280-293）。

    返回的 values/mirrors 在 problems 非空时也照样返回：调用方（体检）要把已读到的键
    拿去继续查文件存在性，多报一条比漏报强；但**判定**只看 problems。

    只按 `\\n` 切行（文件末尾的 `\\r` 逐行剥掉）：发布侧固定 `\\n` 写出，而 note 里若是混进了
    裸 `\\r`，要让它留在值里被 [validate_manifest] 点名，而不是被切行糊成两行。
    """
    values = {}
    mirrors = []
    problems = []
    for line_no, raw in enumerate(text.split("\n"), start=1):
        line = raw[:-1] if raw.endswith("\r") else raw
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        space = line.find(" ")
        if space <= 0:
            problems.append(f"第 {line_no} 行不是「键 值」：{line}")
            continue
        key = line[:space]
        value = line[space + 1:].strip()
        if key == "mirror":
            mirrors.append(value)
            continue
        if key in values:
            problems.append(f"第 {line_no} 行键名重复：{key}")
            continue
        values[key] = value
    if problems:
        return values, mirrors, problems

    format_text = values.get("update-format")
    if format_text is None:
        return values, mirrors, ["清单没有 update-format 行（不是 eratw 更新清单？）"]
    if not _is_int(format_text):
        return values, mirrors, [f"update-format 不是整数：{format_text}"]
    fmt = int(format_text)
    if fmt > FORMAT_SUPPORTED:
        return values, mirrors, [
            f"清单格式 {fmt}，本客户端只支持到 {FORMAT_SUPPORTED}：请先更新应用"]
    if fmt < 1:
        return values, mirrors, [f"update-format 必须为正整数：{format_text}"]
    return values, mirrors, problems


def is_artifact_file_name(name: str) -> bool:
    """`UpdatePolicy.isArtifactFileName`（:135-140）的直译：allowlist + 禁 `..` + 禁前导 `-`。"""
    if not name or len(name) > MAX_FILE_NAME_CHARS:
        return False
    if ".." in name:
        return False
    if name == "." or name.startswith("-"):
        return False
    return all(ch in FILE_NAME_CHARS for ch in name)


def normalize_sha(raw) -> str | None:
    """`UpdatePolicy.normalizeSha`（:143-147）：64 位十六进制，大小写都收，归一成小写。"""
    if raw is None:
        return None
    text = raw.strip().lower()
    if len(text) != 64:
        return None
    return text if all(ch in "0123456789abcdef" for ch in text) else None


def normalize_mirror_value(url: str) -> str | None:
    """`UpdatePolicy.normalizeMirror`（:88-101）的直译：不合法返回 None，合法返回补尾斜杠的地址。

    为什么发布侧也照这一条走：改源的门路是「清单里多写一条 mirror」，而设备端会**丢弃**
    不合法的那些（:318-326 只记 warning）。发布者写错一条 = 那台设备白试一次连接，
    体检必须现在就说破，而不是等设备端逐条回退。
    """
    if url is None:
        return None
    trimmed = url.strip()
    if not trimmed or len(trimmed) > MAX_MIRROR_CHARS:
        return None
    if any(ch.isspace() or ord(ch) < 0x21 or ord(ch) == 0x7F for ch in trimmed):
        return None
    if not trimmed.startswith("https://"):
        return None
    without_scheme = trimmed[len("https://"):]
    if not without_scheme or without_scheme[0] == "/":
        return None
    if "?" in without_scheme or "#" in without_scheme:
        return None
    host = without_scheme.split("/", 1)[0]
    if not host or "." not in host:
        return None
    return trimmed if trimmed.endswith("/") else trimmed + "/"


# ---------------------------------------------------------------- 发布侧严格判式


def validate_manifest(text: str, values: dict, mirrors: list) -> list:
    """发布侧体检：`values/mirrors` 来自 [parse_manifest]（调用前保证 problems 为空）。

    与设备端的差别只在**宽度**：设备端对可选组/镜像/note/min-supported 的坏形状只降级
    （warning 后当缺席），发布侧必须现在红 —— 这些形状一旦上线，玩家侧最好的结局是
    「安静地不更新」，最坏的结局是反复重下整包。
    """
    problems = []

    # —— apk 组（五键齐全；发布目录没有整包就没有可装的东西）
    apk_state = _group_state(values, APK_KEYS)
    apk_code = None
    if apk_state == "absent":
        problems.append("清单没有 apk 组（" + " ".join(APK_KEYS) + "）：发布目录必须有整包")
    elif apk_state == "partial":
        missing = [key for key in APK_KEYS if key not in values]
        problems.append("apk 通道声明不完整，缺：" + " ".join(missing))
    else:
        code_raw = values["apk-version-code"]
        if not _is_int(code_raw) or int(code_raw) <= 0:
            problems.append(f"apk-version-code 必须是正整数：{code_raw}")
        else:
            apk_code = int(code_raw)
        if not values["apk-version-name"]:
            problems.append("apk-version-name 是空串")
        problems += _artifact_problems(values, "apk-file", "apk-size", "apk-sha256")

    # —— assets 组（四键齐全；--skip-assets 的发布整组缺席是合法形状）
    assets_state = _group_state(values, ASSETS_KEYS)
    if assets_state == "partial":
        missing = [key for key in ASSETS_KEYS if key not in values]
        problems.append("assets 通道声明不完整，缺：" + " ".join(missing))
    elif assets_state == "complete":
        version_raw = values["assets-version"]
        if not _is_int(version_raw) or int(version_raw) < 1:
            problems.append(f"assets-version 必须是 ≥1 的整数：{version_raw}")
        problems += _artifact_problems(values, "assets-file", "assets-size", "assets-sha256")

    # —— 三组可选三件套：发布侧「要么整组不写、要么整组写对」
    for group in OPTIONAL_GROUPS:
        state = _group_state(values, (f"{group}-file", f"{group}-size", f"{group}-sha256"))
        if state == "partial":
            missing = [f"{group}-{suffix}" for suffix in ("file", "size", "sha256")
                       if f"{group}-{suffix}" not in values]
            problems.append(f"可选组 {group} 声明不完整，缺：" + " ".join(missing)
                            + "（设备侧会整组忽略、回落全量）")
        elif state == "complete":
            problems += _artifact_problems(values, f"{group}-file", f"{group}-size",
                                           f"{group}-sha256")

    # —— 差量/块级都挂在同一个基线版本上：有线上的差量却没有基线 = 设备永远不启用它
    base_raw = values.get(KEY_ASSETS_BASE_VERSION)
    if base_raw is not None:
        if not _is_int(base_raw) or int(base_raw) < 1:
            problems.append(f"{KEY_ASSETS_BASE_VERSION} 必须是 ≥1 的整数：{base_raw}"
                            "（否则设备侧按无基线处理，差量不启用）")
    elif (_group_state(values, ("assets-delta-file", "assets-delta-size", "assets-delta-sha256"))
          == "complete"
            or _group_state(values, ("assets-blockdelta-file", "assets-blockdelta-size",
                                     "assets-blockdelta-sha256")) == "complete"):
        problems.append(f"有差量包但缺 {KEY_ASSETS_BASE_VERSION}：设备侧无从判断该不该应用")

    # —— note：一份一行一条的清单里出现换行，后半句就成了另一行（设备侧要么当坏行拒收，
    #    要么当未知键吞掉），发布者以为写了说明、设备侧看到的是半句。
    note = values.get("note", "")
    if "\r" in note or "\n" in note:
        problems.append("note 里带换行/回车：清单一行一条，换行会把说明截断（设备侧读到的是半句）")

    # —— min-supported-version：纯数字且 1..apk-version-code。大于当前版本号 = 强制把玩家
    #    推到一版还不存在的包上（设备侧会一直催更新而又无处可下）。
    min_raw = values.get(KEY_MIN_SUPPORTED_VERSION)
    if min_raw is not None:
        if not _is_int(min_raw):
            problems.append(f"{KEY_MIN_SUPPORTED_VERSION} 必须是纯数字 versionCode：{min_raw!r}"
                            "（设备侧遇到坏值按无强制档处理，强制更新就此静默失效）")
        elif int(min_raw) < 1:
            problems.append(f"{KEY_MIN_SUPPORTED_VERSION} 必须 ≥1：{min_raw}")
        elif apk_code is not None and int(min_raw) > apk_code:
            problems.append(f"{KEY_MIN_SUPPORTED_VERSION}={min_raw} 越界：大于本清单的 "
                            f"apk-version-code={apk_code}，等于把玩家推到一个还不存在的版本")

    # —— apk-cert-sha256：设备侧拿它做覆盖升级预检，形状不对按未声明处理（预检静默失效）
    cert_raw = values.get(KEY_APK_CERT_SHA256)
    if cert_raw is not None and normalize_sha(cert_raw) is None:
        problems.append(f"{KEY_APK_CERT_SHA256} 不是 64 位十六进制：{cert_raw!r}"
                        "（设备侧按未声明处理，章不一致的预检就此静默失效）")

    # —— mirror：逐条照设备端归一；写坏的那条会被设备端丢弃（:318-326），发布侧现在就要拦
    for index, raw in enumerate(mirrors, start=1):
        normalized = normalize_mirror_value(raw)
        if normalized is None:
            problems.append(f"第 {index} 条 mirror 不合法（设备侧会直接丢弃）：{raw!r}")
        elif normalized != raw:
            problems.append(f"第 {index} 条 mirror 写法与设备侧归一后的形态不一致："
                            f"{raw!r}（应为 {normalized!r}）")
    if len(mirrors) > MAX_MIRRORS:
        problems.append(f"mirror 有 {len(mirrors)} 条，超过设备侧上限 {MAX_MIRRORS}："
                        "多出来的几条设备端不会试")
    return problems


def _is_int(text) -> bool:
    """十进制非负整数（与 Kotlin `toLongOrNull` 对 "+1"/" 1"/"1.0" 的拒绝同口径）。"""
    return bool(text) and isinstance(text, str) and text.isdigit()


def _group_state(values: dict, keys: tuple) -> str:
    present = [key for key in keys if key in values]
    if not present:
        return "absent"
    return "complete" if len(present) == len(keys) else "partial"


def _artifact_problems(values: dict, key_file: str, key_size: str, key_sha: str) -> list:
    """三件套的发布侧形状判据（设备端 `readArtifact` :407-430 的同一把尺，宽口径不另写）。"""
    problems = []
    name = values.get(key_file, "")
    if not is_artifact_file_name(name):
        problems.append(f"{key_file} 不是合法文件名（只许 A-Za-z0-9._+-，不许带路径）：{name!r}")
    size_raw = values.get(key_size, "")
    if not _is_int(size_raw):
        problems.append(f"{key_size} 不是整数：{size_raw!r}")
    elif int(size_raw) <= 0 or int(size_raw) > MAX_ARTIFACT_BYTES:
        problems.append(f"{key_size} 不在 1 … {MAX_ARTIFACT_BYTES} 区间：{size_raw!r}")
    if normalize_sha(values.get(key_sha, "")) is None:
        problems.append(f"{key_sha} 不是 64 位十六进制：{values.get(key_sha)!r}")
    return problems


# ---------------------------------------------------------------- 验签（设备端 K1 信任锚）


def split_signed_portion(text: str) -> tuple | None:
    """清单文本 → `(taken_bytes_text, signature_b64, trailing_lines)`；无签名行返回 None。

    切割与 `ManifestSignature.splitSignedPortion`（:146-164）逐字同规则：取**最后一行**
    以 `manifest-signature ` 开头的行，被签字节 = 从文件头到该行起点前的一切（含前一行的
    行尾换行），不做任何归一化。`trailing_lines` 是签名行之后仍非空的行 —— 设备端的切割
    规则会把这些行**排除在被签字节之外**（攻击者可以在签名行后面塞新键而不破坏签名），
    所以发布侧体检必须要求签名行是最后一个非空行。
    """
    line_start = 0
    sig_start = -1
    sig_value = ""
    sig_line_end = -1
    length = len(text)
    while line_start < length:
        line_end = text.find("\n", line_start)
        if line_end < 0:
            line_end = length
        line = text[line_start:line_end]
        if line.endswith("\r"):
            line = line[:-1]
        if line.startswith(SIGNATURE_KEY + " "):
            sig_start = line_start
            sig_line_end = line_end
            sig_value = line[len(SIGNATURE_KEY) + 1:].strip()
        line_start = line_end + 1
    if sig_start < 0 or not sig_value:
        return None
    signed_text = text[:sig_start]
    after = text[sig_line_end + 1:] if sig_line_end + 1 <= length else ""
    trailing = [line for line in after.split("\n") if line.strip()]
    return signed_text, sig_value, trailing


def first_value(text: str, prefix: str) -> str | None:
    """取第一行以 `prefix` 开头的值（`ManifestSignature.verify` :119-128 的 `startsWith` 口径）。"""
    for raw in text.split("\n"):
        line = raw[:-1] if raw.endswith("\r") else raw
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    return None


def check_signature(text: str, verify_rsa, builtin_keys=None) -> list:
    """发布侧验签体检：返回问题列表；未签名清单返回空表（由调用方决定要不要大声说）。

    [verify_rsa] 是 `(signed_bytes, signature_bytes, (n, e, d)) -> bool` 的回调（传
    `emit_update.rsa_verify_sha256`，密码学不在这里重写）。

    与设备端 `ManifestSignature.verify` 同一条信任锚与同一条切割规则，只多一条发布侧专属判式：
    **签名行之后不许再有非空行**（设备端对此是「切割时静默丢掉」，等于允许多人往清单里塞
    不被签名覆盖的键；发布侧现在红，别等有人在真清单后面加一行）。
    """
    problems = []
    split = split_signed_portion(text)
    if split is None:
        return problems
    signed_text, sig_b64, trailing = split
    if trailing:
        problems.append(
            f"{SIGNATURE_KEY} 必须是最后一个非空行：其后还有 {len(trailing)} 行"
            "（设备侧的被签字节止于签名行，这些行等于没被签名覆盖）")
    alg = first_value(text, SIG_ALG_KEY + " ")
    if alg is not None and alg != SIG_ALG_VALUE:
        problems.append(f"{SIG_ALG_KEY} 不是 {SIG_ALG_VALUE}：{alg!r}（设备侧判「不过」）")
    key_id = first_value(text, SIG_KEY_ID_KEY + " ")
    if key_id is None:
        key_id = DEFAULT_KEY_ID
    keys = builtin_keys if builtin_keys is not None else load_device_builtin_keys()
    pair = next((item for item in keys if item[0] == key_id), None)
    if pair is None:
        available = "、".join(item[0] for item in keys) or "（空表）"
        problems.append(f"{SIG_KEY_ID_KEY} {key_id!r} 不在设备内置公钥表里（内置：{available}）")
        return problems
    try:
        signature = base64.b64decode(sig_b64, validate=True)
    except (binascii.Error, ValueError):
        problems.append(f"{SIGNATURE_KEY} 不是合法 Base64（截断/被改过）")
        return problems
    try:
        ok = verify_rsa(signed_text.encode("utf-8"), signature,
                        (pair[1][0], pair[1][1], 0))
    except Exception:  # noqa: BLE001 —— 与设备端一致：任何异常都算「不过」，不外抛
        ok = False
    if not ok:
        problems.append(f"{SIGNATURE_KEY} 验签不过：被签字节与签名对不上"
                        "（假签名/清单独被改过/私钥不是设备内置那把 k1）")
    return problems


_DEVICE_KEYS = None


def load_device_builtin_keys(path: str = "") -> list:
    """从 `ManifestSignature.kt` 读设备内置公钥表 → `[(key_id, (n, e)), ...]`。

    为什么不在这里硬编码 K1：内置公钥以 Kotlin 的 `K1_BASE64` 为**唯一权威**（换键时改的是
    那里，且 `ManifestSignatureTest` 的黄金签名会同步吵起来）。体检读同一份源，键轮换时
    发布侧不会拿着旧公钥把合法清单判死。

    读法照 `update_drill.py:278-281`（`kotlin_string_const`）：常量可以是多段相邻字面量拼接。
    """
    global _DEVICE_KEYS
    if _DEVICE_KEYS is not None and not path:
        return _DEVICE_KEYS
    source_path = path or MANIFEST_SIGNATURE_KT
    try:
        with open(source_path, "r", encoding="utf-8") as handle:
            source = handle.read()
    except OSError as exc:
        raise ManifestCheckError(
            f"读不出设备端内置公钥（{source_path}）：{exc}。体检没有信任锚就不能说「合格」") from exc
    key_id = _kotlin_string_const(source, "KEY_ID_K1", source_path)
    pairs = [(key_id, _spki_to_rsa_pair(_kotlin_string_const(source, "K1_BASE64", source_path)))]
    next_b64 = _kotlin_string_const(source, "NEXT_BASE64", source_path)
    if next_b64:
        next_id = _kotlin_string_const(source, "KEY_ID_NEXT", source_path)
        pairs.append((next_id, _spki_to_rsa_pair(next_b64)))
    if not path:
        _DEVICE_KEYS = pairs
    return pairs


def _kotlin_string_const(source: str, name: str, where: str) -> str:
    """`const val NAME: String = "…"`（允许多行、允许相邻字面量拼接），口径同 drill:139-148。"""
    match = re.search(
        r"const val %s(?:: String)?\s*=\s*(.+?)(?:\n\s*\n|\n\s*(?:private |public |internal |val |fun |@|/\*|\*|//))"
        % re.escape(name), source, re.S)
    if not match:
        raise ManifestCheckError(f"{where} 里读不到字符串常量 {name}（换键/改名时同步改本模块）")
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', match.group(1))
    if not parts:
        raise ManifestCheckError(f"{where} 里 {name} 不是字符串字面量")
    return "".join(part.replace('\\"', '"').replace("\\n", "\n") for part in parts)


def _spki_to_rsa_pair(base64_text: str) -> tuple:
    """X.509 SubjectPublicKeyInfo（Base64）→ `(n, e)`；与 drill:203-234 同一套手写 TLV 读法。

    标准库没有 ASN.1 解析，而发布侧零第三方依赖（与 `emit_update.load_pkcs8_key` 同一条纪律）。
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

    der = base64.b64decode(base64_text)
    tag, spki, _ = tlv(der, 0)
    if tag != 0x30:
        raise ManifestCheckError(f"内置公钥不像 SubjectPublicKeyInfo（首字节 0x{tag:02x}）")
    tag, _alg, pos = tlv(spki, 0)
    tag, bitstring, _ = tlv(spki, pos)
    if tag != 0x03:
        raise ManifestCheckError(f"内置公钥的 publicKey 字段形状不对（tag 0x{tag:02x}）")
    rsapk = bitstring[1:]  # 首字节是 unused-bits 计数，RSA 公钥恒为 0
    tag, body, _ = tlv(rsapk, 0)
    if tag != 0x30:
        raise ManifestCheckError("内置公钥的 BIT STRING 里不是 RSAPublicKey")
    tag, n_bytes, pos = tlv(body, 0)
    tag, e_bytes, _ = tlv(body, pos)
    return int.from_bytes(n_bytes, "big"), int.from_bytes(e_bytes, "big")
