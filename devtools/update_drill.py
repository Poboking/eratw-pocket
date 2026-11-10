#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新通道的**本地全链演练**（CONTRACT-UPD2-3LANE §2 U9，评审清单 P2-14）。

    python devtools/update_drill.py --selftest              # 验收主入口：合成场景 → 逐拍断言（离线）
    python devtools/update_drill.py --drill                 # 同上，但额外用真 k1 私钥发一份新 dist
    python devtools/update_drill.py --verify-only <DIR>     # 只体检现成发布目录，逐拍打印，不产生新 dist

这条通道此前从来没有一次「发布侧生成 → 镜像摆好 → 设备取清单 → 验签 → 灰度/强档 → 下载
（断点/失联换源）→ 核对 → 应用 → 安装回执」的端到端排练。单测各钉一块判据（都绿），
但**没有任何东西证明两块判据接起来还成立** —— 例如发布侧的签名三行与设备侧的切割规则
差一个字节，两边各自的单测都照绿，玩家那边就是「所有镜像都验不过」。本脚本补这一环。

## 选定路线：纯函数 seam + 本地回环静态镜像（**不**自签 https）

三个事实决定了路线：

1. 这条链上**所有会判错的部件都是纯函数**：core 的 `UpdatePolicy`/`UpdateManifest`/
   `ManifestSignature`、app 的 `UpdateDownloadPlan`/`UiPrefs` 健康表、平台层的 `ZipAssetTree.safeEntryName`
   与删除表判据。它们的输入是文本、数字与字节，输出是结论 —— 演练直接喂文本就能驱动。
2. 设备的传输层（`HttpURLConnection` + 「必须 https」的策略）不是判定面：
   `normalizeMirror` 只认 https 基址（UpdatePolicy.kt:93），`isHttpsEndpoint` 只认 https 端点
   （ErrorReportUpload.kt:50-54）。**演练不许为了跑起来去动这两条**（契约 §2 U9 硬约束），
   于是把「逻辑镜像地址」与「实际落地的 socket」分开：清单与候选序里永远是
   `https://…invalid/…`（照过生产归一与拒明文那两道闸），只有 socket 绑到 127.0.0.1 的
   一次性端口上。注入点在演练自己的传输函数里，生产侧一个字节都没改。
3. 自签 https 那条路要么让演练自己关掉证书校验（正好是要禁的「给明文/降校验开口子」的形状），
   要么依赖 `openssl` 现造证书（本机有、其它开发机与任何 CI 不保证有），把 selftest 变成
   「看机器」。TLS 本身不是本波任何一条判据，所以不引进新依赖。

## 演练的判定层从哪来（不许「拿 Python 的想象冒充 Kotlin」）

判定函数按 Kotlin 逐条直译，每个函数头写明 `文件:行号`；同时有**两道防漂移**：

- **常量与黄金向量在运行时从 Kotlin 源里读**（`KotlinSource`）：内置公钥、灰度桶数、
  失联阈值、`.part` 年龄上限、退避档位、`ManifestSignatureTest` 的 k1 黄金签名、
  安装回执的 `when` 分类表、遥测事件名。生产改了值，演练跟着变；生产删了判据，演练当场
  报「读不到」而不是继续绿。
- 密码学**不自写**：签名/验签直接调发布侧 `emit_update.rsa_sign_sha256` /
  `rsa_verify_sha256`（那条链已由 `emit_update.py --selftest` 的 openssl 黄金签名钉死）。

## 每拍的形状

每拍 = 造场景 → 跑 → 断言实际 == 期望，逐行打印 `拍号 名称 | 期望 | 实际 | 判定`；
失败时打印**首差**（文本给字节偏移与上下文，列表给第一个不相等的下标与两边读数）。
任一拍红 ⇒ 退出码非零，并把首差那一节排在最前（照 e2e-report 的口径）。

密钥纪律：`--sign-key` 只接路径，内容一律不打印、不复制进任何产物（AGENTS.md §5 出包口径同源）。
镜像地址一律用保留 TLD `.invalid`，不内联任何真实镜像地址。
"""

from __future__ import annotations

import argparse
import base64
import binascii
import dataclasses
import hashlib
import http.client
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID_ROOT = os.path.dirname(HERE)
# 公开分仓布局：core-vm 在姐妹仓 erak-engine；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(ANDROID_ROOT), "erak-engine"))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import emit_update  # 同目录：发布侧真入口（签名/写清单/体检都调它，不复制语义）
import update_manifest_check  # 同目录：设备端清单语义的单源判式（L2；本文件的 parse 只做组装）
from update_drill_mock_mirror import (  # 同目录：本地 mock 镜像与两种传输口
    FAULT_CUT, FAULT_DEAD, FAULT_ERROR, FAULT_MISSING, FAULT_NO_RANGE, FAULT_OK,
    InProcTransport,
    HttpTransport, MockMirror, serve, stop)

CORE_UPDATE = os.path.join(
    KCORE_ROOT, "core-vm", "src", "main", "kotlin", "com", "eratw", "port", "core", "update")
APP_ANDROID = os.path.join(
    ANDROID_ROOT, "app", "src", "main", "kotlin", "com", "eratw", "port", "android")
PLATFORM_STORAGE = os.path.join(
    ANDROID_ROOT, "platform", "src", "main", "kotlin", "com", "eratw", "port", "platform", "storage")
CORE_DIAG = os.path.join(
    KCORE_ROOT, "core-vm", "src", "main", "kotlin", "com", "eratw", "port", "core", "diag")
TEST_UPDATE = os.path.join(
    KCORE_ROOT, "core-vm", "src", "test", "kotlin", "com", "eratw", "port", "core", "update")

DEFAULT_SIGN_KEY = os.path.join(
    os.environ.get("ERATW_ROOT", ""), "local.update-signing", "k1-private.pk8.pem")

# 演练用的逻辑镜像（保留 TLD，永不解析；真实发布地址不出现在本文件里）。
MIRROR_A = "https://drill-a.invalid/eratw/"
MIRROR_B = "https://drill-b.invalid/eratw/"
MIRROR_C = "https://drill-c.invalid/eratw/"
MIRROR_DECLARED = "https://drill-declared.invalid/eratw/"
MIRROR_PERSISTED = "https://drill-cached.invalid/eratw/"
HTTP_CLEARTEXT_MIRROR = "http://drill-plaintext.invalid/eratw/"


def log(text: str) -> None:
    print(f"[drill] {text}")


def die(text: str) -> None:
    print(f"[drill] **不合格**：{text}", file=sys.stderr)
    sys.exit(1)


def _force_utf8_streams() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


_force_utf8_streams()

# =============================================================================
# 1. 生产源的读数口（常量 / 黄金向量 / 分类表）—— 演练不许自带第二套值
# =============================================================================


def read_source(path: str) -> str:
    if not os.path.isfile(path):
        raise KeyError(f"演练读不到生产源：{path}")
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def kotlin_string_const(src: str, name: str, where: str) -> str:
    """`const val NAME: String = "…"`（允许换行续写、允许多段相邻字面量拼接）。"""
    m = re.search(r"const val %s(?:: String)?\s*=\s*(.+?)(?:\n\s*\n|\n\s*(?:private |public |internal |val |fun |@|/\*|\*|//))"
                  % re.escape(name), src, re.S)
    if not m:
        raise KeyError(f"{where} 里读不到字符串常量 {name}")
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    if not parts:
        raise KeyError(f"{where} 里 {name} 不是字符串字面量")
    return "".join(p.replace('\\"', '"').replace("\\n", "\n") for p in parts)


def kotlin_number(src: str, expr: str, where: str, name: str) -> int:
    """Kotlin 数字表达式 → int：去 `L` 后缀与下划线，只认 + - * / ( ) 与数字。"""
    cleaned = re.sub(r"\b(\d[\d_]*)L\b", r"\1", expr).replace("_", "")
    if not re.fullmatch(r"[0-9+\-*/()\s]+", cleaned):
        raise KeyError(f"{where} 里 {name} 的表达式没法安全求值：{expr!r}")
    return int(eval(cleaned, {"__builtins__": {}}, {}))  # noqa: S307 —— 输入限定为数字四则


def kotlin_number_const(src: str, name: str, where: str) -> int:
    m = re.search(r"const val %s(?:: (?:Int|Long))?\s*=\s*([^\n]+)" % re.escape(name), src)
    if not m:
        raise KeyError(f"{where} 里读不到数值常量 {name}")
    return kotlin_number(src, m.group(1).rstrip(), where, name)


def kotlin_list_of(src: str, name: str, where: str) -> list:
    m = re.search(r"val %s(?:: [^=]+)?=\s*listOf\((.*?)\)" % re.escape(name), src, re.S)
    if not m:
        raise KeyError(f"{where} 里读不到 listOf 常量 {name}")
    body = m.group(1)
    out = []
    for token in body.split(","):
        token = token.strip()
        if not token:
            continue
        sm = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', token)
        if sm:
            out.append(sm.group(1))
        else:
            out.append(kotlin_number(src, token, where, name))
    return out


def kotlin_set_of(src: str, name: str, where: str, resolve: dict) -> set:
    m = re.search(r"val %s(?:: [^=]+)?=\s*setOf\((.*?)\)" % re.escape(name), src, re.S)
    if not m:
        raise KeyError(f"{where} 里读不到 setOf 常量 {name}")
    out = set()
    for token in m.group(1).split(","):
        token = token.strip()
        if not token:
            continue
        sm = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', token)
        if sm:
            out.add(sm.group(1))
        elif token in resolve:
            out.add(resolve[token])
        else:
            raise KeyError(f"{where} 里 {name} 引用了演练不认识的标识符 {token}")
    return out


def spki_to_rsa_pair(base64_text: str) -> tuple:
    """X.509 SubjectPublicKeyInfo DER（Base64）→ (n, e)。

    结构（RFC 5280 的 SubjectPublicKeyInfo）：SEQUENCE{ AlgorithmIdentifier, BIT STRING }，
    BIT STRING 里再套 PKCS#1 RSAPublicKey(SEQUENCE{ modulus, publicExponent })。
    与 `emit_update.load_pkcs8_key` 是同一套手写 TLV 读法（标准库无 ASN.1，不塞 cryptography）。
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
        raise KeyError(f"内置公钥不像 SubjectPublicKeyInfo（首字节 0x{tag:02x}）")
    tag, _alg, pos = tlv(spki, 0)
    tag, bitstring, _ = tlv(spki, pos)
    if tag != 0x03:
        raise KeyError(f"内置公钥的 publicKey 字段形状不对（tag 0x{tag:02x}）")
    rsapk = bitstring[1:]  # 首字节是 unused-bits 计数，RSA 公钥恒为 0
    tag, body, _ = tlv(rsapk, 0)
    if tag != 0x30:
        raise KeyError("内置公钥的 BIT STRING 里不是 RSAPublicKey")
    tag, n_b, pos = tlv(body, 0)
    tag, e_b, _ = tlv(body, pos)
    return int.from_bytes(n_b, "big"), int.from_bytes(e_b, "big")


def kotlin_copy_table(src: str, lang: str, want: tuple) -> dict:
    """从 UpdateCopy 的某一档（ZH/EN/JA）里读玩家面句子：`键 = "字面量"`。

    演练要的是「生产此刻说的是哪句话」，所以只认表、不认脚本里自抄的句子：U7b 之前
    `PackageInstallerSession` 的摘要臂右边是内联中文，U7b 把它们全收进这张表后
    `-> copy.installInvalidLine` 这种形状让老解析式抽空、演练**静默落回兜底句**还不报错
    —— 那正是这张表要防的形状。因此这里缺任何一条要用的键就当场炸，禁止带默认值继续绿。
    只支持单行字面量：表里多行拼接的条目（个别 EN/JA 长句）不走这条路，需要时在这里补。
    """
    block = re.search(r"\b%s\s*(?::\s*UpdateCopy)?\s*=\s*UpdateCopy\((.*?)\n        \)" % lang, src, re.S)
    if block is None:
        raise KeyError("UpdateCopy.kt 里读不到 %s 档" % lang)
    body = block.group(1)
    out = {}
    for key in want:
        m = re.search(r"^\s+%s\s*=\s*\"((?:[^\"\\]|\\.)*)\"\s*,?$" % re.escape(key), body, re.M)
        if m is None:
            raise KeyError("UpdateCopy 的 %s 档读不到单行条目 %s（改成多行拼接了就在这里补解析）" % (lang, key))
        out[key] = m.group(1).replace('\\"', '"').replace("\\n", "\n")
    return out


class KotlinSource:
    """演练所需的生产读数；一次读齐，缺任何一条就当场报红（不许带着默认值继续绿）。"""

    def __init__(self) -> None:
        sig_src = read_source(os.path.join(CORE_UPDATE, "ManifestSignature.kt"))
        policy_src = read_source(os.path.join(CORE_UPDATE, "UpdatePolicy.kt"))
        manifest_src = read_source(os.path.join(CORE_UPDATE, "UpdateManifest.kt"))
        plan_src = read_source(os.path.join(APP_ANDROID, "UpdateDownloadPlan.kt"))
        prefs_src = read_source(os.path.join(APP_ANDROID, "UiPrefs.kt"))
        installer_src = read_source(os.path.join(PLATFORM_STORAGE, "GameAssetInstaller.kt"))
        zip_src = read_source(os.path.join(PLATFORM_STORAGE, "ZipAssetTree.kt"))
        session_src = read_source(os.path.join(APP_ANDROID, "PackageInstallerSession.kt"))
        tele_src = read_source(os.path.join(CORE_UPDATE, "UpdateTelemetry.kt"))
        upload_src = read_source(os.path.join(CORE_DIAG, "ErrorReportUpload.kt"))
        golden_src = read_source(os.path.join(TEST_UPDATE, "ManifestSignatureTest.kt"))

        self.policy_src = policy_src
        # —— 验签层（ManifestSignature.kt）
        self.alg_rsa_sha256 = kotlin_string_const(sig_src, "ALG_RSA_SHA256", "ManifestSignature.kt")
        self.key_id_k1 = kotlin_string_const(sig_src, "KEY_ID_K1", "ManifestSignature.kt")
        self.signature_key = kotlin_string_const(sig_src, "SIGNATURE_KEY", "ManifestSignature.kt")
        k1_b64 = kotlin_string_const(sig_src, "K1_BASE64", "ManifestSignature.kt")
        self.builtin_keys = [(self.key_id_k1, spki_to_rsa_pair(k1_b64))]
        next_b64 = kotlin_string_const(sig_src, "NEXT_BASE64", "ManifestSignature.kt")
        if next_b64:  # 轮换位填上公钥后自动多一把（sig-key-id next 的清单也能验过）
            self.builtin_keys.append(("next", spki_to_rsa_pair(next_b64)))

        # —— 判定层常量（UpdatePolicy.kt）
        w = "UpdatePolicy.kt"
        self.max_mirror_chars = kotlin_number_const(policy_src, "MAX_MIRROR_CHARS", w)
        self.max_mirrors = kotlin_number_const(policy_src, "MAX_MIRRORS", w)
        self.rollout_buckets = kotlin_number_const(policy_src, "ROLLOUT_BUCKETS", w)
        self.full_rollout = kotlin_number_const(policy_src, "FULL_ROLLOUT_PERCENTAGE", w)
        self.demote_failures = kotlin_number_const(policy_src, "MIRROR_DEMOTE_FAILURES", w)
        self.stale_millis = kotlin_number_const(policy_src, "MIRROR_STALE_AFTER_MILLIS", w)
        self.part_max_age = kotlin_number_const(policy_src, "PART_MAX_AGE_MILLIS", w)
        self.min_headroom = kotlin_number_const(policy_src, "MIN_FREE_HEADROOM_BYTES", w)
        self.no_assets_installed = kotlin_number_const(policy_src, "NO_ASSETS_INSTALLED", w)
        self.check_interval = kotlin_number_const(policy_src, "CHECK_INTERVAL_MILLIS", w)
        self.retry_backoff = kotlin_list_of(policy_src, "RETRY_BACKOFF_MILLIS", w)

        # —— 解析层常量（UpdateManifest.kt）
        w = "UpdateManifest.kt"
        self.format_supported = kotlin_number_const(manifest_src, "FORMAT_SUPPORTED", w)
        self.max_manifest_bytes = kotlin_number_const(manifest_src, "MAX_MANIFEST_BYTES", w)
        self.max_artifact_bytes = kotlin_number_const(manifest_src, "MAX_ARTIFACT_BYTES", w)
        self.max_note_chars = kotlin_number_const(manifest_src, "MAX_NOTE_CHARS", w)
        self.no_assets_base = kotlin_number_const(manifest_src, "NO_ASSETS_BASE_VERSION", w)
        self.no_min_supported = kotlin_number_const(manifest_src, "NO_MIN_SUPPORTED_VERSION", w)

        # —— 下载计划层（UpdateDownloadPlan.kt）
        w = "UpdateDownloadPlan.kt"
        self.part_suffix = kotlin_string_const(plan_src, "PART_SUFFIX", w)
        self.small_headroom = kotlin_number_const(plan_src, "SMALL_HEADROOM_FLOOR_BYTES", w)

        # —— 灰度桶与镜像健康表（UiPrefs.kt）
        w = "UiPrefs.kt"
        self.mirror_failure_cap = kotlin_number_const(prefs_src, "MIRROR_FAILURE_CAP", w)
        self.no_rollout_bucket = kotlin_number_const(prefs_src, "NO_ROLLOUT_BUCKET", w)

        # —— 解包器（GameAssetInstaller.kt / ZipAssetTree.kt）
        self.artifact_dir = kotlin_string_const(installer_src, "ARTIFACT_DIR", "GameAssetInstaller.kt")
        self.program_file = kotlin_string_const(installer_src, "PROGRAM_FILE", "GameAssetInstaller.kt")
        self.meta_file = kotlin_string_const(installer_src, "META_FILE", "GameAssetInstaller.kt")
        self.update_dir = kotlin_string_const(installer_src, "UPDATE_DIR", "GameAssetInstaller.kt")
        self.config_file = kotlin_string_const(installer_src, "CONFIG_FILE", "GameAssetInstaller.kt")
        self.lock_file = kotlin_string_const(installer_src, "LOCK_FILE", "GameAssetInstaller.kt")
        self.data_dirs = kotlin_list_of(installer_src, "DATA_DIRS", "GameAssetInstaller.kt")
        self.never_delete = kotlin_set_of(
            installer_src, "NEVER_DELETE_SEGMENTS", "GameAssetInstaller.kt",
            {"CONFIG_FILE": self.config_file, "LOCK_FILE": self.lock_file,
             "UPDATE_DIR": self.update_dir})
        self.delta_list_name = kotlin_string_const(zip_src, "DELTA_LIST_NAME", "ZipAssetTree.kt")

        # —— 块级差量（P2-13，U8；判据全在 Kotlin，演练只读不抄）
        bd_manifest_src = read_source(os.path.join(CORE_UPDATE, "BlockDeltaManifest.kt"))
        bd_tree_src = read_source(os.path.join(PLATFORM_STORAGE, "BlockDeltaAssetTree.kt"))

        def bd_const(src, pattern, where):
            m = re.search(pattern, src)
            if not m:
                raise KeyError(f"{where} 里读不到 {pattern}")
            return m.group(1)

        # 清单三件套的键名（BlockDeltaManifest.kt）。三键在源里是相邻三行，中间没有既有
        # kotlin_string_const 认得的终结符，这里用单行锚定：只取等号右边这一个字面量。
        self.bd_key_file = bd_const(bd_manifest_src,
                                    r'const val KEY_FILE(?:: String)?\s*=\s*"([^"]+)"',
                                    "BlockDeltaManifest.kt")
        self.bd_key_size = bd_const(bd_manifest_src,
                                    r'const val KEY_SIZE(?:: String)?\s*=\s*"([^"]+)"',
                                    "BlockDeltaManifest.kt")
        self.bd_key_sha = bd_const(bd_manifest_src,
                                   r'const val KEY_SHA(?:: String)?\s*=\s*"([^"]+)"',
                                   "BlockDeltaManifest.kt")
        # 设备侧补丁判据常量（BlockDeltaAssetTree.kt 的 companion）
        w = "BlockDeltaAssetTree.kt"
        self.bd_patch_prefix = kotlin_string_const(bd_tree_src, "PATCH_DIR_PREFIX", w)
        self.bd_patch_header_max = kotlin_number_const(bd_tree_src, "HEADER_MAX_BYTES", w)
        self.bd_patch_max_block = kotlin_number_const(bd_tree_src, "MAX_BLOCK_BYTES", w)
        self.bd_patch_format = kotlin_string_const(bd_tree_src, "FORMAT", w)
        self.bd_header_keys = tuple(
            bd_const(bd_tree_src, r'const val %s = "([^"]+)"' % re.escape(name), w)
            for name in ("KEY_FORMAT", "KEY_BLOCK", "KEY_BASE_SHA", "KEY_RESULT_SHA",
                         "KEY_RESULT_SIZE", "KEY_OP_COUNT"))
        self.bd_patch_magic = bd_const(bd_tree_src,
                                       r'val MAGIC_LINE = "((?:[^"\\]|\\.)*)"\.toByteArray',
                                       w).replace('\\"', '"').replace("\\n", "\n")
        op_tags = dict(re.findall(r"const val OP_([A-Z]+) = '(.)'", bd_tree_src))
        if "COPY" not in op_tags or "RUN" not in op_tags:
            raise KeyError(f"{w} 里读不到 op 标签（OP_COPY / OP_RUN）")
        self.bd_op_copy, self.bd_op_run = op_tags["COPY"], op_tags["RUN"]
        # 判式链读数（UpdatePolicy.kt）：块级资格闸必须复用 deltaUsable、四参 dataArtifactFor
        # 必须回落三参形 —— 哪条断了都说明生产把回落次序改了，演练拒绝带着旧矩阵继续绿。
        if not re.search(r"fun blockDeltaUsable\(.*?\)\s*:\s*Boolean\s*=\s*deltaUsable\(",
                         policy_src, re.S):
            raise KeyError("UpdatePolicy.kt 的 blockDeltaUsable 不再复用 deltaUsable"
                           "（两档判式分叉，演练停止猜测）")
        if not re.search(r"fun dataArtifactFor\(\s*info: UpdateInfo,\s*blockDelta: Artifact\?.*?"
                         r"dataArtifactFor\(info, installedVersion, diskTrusted\)",
                         policy_src, re.S):
            raise KeyError("UpdatePolicy.kt 的四参 dataArtifactFor 读不到「块级 ⇒ 三参形」的回落链")
        enum_body = re.search(r"enum class DataArtifactKind\s*\{(.*?)\n\}", policy_src, re.S)
        if not enum_body:
            raise KeyError("UpdatePolicy.kt 里读不到 DataArtifactKind 枚举")
        self.data_kind_names = re.findall(r"^\s{4}([A-Z_]+),", enum_body.group(1), re.M)
        if self.data_kind_names != ["BLOCK_DELTA", "FILE_DELTA", "FULL", "NONE"]:
            raise KeyError("UpdatePolicy.kt 的 DataArtifactKind 不再是演练认识的四档互斥次序："
                           + ", ".join(self.data_kind_names) + "（回落矩阵得跟着重写）")

        # —— 安装回执三分类表（PackageInstallerSession.kt 的 when 臂；只认符号名不认数字）
        of_body = re.search(r"fun of\(status: Int.*?-> Success\b(.*?)\n        \}", session_src, re.S)
        self.install_outcome_arms = re.findall(r"PackageInstaller\.(STATUS_[A-Z_]+)\s*->\s*(\w+)",
                                               of_body.group(0) if of_body else "")
        summary = re.search(r"internal fun summaryOf\(.*", session_src, re.S)
        summary_body = summary.group(0)[:2400] if summary else ""
        # 摘要臂现在长这样：`PackageInstaller.STATUS_FAILURE_INVALID -> copy.installInvalidLine`
        # （句子在 UpdateCopy 三语表里，U7b 收的）。同时兼容早期那种右边直接是内联字面量的形状，
        # 两种都抽不出来就炸 —— 静默落兜底句是这一版演练曾经踩过的洞。
        self.install_summary_arms = [
            (name, ("copy", key))
            for name, key in re.findall(
                r"PackageInstaller\.(STATUS_[A-Z_]+)\s*->\s*\n?\s*copy\.(\w+)", summary_body)
        ]
        self.install_summary_literal_arms = [
            (name, ("literal", text))
            for name, text in re.findall(
                r"PackageInstaller\.(STATUS_[A-Z_]+)\s*->\s*\n?\s*\"((?:[^\"\\]|\\.)*)\"", summary_body)
        ]
        generic = re.search(
            r"else\s*->\s*copy\.fill\(\s*copy\.(\w+)", summary_body)
        self.install_generic_key = generic.group(1) if generic else None
        system_msg = re.search(
            r"copy\.fill\(\s*copy\.(\w+),\s*base", summary_body)
        self.install_system_message_key = system_msg.group(1) if system_msg else None
        if not (self.install_summary_arms or self.install_summary_literal_arms):
            raise KeyError("PackageInstallerSession.kt 里读不到失败摘要的 when 臂")
        if not self.install_generic_key:
            raise KeyError("PackageInstallerSession.kt 里读不到通用失败摘要用的表键")
        # 摘要臂引用的表键必须真在表里（表改了名而这里没跟，就该当场红，不该静默换句子）
        self.copy_zh = kotlin_copy_table(
            read_source(os.path.join(APP_ANDROID, "UpdateCopy.kt")), "ZH",
            tuple(sorted({key for _n, (_k, key) in self.install_summary_arms if _k == "copy"})) +
            (self.install_generic_key,) +
            (("installDoneTitle", "installDoneLine", "installCancelledTitle",
              "installCancelledLine", "installFailedTitle", "installFailedBodyFormat")
             if not self.install_system_message_key else
             (self.install_system_message_key, "installDoneTitle", "installDoneLine",
              "installCancelledTitle", "installCancelledLine", "installFailedTitle",
              "installFailedBodyFormat")))
        if not self.install_outcome_arms:
            raise KeyError("PackageInstallerSession.kt 里读不到回执分类的 when 臂")

        # —— 遥测（UpdateTelemetry.kt / UpdateTelemetrySink 的口径）
        self.telemetry_events = set(re.findall(r'const val EVENT_[A-Z_]+(?:\s*:\s*String)?\s*=\s*"([a-z_]+)"', tele_src))
        self.telemetry_kind = kotlin_string_const(
            read_source(os.path.join(APP_ANDROID, "UpdateTelemetrySink.kt")), "KIND",
            "UpdateTelemetrySink.kt")

        # —— 跨语言黄金向量（ManifestSignatureTest.kt）：k1 私钥签出的真实字节对
        gold_body = re.search(r"GOLDEN_BODY: String = listOf\((.*?)\)\s*\n", golden_src, re.S)
        if not gold_body:
            raise KeyError("ManifestSignatureTest.kt 里读不到 GOLDEN_BODY")
        # 先展开通配里的字符串插值（`${"a".repeat(64)}` 这类），再逐行取字面量 ——
        # 顺序反了的话插值里的引号会把字面量的边界吃掉。
        block = re.sub(r"\$\{([^{}]*)\}", KotlinSource._expand_interpolation, gold_body.group(1))
        lines = []
        for raw in block.splitlines():
            sm = re.match(r'\s*"((?:[^"\\]|\\.)*)",?\s*$', raw)
            if not sm:
                continue
            lines.append(sm.group(1).replace('\\"', '"'))
        # GOLDEN_BODY 的 list 末元素是空串 —— joinToString("\n") 于是以一个换行收尾，
        # 而被签字节要的正是「含前一行行尾换行」的那一份，逐字对齐才有跨语言可比。
        self.golden_body = "\n".join(lines)
        self.golden_signature = kotlin_string_const(golden_src, "GOLDEN_SIGNATURE", "ManifestSignatureTest.kt")

    @staticmethod
    def _expand_interpolation(m) -> str:
        inner = m.group(1).strip()
        rep = re.fullmatch(r'"([^"]*)"\.repeat\((\d+)\)', inner)
        if rep:
            return rep.group(1) * int(rep.group(2))
        lit = re.fullmatch(r'"([^"]*)"', inner)
        if lit:
            return lit.group(1)
        raise KeyError(f"GOLDEN_BODY 里有演练不会展开的插值：${inner}")


# =============================================================================
# 2. 设备侧判定层（逐条直译；每处注明 Kotlin 行号）
# =============================================================================

KS = None  # main() 里装好；模块级函数都经它取生产读数


@dataclasses.dataclass
class Artifact:
    file_name: str
    size_bytes: int
    sha256_hex: str


@dataclasses.dataclass
class UpdateInfo:
    format: int = 1
    generated: str = ""
    note: str = ""
    apk: object = None            # ApkChannel | None
    assets: object = None         # AssetsChannel | None
    mirrors: list = dataclasses.field(default_factory=list)
    warnings: list = dataclasses.field(default_factory=list)
    assets_full: object = None    # Artifact | None
    assets_delta: object = None   # Artifact | None
    assets_block_delta: object = None  # Artifact | None（块级差量三件套，P2-13）
    assets_base_version: int = 0
    apk_cert_sha256: str = ""
    rollout_percentage: int = 100
    manifest_signature: str = ""
    sig_alg: str = ""
    sig_key_id: str = ""
    min_supported_version: int = 0


@dataclasses.dataclass
class ApkChannel:
    version_code: int
    version_name: str
    artifact: Artifact


@dataclasses.dataclass
class AssetsChannel:
    version: int
    artifact: Artifact


@dataclasses.dataclass
class MirrorHealth:
    consecutive_failures: int = 0
    last_success_millis: int = 0


VERIFIED, INVALID, UNSIGNED = "VERIFIED", "INVALID", "UNSIGNED"


def normalize_mirror(url):
    """UpdatePolicy.kt:88-101 —— 只认 https、拒查询串/片段/空白/无点主机，末尾补斜杠。"""
    if url is None:
        return None
    trimmed = url.strip()
    if not trimmed or len(trimmed) > KS.max_mirror_chars:
        return None
    if any(ch.isspace() or ord(ch) < 0x21 or ord(ch) == 0x7F for ch in trimmed):
        return None
    if not trimmed.startswith("https://"):
        return None
    rest = trimmed[len("https://"):]
    if not rest or rest[0] == "/":
        return None
    if "?" in rest or "#" in rest:
        return None
    host = rest.split("/")[0]
    if not host or "." not in host:
        return None
    return trimmed if trimmed.endswith("/") else trimmed + "/"


def mirror_order(built_in=(), persisted=(), declared=()):
    """UpdatePolicy.kt:109-122 —— 内置最前、清单声明不得挤到内置之前；归一去重后截断。"""
    ordered = []
    seen = set()
    for group in (built_in, persisted, declared):
        for raw in group:
            base = normalize_mirror(raw)
            if base is None or base in seen:
                continue
            seen.add(base)
            ordered.append(base)
    return ordered[:KS.max_mirrors]


def download_url(mirror_base, file_name):
    """UpdatePolicy.kt:125-126。"""
    return (normalize_mirror(mirror_base) or "") + file_name


def is_artifact_file_name(name):
    """UpdatePolicy.kt:135-140。"""
    if not name or len(name) > 200 or ".." in name:
        return False
    if name == "." or name.startswith("-"):
        return False
    allowed = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._+-")
    return all(ch in allowed for ch in name)


def normalize_sha(sha):
    """UpdatePolicy.kt:143-147。"""
    if sha is None:
        return None
    trimmed = sha.strip().lower()
    if len(trimmed) != 64:
        return None
    return trimmed if all(c in "0123456789abcdef" for c in trimmed) else None


def is_check_due(last_check, now, interval=None):
    """UpdatePolicy.kt:182-190。"""
    interval = KS.check_interval if interval is None else interval
    if last_check <= 0:
        return True
    elapsed = now - last_check
    return elapsed < 0 or elapsed >= interval


def has_enough_space(usable, needed, headroom=None):
    """UpdatePolicy.kt:198-202。"""
    headroom = KS.min_headroom if headroom is None else headroom
    return usable > 0 and (usable - needed) >= headroom


def cert_blocks_install(local_cert, declared_cert):
    """UpdatePolicy.kt:220-224 —— 任一读不出（坏形状）就不拦。"""
    a = normalize_sha(local_cert)
    b = normalize_sha(declared_cert)
    if a is None or b is None:
        return False
    return a != b


def data_update_needed(staged, installed):
    """UpdatePolicy.kt:156-157 —— 严格大于，回退不降级。"""
    return staged > installed


def apk_update_needed(current, info):
    """UpdatePolicy.kt:160-163。"""
    return bool(info.apk) and info.apk.version_code > current


def assets_update_needed(installed, info):
    """UpdatePolicy.kt:171-174。"""
    return bool(info.assets) and data_update_needed(info.assets.version, installed)


def delta_usable(delta, base_version, installed_version, disk_trusted=True):
    """UpdatePolicy.kt:262-267 —— 基线必须**恰好等于**盘上版本，且盘可信。"""
    return delta is not None and base_version >= 1 and base_version == installed_version and disk_trusted


def full_data_artifact(info):
    """UpdatePolicy.kt:276。"""
    return info.assets_full if info.assets_full is not None else (info.assets.artifact if info.assets else None)


def data_artifact_for(info, installed_version, disk_trusted=True):
    """UpdatePolicy.kt:286-295 —— 差量有资格就差量，否则回落全量。"""
    if delta_usable(info.assets_delta, info.assets_base_version, installed_version, disk_trusted):
        return info.assets_delta
    return full_data_artifact(info)


def block_delta_usable(block_delta, base_version, installed_version, disk_trusted=True):
    """UpdatePolicy.kt:307-312 —— 与 deltaUsable 同一条闸，只是把那一件换成块级件。"""
    return delta_usable(block_delta, base_version, installed_version, disk_trusted)


def data_artifact_for_block(info, installed_version, disk_trusted=True):
    """UpdatePolicy.kt:328-338 —— 块级可用 ⇒ 块级；否则既有三参形原样接手（文件级 ⇒ 全量 ⇒ null）。"""
    if block_delta_usable(info.assets_block_delta, info.assets_base_version,
                          installed_version, disk_trusted):
        return info.assets_block_delta
    return data_artifact_for(info, installed_version, disk_trusted)


def data_artifact_kind(info, installed_version, disk_trusted=True):
    """UpdatePolicy.kt:346-358 —— 四档互斥读数，与 data_artifact_for_block 同参数同结论。

    档名取自 KS.data_kind_names（UpdatePolicy.kt 的 DataArtifactKind 枚举），演练不自造档名。
    """
    if block_delta_usable(info.assets_block_delta, info.assets_base_version,
                          installed_version, disk_trusted):
        return KS.data_kind_names[0]
    if delta_usable(info.assets_delta, info.assets_base_version, installed_version, disk_trusted):
        return KS.data_kind_names[1]
    if full_data_artifact(info) is not None:
        return KS.data_kind_names[2]
    return KS.data_kind_names[3]


def resume_offset(part_bytes, declared_size, age_millis, max_age=None):
    """UpdatePolicy.kt:314-325。"""
    max_age = KS.part_max_age if max_age is None else max_age
    if part_bytes <= 0:
        return 0
    if declared_size <= 0:
        return 0
    if part_bytes > declared_size:
        return 0
    if age_millis > max_age:
        return 0
    return part_bytes


def retry_backoff_millis(attempt):
    """UpdatePolicy.kt:338-339。"""
    if 0 <= attempt < len(KS.retry_backoff):
        return KS.retry_backoff[attempt]
    return None


def is_version_suppressed(version_code, skipped_version_code):
    """UpdatePolicy.kt:381-382。"""
    return skipped_version_code > 0 and skipped_version_code == version_code


def forbidden_by_config(forbid):
    """UpdatePolicy.kt:397。"""
    return bool(forbid)


def rollout_selected(percentage, bucket):
    """UpdatePolicy.kt:414-418 —— 坏值往「不误挡」回落。"""
    effective = percentage if 0 <= percentage <= KS.full_rollout else KS.full_rollout
    b = bucket if 0 <= bucket < KS.rollout_buckets else 0
    return b < effective


def is_force_update_required(current_version_code, min_supported_version):
    """UpdatePolicy.kt:367-368。"""
    return (current_version_code > 0 and min_supported_version > 0
            and current_version_code < min_supported_version)


def update_selected(rollout_percentage, bucket, current_version_code, min_supported_version):
    """UpdatePolicy.kt:434-440 —— 强制档优先于灰度。"""
    return (is_force_update_required(current_version_code, min_supported_version)
            or rollout_selected(rollout_percentage, bucket))


def mirror_fetch_healthy(verdict):
    """UpdatePolicy.kt:451-452 —— 只有验签判死算失联。"""
    return verdict != INVALID


def _mirror_unhealthy(state, now, failure_threshold, stale_millis):
    """UpdatePolicy.kt:455-466。"""
    if failure_threshold >= 1 and state.consecutive_failures >= failure_threshold:
        return True
    if state.last_success_millis <= 0:
        return False
    return now - state.last_success_millis > stale_millis


def mirror_order_with_health(built_in=(), persisted=(), declared=(), health=None, now=0,
                             failure_threshold=None, stale_millis=None):
    """UpdatePolicy.kt:480-505 —— 失联内置源降位但不删除，两组各自保序。"""
    health = health or {}
    failure_threshold = KS.demote_failures if failure_threshold is None else failure_threshold
    stale_millis = KS.stale_millis if stale_millis is None else stale_millis
    healthy, demoted = [], []
    for raw in built_in:
        base = normalize_mirror(raw)
        if base is None:
            continue
        state = health.get(base)
        if state is not None and _mirror_unhealthy(state, now, failure_threshold, stale_millis):
            demoted.append(base)
        else:
            healthy.append(base)
    return mirror_order(built_in=healthy + demoted, persisted=persisted, declared=declared)


def split_signed_portion(manifest_text):
    """ManifestSignature.kt:146-164 —— 最后一行签名行之前的一切字节进验签。"""
    line_start = 0
    sig_start = -1
    sig_value = ""
    length = len(manifest_text)
    key = KS.signature_key + " "
    while line_start < length:
        line_end = manifest_text.find("\n", line_start)
        if line_end < 0:
            line_end = length
        line = manifest_text[line_start:line_end]
        if line.endswith("\r"):
            line = line[:-1]
        if line.startswith(key):
            sig_start = line_start
            sig_value = line[len(key):].strip()
        line_start = line_end + 1
    if sig_start < 0 or not sig_value:
        return None
    return manifest_text[:sig_start] if sig_start > 0 else "", sig_value


def verify_manifest(manifest_text):
    """ManifestSignature.kt:117-136 —— 三态；公钥表来自 Kotlin 源里那把内置 k1。

    密码学不自写：`emit_update.rsa_verify_sha256`（PKCS#1 v1.5 + SHA-256，已由
    `emit_update.py --selftest` 的 openssl 黄金签名与 Java 侧 `SHA256withRSA` 互证）。
    """
    split = split_signed_portion(manifest_text)
    if split is None:
        return UNSIGNED
    signed_text, sig_b64 = split
    alg = _first_value(manifest_text, "sig-alg ")
    if alg is not None and alg != KS.alg_rsa_sha256:
        return INVALID
    key_id = _first_value(manifest_text, "sig-key-id ")
    if key_id is None:
        key_id = KS.key_id_k1
    pair = next((kv for name, kv in KS.builtin_keys if name == key_id), None)
    if pair is None:
        return INVALID
    # emit_update.rsa_verify_sha256 的键形是 (n, e, d)；验签只用得上前两元。
    verify_key = (pair[0], pair[1], 0)
    try:
        raw = base64.b64decode(sig_b64, validate=True)
    except (binascii.Error, ValueError):
        return INVALID
    try:
        return VERIFIED if emit_update.rsa_verify_sha256(signed_text.encode("utf-8"), raw, verify_key) else INVALID
    except Exception:  # noqa: BLE001 —— 与 Kotlin 一致：任何异常都算「不过」，不外抛
        return INVALID


def _first_value(text, prefix):
    """ManifestSignature.kt:119-128 —— 取第一行以 prefix 开头的值（剥 \\r 后 trim）。"""
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    return None


# —— UpdateManifest.parse（UpdateManifest.kt:221-326）的直译：认不认识的键都留着，
#    格式高于支持值才整条拒，可选组任何不对只 warning 并当缺席（回落方向永远是「多下字节」）。

_KNOWN_KEYS = {
    "update-format", "generated", "note", "apk-version-code", "apk-version-name", "apk-file",
    "apk-size", "apk-sha256", "apk-cert-sha256", "assets-version", "assets-file", "assets-size",
    "assets-sha256", "assets-full-file", "assets-full-size", "assets-full-sha256",
    "assets-delta-file", "assets-delta-size", "assets-delta-sha256", "assets-base-version",
    "rollout-percentage", "manifest-signature", "sig-alg", "sig-key-id", "min-supported-version",
}


def parse_manifest(text):
    """返回 (UpdateInfo | None, problems)；problems 非空即 Rejected（UpdateManifest.kt:248/271）。

    L2 起「行扫描 / 重复键 / update-format」这三段判式**单源**在
    `update_manifest_check.parse_manifest`（发布侧 `emit_update.py --verify-only` 与设备侧
    直译共用同一份）；本函数只做 UpdateInfo 的组装与设备侧可选组的宽口径，断言语义不变。
    """
    values, mirrors, problems = update_manifest_check.parse_manifest(text)
    if problems:
        return None, problems
    fmt = int(values["update-format"])
    warnings = []
    # 块级三键归 BlockDeltaManifest 所有，UpdateManifest 只做「已知键」登记（UpdateManifest.kt:548-553）
    known = _KNOWN_KEYS | {KS.bd_key_file, KS.bd_key_size, KS.bd_key_sha}
    for key in values:
        if key not in known:
            warnings.append(f"忽略不认识的键：{key}")

    apk, p = _read_apk(values)
    problems += p
    assets, p = _read_assets(values)
    problems += p
    if problems:
        return None, problems
    assets_full, p = _read_optional_group(values, "assets-full")
    warnings += p
    assets_delta, p = _read_optional_group(values, "assets-delta")
    warnings += p
    # 块级那一组（P2-13）：判式复用 BlockDeltaManifest 的直译；problem 只告警不拒清单
    #（UpdateManifest.kt:310-314 —— 回落方向仍然是「多下点字节」）。
    assets_block_delta, block_problem = _read_block_delta_group(values)
    if block_problem:
        warnings.append(f"{block_problem}：块级差量不启用，回落文件级差量或全量")
    base_version, p = _read_base_version(values)
    warnings += p
    min_supported, p = _read_min_supported(values)
    warnings += p

    clean, seen = [], set()
    for index, raw in enumerate(mirrors):
        normalized = normalize_mirror(raw)
        if normalized is None:
            warnings.append(f"第 {index + 1} 条 mirror 不合法，已丢弃：{raw}")
        elif normalized not in seen:
            seen.add(normalized)
            clean.append(normalized)
    if len(clean) > KS.max_mirrors:
        warnings.append(f"mirror 超过 {KS.max_mirrors} 条，只取前 {KS.max_mirrors} 条")

    note = values.get("note", "")
    if len(note) > KS.max_note_chars:
        note = note[:KS.max_note_chars] + "…"
    cert_raw = values.get("apk-cert-sha256")
    cert = ""
    if cert_raw is not None:
        cert = normalize_sha(cert_raw)
        if cert is None:
            warnings.append(f"apk-cert-sha256 形状不对，按未声明处理：{cert_raw}")
            cert = ""
    rollout, p = _read_rollout(values)
    warnings += p
    info = UpdateInfo(
        format=fmt, generated=values.get("generated", ""), note=note, apk=apk, assets=assets,
        mirrors=clean[:KS.max_mirrors], warnings=warnings, assets_full=assets_full,
        assets_delta=assets_delta, assets_block_delta=assets_block_delta,
        assets_base_version=base_version, apk_cert_sha256=cert,
        rollout_percentage=rollout, manifest_signature=values.get("manifest-signature", ""),
        sig_alg=values.get("sig-alg", ""), sig_key_id=values.get("sig-key-id", ""),
        min_supported_version=min_supported,
    )
    return info, []


def _read_artifact(values, key_file, key_size, key_sha):
    """UpdateManifest.kt:371-394（必填组与可选组共用同一套形状校验）。"""
    file_name = values[key_file]
    if not is_artifact_file_name(file_name):
        return None, [f"{key_file} 不是合法文件名（只许 A-Za-z0-9._+-，不许带路径）：{file_name}"]
    try:
        size = int(values[key_size])
    except ValueError:
        size = None
    if size is None or size <= 0 or size > KS.max_artifact_bytes:
        return None, [f"{key_size} 不在 1 … {KS.max_artifact_bytes} 区间：{values[key_size]}"]
    sha = normalize_sha(values[key_sha])
    if sha is None:
        return None, [f"{key_sha} 不是 64 位十六进制：{values[key_sha]}"]
    return Artifact(file_name, size, sha), []


def _read_apk(values):
    """UpdateManifest.kt:329-349。"""
    keys = ["apk-file", "apk-size", "apk-sha256", "apk-version-code", "apk-version-name"]
    present = [k for k in keys if k in values]
    if not present:
        return None, []
    if len(present) != len(keys):
        missing = [k for k in keys if k not in values]
        return None, ["apk 通道声明不完整，缺：" + " ".join(missing)]
    try:
        code = int(values["apk-version-code"])
    except ValueError:
        code = None
    if code is None or code <= 0:
        return None, [f"apk-version-code 必须是正整数：{values['apk-version-code']}"]
    if not values["apk-version-name"]:
        return None, ["apk-version-name 是空串"]
    artifact, problems = _read_artifact(values, "apk-file", "apk-size", "apk-sha256")
    if artifact is None:
        return None, problems
    return ApkChannel(code, values["apk-version-name"], artifact), []


def _read_assets(values):
    """UpdateManifest.kt:352-368。"""
    keys = ["assets-file", "assets-size", "assets-sha256", "assets-version"]
    present = [k for k in keys if k in values]
    if not present:
        return None, []
    if len(present) != len(keys):
        missing = [k for k in keys if k not in values]
        return None, ["assets 通道声明不完整，缺：" + " ".join(missing)]
    try:
        version = int(values["assets-version"])
    except ValueError:
        version = None
    if version is None or version < 1:
        return None, [f"assets-version 必须是 ≥1 的整数：{values['assets-version']}"]
    artifact, problems = _read_artifact(values, "assets-file", "assets-size", "assets-sha256")
    if artifact is None:
        return None, problems
    return AssetsChannel(version, artifact), []


def _read_optional_group(values, group):
    """UpdateManifest.kt:409-432 —— 缺半组/形状不对 ⇒ warning + 当缺席（绝不拒清单）。"""
    keys = [f"{group}-file", f"{group}-size", f"{group}-sha256"]
    present = [k for k in keys if k in values]
    if not present:
        return None, []
    if len(present) != len(keys):
        missing = [k for k in keys if k not in values]
        return None, [f"可选组 {group} 声明不完整，缺：{' '.join(missing)}：整组忽略，回落全量"]
    artifact, problems = _read_artifact(values, keys[0], keys[1], keys[2])
    if artifact is None:
        return None, ["；".join(problems) + f"：可选组 {group} 整组忽略，回落全量"]
    return artifact, []


def _read_block_delta_group(values):
    """BlockDeltaManifest.kt 的直译（UpdateManifest.parse 经它读数，键名与形状只有一份）。

    整组缺席 = (None, None)；缺半组 / 形状不对 = (None, problem 文本)。形状校验与
    _read_artifact 同一套 —— readArtifact 的直译（BlockDeltaManifest.parse 与
    readOptionalArtifact 判式逐字同口径）。
    """
    keys = (KS.bd_key_file, KS.bd_key_size, KS.bd_key_sha)
    present = [k for k in keys if k in values]
    if not present:
        return None, None
    if len(present) != len(keys):
        missing = [k for k in keys if k not in values]
        group = KS.bd_key_file[:-len("-file")]
        return None, f"可选组 {group} 声明不完整，缺：{' '.join(missing)}"
    artifact, problems = _read_artifact(values, keys[0], keys[1], keys[2])
    if artifact is None:
        return None, "；".join(problems)
    return artifact, None


def block_delta_values_of(text):
    """清单原文 → 只含块级三键的 values 表（BlockDeltaManifest.parseManifest 的取行口径）。"""
    keys = {KS.bd_key_file, KS.bd_key_size, KS.bd_key_sha}
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or " " not in line:
            continue
        key, value = line.split(" ", 1)
        if key in keys:
            values[key] = value.strip()
    return values


def _read_base_version(values):
    """UpdateManifest.kt:440-448。"""
    raw = values.get("assets-base-version")
    if raw is None:
        return KS.no_assets_base, []
    try:
        version = int(raw)
    except ValueError:
        version = None
    if version is None or version < 1:
        return KS.no_assets_base, [f"assets-base-version 不是 ≥1 的整数：{raw}：按无基线处理，差量不启用"]
    return version, []


def _read_min_supported(values):
    """UpdateManifest.kt:478-489。"""
    raw = values.get("min-supported-version")
    if raw is None:
        return KS.no_min_supported, []
    try:
        version = int(raw)
    except ValueError:
        version = None
    if version is None or version < 1:
        return KS.no_min_supported, [f"min-supported-version 不是 ≥1 的整数：{raw}：按无强制档处理"]
    return version, []


def _read_rollout(values):
    """UpdateManifest.kt:460-468 —— 缺席不告警，坏值回落 100 并留痕。"""
    raw = values.get("rollout-percentage")
    if raw is None:
        return KS.full_rollout, []
    try:
        value = int(raw)
    except ValueError:
        value = None
    if value is None or not 0 <= value <= KS.full_rollout:
        return KS.full_rollout, [f"rollout-percentage 不是 0..100 的整数：{raw}：按 100 全量处理"]
    return value, []


# —— UpdateDownloadPlan（app 层纯判定）

def resume_offset_for(artifact, part):
    """UpdateDownloadPlan.kt:116-119。part = (bytes, age) | None。"""
    if part is None:
        return 0
    return resume_offset(part[0], artifact.size_bytes, part[1])


def is_part_complete_for(artifact, part):
    """UpdateDownloadPlan.kt:122-123。"""
    return part is not None and part[0] == artifact.size_bytes


def remaining_bytes(artifact, part):
    """UpdateDownloadPlan.kt:126-127。"""
    return artifact.size_bytes - resume_offset_for(artifact, part)


def content_range_start(header):
    """UpdateDownloadPlan.kt:158-166。"""
    if header is None:
        return None
    trimmed = header.strip()
    if not trimmed.lower().startswith("bytes"):
        return None
    range_part = trimmed.split(" ", 1)[1] if " " in trimmed else ""
    range_part = range_part.split("/")[0].strip().lstrip("=").strip()
    start_text = range_part.split("-")[0] if "-" in range_part else (range_part or "")
    if not range_part or not start_text:
        return None
    try:
        start = int(start_text)
    except ValueError:
        return None
    return start if start >= 0 else None


def resume_trusted(requested_offset, response_code, cr_start):
    """UpdateDownloadPlan.kt:138-139。"""
    return requested_offset > 0 and response_code == 206 and cr_start == requested_offset


def must_rewrite_from_scratch(requested_offset, response_code, cr_start):
    """UpdateDownloadPlan.kt:149-150。"""
    return requested_offset > 0 and not resume_trusted(requested_offset, response_code, cr_start)


def required_headroom_bytes(declared_size):
    """UpdateDownloadPlan.kt:185-186。"""
    return min(max(declared_size, KS.small_headroom), KS.min_headroom)


def space_ok(usable, artifact, part):
    """UpdateDownloadPlan.kt:195-196。"""
    return has_enough_space(usable, remaining_bytes(artifact, part),
                            required_headroom_bytes(artifact.size_bytes))


NETWORK, HTTP_STATUS, MISSING, PLAIN_HTTP, DIGEST, OVERSIZE, LOCAL_FS = (
    "Network", "HttpStatus", "MissingOnMirror", "PlainHttp", "DigestMismatch",
    "OversizeStream", "LocalFileSystem")


def decide_failure(kind):
    """UpdateDownloadPlan.kt:103-106 —— 只有网络与临时 HTTP 错误配得上退避重试。"""
    return "RetrySameMirror" if kind in (NETWORK, HTTP_STATUS) else "NextMirror"


def plan_cleanup(entries, keep_artifacts, extra_keep=()):
    """UpdateDownloadPlan.kt:215-240。entries = [(name, bytes, age)]。"""
    targets = {a.file_name for a in keep_artifacts} | set(extra_keep)
    by_part = {a.file_name + KS.part_suffix: a for a in keep_artifacts}
    present = {name for name, _b, _a in entries}
    doomed = []
    for name, size, age in entries:
        if name in targets:
            continue
        if name.endswith(KS.part_suffix):
            artifact = by_part.get(name)
            if artifact is None or artifact.file_name in present:
                doomed.append(name)
            elif resume_offset_for(artifact, (size, age)) <= 0:
                doomed.append(name)
        else:
            doomed.append(name)
    return doomed


def note_mirror_result(rows, mirror_base, success, now):
    """UiPrefs.kt:463-482 —— 成功清零并记时刻；失败 +1 封顶；表条目封顶 MAX_MIRRORS。"""
    base = normalize_mirror(mirror_base)
    if base is None:
        return rows
    rows = dict(rows)
    if base not in rows and len(rows) >= KS.max_mirrors:
        return rows
    old = rows.get(base)
    if success:
        rows[base] = MirrorHealth(0, max(now, 0))
    else:
        failures = min((old.consecutive_failures if old else 0) + 1, KS.mirror_failure_cap)
        rows[base] = MirrorHealth(failures, old.last_success_millis if old else 0)
    return rows


# —— 解包器的删除/条目名判据（平台层）

def safe_entry_name(raw):
    """ZipAssetTree.kt:268-287 —— 归一后判死：`..`/空段/`.` 段/控制字符都不进索引。"""
    name = raw.replace("\\", "/")
    while True:
        if name.startswith("/"):
            name = name[1:]
        elif name.startswith("./"):
            name = name[2:]
        else:
            break
    if name.endswith("/"):
        name = name[:-1]
    if not name or name == ".":
        return None
    for segment in name.split("/"):
        if segment in ("", ".", ".."):
            return None
    if any(ord(ch) < 0x20 for ch in name):
        return None
    return name


def deletion_is_protected(rel):
    """GameAssetInstaller.kt:1535-1536 —— 判任意一段。"""
    return any(segment in KS.never_delete for segment in rel.split("/"))


def deletion_within_asset_universe(rel):
    """GameAssetInstaller.kt:1546-1549 —— 只有解包器写过的范围才轮得到它删。"""
    head = rel.split("/")[0]
    return head == KS.artifact_dir or head in KS.data_dirs


def is_https_endpoint(endpoint):
    """ErrorReportUpload.kt:50-54（UpdateTelemetrySink.isEnabled 复用的那一条）。"""
    if endpoint is None or not endpoint.strip():
        return False
    trimmed = endpoint.strip()
    return trimmed.startswith("https://") and not any(ch.isspace() for ch in trimmed)


def install_outcome_for(status_symbol, status_message=None):
    """PackageInstallerSession.kt:52-56 + :64-77 的分类表（表体从 Kotlin 源解析而来）。

    演练按 **SDK 常量名**分派而不是按数字：数字属于 android.jar，真机侧才有（TODO(verify)），
    而分类判据就是那张 when 表；把数字抄进演练等于自造第二份真相。
    """
    for name, cls in KS.install_outcome_arms:
        if name == status_symbol:
            if cls == "Success":
                return "Success", None
            if cls == "UserCancelled":
                return "UserCancelled", status_message
            return cls, status_message
    arms = list(KS.install_summary_arms) + list(KS.install_summary_literal_arms)
    hit = next((val for name, (_how, val) in arms if name == status_symbol), None)
    if hit is not None:
        base = KS.copy_zh[hit] if _kind_of(arms, status_symbol) == "copy" else hit
    else:
        base = KS.copy_zh[KS.install_generic_key].replace("{0}", status_symbol)
    if status_message and status_message.strip():
        base = (KS.copy_zh[KS.install_system_message_key]
                .replace("{0}", base).replace("{1}", status_message))
    return "Failed", base


def _kind_of(arms, status_symbol):
    for name, (how, _val) in arms:
        if name == status_symbol:
            return how
    return None


def install_deliver_for(outcome_kind, summary):
    """PackageInstallerSession.kt 的 `deliver` 可观测面：通知标题 + 正文 + 有没有重试 intent。

    标题与正文句子全部从 UpdateCopy 的 ZH 档取（同 production 的单路取词），演练不自造第二份话。
    """
    zh = KS.copy_zh
    if outcome_kind == "Success":
        return {"title": zh["installDoneTitle"], "body": zh["installDoneLine"], "retry": False}
    if outcome_kind == "UserCancelled":
        return {"title": zh["installCancelledTitle"], "body": zh["installCancelledLine"], "retry": True}
    return {"title": zh["installFailedTitle"],
            "body": zh["installFailedBodyFormat"].replace("{0}", summary or ""), "retry": True}


# =============================================================================
# 3. 设备替身 + 客户端链（UpdateManager / applyStagedBundleIfPresent 的演练替身）
# =============================================================================

MANIFEST_FILE_NAME = "manifest.txt"  # UpdateManager.kt:93


class DownloadAbort(Exception):
    """对应 UpdateManager 内部的 DownloadAbort（带失败类别与「.part 留不留」）。"""

    def __init__(self, kind, message, discard_part=False):
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.discard_part = discard_part


@dataclasses.dataclass
class Device:
    """一台替身设备：UiPrefs 的读数 + 下载目录 + 游戏根，全是演练自己的临时目录。"""

    workdir: str
    built_in_mirrors: list
    current_version_code: int = 1
    rollout_bucket: int = 0
    skipped_version_code: int = 0
    persisted_mirrors: list = dataclasses.field(default_factory=list)
    mirror_health: dict = dataclasses.field(default_factory=dict)
    usable_bytes: int = 8 << 30
    local_cert_sha256: str = ""
    expected_apk_file: str = ""
    expected_apk_cert: str = ""
    transport: object = None
    last_response: object = None
    staged_assets_path: str = ""
    staged_assets_version: int = 0
    installed_data_version: int = 0
    disk_digest_trusted: bool = True
    delta_failed_on_base: int = -1
    waits: list = dataclasses.field(default_factory=list)  # 退避真实等待由演练记录、不真等
    log: list = dataclasses.field(default_factory=list)
    telemetry_endpoint: str = ""
    telemetry_token: str = ""
    telemetry_posts: list = dataclasses.field(default_factory=list)
    events: list = dataclasses.field(default_factory=list)

    @property
    def download_dir(self):
        path = os.path.join(self.workdir, "files", "download")
        os.makedirs(path, exist_ok=True)
        return path

    @property
    def game_root(self):
        path = os.path.join(self.workdir, "game")
        os.makedirs(path, exist_ok=True)
        return path

    def note(self, text):
        self.log.append(text)

    def record_health(self, mirror, success, now):
        """UpdateManager.kt:1110-1115：只给**内置源**记健康度，清单补充源不进这张表。"""
        base = normalize_mirror(mirror)
        if base is None:
            return
        built_in = [b for b in (normalize_mirror(m) for m in self.built_in_mirrors) if b]
        if base not in built_in:
            return
        self.mirror_health = note_mirror_result(self.mirror_health, base, success, now)

    def candidates(self, declared=(), now=0):
        """UpdateManager.kt:175-180 mirrorCandidates。"""
        return mirror_order_with_health(
            built_in=self.built_in_mirrors, persisted=self.persisted_mirrors,
            declared=list(declared), health=self.mirror_health, now=now)

    def record_event(self, name, **fields):
        """遥测埋点的演练替面：事件名必须在 core 的白名单里（UpdateTelemetry.validate）。"""
        if name not in KS.telemetry_events:
            raise AssertionError(f"演练发出了 core 不认识的事件名 {name}（真机上会被 validate 拒收）")
        self.events.append((name, fields))
        return name

    def flush_telemetry(self):
        """UpdateTelemetrySink.kt:85-89：通道未配置 ⇒ 第一行返回，一次都不碰传输口。"""
        lines = [name for name, _f in self.events]
        if not is_https_endpoint(self.telemetry_endpoint) or not lines:
            return True
        self.telemetry_posts.append(emit_body_shape(lines))
        return True


def emit_body_shape(lines):
    """UpdateTelemetrySink.kt:70-77 encodeBody 的形状（演练只验 kind 标签与数组装配）。"""
    return '{"kind":"' + KS.telemetry_kind + '","events":[' + ",".join(lines) + "]}"


def host_of(mirror):
    """UpdateManager.kt:1196。"""
    return mirror.split("://", 1)[-1].split("/", 1)[0]


def read_part_state(path, now_millis):
    """UpdateManager.kt:1021-1026：不在盘上 = None；否则（字节数, 年龄）。"""
    if not os.path.isfile(path):
        return None
    age = now_millis - int(os.path.getmtime(path) * 1000)
    return (os.path.getsize(path), age)


def client_check_now(device, now_millis):
    """UpdateManager.checkNow（:380-495）的演练替身：逐条镜像取清单 → 验签 → 健康度 → 灰度/强档闸。

    返回 (info | None, problems, warnings, offer | None)；offer 是 UpdateOffer 的形状
    （apkNeeded / assetsNeeded / forceUpdate / installBlockedReason / dataArtifact）。
    """
    transport = device.transport
    candidates = device.candidates(now=now_millis)
    if not candidates:
        return None, ["没有可用的镜像地址"], [], None
    problems, unsigned_host, info = [], "", None
    for mirror in candidates:
        url = download_url(mirror, MANIFEST_FILE_NAME)
        text = None
        try:
            status, _headers, body = transport.get(url)
            if 200 <= status <= 299 and len(body) <= KS.max_manifest_bytes:
                text = body.decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001 —— 与 fetchManifest 同：任何失败都是「没拿到」
            text = None
        if text is None:
            problems.append(f"{host_of(mirror)}：连不上，没拿到新版信息")
            device.record_health(mirror, False, now_millis)
            continue
        verdict = verify_manifest(text)
        device.record_health(mirror, mirror_fetch_healthy(verdict), now_millis)
        if verdict == INVALID:
            problems.append(f"{host_of(mirror)}：更新信息的签名验证没通过（内容可能被换过），已放弃这条源")
            continue
        parsed, rejects = parse_manifest(text)
        if parsed is None:
            problems.append(f"{host_of(mirror)}：" + "；".join(rejects))
            continue
        info = parsed
        if verdict == UNSIGNED:
            unsigned_host = host_of(mirror)
        break
    if info is None:
        return None, problems, [], None
    warnings = list(info.warnings)
    if unsigned_host:
        warnings.append(f"镜像 {unsigned_host} 的清单未签名（旧格式），无法验证它有没有被换过")
    device.persisted_mirrors = list(info.mirrors)
    if not update_selected(info.rollout_percentage, device.rollout_bucket,
                           device.current_version_code, info.min_supported_version):
        device.note(f"本设备不在本次灰度范围（percentage={info.rollout_percentage}, "
                    f"bucket={device.rollout_bucket}），不提醒")
        return info, [], warnings, None
    force = is_force_update_required(device.current_version_code, info.min_supported_version)
    apk_needed = apk_update_needed(device.current_version_code, info)
    if apk_needed and not force and is_version_suppressed(
            info.apk.version_code if info.apk else -1, device.skipped_version_code):
        apk_needed = False
        device.note(f"版本 {device.skipped_version_code} 已被玩家跳过，本次不提醒")
    assets_needed = assets_update_needed(device.installed_data_version, info)
    disk_trusted = device.disk_digest_trusted and not (
        device.delta_failed_on_base == info.assets_base_version)
    # 选件走四参形（UpdateDownloadPlan.kt 的 selectDataArtifact 同一口）：下哪一件与
    # 算哪一档同源，块级那一件从 info.assets_block_delta 取，不重算回落次序。
    artifact = data_artifact_for_block(info, device.installed_data_version, disk_trusted)
    kind = data_artifact_kind(info, device.installed_data_version, disk_trusted)
    blocked = None
    if info.apk_cert_sha256 and cert_blocks_install(device.local_cert_sha256, info.apk_cert_sha256):
        blocked = "这台设备的签名章与更新包不同"
    offer = {
        "apkNeeded": apk_needed, "assetsNeeded": assets_needed, "forceUpdate": force,
        "installBlockedReason": blocked, "dataArtifact": artifact, "dataKind": kind,
        "currentVersionCode": device.current_version_code,
        "installedDataVersion": device.installed_data_version,
    }
    return info, problems, warnings, offer


def client_download(device, artifact, now_millis):
    """UpdateManager.download / downloadOnce（:580-775）的替身：逐镜像 + 退避 + `.part` 续传。

    返回 dict(file, problem, kind)；退避只记时长不真睡（演练不许花 53 秒等档位）。
    """
    candidates = device.candidates(now=now_millis)
    if not candidates:
        return {"file": None, "problem": "没有可用的镜像地址", "kind": None}
    expected = device.expected_apk_file
    if expected and artifact.file_name == expected and cert_blocks_install(
            device.local_cert_sha256, device.expected_apk_cert):
        return {"file": None, "kind": None,
                "problem": "这台设备的签名章与更新包不同，装不上（要先卸载，存档会没）——已阻止下载"}
    directory = device.download_dir
    part_path = os.path.join(directory, artifact.file_name + KS.part_suffix)
    part_state = read_part_state(part_path, now_millis)
    if not space_ok(device.usable_bytes, artifact, part_state):
        return {"file": None, "kind": LOCAL_FS,
                "problem": f"空间不够：还差 {remaining_bytes(artifact, part_state)} 字节"}
    last = "取不到文件"
    for mirror in candidates:
        url = download_url(mirror, artifact.file_name)
        attempt = 0
        while True:
            try:
                file_path = download_once(device, url, artifact, now_millis)
                device.record_health(mirror, True, now_millis)
                return {"file": file_path, "problem": "", "kind": None}
            except DownloadAbort as abort:
                if abort.discard_part and os.path.isfile(part_path):
                    os.remove(part_path)
                last = f"{host_of(mirror)}：{abort.message}"
                device.note(f"下载失败（{host_of(mirror)}）：{abort.kind} —— {abort.message}")
                if decide_failure(abort.kind) != "RetrySameMirror":
                    break
                wait = retry_backoff_millis(attempt)
                if wait is None:
                    break
                device.waits.append(wait)
                attempt += 1
        device.record_health(mirror, False, now_millis)
    return {"file": None, "problem": last, "kind": None}


def download_once(device, url, artifact, now_millis):
    """UpdateManager.downloadOnce（:656-775）：一次尝试，一切失败都编成 DownloadAbort。"""
    directory = device.download_dir
    part_path = os.path.join(directory, artifact.file_name + KS.part_suffix)
    target_path = os.path.join(directory, artifact.file_name)
    if os.path.isfile(target_path):
        if os.path.getsize(target_path) == artifact.size_bytes \
                and emit_update.sha256_of(target_path) == artifact.sha256_hex:
            return target_path
        os.remove(target_path)
    part_state = read_part_state(part_path, now_millis)
    if is_part_complete_for(artifact, part_state):
        actual = emit_update.sha256_of(part_path)
        if actual == artifact.sha256_hex:
            os.replace(part_path, target_path)
            return target_path
        os.remove(part_path)
        part_state = None
    offset = resume_offset_for(artifact, part_state)
    if part_state is not None and offset <= 0:
        os.remove(part_path)
    try:
        status, headers, body = device.transport.get(url, offset if offset > 0 else None)
    except Exception as exc:  # noqa: BLE001 —— 连接层失败：前缀完好，保留 .part
        raise DownloadAbort(NETWORK, f"连接失败：{exc}", discard_part=False) from exc
    device.last_response = (status, headers.get("Content-Range"))
    if not 200 <= status <= 299:
        kind = MISSING if status in (404, 403, 410) else HTTP_STATUS
        raise DownloadAbort(kind, f"HTTP {status}", discard_part=False)
    resume = resume_trusted(offset, status, content_range_start(headers.get("Content-Range")))
    if offset > 0 and not resume:
        device.note(f"服务端不认续传请求（HTTP {status}），从头重写整个文件："
                    f"{artifact.file_name}（作废 {offset} 字节前缀）")
    digest = hashlib.sha256()
    written = 0
    mode = "ab" if resume else "wb"
    if resume:
        with open(part_path, "rb") as prefix:
            remaining = offset
            while remaining > 0:
                piece = prefix.read(min(remaining, 1 << 20))
                if not piece:
                    break
                digest.update(piece)
                remaining -= len(piece)
        written = offset
    with open(part_path, mode) as out:
        step = 1 << 20
        for start in range(0, len(body), step):
            chunk = body[start:start + step]
            written += len(chunk)
            if written > artifact.size_bytes:
                raise DownloadAbort(OVERSIZE, "实收字节数超过清单声明", discard_part=True)
            out.write(chunk)
            digest.update(chunk)
    if written != artifact.size_bytes:
        raise DownloadAbort(NETWORK,
                            f"连接中断：已收 {written} / 声明 {artifact.size_bytes} 字节，断点已保留",
                            discard_part=False)
    actual = digest.hexdigest()
    if actual != artifact.sha256_hex:
        raise DownloadAbort(DIGEST,
                            f"摘要不符（源上的文件被换过或下载被截断）：声明 {artifact.sha256_hex[:16]}…，"
                            f"实收 {actual[:16]}…",
                            discard_part=True)
    os.replace(part_path, target_path)
    return target_path


def client_apply_staged(device, declared_sha256=None):
    """UpdateManager.applyStagedBundleIfPresent（:828-880）+ 解包器的落盘/删除判据。

    返回 (kind, detail)：kind ∈ nothing / rejected / failed / applied。
    """
    if not device.staged_assets_path or not os.path.isfile(device.staged_assets_path):
        return "nothing", "没有暂存数据包"
    staged_path, staged_version = device.staged_assets_path, device.staged_assets_version
    if not data_update_needed(staged_version, device.installed_data_version):
        os.remove(staged_path)
        device.staged_assets_path = ""
        return "nothing", f"暂存版本 {staged_version} 不高于盘上 {device.installed_data_version}，丢弃"
    with zipfile.ZipFile(staged_path) as zf:
        names = [info.filename for info in zf.infolist() if not info.is_dir()]
        payloads = {name: zf.read(name) for name in names}
    # 到这里归档句柄已关：Windows 上「开着 zip 删文件」直接 PermissionError，而设备侧
    # ZipAssetTree 是长驻句柄、落盘由解包器做（两件事本就分在两个类里）。
    rejected = sum(1 for name in names if safe_entry_name(name) is None)
    is_delta = KS.delta_list_name in payloads
    deletions = []
    if is_delta:
        raw = payloads[KS.delta_list_name].decode("utf-8", errors="replace")
        for line in raw.splitlines():
            text = line[:-1] if line.endswith("\r") else line
            if not text.strip():
                continue
            normalized = safe_entry_name(text)
            if normalized is None:
                rejected += 1
            else:
                deletions.append(normalized)
    if rejected > 0:
        os.remove(staged_path)
        device.staged_assets_path = ""
        return "rejected", f"数据包里有 {rejected} 条非法路径，已拒绝应用并丢弃该包"
    program = f"{KS.artifact_dir}/{KS.program_file}"
    meta = f"{KS.artifact_dir}/{KS.meta_file}"
    has_program, has_meta = program in payloads, meta in payloads
    if is_delta and (has_program != has_meta):
        return "failed", "差量包只带了 program 或只带了侧车：配对不齐，整轮回滚"
    written, skipped = [], []
    backups = {}
    for name in names:
        if name == KS.delta_list_name:
            continue
        head = name.split("/")[0]
        if head != KS.artifact_dir and head not in KS.data_dirs:
            skipped.append(name)
            continue
        target = os.path.join(device.game_root, *name.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.isfile(target):
            backups[target] = target + ".bak"
            shutil.copyfile(target, backups[target])
        with open(target, "wb") as out:
            out.write(payloads[name])
        written.append(name)
    if has_program and has_meta:
        declared = declared_program_sha(payloads[meta])
        actual = hashlib.sha256(payloads[program]).hexdigest()
        if declared is not None and declared != actual:
            for original, backup in backups.items():
                shutil.move(backup, original)
            for name in written:
                path = os.path.join(device.game_root, *name.split("/"))
                if os.path.isfile(path) and path not in backups:
                    os.remove(path)
            return "failed", (f"program.erbc 与 program.meta 不配对：侧车声明 {declared}，"
                              f"实际 {actual}")
    for backup in backups.values():
        if os.path.isfile(backup):
            os.remove(backup)
    deleted, blocked = [], 0
    package_names = set(names)
    for rel in deletions:
        if deletion_is_protected(rel) or not deletion_within_asset_universe(rel):
            blocked += 1
            continue
        if rel in package_names:
            continue
        if rel in (program, meta):
            blocked += 1
            continue
        target = os.path.join(device.game_root, *rel.split("/"))
        if not os.path.isfile(target):
            continue
        os.remove(target)
        deleted.append(rel)
    marker = os.path.join(device.game_root, KS.update_dir, "data-version.txt")
    os.makedirs(os.path.dirname(marker), exist_ok=True)
    tmp = marker + KS.part_suffix
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(str(staged_version))
    os.replace(tmp, marker)
    device.installed_data_version = staged_version
    device.staged_assets_path = ""
    return "applied", f"落盘 {len(written)} 条、跳过 {len(skipped)} 条、删除 {len(deleted)} 条、受阻 {blocked} 条"


def declared_program_sha(meta_bytes):
    """GameAssetInstaller.kt:1266-1275 —— 侧车第 3 行的 program-sha256；`-`/缺行 = None。"""
    head = meta_bytes[:4096].decode("utf-8", errors="replace")
    for raw in head.splitlines():
        line = raw.rstrip("\r")
        if not line.startswith("program-sha256 "):
            continue
        value = line[len("program-sha256 "):].strip()
        if not value or value == "-":
            return None
        return value.strip('"').strip().lower() or None
    return None


# =============================================================================
# 4. 场景构造：发布侧走 emit_update 的真函数，设备侧用临时目录
# =============================================================================


def synth_game_root(path, program_bytes=None, meta_declared=None, csv_line="ID,NAME\n1,演练\n"):
    """合成一个「小语料根」（不碰真实语料）：erbc.v1 三件 + 四个随包目录各一条。"""
    os.makedirs(path, exist_ok=True)
    artifact = os.path.join(path, KS.artifact_dir)
    os.makedirs(artifact, exist_ok=True)
    program = program_bytes if program_bytes is not None else b"ERATW-DRILL-PROGRAM-v1"
    with open(os.path.join(artifact, KS.program_file), "wb") as fh:
        fh.write(program)
    declared = meta_declared if meta_declared is not None else hashlib.sha256(program).hexdigest()
    with open(os.path.join(artifact, KS.meta_file), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("boot 1\nformat 1\nprogram-sha256 \"%s\"\n" % declared)
    for rel_dir, name, payload in (
            ("CSV", "DRILL.CSV", csv_line),
            ("resources", "立绘-演练.png", "PNG-演练-0001"),
            ("sound", "bgm.txt", "BGM"),
            ("plugins", "note.txt", "PLUGIN")):
        directory = os.path.join(path, rel_dir)
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(payload)
    return path


def build_dist(out_dir, *, sign_key=None, version_code="17", version_name="0.2.40",
               rollout=None, min_supported="", mirrors=(MIRROR_A, MIRROR_B),
               assets_version="17", delta_base="", with_assets=True, with_delta=False,
               note="演练用合成包", program_bytes=None, meta_declared=None,
               csv_line="ID,NAME\n1,演练\n", cert_sha256=None):
    """用**发布侧真函数**生成一份可体检的 dist：stage_assets_zip → write_manifest → 版本账。

    返回 dict：dist 目录、清单文本、各产物名与 sha、合成语料根（供设备侧对照）。
    """
    os.makedirs(out_dir, exist_ok=True)
    corpus = synth_game_root(os.path.join(out_dir, "_corpus"), program_bytes=program_bytes,
                             meta_declared=meta_declared, csv_line=csv_line)
    apk_name = f"eratw-{version_name}+{version_code}-drillfull.apk"
    apk_bytes = b"ERATW-DRILL-APK/" + version_code.encode("ascii") + b"/" + os.urandom(0)
    apk_path = os.path.join(out_dir, apk_name)
    with open(apk_path, "wb") as fh:
        fh.write(apk_bytes)
    assets_full = None
    assets_delta = None
    index = {}
    if with_assets:
        zip_path, index = emit_update.stage_assets_zip(
            out_dir, corpus, "", assets_version, stored=False, include_bad=False)
        assets_full = (os.path.basename(zip_path), os.path.getsize(zip_path),
                       emit_update.sha256_of(zip_path), assets_version)
        if with_delta and delta_base:
            old_index = {"version": delta_base, "entries": _index_shift(index)}
            changed, deleted = emit_update.compute_delta(index, old_index)
            delta_path = emit_update.stage_delta_zip(
                out_dir, zip_path, changed or [], deleted or [], delta_base, assets_version)
            if delta_path:
                assets_delta = (os.path.basename(delta_path), os.path.getsize(delta_path),
                                emit_update.sha256_of(delta_path))
        emit_update.save_index(out_dir, assets_version, index)
    generated = "2026-11-05T00:00:00Z"
    if cert_sha256 is None:
        cert_sha256 = "c" * 64
    manifest = emit_update.write_manifest(
        out_dir, apk_name, os.path.getsize(apk_path), emit_update.sha256_of(apk_path),
        version_name, version_code, assets_full, assets_delta, delta_base, list(mirrors),
        note, generated, cert_sha256, min_supported_version=min_supported,
        rollout_percentage=rollout, sign_key=sign_key)
    ledger = os.path.join(out_dir, f"{emit_update.MANIFEST_HISTORY_PREFIX}{version_code}.txt")
    shutil.copyfile(manifest, ledger)
    with open(manifest, "r", encoding="utf-8") as fh:
        text = fh.read()
    return {"dir": out_dir, "manifest_path": manifest, "manifest": text, "corpus": corpus,
            "apk_name": apk_name, "apk_bytes": apk_bytes, "assets_full": assets_full,
            "assets_delta": assets_delta, "assets_base_version": delta_base,
            "assets_version": assets_version if with_assets else "",
            "version_code": version_code, "index": index, "ledger": ledger}


def _index_shift(index):
    """把当前索引改成「上一版的样子」：改一条内容、删一条、加一条只在盘上存在的空位。"""
    shifted = json.loads(json.dumps(index))
    for key in sorted(shifted):
        if key.startswith("CSV/"):
            shifted[key] = {"size": 1, "sha256": "0" * 64}
            continue
        if key.startswith("plugins/"):
            del shifted[key]
    shifted["resources/已下架.png"] = {"size": 3, "sha256": hashlib.sha256(b"old").hexdigest()}
    return shifted


# ---------------------------------------------------------------------------
# 4b. 块级差量（P2-13，U8）：发布侧全走真生产函数，设备侧判据逐条直译
# ---------------------------------------------------------------------------


def _block_noise(size, seed=b"eratw-drill-blockdelta"):
    """确定性「不可压」字节流（sha256 链）：块级补丁要有赢面，文件得大且压不动。"""
    out = bytearray()
    counter = 0
    while len(out) < size:
        out += hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
        counter += 1
    return bytes(out[:size])


def root_to_files(root):
    """目录树 → {相对路径: 字节}（供 seed_game_root 与盘上逐字节对照）。"""
    out = {}
    for dirpath, _dirs, filenames in os.walk(root):
        for fname in filenames:
            full = os.path.join(dirpath, fname)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as fh:
                out[rel] = fh.read()
    return out


def _rewrite_zip(src_path, dst_path, mutator):
    """逐条目复制 zip；mutator(条目名, 字节) → 字节 做逐条目改写（篡改拍用）。"""
    os.makedirs(os.path.dirname(dst_path), exist_ok=True)
    with zipfile.ZipFile(src_path) as src, \
            zipfile.ZipFile(dst_path, "w", compression=zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            dst.writestr(info.filename, mutator(info.filename, src.read(info)))


def build_block_dist(out_dir, *, sign_key=None, base_version="20", new_version="21",
                     version_code="22", version_name="0.2.44",
                     mirrors=(MIRROR_A,), cert_sha256=None):
    """块级差量专用 dist：v20 基线包（真字节）→ 改语料出 v21 全量包 → 两档差量一起发。

    只发单一镜像：BD6 要走「唯一源摘要不符 ⇒ 拒收」这条终态，双源会先跳到没 serve 的
    B 上把终态换成连接失败。发布侧全走 emit_update 的真生产函数：stage_assets_zip × 2 →
    build_assets_index 对账 → compute_delta → stage_block_delta_zip（块级）+
    stage_delta_zip（文件级同源）→ write_manifest（两档三件套共用同一个 assets-base-version）
    → 版本账。
    返回 dict：目录、清单文本、基线/新全量/文件级/块级包路径、新旧语料根、块级生成统计。
    """
    os.makedirs(out_dir, exist_ok=True)
    corpus_old = synth_game_root(os.path.join(out_dir, "_corpus-v20"), csv_line="ID,NAME\n1,基线版\n")
    # 立绘换成 16 KiB 不可压字节：改一个字节 ⇒ 块级补丁有赢面；CSV 整文件重写 ⇒ 落整条。
    with open(os.path.join(corpus_old, "resources", "立绘-演练.png"), "wb") as fh:
        fh.write(_block_noise(16 * 1024))
    corpus_new = os.path.join(out_dir, "_corpus-v21")
    shutil.copytree(corpus_old, corpus_new)
    with open(os.path.join(corpus_new, "CSV", "DRILL.CSV"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("ID,NAME\n2,新版语料\n")
    png = bytearray(_block_noise(16 * 1024))
    png[2000] ^= 0x20
    with open(os.path.join(corpus_new, "resources", "立绘-演练.png"), "wb") as fh:
        fh.write(bytes(png))
    os.remove(os.path.join(corpus_new, "plugins", "note.txt"))
    apk_name = f"eratw-{version_name}+{version_code}-drillfull.apk"
    apk_path = os.path.join(out_dir, apk_name)
    with open(apk_path, "wb") as fh:
        fh.write(b"ERATW-DRILL-APK/" + version_code.encode("ascii"))
    base_zip, index_old = emit_update.stage_assets_zip(
        out_dir, corpus_old, "", base_version, stored=False, include_bad=False)
    full_zip, index_new = emit_update.stage_assets_zip(
        out_dir, corpus_new, "", new_version, stored=False, include_bad=False)
    changed, deleted = emit_update.compute_delta(
        index_new, {"version": base_version, "entries": index_old})
    delta_path = emit_update.stage_delta_zip(
        out_dir, full_zip, changed or [], deleted or [], base_version, new_version)
    block_zip, block_stats = emit_update.stage_block_delta_zip(
        out_dir, full_zip, base_zip, changed or [], deleted or [], new_version)
    emit_update.save_index(out_dir, new_version, index_new)
    if cert_sha256 is None:
        cert_sha256 = "c" * 64
    manifest = emit_update.write_manifest(
        out_dir, apk_name, os.path.getsize(apk_path), emit_update.sha256_of(apk_path),
        version_name, version_code,
        (os.path.basename(full_zip), os.path.getsize(full_zip),
         emit_update.sha256_of(full_zip), new_version),
        (os.path.basename(delta_path), os.path.getsize(delta_path),
         emit_update.sha256_of(delta_path)) if delta_path else None,
        base_version, list(mirrors), "演练用合成包（块级差量）", "2026-11-05T00:00:00Z",
        cert_sha256, min_supported_version="15",
        assets_block_delta=(os.path.basename(block_zip), os.path.getsize(block_zip),
                            emit_update.sha256_of(block_zip)) if block_zip else None,
        sign_key=sign_key)
    ledger = os.path.join(out_dir, f"{emit_update.MANIFEST_HISTORY_PREFIX}{version_code}.txt")
    shutil.copyfile(manifest, ledger)
    with open(manifest, "r", encoding="utf-8") as fh:
        text = fh.read()
    return {"dir": out_dir, "manifest_path": manifest, "manifest": text,
            "corpus_old": corpus_old, "corpus_new": corpus_new,
            "base_path": base_zip, "full_path": full_zip,
            "delta_path": delta_path or "", "block_path": block_zip or "",
            "block_stats": block_stats or {}, "apk_name": apk_name,
            "assets_version": new_version, "base_version": base_version,
            "version_code": version_code, "ledger": ledger}


def staged_apply_kind(zip_path):
    """UpdateDownloadPlan.kt 的 stagedKindOf 直译：落地那一拍按**包内实况**选档，不读清单。"""
    with zipfile.ZipFile(zip_path) as zf:
        names = [info.filename for info in zf.infolist() if not info.is_dir()]
    if any(name.startswith(KS.bd_patch_prefix) for name in names):
        return KS.data_kind_names[0]
    if KS.delta_list_name in names:
        return KS.data_kind_names[1]
    return KS.data_kind_names[2]


def _count_block_ops(body):
    """op 流条数（与设备侧 PatchStream 的 opsRead 同口径：读到流末尾；截断按已读数计）。"""
    ops = 0
    pos = 0
    end = len(body)
    while pos < end:
        if end - pos < 5:
            return ops  # 设备侧会在 readInt32 处抛「op 头被截断」；这里只报条数不符
        tag = body[pos:pos + 1]
        num = int.from_bytes(body[pos + 1:pos + 5], "big")
        pos += 5
        ops += 1
        if tag == KS.bd_op_run.encode("ascii"):
            pos += num
    return ops


def apply_block_delta_package(device, declared_sha256=None):
    """块级差量包的设备侧替身：路由（UpdateDownloadPlan.kt）+ BlockDeltaAssetTree 三道闸直译。

    判据出处（platform 层 BlockDeltaAssetTree.kt）：
    - 形状闸（:104-134 + readPatchHeader:209-251）：safe 名 / 与删除表条目撞名 / 资产范围 /
      重复目标 / 头部形状，违者计拒绝数 ⇒ 整包拒装；头部解析与发布侧同一套判据，直接调
      emit_update.split_block_patch（其 KDoc：与设备侧同一套键名、同一个「空行结束」判据）。
    - 基线闸（:297-314）：盘上旧文件的 sha256 必须等于 base-sha256，错 ⇒ failed（字节未落）。
    - 还原闸（:441-453）：按 op 流拼装（emit_update.reassemble_block_patch —— KDoc：与设备侧
      同一条读法），字节数 / 摘要 / op 条数必须等于声明。
    落盘 / 删除表 / 版本标记与 client_apply_staged 同一套判据（GameAssetInstaller 不分档）。
    返回 (kind, detail)：kind ∈ nothing / rejected / failed / applied。
    """
    if not device.staged_assets_path or not os.path.isfile(device.staged_assets_path):
        return "nothing", "没有暂存数据包"
    staged_path, staged_version = device.staged_assets_path, device.staged_assets_version
    if not data_update_needed(staged_version, device.installed_data_version):
        os.remove(staged_path)
        device.staged_assets_path = ""
        return "nothing", f"暂存版本 {staged_version} 不高于盘上 {device.installed_data_version}，丢弃"
    with zipfile.ZipFile(staged_path) as zf:
        names = [info.filename for info in zf.infolist() if not info.is_dir()]
        payloads = {name: zf.read(name) for name in names}
    # —— 索引阶段（与 client_apply_staged 同：归档句柄先关再动盘）
    rejected = sum(1 for name in names if safe_entry_name(name) is None)  # 内层 zip-slip
    deletions = []
    raw = payloads.get(KS.delta_list_name)
    if raw is not None:
        for line in raw.decode("utf-8", errors="replace").splitlines():
            text = line[:-1] if line.endswith("\r") else line
            if not text.strip():
                continue
            normalized = safe_entry_name(text)
            if normalized is None:
                rejected += 1
            else:
                deletions.append(normalized)
    patches = {}
    for entry_name in (n for n in names if n.startswith(KS.bd_patch_prefix)):
        rel = entry_name[len(KS.bd_patch_prefix):]
        safe = safe_entry_name(rel)
        if safe is None or safe == KS.delta_list_name or not deletion_within_asset_universe(safe):
            rejected += 1  # BlockDeltaAssetTree.kt:108-113
            continue
        if safe in patches:
            rejected += 1  # BlockDeltaAssetTree.kt:114-117：同一目标两条补丁 ⇒ 整包拒装
            continue
        fields, body = emit_update.split_block_patch(payloads[entry_name])
        if fields is None or not 1 <= int(fields["block"]) <= KS.bd_patch_max_block:
            rejected += 1  # BlockDeltaAssetTree.kt:118-121 + readPatchHeader 的块号界
            continue
        patches[safe] = (entry_name, fields, body)
    if rejected > 0:
        os.remove(staged_path)
        device.staged_assets_path = ""
        return "rejected", (f"数据包里有 {rejected} 条不合格条目（非法路径或补丁形状），"
                            "已拒绝应用并丢弃该包")
    # —— 基线闸 + 还原闸：先全部拼出来，再动盘（与设备侧「前两闸在落盘之前判死」同结果）
    reassembled = {}
    for rel, (entry_name, fields, body) in patches.items():
        target = os.path.join(device.game_root, *rel.split("/"))
        if not os.path.isfile(target):
            return "failed", f"块级差量要引用的旧文件不在盘上：{rel}（基线缺文件，回落全量）"
        with open(target, "rb") as fh:
            old = fh.read()
        if hashlib.sha256(old).hexdigest() != fields["base-sha256"]:
            return "failed", (f"块级差量的基线与盘上内容不符：{rel}（盘上那份不是补丁假设的那一版，"
                              "已拒绝应用）")
        got, why = emit_update.reassemble_block_patch(old, body, int(fields["block"]))
        if got is None:
            return "failed", f"块级补丁重放失败：{entry_name}（{why}）"
        if len(got) != int(fields["result-size"]) \
                or hashlib.sha256(got).hexdigest() != fields["result-sha256"]:
            return "failed", f"块级差量拼出的内容与声明不符：{rel}（已拒绝应用，回落全量）"
        if _count_block_ops(body) != int(fields["op-count"]):
            return "failed", f"块级差量的 op 条数与声明不符：{entry_name}"
        reassembled[rel] = got
    # —— 落盘：整条 + 拼出来的补丁目标，删除表与版本标记与文件级同一套
    written, skipped = [], []
    backups = {}
    for name in names:
        if name == KS.delta_list_name or name.startswith(KS.bd_patch_prefix):
            continue
        head = name.split("/")[0]
        if head != KS.artifact_dir and head not in KS.data_dirs:
            skipped.append(name)
            continue
        target = os.path.join(device.game_root, *name.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.isfile(target):
            backups[target] = target + ".bak"
            shutil.copyfile(target, backups[target])
        with open(target, "wb") as out:
            out.write(payloads[name])
        written.append(name)
    for rel, data in reassembled.items():
        target = os.path.join(device.game_root, *rel.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.isfile(target):
            backups.setdefault(target, target + ".bak")
            shutil.copyfile(target, backups[target])
        with open(target, "wb") as out:
            out.write(data)
        written.append(rel)
    for backup in backups.values():
        if os.path.isfile(backup):
            os.remove(backup)
    deleted, blocked = [], 0
    package_names = set(names)
    program = f"{KS.artifact_dir}/{KS.program_file}"
    meta = f"{KS.artifact_dir}/{KS.meta_file}"
    for rel in deletions:
        if deletion_is_protected(rel) or not deletion_within_asset_universe(rel):
            blocked += 1
            continue
        if rel in package_names:
            continue
        if rel in (program, meta):
            blocked += 1
            continue
        target = os.path.join(device.game_root, *rel.split("/"))
        if not os.path.isfile(target):
            continue
        os.remove(target)
        deleted.append(rel)
    marker = os.path.join(device.game_root, KS.update_dir, "data-version.txt")
    os.makedirs(os.path.dirname(marker), exist_ok=True)
    tmp = marker + KS.part_suffix
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(str(staged_version))
    os.replace(tmp, marker)
    device.installed_data_version = staged_version
    device.staged_assets_path = ""
    return "applied", (f"块级落盘 {len(written)} 条（补丁 {len(reassembled)} + "
                       f"整条 {len(written) - len(reassembled)}）、跳过 {len(skipped)} 条、"
                       f"删除 {len(deleted)} 条、受阻 {blocked} 条")


# =============================================================================
# 5. 拍：造场景 → 跑 → 断言实际 == 期望
# =============================================================================


class Drill:
    """逐拍执行器：打印每一拍的期望与实际，首差落到具体位置。"""

    def __init__(self, workdir, sign_key_path=None, serve_http=True, existing_dist=None):
        self.workdir = workdir
        self.sign_key_path = sign_key_path or None
        self.sign_key = None
        if self.sign_key_path:
            if not os.path.isfile(self.sign_key_path):
                die(f"--sign-key 指向的私钥不存在：{self.sign_key_path}")
            self.sign_key = emit_update.load_pkcs8_key(self.sign_key_path)
        self.existing_dist = existing_dist
        self.rows = []
        self.beat_no = 0
        self.beat_title = ""
        self.checks = 0
        self.failures = []
        self.skips = []
        self.servers = []
        self.serve_http = serve_http
        self.transport_kind = "http" if serve_http else "inproc"
        self.read_only = bool(existing_dist)
        # 「现在」用真实时刻：判据里所有年龄都是相对量（时钟回拨那几档另造场景）。
        self.now = int(time.time() * 1000)
        self.golden_body = KS.golden_body
        self.golden_signature = KS.golden_signature
        self.builtin_key_id, (self.builtin_n, self.builtin_e) = KS.builtin_keys[0]
        self.signed_dist = None
        self.unsigned_dist = None
        self.sub_no = 0

    # -- 场景目录 ----------------------------------------------------------

    def sub(self, tag):
        self.sub_no += 1
        path = os.path.join(self.workdir, f"{self.sub_no:02d}-{tag}")
        os.makedirs(path, exist_ok=True)
        return path

    def emit_cli(self, args):
        """在**子进程**里跑发布侧真入口（「拒发未签名」这类闸门只能在 CLI 上证）。"""
        cmd = [sys.executable, os.path.join(HERE, "emit_update.py")] + list(args)
        proc = subprocess.run(cmd, cwd=ANDROID_ROOT, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=900)
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

    # -- 断言 --------------------------------------------------------------

    def beat(self, name):
        self.beat_no += 1
        self.beat_title = name
        print(f"[拍 {self.beat_no}] {name}")

    def expect(self, label, expected, actual):
        ok = expected == actual
        self.checks += 1
        line = f"      {label:<40} 期望={_short(expected)} 实际={_short(actual)} " + \
               ("ok" if ok else "**FAIL**")
        self.rows.append(f"拍 {self.beat_no}｜{line.strip()}")
        print(line)
        if not ok:
            self.failures.append(f"拍 {self.beat_no}｜{self.beat_title}｜{label} —— "
                                 + first_diff(expected, actual))
        return ok

    def skip(self, label, why):
        self.skips.append(f"拍 {self.beat_no}｜{self.beat_title}｜{label}：{why}")
        print(f"      {label:<40} SKIP —— {why}")

    def section(self, title):
        print("")
        print(f"===== {title} " + "=" * max(4, 70 - len(title)))

    # -- 镜像 --------------------------------------------------------------

    def serve(self, dist_dir, bases=(MIRROR_A, MIRROR_B, MIRROR_C), faults=None):
        """把发布目录摆成 N 条镜像（各自独立 origin），返回设备替身可用的传输口。"""
        mirrors = {}
        for base in bases:
            mirror = MockMirror(dist_dir, normalize_mirror(base), host_of(base))
            if faults and base in faults:
                mirror.set_fault(*faults[base])
            mirrors[normalize_mirror(base)] = mirror
        if self.serve_http:
            try:
                mapping, servers = serve(list(mirrors.values()))
                self.servers.extend(servers)
                self.transport_kind = "http"
                return HttpTransport(mapping, mirrors), mirrors
            except OSError as exc:
                log(f"警告：回环端口绑不上（{exc}），传输降级为进程内直调 mock —— "
                    "判据完全同一份，只是不过 socket")
        self.transport_kind = "inproc"
        return InProcTransport(mirrors), mirrors

    def stop(self):
        stop(self.servers)
        self.servers = []

    # -- 设备 --------------------------------------------------------------

    def device(self, tag, built_in=(MIRROR_A, MIRROR_B), **fields):
        work = self.sub("device-" + tag)
        transport = fields.pop("transport", None)
        return Device(workdir=work, built_in_mirrors=[normalize_mirror(m) for m in built_in],
                      transport=transport, **fields)

    def run(self, name, fn):
        self.beat(name)
        try:
            fn()
        except AssertionError as exc:
            self.checks += 1
            self.failures.append(f"拍 {self.beat_no}｜{name}｜断言 —— {exc}")
            print(f"      断言失败：{exc}")
        except Exception as exc:  # noqa: BLE001 —— 演练要把炸掉的那一拍报成红，而不是整跑中断
            self.checks += 1
            self.failures.append(f"拍 {self.beat_no}｜{name}｜异常 {type(exc).__name__}: {exc}")
            print(f"      异常：{type(exc).__name__}: {exc}")

    # -- 现成 dist（三态与下载拍共用）-------------------------------------

    def prepare_dists(self):
        """造两份发布目录：签名的（有私钥时）与 --unsigned-explicit 形状的（未签名）。"""
        if self.existing_dist:
            with open(os.path.join(self.existing_dist, MANIFEST_FILE_NAME), "r",
                      encoding="utf-8") as fh:
                text = fh.read()
            bundle = {"dir": self.existing_dist, "manifest": text,
                      "manifest_path": os.path.join(self.existing_dist, MANIFEST_FILE_NAME),
                      "apk_name": None, "corpus": None, "assets_full": None,
                      "assets_delta": None, "assets_base_version": "", "assets_version": "",
                      "version_code": "", "index": {}, "ledger": None}
            info, _problems = parse_manifest(text)
            if info and info.apk:
                bundle["apk_name"] = info.apk.artifact.file_name
            return bundle, None
        common = dict(min_supported="15", with_delta=True, delta_base="20",
                      version_code="21", version_name="0.2.43", assets_version="21")
        unsigned = build_dist(self.sub("dist-unsigned"), **common)
        if self.sign_key is None:
            return unsigned, unsigned
        return build_dist(self.sub("dist-signed"), sign_key=self.sign_key, **common), unsigned


def _short(value, limit=52):
    text = repr(value) if not isinstance(value, str) else value
    text = text.replace("\n", "\\n")
    return text if len(text) <= limit else text[:limit - 3] + "…"


def first_diff(expected, actual):
    """首差：文本给字符偏移与上下文，序列给第一个不相等的下标，其余给两边读数。"""
    if isinstance(expected, str) and isinstance(actual, str):
        for index in range(min(len(expected), len(actual))):
            if expected[index] != actual[index]:
                start = max(0, index - 20)
                return (f"首差在第 {index} 个字符：期望 …{expected[start:index + 20]!r}…，"
                        f"实际 …{actual[start:index + 20]!r}…")
        if len(expected) != len(actual):
            return f"前缀相同、长度不同：期望 {len(expected)} 字符，实际 {len(actual)} 字符"
        return f"期望 {expected!r}，实际 {actual!r}"
    if isinstance(expected, (list, tuple)) and isinstance(actual, (list, tuple)):
        for index, (want, got) in enumerate(zip(expected, actual)):
            if want != got:
                return f"首个不相等的元素在下标 {index}：期望 {_short(want)}，实际 {_short(got)}"
        return f"长度不同：期望 {len(expected)} 项，实际 {len(actual)} 项"
    return f"期望 {_short(expected)}，实际 {_short(actual)}"


# =============================================================================
# 6. 逐拍：造场景 → 跑 → 断言实际 == 期望
# =============================================================================


def beats_publish_side(d):
    d.section("发布侧（emit_update 的产物与闸门）")

    def p1():
        if d.sign_key is None:
            d.skip("签名 dist 的发布侧体检零问题",
                   "本机没有 k1 私钥（--sign-key 未给且共享根里没有），改由黄金向量拍覆盖验签链")
            return
        problems = emit_update.verify(d.signed_dist["dir"], sign_key=d.sign_key)
        d.expect("体检问题数", 0, len(problems))
        text = d.signed_dist["manifest"]
        last = text.rstrip("\n").split("\n")[-1]
        d.expect("签名行恒为最后一行", True, last.startswith(f"{KS.signature_key} "))
        d.expect("sig-alg 在被签字节之内", True, f"{emit_update.SIG_ALG_KEY} rsa-sha256" in text)
        d.expect("sig-key-id 与设备侧内置键 id 同值",
                 KS.key_id_k1, emit_update.DEFAULT_SIG_KEY_ID)

    def p2():
        source = "真私钥现签的 dist" if d.sign_key else "ManifestSignatureTest 的 k1 黄金签名对"
        text = d.signed_dist["manifest"] if d.sign_key else (
            d.golden_body + f"{KS.signature_key} {d.golden_signature}\n")
        d.expect(f"{source} ⇒ VERIFIED", VERIFIED, verify_manifest(text))

    def p3():
        if not d.sign_key_path:
            d.skip("私钥与包内公钥是同一把", "本机没有 k1 私钥")
            return
        private = emit_update.load_pkcs8_key(d.sign_key_path)
        d.expect("模数 n 逐位一致", d.builtin_n, private[0])
        d.expect("公钥指数 e 一致", d.builtin_e, private[1])
        d.expect("RSA 模长", 2048, private[0].bit_length())

    def p4():
        # L2 行为契约变更（组长 2026-11-06 授权）：`--verify-only` 前移到未签名闸之前 ——
        # 它只读不写（不生成/不上传/不碰桶），体检一份现成目录不该被「手上有没有签名私钥」拦住。
        # 旧断言（exit 1 + 「未签名清单被拦下」）编码的正是被修掉的那个 bug 本体。
        code, out = d.emit_cli(["--verify-only", d.unsigned_dist["dir"]])
        d.expect("verify-only 不再被未签名闸前置拦死（退出码）", 0, code)
        d.expect("未签名清单在体检里仍大声提示", True, "清单未签名" in out)

    def p5():
        code, out = d.emit_cli(["--verify-only", d.unsigned_dist["dir"], "--unsigned-explicit"])
        d.expect("显式认领后放行（退出码）", 0, code)
        d.expect("认领仍大声警告", True, "已按 --unsigned-explicit 认领" in out)

    def p6():
        code, out = d.emit_cli(["--rollout-percentage", "101", "--skip-assets"])
        d.expect("灰度越界在 CLI 就被挡（退出码）", 1, code)
        d.expect("文案点名 rollout-percentage", True, "rollout-percentage" in out)

    def p7():
        code, _out = d.emit_cli(["--min-supported-version", "1.5", "--skip-assets"])
        d.expect("强制档非纯数字在 CLI 就被挡（退出码）", 1, code)

    def p8():
        tampered = d.sub("dist-tampered")
        shutil.copytree(d.signed_dist["dir"], tampered, dirs_exist_ok=True)
        name = d.signed_dist["apk_name"]
        apk = os.path.join(tampered, name) if name else None
        if apk is None or not os.path.isfile(apk):
            d.skip("产物被改一字节 ⇒ 体检报摘要不符", "现成 dist 里找不到清单点名的整包")
            return
        with open(apk, "r+b") as fh:
            fh.seek(3)
            first = fh.read(1)
            fh.seek(3)
            fh.write(bytes([first[0] ^ 0x01]))
        problems = emit_update.verify(tampered)
        d.expect("整包摘要不符被点名", True, any("整包摘要不符" in p for p in problems))

    for title, fn in (("P1 签名 dist：发布侧写清单 + --verify 体检零问题", p1),
                      ("P2 签名链对上：发布侧签的字节 = 设备侧内置公钥验得过", p2),
                      ("P3 私钥身份：k1 私钥的模数就是包内内置公钥那把", p3),
                      ("P4 发布侧拒绝发未签名清单（没给 --unsigned-explicit）", p4),
                      ("P5 --unsigned-explicit 认领后放行 + 警告", p5),
                      ("P6 --rollout-percentage 坏值被 CLI 挡", p6),
                      ("P7 --min-supported-version 坏值被 CLI 挡", p7),
                      ("P8 产物被改一字节 ⇒ --verify-only 报摘要不符", p8)):
        d.run(title, fn)


def beats_signature(d):
    d.section("验签三态（含「篡改降位」「未签名放行」）")
    signed_text = d.signed_dist["manifest"] if d.sign_key else (
        d.golden_body + f"{KS.signature_key} {d.golden_signature}\n")

    def s1():
        d.expect("已签名 ⇒ VERIFIED", VERIFIED, verify_manifest(signed_text))
        d.expect("验签过的源算活着", True, mirror_fetch_healthy(VERIFIED))

    def s2():
        mutated = signed_text.replace("apk-version-code", "apk-version-codf", 1)
        d.expect("改一个字母 ⇒ INVALID", INVALID, verify_manifest(mutated))
        d.expect("验签判死的源算失联（这条源要降位）", False, mirror_fetch_healthy(INVALID))

    def s3():
        body = signed_text.rsplit(f"{KS.signature_key}", 1)[0]
        # 签名行原样留着，只动声明面（算法名 / 键 id）—— 动的若是字节面就落到 S2 那一拍了。
        signature_line = f"{KS.signature_key} " + signed_text.rsplit(
            f"{KS.signature_key} ", 1)[-1].strip() + "\n"
        d.expect("坏 Base64 ⇒ INVALID", INVALID,
                 verify_manifest(body + f"{KS.signature_key} !!!not-base64!!!\n"))
        d.expect("四字节短签名 ⇒ INVALID", INVALID,
                 verify_manifest(body + f"{KS.signature_key} AAAA\n"))
        d.expect("不认识的算法 ⇒ INVALID", INVALID,
                 verify_manifest(body.replace("sig-alg rsa-sha256", "sig-alg ed25519")
                                 + signature_line))
        d.expect("包里没有的键 id ⇒ INVALID（绝不回落成未签名放行）", INVALID,
                 verify_manifest(body.replace("sig-key-id k1", "sig-key-id next") + signature_line))
        d.expect("删掉签名行 ⇒ 回到未签名口径（迁移期放行，不是判死）", UNSIGNED,
                 verify_manifest(body))

    def s4():
        legacy = "update-format 1\napk-version-code 17\napk-file eratw.apk\n"
        d.expect("没有签名行 ⇒ UNSIGNED", UNSIGNED, verify_manifest(legacy))
        d.expect("未签名按「还活着」记（迁移口径）", True, mirror_fetch_healthy(UNSIGNED))

    def s5():
        transport, _m = d.serve(d.unsigned_dist["dir"], bases=(MIRROR_A,))
        device = d.device("s5", built_in=(MIRROR_A,), transport=transport,
                          current_version_code=16)
        info, problems, warnings, offer = client_check_now(device, d.now)
        d.expect("未签名清单放行（拿到 offer）", True, offer is not None)
        d.expect("落一条指名道姓的 warning", True,
                 any("未签名" in w and host_of(MIRROR_A) in w for w in warnings))
        d.expect("问题清单为空", [], problems)
        d.expect("健康度没被记成失败", 0,
                 device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)
        d.expect("info 里的签名读数缺席", "", info.manifest_signature)

    def s6():
        bad_root = d.sub("mirror-tampered-manifest")
        for name in os.listdir(d.signed_dist["dir"]):
            source = os.path.join(d.signed_dist["dir"], name)
            if not os.path.isfile(source):
                continue
            if name == MANIFEST_FILE_NAME:
                data = d.signed_dist["manifest"].replace("apk-version-code", "apk-version-codg", 1)
                with open(os.path.join(bad_root, MANIFEST_FILE_NAME), "w",
                          encoding="utf-8", newline="\n") as fh:
                    fh.write(data)
            else:
                shutil.copyfile(source, os.path.join(bad_root, name))
        # B 源要有「活清单」：有真私钥时就是 d.signed_dist 本身；没有真私钥时用黄金签名对，
        # 但**必须放在独立副本目录里**（L7 修）：旧写法直接改写 d.signed_dist 的
        # manifest.txt，把它换成了没有 assets 组的 `golden_body` —— 那个目录是后面十几拍
        # （BD7 段① 等）共用的发布目录，状态一泄漏，后面每拍都拿到「清单里没有数据包」的
        # 假场景，演练尾部整片假红。夹具只管造场景，不许改共用发布目录的字节。
        good_root = os.path.abspath(d.signed_dist["dir"])
        if not d.sign_key:
            good_root = d.sub("mirror-golden-good")
            for name in os.listdir(d.signed_dist["dir"]):
                source = os.path.join(d.signed_dist["dir"], name)
                if not os.path.isfile(source) or name == MANIFEST_FILE_NAME:
                    continue
                shutil.copyfile(source, os.path.join(good_root, name))
            with open(os.path.join(good_root, MANIFEST_FILE_NAME), "w",
                      encoding="utf-8", newline="\n") as fh:
                fh.write(d.golden_body + f"{KS.signature_key} {d.golden_signature}\n")
            # A 源（篡改源）：同样换成「改一个字节的黄金清单」，被签字节一变验签必败。
            with open(os.path.join(bad_root, MANIFEST_FILE_NAME), "w",
                      encoding="utf-8", newline="\n") as fh:
                fh.write(d.golden_body.replace("apk-version-code", "apk-version-codg", 1)
                         + f"{KS.signature_key} {d.golden_signature}\n")
        transport, mirrors = d.serve(good_root, bases=(MIRROR_A, MIRROR_B))
        mirrors[normalize_mirror(MIRROR_A)].root = os.path.abspath(bad_root)
        device = d.device("s6", built_in=(MIRROR_A, MIRROR_B), transport=transport,
                          current_version_code=16)
        _info, problems, _w, offer = client_check_now(device, d.now)
        d.expect("A 源的验签失败进了 problems", True, any("签名验证没通过" in p for p in problems))
        d.expect("B 源接住（offer 成立）", True, offer is not None)
        d.expect("A 源连续失败计 1", 1,
                 device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)
        for _round in range(KS.demote_failures - 1):
            client_check_now(device, d.now)
        order = device.candidates(now=d.now)
        d.expect(f"连续 {KS.demote_failures} 轮验签判死后 A 降位",
                 normalize_mirror(MIRROR_B), order[0])
        d.expect("降位不是删除：两条都还在候选表里", 2, len(order))
        # A 换上活清单成功一次 ⇒ 失败清零 ⇒ 回原位
        mirrors[normalize_mirror(MIRROR_A)].root = good_root
        if not d.sign_key:
            with open(os.path.join(bad_root, MANIFEST_FILE_NAME), "w",
                      encoding="utf-8", newline="\n") as fh:
                fh.write(d.golden_body + f"{KS.signature_key} {d.golden_signature}\n")
        mirrors[normalize_mirror(MIRROR_B)].set_fault(FAULT_DEAD, times=None)
        client_check_now(device, d.now)
        d.expect("成功一次即清零失败数", 0,
                 device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)
        d.expect("A 回到原位", normalize_mirror(MIRROR_A), device.candidates(now=d.now)[0])

    for title, fn in (("S1 三态①：已签名 ⇒ VERIFIED", s1),
                      ("S2 三态②：篡改一个字节 ⇒ INVALID + 该源降位判据", s2),
                      ("S3 签名形状坏值全判 INVALID（坏 Base64/短签/未知算法/未知键 id）", s3),
                      ("S4 三态③：未签名 ⇒ UNSIGNED + 不算失联", s4),
                      ("S5 端到端：未签名清单放行并落 warning", s5),
                      ("S6 端到端：篡改源被放弃→换源→三轮后降位→成功一次回原位", s6)):
        d.run(title, fn)


def beats_manifest(d):
    d.section("清单解析（消费侧宽：坏值回落、可选组缺席、绝不哑掉通道）")

    def m1():
        text = d.signed_dist["manifest"] if d.sign_key else (
            d.golden_body + f"{KS.signature_key} {d.golden_signature}\n")
        info, problems = parse_manifest(text)
        d.expect("合法签名清单解析问题数", 0, len(problems))
        declared_min = _first_value(text, "min-supported-version ")
        d.expect("min-supported-version 读数 == 清单那一行",
                 int(declared_min) if declared_min else KS.no_min_supported,
                 info.min_supported_version)
        d.expect("签名读数就是签名行那一串", True,
                 info.manifest_signature == text.strip().rsplit(" ", 1)[-1])
        d.expect("sig-alg 读数", "rsa-sha256", info.sig_alg)
        d.expect("键名与发布侧同值", emit_update.KEY_ROLLOUT_PERCENTAGE, "rollout-percentage")

    def m2():
        base = ("update-format 1\nassets-version 7\nassets-file a.zip\nassets-size 1024\n"
                "assets-sha256 " + "ab" * 32)
        info, problems = parse_manifest(base)
        d.expect("老清单解析成功", 0, len(problems))
        d.expect("灰度缺席按 100 全量", KS.full_rollout, info.rollout_percentage)
        d.expect("老清单零告警", [], info.warnings)
        d.expect("无 min-supported ⇒ 哨兵", KS.no_min_supported, info.min_supported_version)
        d.expect("无 assets-base-version ⇒ 哨兵", KS.no_assets_base, info.assets_base_version)

    def m3():
        base = ("update-format 1\nassets-version 7\nassets-file a.zip\nassets-size 1024\n"
                "assets-sha256 " + "ab" * 32)
        for bad in ("abc", "-1", "101", "50.5", "1 2", "99999999999999999999"):
            info, problems = parse_manifest(f"{base}\nrollout-percentage {bad}")
            d.expect(f"灰度坏值 [{bad}] 不拒清单", 0, len(problems))
            d.expect(f"灰度坏值 [{bad}] 回落 100", KS.full_rollout, info.rollout_percentage)
            d.expect(f"灰度坏值 [{bad}] 留痕", True,
                     any("rollout-percentage" in w and "100 全量" in w for w in info.warnings))

    def m4():
        base = "update-format 1\nassets-version 7\nassets-file a.zip\nassets-size 1024\nassets-sha256 " \
               + "ab" * 32
        info, problems = parse_manifest(f"{base}\nrollout-percentage 10\nfuture-field 以后的东西")
        d.expect("未知键只告警不拒", 0, len(problems))
        d.expect("未知键告警一条", 1, sum(1 for w in info.warnings if "忽略不认识的键" in w))
        _info, problems = parse_manifest(f"{base}\nrollout-percentage 10\nrollout-percentage 20")
        d.expect("重复键仍拒", True, len(problems) > 0)
        _info, problems = parse_manifest("update-format 1\napk-file a.apk\n")
        d.expect("缺半 apk 组 ⇒ 拒", True, len(problems) > 0 and any("apk 通道声明不完整" in p
                                                                     for p in problems))

    def m5():
        mirrors = [f"mirror {MIRROR_A}", f"mirror {HTTP_CLEARTEXT_MIRROR}",
                   f"mirror {MIRROR_A}", "mirror https://no-dot-host/eratw/"]
        text = ("update-format 1\nassets-version 7\nassets-file a.zip\nassets-size 1024\nassets-sha256 "
                + "ab" * 32 + "\n" + "\n".join(mirrors))
        info, problems = parse_manifest(text)
        d.expect("明文 http 镜像被丢（不给降级留口子）", 1, len(info.mirrors))
        d.expect("留下的那条就是 https 那条", [normalize_mirror(MIRROR_A)], info.mirrors)
        d.expect("坏镜像各丢一条并留痕", 2, sum(1 for w in info.warnings if "mirror 不合法" in w))
        d.expect("解析不因此拒清单", 0, len(problems))

    for title, fn in (("M1 签名清单的新键读数", m1), ("M2 老清单：缺席即全量且零告警", m2),
                      ("M3 灰度坏值回落 100 并留痕（消费侧比发布侧宽）", m3),
                      ("M4 未知键只告警、重复键与缺半组仍拒", m4),
                      ("M5 mirror 行：明文 http 一律丢 + 去重 + 留痕", m5)):
        d.run(title, fn)


def beats_rollout(d):
    d.section("灰度与强制档（UpdateRolloutTest 的矩阵逐行复跑）")

    def r1():
        bad = []
        for bucket in range(KS.rollout_buckets):
            if rollout_selected(0, bucket):
                bad.append(("0%", bucket))
            if not rollout_selected(100, bucket):
                bad.append(("100%", bucket))
        for bucket in range(0, 50):
            if not rollout_selected(50, bucket):
                bad.append(("50%-低桶", bucket))
        for bucket in range(50, 100):
            if rollout_selected(50, bucket):
                bad.append(("50%-高桶", bucket))
        d.expect("灰度百分比 0/50/100 全桶矩阵（首差为空的差异清单）", [], bad)
        d.expect("1% 只中 0 号桶", [True, False],
                 [rollout_selected(1, 0), rollout_selected(1, 1)])
        d.expect("99% 挡 99 号桶", [True, False],
                 [rollout_selected(99, 98), rollout_selected(99, 99)])

    def r2():
        got = [rollout_selected(bad, 99) for bad in (-1, 101, -999, -2 ** 31, 2 ** 31 - 1)]
        d.expect("坏百分比一律按 100 全量", [True] * 5, got)
        got = [rollout_selected(50, bad) for bad in (-1, 100, 999, -2 ** 31, 2 ** 31 - 1)]
        d.expect("坏 bucket 归 0 号桶（50% 必中）", [True] * 5, got)
        d.expect("0% 是明确「谁都不推」：坏 bucket 也不放行", False, rollout_selected(0, -1))

    def r3():
        d.expect("低于 min ⇒ 强制", True, is_force_update_required(5, 6))
        d.expect("等于 min 不强制", False, is_force_update_required(6, 6))
        d.expect("高于 min 不强制", False, is_force_update_required(7, 6))
        d.expect("清单没写不强制", False, is_force_update_required(5, 0))
        d.expect("清单坏值不强制", False, is_force_update_required(5, -1))
        d.expect("本机版本读不出不强制", False, is_force_update_required(0, 1))
        d.expect("本机版本负读数不强制", False, is_force_update_required(-3, 6))

    def r4():
        missed = [b for b in range(KS.rollout_buckets)
                  if not update_selected(0, b, 5, 6)]
        d.expect("0% 灰度 + 强档命中：一个桶都不许被挡", [], missed)
        missed = [b for b in range(10, KS.rollout_buckets)
                  if not update_selected(10, b, 5, 6)]
        d.expect("10% 灰度 + 强档命中：本来不中的也放行", [], missed)
        disagree = [b for b in range(KS.rollout_buckets)
                    if (b < 10) != update_selected(10, b, 6, 6)]
        d.expect("未命中强档（版本达标）⇒ 完全按灰度", [], disagree)
        disagree = [b for b in range(KS.rollout_buckets)
                    if (b < 10) != update_selected(10, b, 0, 6)]
        d.expect("读不出本机版本 ⇒ 退化成纯灰度（强更判据只有一个源）", [], disagree)

    def r5():
        dist = build_dist(d.sub("dist-rollout10"), sign_key=d.sign_key, rollout=10,
                          version_code="18", version_name="0.2.41", assets_version="18")
        transport, _m = d.serve(dist["dir"], bases=(MIRROR_A,))
        hit = d.device("r5-hit", built_in=(MIRROR_A,), transport=transport,
                       current_version_code=17, rollout_bucket=5)
        _info, _p, _w, offer_hit = client_check_now(hit, d.now)
        miss = d.device("r5-miss", built_in=(MIRROR_A,), transport=transport,
                        current_version_code=17, rollout_bucket=50)
        _info, _p, warnings, offer_miss = client_check_now(miss, d.now)
        d.expect("同一份 10% 清单：bucket 5 命中 ⇒ 出 offer", True, offer_hit is not None)
        d.expect("同一份 10% 清单：bucket 50 不命中 ⇒ 不提醒", None, offer_miss)
        d.expect("不命中那一拍不落 problems", [], _p if isinstance(_p, list) else [])
        d.expect("不命中那一拍仍把清单告警原样带回", True, isinstance(warnings, list))

    def r6():
        dist = build_dist(d.sub("dist-force"), sign_key=d.sign_key, rollout=0,
                          min_supported="19", version_code="20", version_name="0.2.42",
                          assets_version="20")
        transport, _m = d.serve(dist["dir"], bases=(MIRROR_A,))
        device = d.device("r6", built_in=(MIRROR_A,), transport=transport,
                          current_version_code=18, rollout_bucket=99, skipped_version_code=20)
        info, _p, _w, offer = client_check_now(device, d.now)
        d.expect("0% 灰度 + 强档命中 ⇒ 仍然出 offer", True, offer is not None)
        d.expect("offer 标了强制", True, offer["forceUpdate"])
        d.expect("强档命中时「跳过此版本」抑制不生效", True, offer["apkNeeded"])
        d.expect("清单读数：灰度 0", 0, info.rollout_percentage)
        d.expect("清单读数：min-supported 19", 19, info.min_supported_version)
        calm = d.device("r6-calm", built_in=(MIRROR_A,), transport=transport,
                        current_version_code=20, rollout_bucket=99)
        _i2, _p2, _w2, offer2 = client_check_now(calm, d.now)
        d.expect("版本已达标 ⇒ 同一份 0% 清单就不提醒", None, offer2)

    for title, fn in (("R1 灰度分桶矩阵（0/50/100 全 100 桶 + 边界）", r1),
                      ("R2 灰度坏值往不误挡方向回落", r2),
                      ("R3 强制档命中判定：只认「低于最低支持版本」", r3),
                      ("R4 强制档优先于灰度（含读不出本机版本的退化）", r4),
                      ("R5 端到端：同一份 10% 清单，命中与不命中各一次", r5),
                      ("R6 端到端：0% 灰度 + 强档命中 ⇒ 必达且抑制不生效", r6)):
        d.run(title, fn)


def beats_mirror_health(d):
    d.section("镜像健康降位（P1-7：死源不再每次都当第一条试）")

    def h1():
        built = [MIRROR_A, MIRROR_B, MIRROR_C]
        d.expect("全健康时与 mirrorOrder 逐字同序",
                 mirror_order(built_in=built),
                 mirror_order_with_health(built, health={}, now=d.now))

    def h2():
        built = [MIRROR_A, MIRROR_B, MIRROR_C]
        health = {
            normalize_mirror(MIRROR_A): MirrorHealth(consecutive_failures=3, last_success_millis=1),
            normalize_mirror(MIRROR_C): MirrorHealth(0, d.now - KS.stale_millis - 1),
        }
        order = mirror_order_with_health(built, health=health, now=d.now)
        d.expect("失联源降位、两组各自保序",
                 [normalize_mirror(m) for m in (MIRROR_B, MIRROR_A, MIRROR_C)], order)
        d.expect("降位不是删除：三条一条不少", 3, len(order))
        d.expect("集合不变", {normalize_mirror(m) for m in built}, set(order))

    def h3():
        healthy = {normalize_mirror(m): MirrorHealth(0, d.now) for m in (MIRROR_A, MIRROR_B, MIRROR_C)}
        d.expect("成功一次即清零失败数 ⇒ 自动回原位",
                 [normalize_mirror(m) for m in (MIRROR_A, MIRROR_B, MIRROR_C)],
                 mirror_order_with_health([MIRROR_A, MIRROR_B, MIRROR_C], health=healthy,
                                          now=d.now))

    def h4():
        health = {normalize_mirror(MIRROR_A): MirrorHealth(consecutive_failures=3,
                                                           last_success_millis=1)}
        order = mirror_order_with_health(
            built_in=[MIRROR_A, MIRROR_B, MIRROR_C], persisted=[MIRROR_PERSISTED],
            declared=[MIRROR_DECLARED, MIRROR_A], health=health, now=d.now)
        d.expect("降位的内置源仍排在 persisted 与 declared 之前",
                 [normalize_mirror(m) for m in
                  (MIRROR_B, MIRROR_C, MIRROR_A, MIRROR_PERSISTED, MIRROR_DECLARED)], order)

    def h5():
        many = [f"https://m{n}.invalid/" for n in range(1, 13)]
        capped = mirror_order_with_health(many, health={}, now=d.now)
        d.expect(f"MAX_MIRRORS({KS.max_mirrors}) 截断不变", KS.max_mirrors, len(capped))
        d.expect("截断取的是前 N 条", many[:KS.max_mirrors], capped)
        d.expect("明文 http 一律丢掉（演练也没给它留口子）", [normalize_mirror(MIRROR_A)],
                 mirror_order_with_health([HTTP_CLEARTEXT_MIRROR, MIRROR_A], health={}, now=d.now))

    def h6():
        d.expect("从没成功过且没失败过：先给机会", False, _demoted(MirrorHealth(0, 0), d))
        d.expect("时钟回拨（成功时刻在未来）：按新鲜处理", False,
                 _demoted(MirrorHealth(0, d.now + 10_000), d))
        d.expect("连续失败没到阈值不降位", False, _demoted(MirrorHealth(2, d.now - 10_000), d))
        d.expect("阈值 <=0 不许把所有源降位", False,
                 _demoted(MirrorHealth(0, 0), d, failure_threshold=0))
        d.expect("连续失败到阈值就降位", True, _demoted(MirrorHealth(3, d.now - 10_000), d))
        d.expect("成功过但太久没成功也降位", True,
                 _demoted(MirrorHealth(0, d.now - KS.stale_millis - 1), d))

    def h7():
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A, MIRROR_B))
        mirrors[normalize_mirror(MIRROR_A)].set_fault(FAULT_DEAD, times=None)
        device = d.device("h7", built_in=(MIRROR_A, MIRROR_B), transport=transport,
                          current_version_code=16)
        first = client_check_now(device, d.now)[0]
        d.expect("A 失联 ⇒ B 接住清单", True, first is not None)
        d.expect("A 记一次失败", 1, device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)
        for _round in range(KS.demote_failures - 1):
            client_check_now(device, d.now)
        d.expect(f"{KS.demote_failures} 轮失联后 A 降位", normalize_mirror(MIRROR_B),
                 device.candidates(now=d.now)[0])
        a_requests = len(mirrors[normalize_mirror(MIRROR_A)].requests)
        mirrors[normalize_mirror(MIRROR_A)].set_fault(FAULT_OK)
        # 降位之后 B 先应答、循环就 break 了 —— A 想被试到，得让 B 也让位一次。
        mirrors[normalize_mirror(MIRROR_B)].set_fault(FAULT_DEAD, times=1)
        client_check_now(device, d.now)
        d.expect("A 恢复后成功一次即回原位", normalize_mirror(MIRROR_A),
                 device.candidates(now=d.now)[0])
        d.expect("A 的失败数被这次成功清零", 0,
                 device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)
        d.expect("降位期间 A 仍每轮被试一次（降位≠拉黑）", KS.demote_failures, a_requests)

    for title, fn in (("H1 全健康 ⇒ 与 mirrorOrder 同序", h1),
                      ("H2 失联源降位但不删除、两组各自保序", h2),
                      ("H3 成功一次清零、自动回原位", h3),
                      ("H4 清单声明的源挤不到内置源之前", h4),
                      ("H5 截断上限与明文 http 拒收", h5),
                      ("H6 健康判据的坏值不误降位", h6),
                      ("H7 端到端：死源降位、恢复后回原位", h7)):
        d.run(title, fn)


def _demoted(state, d, failure_threshold=None):
    order = mirror_order_with_health(
        built_in=[MIRROR_A, MIRROR_B], health={normalize_mirror(MIRROR_A): state},
        now=d.now, failure_threshold=KS.demote_failures if failure_threshold is None
        else failure_threshold)
    return order[0] != normalize_mirror(MIRROR_A)


def seed_game_root(device, files):
    for rel, payload in files.items():
        path = os.path.join(device.game_root, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if isinstance(payload, bytes):
            with open(path, "wb") as fh:
                fh.write(payload)
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(payload)
    marker = os.path.join(device.game_root, KS.update_dir, "data-version.txt")
    os.makedirs(os.path.dirname(marker), exist_ok=True)
    with open(marker, "w", encoding="utf-8") as fh:
        fh.write(str(device.installed_data_version))


def beats_download(d):
    d.section("下载（全量 / `.part` 断点续传 / 不认 Range / 失联换源 / 摘要不符）")
    info, _problems = parse_manifest(d.signed_dist["manifest"])
    apk_artifact = info.apk.artifact if info and info.apk else None
    data_artifact = full_data_artifact(info) if info else None

    def need_artifact():
        if apk_artifact is None:
            d.skip("整包下载", "现成 dist 的清单没有 apk 组")
            return False
        return True

    def dd1():
        if not need_artifact():
            return
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("d1", built_in=(MIRROR_A,), transport=transport)
        out = client_download(device, apk_artifact, d.now)
        d.expect("全量下载成功", True, out["file"] is not None)
        d.expect("落盘字节数 == 清单声明", apk_artifact.size_bytes,
                 os.path.getsize(out["file"]) if out["file"] else -1)
        d.expect("sha256 对得上", apk_artifact.sha256_hex,
                 emit_update.sha256_of(out["file"]) if out["file"] else "")
        d.expect(".part 已改名成正式名，目录里不留半截", False,
                 os.path.exists(os.path.join(device.download_dir,
                                             apk_artifact.file_name + KS.part_suffix)))

    def dd2():
        if not need_artifact():
            return
        cut = max(1, apk_artifact.size_bytes // 3)
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        mirror = mirrors[normalize_mirror(MIRROR_A)]
        mirror.set_fault(FAULT_CUT, cut, times=1)
        device = d.device("d2", built_in=(MIRROR_A,), transport=transport)
        out = client_download(device, apk_artifact, d.now)
        d.expect("第一次掐断、退避后续传 ⇒ 最终成功", True, out["file"] is not None)
        d.expect("sha256 仍然对上", apk_artifact.sha256_hex,
                 emit_update.sha256_of(out["file"]) if out["file"] else "")
        d.expect("只退避了一档（1 秒）", [KS.retry_backoff[0]], device.waits)
        ranges = [r for _p, r in mirror.requests]
        d.expect("第二次带的就是断点偏移", [None, cut], ranges)
        d.expect("断点前缀没被作废重来", cut, mirror.requests[1][1])
        # 断流形状自证（L7）：mock 掐线档必须与真实断流同形 —— 响应头里是**声明全长**的
        # Content-Length（续传口是完整 Content-Range + 剩余全长），body 只写到 cut 字节就收口。
        # 旧实现让发送层按实收字节补 CL，客户端拿到的是「自洽的短响应」，只有清单声明的
        # 字节数知道少了东西 —— 那不是真实断流会给出的形状。
        probe = MockMirror(d.signed_dist["dir"], MIRROR_A)
        probe.set_fault(FAULT_CUT, cut, times=1)
        status, headers, body = probe.handle("/" + apk_artifact.file_name)
        d.expect("断流形状：整份口回 200", 200, status)
        d.expect("断流形状：Content-Length 声明的是**全长**而不是实收字节",
                 str(apk_artifact.size_bytes), headers.get("Content-Length"))
        d.expect("断流形状：body 实发就是 cut 字节（与声明不符）", cut, len(body))
        probe.set_fault(FAULT_CUT, cut, times=1)
        status, headers, body = probe.handle("/" + apk_artifact.file_name, cut)
        d.expect("断流形状：续传口回 206 + 完整 Content-Range", 206, status)
        d.expect("断流形状：Content-Range 给的是剩余区间",
                 f"bytes {cut}-{apk_artifact.size_bytes - 1}/{apk_artifact.size_bytes}",
                 headers.get("Content-Range"))
        d.expect("断流形状：续传口的 Content-Length 也是剩余全长",
                 str(apk_artifact.size_bytes - cut), headers.get("Content-Length"))
        d.expect("断流形状：续传口 body 实发 cut 字节", cut, len(body))

    def dd3():
        if not need_artifact():
            return
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        mirror = mirrors[normalize_mirror(MIRROR_A)]
        device = d.device("d3", built_in=(MIRROR_A,), transport=transport)
        os.makedirs(device.download_dir, exist_ok=True)
        part = os.path.join(device.download_dir, apk_artifact.file_name + KS.part_suffix)
        prefix = (d.signed_dist["apk_bytes"] or b"")[0:7] or hashlib.sha256(b"x").digest()[:7]
        with open(part, "wb") as fh:
            fh.write(prefix)
        mirror.set_fault(FAULT_NO_RANGE, times=None)
        out = client_download(device, apk_artifact, d.now)
        d.expect("源不认 Range ⇒ 从头重写后仍成功", True, out["file"] is not None)
        d.expect("重写后 sha256 对得上", apk_artifact.sha256_hex,
                 emit_update.sha256_of(out["file"]) if out["file"] else "")
        d.expect("设备侧确实请求过续传（才被拒）", True,
                 any(r is not None for _p, r in mirror.requests))
        d.expect("判据：mustRewriteFromScratch(偏移, 200, None)", True,
                 must_rewrite_from_scratch(7, 200, content_range_start(None)))
        with open(os.path.join(d.signed_dist["dir"], apk_artifact.file_name), "rb") as fh:
            whole = fh.read()
        stitched = prefix + whole
        d.expect("反证：把整份追加到旧前缀后面 ⇒ sha 必不符（这就是必须重写的理由）",
                 False, hashlib.sha256(stitched).hexdigest() == apk_artifact.sha256_hex)

    def dd4():
        if not need_artifact():
            return
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A, MIRROR_B))
        mirrors[normalize_mirror(MIRROR_A)].set_fault(FAULT_MISSING,
                                                      apk_artifact.file_name, times=None)
        device = d.device("d4", built_in=(MIRROR_A, MIRROR_B), transport=transport)
        out = client_download(device, apk_artifact, d.now)
        d.expect("404 之后换源成功", True, out["file"] is not None)
        d.expect("404 不在死源上烧退避档（一次都不重试）", [], device.waits)
        d.expect("A 源只被打了一次", 1, len(mirrors[normalize_mirror(MIRROR_A)].requests))

    def dd5():
        if not need_artifact():
            return
        bad = d.sub("mirror-bad-bytes")
        for name in os.listdir(d.signed_dist["dir"]):
            source = os.path.join(d.signed_dist["dir"], name)
            if os.path.isfile(source):
                shutil.copyfile(source, os.path.join(bad, name))
        with open(os.path.join(bad, apk_artifact.file_name), "r+b") as fh:
            fh.seek(0)
            first = fh.read(1)
            fh.seek(0)
            fh.write(bytes([first[0] ^ 0xFF]))
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A, MIRROR_B))
        mirrors[normalize_mirror(MIRROR_A)].root = os.path.abspath(bad)
        device = d.device("d5", built_in=(MIRROR_A, MIRROR_B), transport=transport)
        out = client_download(device, apk_artifact, d.now)
        d.expect("坏字节源摘要不符后换到好源成功", True, out["file"] is not None)
        d.expect("最终 sha256 对得上", apk_artifact.sha256_hex,
                 emit_update.sha256_of(out["file"]) if out["file"] else "")
        d.expect("摘要不符的 .part 被删掉（内容不可信不续）", False,
                 os.path.exists(os.path.join(device.download_dir,
                                             apk_artifact.file_name + KS.part_suffix)))
        d.expect("A 源记一次连续失败", 1,
                 device.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)

    def dd6():
        if not need_artifact():
            return
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("d6", built_in=(MIRROR_A,), transport=transport,
                          usable_bytes=KS.small_headroom - 1)
        out = client_download(device, apk_artifact, d.now)
        d.expect("空间闸按「还差的字节 + 随 size 缩放的余量」挡下", None, out["file"])
        d.expect("失败类别是本地文件系统", LOCAL_FS, out["kind"])

    def dd7():
        if not need_artifact():
            return
        transport, mirrors = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        mirrors[normalize_mirror(MIRROR_A)].set_fault(FAULT_ERROR, 503, times=None)
        device = d.device("d7", built_in=(MIRROR_A,), transport=transport)
        out = client_download(device, apk_artifact, d.now)
        d.expect("5xx 走同镜像退避重试（档位走完才收手）", None, out["file"])
        d.expect("退避档位逐档走完", KS.retry_backoff, device.waits)
        d.expect("访问次数 = 档位数 + 首发", len(KS.retry_backoff) + 1,
                 len(mirrors[normalize_mirror(MIRROR_A)].requests))

    def dd8():
        keep = [a for a in (apk_artifact, data_artifact) if a is not None]
        if not keep:
            d.skip("陈旧产物点名回收", "清单里没有可保名的产物")
            return
        stale_part = keep[0].file_name + KS.part_suffix
        entries = [(keep[0].file_name, keep[0].size_bytes, 10),
                   ("eratw-0.0.1+1-old.apk", 999, 10),
                   (stale_part, keep[0].size_bytes - 1, 8 * 24 * 3600 * 1000),
                   (keep[1].file_name if len(keep) > 1 else "z.zip", 10, 10)]
        doomed = plan_cleanup(entries, keep, extra_keep={"keepme.zip"})
        d.expect("超期 .part 回收", True, stale_part in doomed)
        d.expect("与当前清单无关的旧整包回收", True, "eratw-0.0.1+1-old.apk" in doomed)
        d.expect("清单认识的正式文件不删（重试点还在）", False, keep[0].file_name in doomed)

    def dd9():
        if not need_artifact():
            return
        max_age = KS.part_max_age
        d.expect("年龄超过上限 ⇒ 不认（整段重来）", 0, resume_offset(100, 1000, max_age + 1))
        d.expect("字节数超过声明 ⇒ 不认（缝合必败）", 0, resume_offset(1001, 1000, 0))
        d.expect("字节数刚好齐 ⇒ 返回偏移让调用方去核 sha", 1000, resume_offset(1000, 1000, 0))
        d.expect("0 字节空桩 ⇒ 不认", 0, resume_offset(0, 1000, 0))
        d.expect("时钟回拨导致的负年龄按新鲜处理", 100, resume_offset(100, 1000, -5))

    for title, fn in (("D1 全量下载成功：字节数与 sha256 双核", dd1),
                      ("D2 `.part` 断点续传：掐断→退避→Range 206→sha 对上", dd2),
                      ("D3 源不认 Range：截断从头重写（附「追加必失败」反证）", dd3),
                      ("D4 源上没有这个文件：404 立刻换源，不烧退避档", dd4),
                      ("D5 摘要不符：删 .part、换源、失败计数 +1", dd5),
                      ("D6 空间闸：按剩余字节 + 缩放余量挡下", dd6),
                      ("D7 5xx：同镜像退避逐档走完才收手", dd7),
                      ("D8 陈旧产物回收点名（planCleanup）", dd8),
                      ("D9 续传信任判据（超期/超长/齐了/空桩/回拨）", dd9)):
        d.run(title, fn)


def beats_apply(d):
    d.section("应用（数据包落盘 / 坏 sha 必拒 / 差量回落）")
    info, _problems = parse_manifest(d.signed_dist["manifest"])
    data_artifact = full_data_artifact(info) if info else None
    delta_artifact = info.assets_delta if info else None

    def stage(device, path, version):
        device.staged_assets_path = path
        device.staged_assets_version = version

    def a1():
        if data_artifact is None:
            d.skip("全量数据包应用", "清单没有数据包那一组键")
            return
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("a1", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=0)
        seed_game_root(device, {})
        out = client_download(device, data_artifact, d.now)
        d.expect("数据包下载成功", True, out["file"] is not None)
        stage(device, out["file"], info.assets.version if info.assets else 17)
        kind, detail = client_apply_staged(device)
        d.expect("应用结果", "applied", kind)
        d.expect("盘上 data-version 升到暂存版本", device.staged_assets_version + 0,
                 device.installed_data_version)
        landed = os.path.join(device.game_root, "CSV", "DRILL.CSV")
        d.expect("语料条目真的落了盘", True, os.path.isfile(landed))
        program = os.path.join(device.game_root, KS.artifact_dir, KS.program_file)
        d.expect("erbc 产物落了盘", True, os.path.isfile(program))
        d.expect(detail.startswith("落盘"), True, True)

    def a2():
        if data_artifact is None:
            d.skip("版本不高于盘上 ⇒ 丢弃不降级", "同上")
            return
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("a2", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=99)
        seed_game_root(device, {"CSV/DRILL.CSV": "ID,NAME\n99,盘上更新的那版\n"})
        out = client_download(device, data_artifact, d.now)
        stage(device, out["file"], 17)
        kind, _detail = client_apply_staged(device)
        d.expect("暂存版本低于盘上 ⇒ 不应用", "nothing", kind)
        d.expect("盘上那份没被旧包盖掉", "ID,NAME\n99,盘上更新的那版\n",
                 open(os.path.join(device.game_root, "CSV", "DRILL.CSV"),
                      encoding="utf-8").read())

    def a3():
        if data_artifact is None:
            d.skip("坏 sha 必拒", "同上")
            return
        bad = d.sub("mirror-bad-zip")
        for name in os.listdir(d.signed_dist["dir"]):
            source = os.path.join(d.signed_dist["dir"], name)
            if os.path.isfile(source):
                shutil.copyfile(source, os.path.join(bad, name))
        zip_in_bad = os.path.join(bad, data_artifact.file_name)
        with open(zip_in_bad, "r+b") as fh:
            fh.seek(8)
            byte = fh.read(1)
            fh.seek(8)
            fh.write(bytes([byte[0] ^ 0xFF]))  # 等长换一字节：字节数对得上、摘要对不上
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        _m[normalize_mirror(MIRROR_A)].root = os.path.abspath(bad)
        device = d.device("a3", built_in=(MIRROR_A,), transport=transport)
        seed_game_root(device, {})
        out = client_download(device, data_artifact, d.now)
        d.expect("等长被换一字节 ⇒ 下载必败", None, out["file"])
        d.expect("失败原话点名摘要不符", True, "摘要不符" in out["problem"])
        d.expect("过程日志里的类别是 DigestMismatch", True,
                 any("DigestMismatch" in line for line in device.log))
        d.expect("坏字节没落进下载目录", False,
                 os.path.exists(os.path.join(device.download_dir, data_artifact.file_name)))
        d.expect("半截 .part 也被删掉（内容不可信不续）", False,
                 os.path.exists(os.path.join(device.download_dir,
                                             data_artifact.file_name + KS.part_suffix)))
        kind, _detail = client_apply_staged(device)
        d.expect("谈不上应用这一步", "nothing", kind)
        # 同一件坏字节 + 一条好源：设备侧该换源拿对的那份，而不是把坏的缝完再报
        transport2, mirrors2 = d.serve(d.signed_dist["dir"], bases=(MIRROR_A, MIRROR_B))
        mirrors2[normalize_mirror(MIRROR_A)].root = os.path.abspath(bad)
        device2 = d.device("a3b", built_in=(MIRROR_A, MIRROR_B), transport=transport2)
        seed_game_root(device2, {})
        out2 = client_download(device2, data_artifact, d.now)
        d.expect("坏源之后换到好源成功", True, out2["file"] is not None)
        d.expect("到手的是清单声明的那份字节", data_artifact.sha256_hex,
                 emit_update.sha256_of(out2["file"]) if out2["file"] else "")
        d.expect("坏源记一次连续失败", 1,
                 device2.mirror_health[normalize_mirror(MIRROR_A)].consecutive_failures)

    def a4():
        slip = os.path.join(d.sub("dist-slip"), "eratw-assets-slip.zip")
        os.makedirs(os.path.dirname(slip), exist_ok=True)
        with zipfile.ZipFile(slip, "w") as zf:
            zf.writestr("CSV/ok.csv", "ID,NAME\n1,好条目\n")
            zf.writestr("../escape.txt", "越界条目")
        device = d.device("a4", built_in=(MIRROR_A,), installed_data_version=0)
        seed_game_root(device, {})
        stage(device, slip, 42)
        kind, detail = client_apply_staged(device)
        d.expect("带 `../` 条目的包整包拒装（不是跳过几个文件继续装）", "rejected", kind)
        d.expect("拒装文案点名非法路径条数", True, "非法路径" in detail)
        d.expect("包被丢弃（不留在盘上等下次）", False, os.path.isfile(slip))
        d.expect("游戏根外没有落进任何东西", False,
                 os.path.exists(os.path.join(device.workdir, "escape.txt")))

    def a5():
        pair = os.path.join(d.sub("dist-pair"), "eratw-assets-pair.zip")
        os.makedirs(os.path.dirname(pair), exist_ok=True)
        with zipfile.ZipFile(pair, "w") as zf:
            zf.writestr(f"{KS.artifact_dir}/{KS.program_file}", b"NEW-PROGRAM")
            zf.writestr(f"{KS.artifact_dir}/{KS.meta_file}",
                        'boot 1\nformat 1\nprogram-sha256 "%s"\n' % ("0" * 64))
        device = d.device("a5", built_in=(MIRROR_A,), installed_data_version=0)
        seed_game_root(device, {f"{KS.artifact_dir}/{KS.program_file}": b"OLD-PROGRAM",
                                f"{KS.artifact_dir}/{KS.meta_file}": "old\n"})
        stage(device, pair, 43)
        kind, detail = client_apply_staged(device)
        d.expect("program 与侧车不配对 ⇒ 拒绝应用", "failed", kind)
        d.expect("不配对文案带侧车声明与实际读数", True, "不配对" in detail)
        d.expect("盘上仍是上一版产物（配对回滚）", b"OLD-PROGRAM",
                 open(os.path.join(device.game_root, KS.artifact_dir, KS.program_file),
                      "rb").read())
        d.expect("data-version 没写（半轮不算成功）", 0, device.installed_data_version)

    def a6():
        base = build_dist(d.sub("dist-delta"), sign_key=d.sign_key, version_code="21",
                          version_name="0.2.43", assets_version="21", with_delta=True,
                          delta_base="20")
        info_delta, problems = parse_manifest(base["manifest"])
        d.expect("带差量的 dist 体检：解析问题数", 0, len(problems))
        d.expect("清单读出基线版本", 20, info_delta.assets_base_version)
        d.expect("差量三件套齐全", True, info_delta.assets_delta is not None)
        wrong = data_artifact_for(info_delta, 19, True)
        d.expect("基线不符（盘上 19 ≠ 基线 20）⇒ 回落全量",
                 full_data_artifact(info_delta).file_name, wrong.file_name)
        right = data_artifact_for(info_delta, 20, True)
        d.expect("基线恰好相等 ⇒ 走差量", info_delta.assets_delta.file_name, right.file_name)
        untrusted = data_artifact_for(info_delta, 20, False)
        d.expect("盘不可信（没摘要）⇒ 回落全量", full_data_artifact(info_delta).file_name,
                 untrusted.file_name)

    def a7():
        """删除表四档判据：范围外/保护名单/包内优先级/产物对只替换不删。"""
        slip = os.path.join(d.sub("dist-delta-table"), "eratw-assets-table.zip")
        os.makedirs(os.path.dirname(slip), exist_ok=True)
        table = "\n".join([
            "CSV/to-delete.csv",
            "plugins/also-in-package.csv",
            "sav/archive.dat",
            "dat/player-font.ttf",
            f"{KS.artifact_dir}/{KS.program_file}",
            "resources/not-on-disk.csv",
        ]) + "\n"
        with zipfile.ZipFile(slip, "w") as zf:
            zf.writestr("CSV/new.csv", "新版语料\n")
            zf.writestr("plugins/also-in-package.csv", "包里的那份为准\n")
            zf.writestr(KS.delta_list_name, table)
        device = d.device("a7", built_in=(MIRROR_A,), installed_data_version=0)
        seed_game_root(device, {
            "CSV/to-delete.csv": "旧\n",
            "plugins/also-in-package.csv": "盘上旧的那份\n",
            "sav/archive.dat": "玩家的存档",
            "dat/player-font.ttf": "中文字体",
            f"{KS.artifact_dir}/{KS.program_file}": b"OLD-PROGRAM",
            f"{KS.artifact_dir}/{KS.meta_file}": "old\n"})
        stage(device, slip, 44)
        kind, detail = client_apply_staged(device)
        d.expect("带删除表的差量包应用结果", "applied", kind)
        d.expect("范围内的旧文件真被删掉", False,
                 os.path.isfile(os.path.join(device.game_root, "CSV", "to-delete.csv")))
        d.expect("包内优先级：这条按新内容兑现，不删", True,
                 os.path.isfile(os.path.join(device.game_root, "plugins", "also-in-package.csv")))
        d.expect("存档目录撞保护名单 ⇒ 不删", True,
                 os.path.isfile(os.path.join(device.game_root, "sav", "archive.dat")))
        d.expect("解包器管不着的目录（dat）⇒ 不删", True,
                 os.path.isfile(os.path.join(device.game_root, "dat", "player-font.ttf")))
        d.expect("产物对里的 program 不会被删除表清掉", True,
                 os.path.isfile(os.path.join(device.game_root, KS.artifact_dir, KS.program_file)))
        d.expect("受阻计数留痕（sav / dat / program 三档）", "受阻 3 条", detail.split("、")[-1])
        d.expect("data-version 标记升到暂存版本", 44, device.installed_data_version)

    def a8():
        if delta_artifact is None:
            d.skip("发布侧真产出的差量包能落盘", "现成 dist 没有差量那一组键")
            return
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("a8", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=int(d.signed_dist["assets_base_version"] or 0))
        seed_game_root(device, {"CSV/DRILL.CSV": "旧版\n",
                                "resources/已下架.png": "PNG-old"})
        out = client_download(device, delta_artifact, d.now)
        d.expect("差量包下载成功", True, out["file"] is not None)
        stage(device, out["file"], int(d.signed_dist["assets_version"] or 0))
        kind, _detail = client_apply_staged(device)
        d.expect("发布侧 stage_delta_zip 的包设备侧认账", "applied", kind)
        d.expect("差量删除表里的条目真被删", False,
                 os.path.isfile(os.path.join(device.game_root, "resources", "已下架.png")))


    for title, fn in (("A1 全量数据包：落盘 + data-version 升档", a1),
                      ("A2 暂存版本不高于盘上 ⇒ 丢弃、语料不降级", a2),
                      ("A3 坏 sha 必拒：下载期就失败，谈不上应用", a3),
                      ("A4 包里带越界条目 ⇒ 整包拒装并丢包", a4),
                      ("A5 program/侧车不配对 ⇒ 拒绝并回滚到上一版配对", a5),
                      ("A6 差量资格矩阵：基线不符 / 恰好相等 / 盘不可信", a6),
                      ("A7 删除表四档判据（范围内/包内优先/保护名单/范围外/产物对）", a7),
                      ("A8 发布侧 stage_delta_zip 的差量包：设备侧下载并应用", a8)):
        d.run(title, fn)


def beats_block_delta(d):
    d.section("块级差量（P2-13，U8：发布侧生成 → 设备侧判据 → 资格矩阵 → 传输与应用）")

    def stage(device, path, version):
        device.staged_assets_path = path
        device.staged_assets_version = version

    def block_dist():
        dist = getattr(d, "block_dist", None)
        if dist is None:
            d.skip("块级 dist", "BD1 没建起来（后续拍依赖它）")
        return dist

    def patch_entries(dist):
        with zipfile.ZipFile(dist["block_path"]) as zf:
            patch_names = sorted(n for n in zf.namelist() if n.startswith(KS.bd_patch_prefix))
            payloads = {n: zf.read(n) for n in zf.namelist() if not n.endswith("/")}
        return patch_names, payloads

    def bd1():
        """发布侧：块级包由真生产函数生成；清单三件套 + --verify-only 复核 + 跨语言常量钉。"""
        dist = build_block_dist(d.sub("dist-block"), sign_key=d.sign_key)
        d.block_dist = dist
        verify_args = ["--verify-only", dist["dir"]]
        if d.sign_key_path:
            verify_args += ["--sign-key", d.sign_key_path]  # 带签名的清单，体检也得拿同一把章
        code, _out = d.emit_cli(verify_args)
        d.expect("--verify-only（CLI 子进程，真入口）退出码", 0, code)
        problems = emit_update.verify(dist["dir"], sign_key=d.sign_key)
        d.expect("发布侧体检问题数", 0, len(problems))
        # 跨语言钉：两侧常量逐字同值（Kotlin KDoc 写明「改一边必须改另一边」）
        d.expect("补丁魔数两侧同值", emit_update.BLOCK_PATCH_MAGIC + "\n", KS.bd_patch_magic)
        d.expect("补丁 fmt 两侧同值", emit_update.BLOCK_PATCH_FORMAT, KS.bd_patch_format)
        d.expect("补丁条目前缀两侧同值", emit_update.BLOCK_DELTA_PREFIX, KS.bd_patch_prefix)
        d.expect("补丁头部字节上限两侧同值", emit_update.BLOCK_PATCH_HEADER_MAX,
                 KS.bd_patch_header_max)
        d.expect("发布侧块长不超设备侧上限", True,
                 0 < emit_update.BLOCK_SIZE_BYTES <= KS.bd_patch_max_block)
        # 清单三件套：键名读自 BlockDeltaManifest.kt，行就在生产 write_manifest 写出的清单里
        text = dist["manifest"]
        for key in (KS.bd_key_file, KS.bd_key_size, KS.bd_key_sha):
            d.expect(f"清单带 {key} 行", True, ("\n" + key + " ") in ("\n" + text))
        info, problems = parse_manifest(text)
        d.expect("设备侧解析问题数（块级键已登记为已知键）", 0, len(problems))
        d.expect("块级键不再谎报「忽略不认识的键」", [],
                 [w for w in info.warnings if "忽略不认识的键" in w])
        d.expect("UpdateInfo 的块级件读数非空", True, info.assets_block_delta is not None)
        d.expect("块级件名与清单声明一致", True,
                 f"{KS.bd_key_file} {info.assets_block_delta.file_name}" in text)
        artifact, _problem = _read_block_delta_group(block_delta_values_of(text))
        d.expect("BlockDeltaManifest 取行口径读数一致",
                 info.assets_block_delta.file_name, artifact.file_name if artifact else None)
        # 缺半组：只告警并当缺席，绝不拒清单（UpdateManifest.kt:310-314）
        stripped = "\n".join(line for line in text.splitlines()
                             if not line.startswith(KS.bd_key_size + " ")) + "\n"
        info_half, problems_half = parse_manifest(stripped)
        d.expect("缺半组不拒清单", 0, len(problems_half))
        d.expect("缺半组 ⇒ 告警点名回落方向", True,
                 any("声明不完整" in w and "块级差量不启用" in w for w in info_half.warnings))
        d.expect("缺半组 ⇒ 块级件按缺席", None, info_half.assets_block_delta)
        # 包形状：补丁条目在设备侧前缀下、删除表与文件级同一个条目名、两种形态都出现
        patch_names, _payloads = patch_entries(dist)
        with zipfile.ZipFile(dist["block_path"]) as zf:
            has_delta_list = KS.delta_list_name in zf.namelist()
        d.expect("块级包带补丁条目（前缀读自 Kotlin）", True, len(patch_names) >= 1)
        d.expect("删除表条目名与文件级同一条", True, has_delta_list)
        d.expect("生成统计：补丁与整条两种形态都出现", True,
                 dist["block_stats"]["patched"] >= 1 and dist["block_stats"]["whole"] >= 1)

    def bd2():
        """发布侧复核必红：篡改一个块 / 改一处旧块引用 ⇒ verify_block_patches 红。"""
        dist = block_dist()
        if dist is None:
            return
        patch_names, _payloads = patch_entries(dist)
        target = patch_names[0]
        rel = target[len(KS.bd_patch_prefix):]
        copy_tag = KS.bd_op_copy.encode("ascii")

        def flip_payload(_name, data):
            return data[:-1] + bytes([data[-1] ^ 0xFF])  # 等长换一字节：改的是补丁字节本身

        def bump_copy_ref(_name, data):
            head, sep, body = data.partition(b"\n\n")
            index = body.find(copy_tag)
            if index < 0 or index + 5 > len(body):
                return data
            number = int.from_bytes(body[index + 1:index + 5], "big")
            return head + sep + body[:index + 1] + \
                (number + 1).to_bytes(4, "big") + body[index + 5:]

        def mutate_only(choice, fn):
            def _m(name, data):
                if name == choice:
                    return fn(name, data)
                return data
            return _m

        bad_a = os.path.join(d.sub("blockzip-bad-payload"), os.path.basename(dist["block_path"]))
        _rewrite_zip(dist["block_path"], bad_a, mutate_only(target, flip_payload))
        bad_b = os.path.join(d.sub("blockzip-bad-ref"), os.path.basename(dist["block_path"]))
        _rewrite_zip(dist["block_path"], bad_b, mutate_only(target, bump_copy_ref))
        d.expect("对照：原包块级复核零问题", [],
                 emit_update.verify_block_patches(dist["block_path"], dist["full_path"],
                                                  dist["base_path"]))
        red_a = emit_update.verify_block_patches(bad_a, dist["full_path"], dist["base_path"])
        d.expect("篡改一个块 ⇒ 复核红", True, len(red_a) > 0)
        d.expect("红的话点名被篡改的那条", True, any(rel in p for p in red_a))
        red_b = emit_update.verify_block_patches(bad_b, dist["full_path"], dist["base_path"])
        d.expect("改一处旧块引用 ⇒ 复核红", True, len(red_b) > 0)
        d.expect("红的话点名被改的那条", True, any(rel in p for p in red_b))

    def bd3():
        """设备侧判据读数：判式链留痕 + 形状闸用生产解析器行为钉（读不到即炸，不落兜底值）。"""
        d.expect("四档互斥读数（枚举名读自 UpdatePolicy.kt）",
                 ["BLOCK_DELTA", "FILE_DELTA", "FULL", "NONE"], KS.data_kind_names)
        usable = re.search(r"fun blockDeltaUsable\(.*?\)\s*:\s*Boolean\s*=\s*deltaUsable\(",
                           KS.policy_src, re.S)
        d.expect("blockDeltaUsable 判式复用 deltaUsable（UpdatePolicy.kt:307-312）", True,
                 usable is not None)
        chain = re.search(r"fun dataArtifactFor\(\s*info: UpdateInfo,\s*blockDelta: Artifact\?.*?"
                          r"dataArtifactFor\(info, installedVersion, diskTrusted\)",
                          KS.policy_src, re.S)
        d.expect("四参 dataArtifactFor 回落三参形（UpdatePolicy.kt:328-338）", True,
                 chain is not None)
        # 形状闸探针：真生产解析器 + 设备侧常量（KS 读自 Kotlin）
        old = _block_noise(3 * emit_update.BLOCK_SIZE_BYTES)
        new = bytearray(old)
        new[100] ^= 0x20
        patch, _pstats = emit_update.build_block_patch(bytes(old), bytes(new))
        d.expect("对照：单字节改动真的生成补丁", True, patch is not None)
        if patch is None:
            return
        fields, _body = emit_update.split_block_patch(patch)
        d.expect("补丁头键名与设备侧逐字同值", sorted(KS.bd_header_keys), sorted(fields))
        head, sep, body = patch.partition(b"\n\n")
        over = head.replace(f"block {fields['block']}".encode("utf-8"),
                            f"block {KS.bd_patch_max_block + 1}".encode("utf-8")) + sep + body
        d.expect("块号越过设备侧 MAX_BLOCK_BYTES ⇒ 头部形状判死", None,
                 emit_update.split_block_patch(over)[0])
        bad_fmt = patch.replace(b"fmt 1\n", b"fmt 9\n", 1)
        d.expect("fmt 不是认识的那一代 ⇒ 判死", None, emit_update.split_block_patch(bad_fmt)[0])
        truncated = patch[:len(head)]
        d.expect("头部之后没有空行收尾 ⇒ 判死（不是当空补丁放行）", None,
                 emit_update.split_block_patch(truncated)[0])
        d.expect("对照：原样补丁头部解析通过", True,
                 emit_update.split_block_patch(patch)[0] is not None)

    def bd4():
        """资格矩阵扩档：四档互斥、回落次序同向，逐格断言（判据 UpdatePolicy.kt:328-358）。"""
        dist = block_dist()
        if dist is None:
            return
        info, _p = parse_manifest(dist["manifest"])
        full_name = full_data_artifact(info).file_name
        block_name = info.assets_block_delta.file_name
        delta_name = info.assets_delta.file_name
        got = data_artifact_for_block(info, 20, True)
        d.expect("盘上 20 + 盘可信 ⇒ 块级件", block_name, got.file_name)
        d.expect("同格 kind = BLOCK_DELTA", KS.data_kind_names[0],
                 data_artifact_kind(info, 20, True))
        d.expect("盘不可信 ⇒ 块级与文件级同一条闸关死 ⇒ 全量", full_name,
                 data_artifact_for_block(info, 20, False).file_name)
        d.expect("同格 kind = FULL", KS.data_kind_names[2], data_artifact_kind(info, 20, False))
        d.expect("盘上 19 ≠ 基线 20 ⇒ 两档差量都不够格 ⇒ 全量", full_name,
                 data_artifact_for_block(info, 19, True).file_name)
        d.expect("同格 kind = FULL", KS.data_kind_names[2], data_artifact_kind(info, 19, True))
        d.expect("盘上 21 比基线新 ⇒ 全量（回落不降级）", full_name,
                 data_artifact_for_block(info, 21, True).file_name)
        d.expect("同格 kind = FULL", KS.data_kind_names[2], data_artifact_kind(info, 21, True))
        # 块级缺席 ⇒ 与三参形逐字同结论（UpdatePolicy.kt:319-321 的 KDoc 承诺）
        no_block = dataclasses.replace(info, assets_block_delta=None)
        for installed in (19, 20, 21):
            d.expect(f"块级缺席（盘上 {installed}）⇒ 与三参形同结论",
                     data_artifact_for(no_block, installed, True).file_name,
                     data_artifact_for_block(no_block, installed, True).file_name)
        d.expect("块级缺席 + 盘上 20 ⇒ 文件级", delta_name,
                 data_artifact_for_block(no_block, 20, True).file_name)
        d.expect("同格 kind = FILE_DELTA", KS.data_kind_names[1],
                 data_artifact_kind(no_block, 20, True))
        no_deltas = dataclasses.replace(info, assets_block_delta=None, assets_delta=None)
        d.expect("文件级也缺席 ⇒ 全量", full_name,
                 data_artifact_for_block(no_deltas, 20, True).file_name)
        d.expect("同格 kind = FULL", KS.data_kind_names[2], data_artifact_kind(no_deltas, 20, True))
        bare = dataclasses.replace(info, assets=None, assets_full=None, assets_delta=None,
                                   assets_block_delta=None)
        d.expect("什么件都没有 ⇒ kind = NONE", KS.data_kind_names[3],
                 data_artifact_kind(bare, 20, True))
        d.expect("什么件都没有 ⇒ 选不出件", None, data_artifact_for_block(bare, 20, True))
        # 端到端同向：真清单解析下也落同一档
        full_only = build_dist(d.sub("dist-fullonly"), sign_key=d.sign_key, min_supported="15",
                               version_code="23", version_name="0.2.45", assets_version="23",
                               with_delta=False)
        info_f, _pf = parse_manifest(full_only["manifest"])
        d.expect("full-only dist：块级/文件级组都不在清单里", [None, None],
                 [info_f.assets_block_delta, info_f.assets_delta])
        d.expect("full-only dist：盘上 20 ⇒ 全量件", full_data_artifact(info_f).file_name,
                 data_artifact_for_block(info_f, 20, True).file_name)
        d.expect("full-only dist：kind = FULL", KS.data_kind_names[2],
                 data_artifact_kind(info_f, 20, True))
        info_s, _ps = parse_manifest(d.signed_dist["manifest"])
        if info_s.assets_delta is not None:
            d.expect("现成签名 dist（只有文件级）：盘上 20 ⇒ 文件级件", info_s.assets_delta.file_name,
                     data_artifact_for_block(info_s, 20, True).file_name)
            d.expect("同格 kind = FILE_DELTA", KS.data_kind_names[1],
                     data_artifact_kind(info_s, 20, True))
        else:
            d.skip("签名 dist 的文件级格", "现成 dist 没有差量组")

    def bd5():
        """传输拍：mock 镜像下发块级包 → `.part` 断点续传 → 应用 → 盘上与 v21 逐字节相等。"""
        dist = block_dist()
        if dist is None:
            return
        info, _p = parse_manifest(dist["manifest"])
        transport, mirrors = d.serve(dist["dir"], bases=(MIRROR_A,))
        device = d.device("bd5", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=int(dist["base_version"]))
        seed_game_root(device, root_to_files(dist["corpus_old"]))
        _i, _pr, _w, offer = client_check_now(device, d.now)
        d.expect("选件落在块级档", KS.data_kind_names[0], offer["dataKind"])
        d.expect("要下的就是清单里的块级包", info.assets_block_delta.file_name,
                 offer["dataArtifact"].file_name)
        cut = max(1, info.assets_block_delta.size_bytes // 3)
        mirrors[normalize_mirror(MIRROR_A)].set_fault(FAULT_CUT, cut, times=1)
        out = client_download(device, info.assets_block_delta, d.now)
        d.expect("掐断后续传 ⇒ 下载成功", True, out["file"] is not None)
        if out["file"] is None:
            return
        zip_reqs = [r for path, r in mirrors[normalize_mirror(MIRROR_A)].requests
                    if path.endswith(info.assets_block_delta.file_name)]
        d.expect("第二次请求带的就是断点偏移", [None, cut], zip_reqs)
        d.expect("sha256 对上清单声明", info.assets_block_delta.sha256_hex,
                 emit_update.sha256_of(out["file"]))
        d.expect("按包内实况路由到块级树", KS.data_kind_names[0], staged_apply_kind(out["file"]))
        stage(device, out["file"], int(dist["assets_version"]))
        kind, detail = apply_block_delta_package(device)
        d.expect("块级包应用成功", "applied", kind)
        d.expect("data-version 升到 21", int(dist["assets_version"]),
                 device.installed_data_version)
        for rel, payload in sorted(root_to_files(dist["corpus_new"]).items()):
            path = os.path.join(device.game_root, *rel.split("/"))
            d.expect(f"盘上 {rel} 与 v21 逐字节相等", True,
                     os.path.isfile(path) and open(path, "rb").read() == payload)
        d.expect("删除表兑现：v20 独有文件被删", False,
                 os.path.isfile(os.path.join(device.game_root, "plugins", "note.txt")))
        expected = f"补丁 {dist['block_stats']['patched']} + 整条 {dist['block_stats']['whole']}"
        d.expect("detail 留痕与发布侧统计同数", True, expected in detail)

    def bd6():
        """传输拍：镜像上的块级包被换一字节 ⇒ 摘要不符必拒，`.part` 不落、盘不动。"""
        dist = block_dist()
        if dist is None:
            return
        info, _p = parse_manifest(dist["manifest"])
        bad = d.sub("mirror-bad-blockzip")
        for name in os.listdir(dist["dir"]):
            source = os.path.join(dist["dir"], name)
            if os.path.isfile(source):
                shutil.copyfile(source, os.path.join(bad, name))
        _rewrite_zip(dist["block_path"],
                     os.path.join(bad, info.assets_block_delta.file_name),
                     lambda _n, data: data[:-1] + bytes([data[-1] ^ 0xFF]))
        transport, mirrors = d.serve(dist["dir"], bases=(MIRROR_A,))
        mirrors[normalize_mirror(MIRROR_A)].root = os.path.abspath(bad)
        device = d.device("bd6", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=int(dist["base_version"]))
        seed_game_root(device, root_to_files(dist["corpus_old"]))
        _i, _pr, _w, _offer = client_check_now(device, d.now)
        out = client_download(device, info.assets_block_delta, d.now)
        d.expect("块级包被换一字节 ⇒ 下载必拒", None, out["file"])
        d.expect("失败原话点名摘要不符", True, "摘要不符" in out["problem"])
        d.expect("半截 `.part` 被清掉（内容不可信不续传）", False,
                 os.path.exists(os.path.join(device.download_dir,
                                             info.assets_block_delta.file_name
                                             + KS.part_suffix)))
        d.expect("盘上还是 v20", int(dist["base_version"]), device.installed_data_version)

    def bd7():
        """端到端回落链：块级件缺席⇒文件级⇒文件级也缺席⇒全量，每一跳都真下载真应用。"""
        dist = block_dist()
        if dist is None:
            return
        # 段①：清单只有文件级（块级件缺席）⇒ 落文件级
        transport, _m = d.serve(d.signed_dist["dir"], bases=(MIRROR_A,))
        device = d.device("bd7a", built_in=(MIRROR_A,), transport=transport,
                          installed_data_version=int(d.signed_dist["assets_base_version"] or 0))
        seed_game_root(device, {"CSV/DRILL.CSV": "旧\n", "resources/已下架.png": "PNG-old"})
        _i, _pr, _w, offer = client_check_now(device, d.now)
        d.expect("段①：块级件缺席 ⇒ 选文件级", KS.data_kind_names[1], offer["dataKind"])
        out = client_download(device, offer["dataArtifact"], d.now)
        d.expect("段①：下载成功", True, out["file"] is not None)
        if out["file"] is None:
            return
        stage(device, out["file"], int(d.signed_dist["assets_version"] or 0))
        kind, _detail = client_apply_staged(device)
        d.expect("段①：文件级包应用成功", "applied", kind)
        d.expect("段①：data-version 升档", int(d.signed_dist["assets_version"] or 0),
                 device.installed_data_version)
        # 段②：块级与文件级都因基线不符不够格 ⇒ 落全量
        transport2, _m2 = d.serve(dist["dir"], bases=(MIRROR_A,))
        device2 = d.device("bd7b", built_in=(MIRROR_A,), transport=transport2,
                           installed_data_version=19)
        seed_game_root(device2, {"CSV/DRILL.CSV": "旧\n"})
        _i2, _pr2, _w2, offer2 = client_check_now(device2, d.now)
        d.expect("段②：基线不符 ⇒ 两档差量都不够格 ⇒ 全量", KS.data_kind_names[2],
                 offer2["dataKind"])
        out2 = client_download(device2, offer2["dataArtifact"], d.now)
        d.expect("段②：下载成功", True, out2["file"] is not None)
        if out2["file"] is None:
            return
        stage(device2, out2["file"], int(dist["assets_version"]))
        kind2, _detail2 = client_apply_staged(device2)
        d.expect("段②：全量包应用成功", "applied", kind2)
        d.expect("段②：data-version 升到 21", int(dist["assets_version"]),
                 device2.installed_data_version)
        # 段③：文件级也缺席（清单只发全量）⇒ 落全量
        full_only = build_dist(d.sub("dist-fullonly-chain"), sign_key=d.sign_key,
                               min_supported="15", version_code="24", version_name="0.2.46",
                               assets_version="24", with_delta=False)
        transport3, _m3 = d.serve(full_only["dir"], bases=(MIRROR_A,))
        device3 = d.device("bd7c", built_in=(MIRROR_A,), transport=transport3,
                           installed_data_version=20)
        seed_game_root(device3, {"CSV/DRILL.CSV": "旧\n"})
        _i3, _pr3, _w3, offer3 = client_check_now(device3, d.now)
        d.expect("段③：文件级也缺席 ⇒ 全量", KS.data_kind_names[2], offer3["dataKind"])
        out3 = client_download(device3, offer3["dataArtifact"], d.now)
        d.expect("段③：下载成功", True, out3["file"] is not None)
        if out3["file"] is None:
            return
        stage(device3, out3["file"], int(full_only["assets_version"]))
        kind3, _detail3 = client_apply_staged(device3)
        d.expect("段③：全量包应用成功", "applied", kind3)

    def bd8():
        """块级应用的红档：基线不符 / 缺旧文件 / 越界块引用 / 重复目标 / 头形状 ⇒ 拒或败。"""
        dist = block_dist()
        if dist is None:
            return
        patch_names, payloads = patch_entries(dist)
        target = patch_names[0]
        rel = target[len(KS.bd_patch_prefix):]
        fields, _body = emit_update.split_block_patch(payloads[target])
        base_files = root_to_files(dist["corpus_old"])

        def fresh_device(tag, files):
            device = d.device(tag, built_in=(MIRROR_A,),
                              installed_data_version=int(dist["base_version"]))
            seed_game_root(device, files)
            return device

        def crafted_zip(tag, overrides):
            path = os.path.join(d.sub(tag), os.path.basename(dist["block_path"]))
            _rewrite_zip(dist["block_path"], path, lambda n, data: overrides.get(n, data))
            return path

        # ①基线不符：盘上被玩家改过 ⇒ 基线闸红，字节一个都没落
        tampered = dict(base_files)
        tampered[rel] = tampered[rel] + "（玩家改过）\n".encode("utf-8")
        device = fresh_device("bd8a", tampered)
        stage(device, dist["block_path"], int(dist["assets_version"]))
        kind, detail = apply_block_delta_package(device)
        d.expect("基线不符 ⇒ failed（不是静默盖写）", "failed", kind)
        d.expect("红的话点名基线不符", True, "基线" in detail)
        d.expect("盘上那份没被盖", tampered[rel],
                 open(os.path.join(device.game_root, *rel.split("/")), "rb").read())
        d.expect("data-version 没动", int(dist["base_version"]), device.installed_data_version)
        # ②旧文件缺席：补丁引用的目标不在盘上 ⇒ failed
        missing = {k: v for k, v in base_files.items() if k != rel}
        device = fresh_device("bd8b", missing)
        stage(device, dist["block_path"], int(dist["assets_version"]))
        kind, detail = apply_block_delta_package(device)
        d.expect("旧文件不在盘上 ⇒ failed（BlockDeltaAssetTree:463-467）", "failed", kind)
        d.expect("红的话点名缺的文件", True, rel in detail)
        # ③越界块引用：补丁体里的 C 引用越过旧文件长度 ⇒ 重放失败（两侧判据同源）
        head, sep, body = payloads[target].partition(b"\n\n")
        c_index = body.find(KS.bd_op_copy.encode("ascii"))
        huge = (2 ** 32 - 1).to_bytes(4, "big")
        device = fresh_device("bd8c", base_files)
        stage(device, crafted_zip("blockzip-crafted",
                                  {target: head + sep + body[:c_index + 1] + huge
                                   + body[c_index + 5:]}), int(dist["assets_version"]))
        kind, _detail = apply_block_delta_package(device)
        d.expect("越界块引用 ⇒ 重放失败（failed）", "failed", kind)
        d.expect("发布侧同读也判 None（判据同源）", None,
                 emit_update.reassemble_block_patch(base_files[rel], body[c_index:],
                                                    int(fields["block"]))[0])
        # ④重复目标：同一目标两条补丁 ⇒ 整包拒装（BlockDeltaAssetTree:114-117）
        dup = os.path.join(d.sub("blockzip-dup"), os.path.basename(dist["block_path"]))
        with zipfile.ZipFile(dist["block_path"]) as src, \
                zipfile.ZipFile(dup, "w", compression=zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.is_dir():
                    continue
                dst.writestr(info.filename, src.read(info))
                if info.filename == target:
                    dst.writestr(target, src.read(info))  # 同名条目写两遍（zip 允许重复名）
        device = fresh_device("bd8d", base_files)
        stage(device, dup, int(dist["assets_version"]))
        kind, _detail = apply_block_delta_package(device)
        d.expect("同一目标两条补丁 ⇒ 整包拒装", "rejected", kind)
        # ⑤头部形状坏：fmt 不认识 ⇒ 拒装（BlockDeltaAssetTree:118-121）
        broken = head.replace(b"fmt 1\n", b"fmt 9\n", 1) + sep + body
        device = fresh_device("bd8e", base_files)
        stage(device, crafted_zip("blockzip-broken", {target: broken}),
              int(dist["assets_version"]))
        kind, _detail = apply_block_delta_package(device)
        d.expect("补丁头部形状不对 ⇒ 整包拒装", "rejected", kind)

    for title, fn in (
        ("BD1 发布侧：块级包真生产函数生成 + 清单三件套 + --verify-only 复核", bd1),
        ("BD2 发布侧复核必红：篡改一个块 / 改一处旧块引用", bd2),
        ("BD3 设备侧判据读数：判式链 + 形状闸用生产解析器钉", bd3),
        ("BD4 资格矩阵扩档：四档互斥、回落次序同向（逐格）", bd4),
        ("BD5 传输：mock 镜像下发块级包 → .part 断点续传 → 应用与 v21 逐字节相等", bd5),
        ("BD6 传输：块级包摘要不符必拒", bd6),
        ("BD7 端到端回落链：块级缺席⇒文件级⇒文件级也缺席⇒全量", bd7),
        ("BD8 块级应用红档：基线不符 / 缺旧文件 / 越界块 / 重复目标 / 头形状", bd8),
    ):
        d.run(title, fn)


def beats_receipt(d):
    d.section("安装回执（三类回执在演练层的可观测点）")

    def i1():
        kind, message = install_outcome_for("STATUS_SUCCESS", None)
        receipt = install_deliver_for(kind, message)
        d.expect("成功：分类", "Success", kind)
        d.expect("成功：通知标题", "更新安装完成", receipt["title"])
        d.expect("成功：不给「再装一次」的点击", False, receipt["retry"])

    def i2():
        kind, message = install_outcome_for("STATUS_FAILURE_ABORTED", "玩家自己按了退")
        receipt = install_deliver_for(kind, message)
        d.expect("用户取消：分类", "UserCancelled", kind)
        d.expect("用户取消：通知标题", "安装已取消", receipt["title"])
        d.expect("用户取消：带重试入口", True, receipt["retry"])
        d.expect("用户取消：系统原话留在回执里", "玩家自己按了退", message)

    def i3():
        kind, message = install_outcome_for("STATUS_FAILURE_INVALID", None)
        receipt = install_deliver_for(kind, message)
        d.expect("失败：分类", "Failed", kind)
        d.expect("失败：摘要取的是表里那句玩家能照着做的",
                 "安装包无效或已损坏：回游戏里重新下载一次再装", message)
        d.expect("失败：通知正文带前缀", True, receipt["body"].startswith("安装失败："))
        d.expect("失败：带重试入口", True, receipt["retry"])

    def i4():
        kind, message = install_outcome_for("STATUS_FUTURE_SDK_CODE", "空间不够了（系统原话）")
        d.expect("表里没有的状态码也落 Failed（else 臂）", "Failed", kind)
        # 兜底句按表里的通用模板 + 错误码断言，不把某一句具体措辞钉进演练：
        # U7b 已经把「安装失败（错误码 N）」改成表条目「原因不明（错误码 {0}）」，
        # 演练照句子断言就会在每次改话术后假红；照模板断言才跟生产同一条真相。
        expect_base = KS.copy_zh[KS.install_generic_key].replace("{0}", "STATUS_FUTURE_SDK_CODE")
        d.expect("兜底摘要用表的通用模板并带上错误码", True, expect_base in message)
        d.expect("系统原话原样附带不翻译", True, "空间不够了（系统原话）" in message)

    def i5():
        kind, _message = install_outcome_for("STATUS_PENDING_USER_ACTION", None)
        d.expect("中间态不进 of() 的判据：演练按未知码落 Failed（真机上由接收器单列）",
                 "Failed", kind)
        d.skip("会话真的提交成功（PackageInstaller 落盘 commit）",
               "需要真机：本机 adb devices 为空，演练只能驱动到「分类 + 回执面形状」这一层")

    def i6():
        name = install_outcome_for("STATUS_SUCCESS", None)[0]
        device = d.device("i6", built_in=(MIRROR_A,))
        device.record_event("install_requested", kind="apk")
        device.record_event("download_finished", bytes=name and 1)
        device.flush_telemetry()
        d.expect("回执三分类都对应到 core 认识的事件名（安装请求那一拍）", True,
                 "install_requested" in [n for n, _f in device.events])

    for title, fn in (("I1 回执①：成功", i1), ("I2 回执②：用户取消", i2),
                      ("I3 回执③：失败（带原因摘要与重试入口）", i3),
                      ("I4 未知状态码兜底 + 系统原话不翻译", i4),
                      ("I5 中间态（等用户动作）不落终态分类", i5),
                      ("I6 回执面的事件名过 core 白名单", i6)):
        d.run(title, fn)


def beats_telemetry(d):
    d.section("遥测（通道未配置 = 一个字节都不出网）")

    def t1():
        device = d.device("t1", built_in=(MIRROR_A,))
        device.record_event("check_started")
        device.record_event("offer_shown")
        consumed = device.flush_telemetry()
        d.expect("未配置端点：flush 视为已消费（不留缓冲）", True, consumed)
        d.expect("未配置端点：一次 POST 都没发", 0, len(device.telemetry_posts))

    def t2():
        device = d.device("t2", built_in=(MIRROR_A,), telemetry_endpoint="http://明文.invalid/x")
        device.record_event("check_started")
        device.flush_telemetry()
        d.expect("明文 http 端点同样零出网", 0, len(device.telemetry_posts))
        d.expect("isHttpsEndpoint 判明文为关", False,
                 is_https_endpoint("http://明文.invalid/x"))

    def t3():
        device = d.device("t3", built_in=(MIRROR_A,),
                          telemetry_endpoint="https://drill-telemetry.invalid/x")
        device.record_event("check_ok")
        device.flush_telemetry()
        d.expect("https 端点：发了一批", 1, len(device.telemetry_posts))
        d.expect("body 形状照 UpdateTelemetrySink.encodeBody",
                 '{"kind":"update-funnel","events":[check_ok]}', device.telemetry_posts[0])

    def t4():
        used = {"check_started", "check_ok", "offer_shown", "install_requested",
                "download_finished", "download_failed", "assets_applied"}
        d.expect("演练各拍用到的事件名全在 core 的白名单里", [], sorted(used - KS.telemetry_events))
        device = d.device("t4", built_in=(MIRROR_A,),
                          telemetry_endpoint="https://drill-telemetry.invalid/x")
        try:
            device.record_event("not_a_real_event")
            d.expect("白名单外的事件名当场炸（不许静默发出去）", "没有炸", "炸了")
        except AssertionError:
            d.expect("白名单外的事件名当场炸（不许静默发出去）", "炸了", "炸了")

    for title, fn in (("T1 通道未配置 ⇒ 零出网", t1), ("T2 明文端点 ⇒ 零出网", t2),
                      ("T3 https 端点：body 形状对得上", t3),
                      ("T4 事件名白名单（core 的 validate 口径）", t4)):
        d.run(title, fn)


BEAT_GROUPS = (beats_publish_side, beats_signature, beats_manifest, beats_rollout,
               beats_mirror_health, beats_download, beats_apply, beats_block_delta,
               beats_receipt, beats_telemetry)


def run_suite(d):
    d.expect("演练读到的内置公钥条数（k1 现役 + 轮换空位）", 1, len(KS.builtin_keys))
    for group in BEAT_GROUPS:
        group(d)


# =============================================================================
# 7. 入口
# =============================================================================


def resolve_sign_key(explicit):
    """私钥只接路径：内容不进日志、不进任何产物。缺省找共享根里那把 k1。"""
    if explicit:
        return explicit
    root = os.environ.get("ERATW_ROOT", "")
    candidate = os.path.join(root, "local.update-signing", "k1-private.pk8.pem") if root else ""
    return candidate if candidate and os.path.isfile(candidate) else ""


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="更新通道发布侧↔设备侧的本地全链演练（CONTRACT-UPD2 §2 U9）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--selftest", action="store_true",
                      help="合成场景跑完整逐拍（验收主入口；不需要网络/真机/语料/gradle）")
    mode.add_argument("--drill", action="store_true",
                      help="完整演练：保留场景目录便于取证")
    mode.add_argument("--verify-only", metavar="DIR", default=None,
                      help="只体检现成发布目录（不产生新 dist），逐拍打印读数")
    parser.add_argument("--sign-key", default="",
                        help=f"k1 私钥 PEM 路径（缺省找共享根 {emit_update.SIGNING_KEY_DIR}/ 下那把；"
                             "找不到就按未签名清单演练，验签拍改用 ManifestSignatureTest 的黄金签名对）")
    parser.add_argument("--work", default="", help="场景与设备目录（缺省 build/update-drill）")
    parser.add_argument("--transport", choices=("http", "inproc"), default="http",
                        help="mock 镜像的传输档：http = 真回环 socket（缺省）；inproc = 进程内直调")
    args = parser.parse_args(argv)

    global KS
    try:
        KS = KotlinSource()
    except KeyError as exc:
        die(f"生产源读数失败：{exc}")
    work = args.work or os.path.join(ANDROID_ROOT, "build", "update-drill")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work, exist_ok=True)
    verify_dir = None
    if args.verify_only is not None:
        verify_dir = args.verify_only or os.path.join(ANDROID_ROOT, "build", "update-dist")
        if not os.path.isfile(os.path.join(verify_dir, MANIFEST_FILE_NAME)):
            die(f"--verify-only 的目录里没有清单：{verify_dir}")
    sign_key = resolve_sign_key(args.sign_key) if args.verify_only is None else ""
    if args.sign_key and not os.path.isfile(args.sign_key):
        die(f"--sign-key 指向的私钥不存在：{args.sign_key}")
    if args.verify_only is None and not sign_key:
        log("提示：本机没有 k1 私钥 ⇒ 签名链那一拍改用 ManifestSignatureTest 的黄金签名对；"
            "发布侧「现签」那几拍会记 SKIP。")

    drill = Drill(work, sign_key_path=(args.sign_key or sign_key),
                  serve_http=(args.transport == "http"), existing_dist=verify_dir)
    drill.signed_dist, drill.unsigned_dist = drill.prepare_dists()
    print(f"更新通道本地演练 —— 模式={_mode_name(args)}  传输={drill.transport_kind}  "
          f"私钥={'有' if drill.sign_key else '无（走黄金签名对）'}")
    print(f"场景目录={work}")
    try:
        run_suite(drill)
    finally:
        drill.stop()
        if args.selftest:
            shutil.rmtree(work, ignore_errors=True)

    print("")
    print("=" * 78)
    if drill.failures:
        print(f"**首差：{drill.failures[0]}**；共 {len(drill.failures)} 条不合格：")
        for line in drill.failures:
            print("  - " + line)
    if drill.skips:
        print(f"如实登记的 SKIP {len(drill.skips)} 条：")
        for line in drill.skips:
            print("  - " + line)
    print(f"读数：{drill.beat_no} 拍 / {drill.checks} 条断言 / 不合格 {len(drill.failures)} / "
          f"SKIP {len(drill.skips)}")
    if drill.failures:
        return 1
    print(f"update drill ok ({drill.checks} checks in {drill.beat_no} beats, "
          f"transport={drill.transport_kind})")
    return 0


def _mode_name(args):
    if args.selftest:
        return "selftest"
    if args.drill:
        return "drill"
    return "verify-only"


if __name__ == "__main__":
    sys.exit(main())
