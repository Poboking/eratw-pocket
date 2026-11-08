# -*- coding: utf-8 -*-
"""APK 本体的「真实版本」与「真实签名章」读取工具（自动更新发布侧专用）。

为什么单独一个文件：这两个读数决定玩家能不能装上，而它们**不能来自文件名，也不能来自
`app/build.gradle.kts`**。清单里的 `apk-version-code` 是客户端判「要不要再下一遍」的唯一依据；
包内实际 versionCode 只要比它小一点，玩家就会每次检查都看到「有新版」→ 下几百 MiB → 装上还是老
码 → 下次又催，无限循环白耗流量，而屏上没有任何痕迹。这类错只能在这一层堵死（与本仓
「漏带上报参数不报错」同源，见 `devtools/pack_release.py` 的教训记录）。
"""
from __future__ import annotations

import glob
import os
import re
import subprocess

# debug 章指纹（与 `pack_release.py` 的 `PINNED_DEBUG_SHA256` 同值）：debug 章与发布章互斥，
# 装过 debug 章包的设备**装不上**发布包（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
# 实测（2026-11-01）：历史分发包里有相当一批就是 debug 章签的。
PINNED_DEBUG_SHA256 = (
    "76b0aa436970ce200f05b97d3c268872aa1740da"
    "3c0c863f6190f0078fab4085"
)

# 发布章指纹（`CN=eratw-android-port`）；与 `pack_release.PINNED_RELEASE_SHA256` 同值。
PINNED_RELEASE_SHA256 = (
    "e2fad8c6342ca97a54826ad8beb45b1f4f0ef0ba"
    "b5aad7bb8ac398efd645a181"
)

_SHA256_COLON = re.compile(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")


def build_tools_version(path):
    """从 build-tools 路径里取版本段并解析成整数元组；解析不出来返回 None（排最后）。

    `~/asdk/build-tools/34.0.0/aapt2.exe` → `(34, 0, 0)`；`9.0.4` → `(9, 0, 4)`。
    非数字目录（`canary`、`preview` 之类）返回 None：宁可当「认不出版本」排到末位，
    也不要拿它去和数字版本比字符串。
    """
    seg = os.path.basename(os.path.dirname(path))
    m = re.match(r"(\d+(?:\.\d+)*)", seg)
    if not m:
        return None
    return tuple(int(x) for x in m.group(1).split("."))


def pick_aapt2(candidates):
    """按**版本段数值**挑最高的 build-tools；空表返回 None。

    为什么不能用 `max(candidates)`（字符串序）：SDK 里只要还留着老平台的 build-tools
    ——`9.0.4`、`8.1.0` 这种（装过旧 Android Studio 的机器上很常见，卸不掉）——
    字符串序里 `'9' > '3'`，`build-tools/9.0.4/aapt2.exe` 就会**赢过** `34.0.0`。
    拿错的那一档不是「稍旧一点」而是可能读不动新 APK：aapt2 对未知的资源类型/
    `versionCode` 编码会直接报错或输出少字段的 badging，本函数的调用方
    [version_from_apk_binary] 于是返回 None ⇒ 清单里没有 `apk-version-code` ⇒
    客户端每次检查都看不到「有新版」，安静地不发更新（与本文件类头立的教训同一条）。
    并列（同一版本号既有 `aapt2` 又有 `aapt2.exe`）按路径字符串定序，Windows 只会命中
    `.exe` 那一条，POSIX 只会命中无后缀那一条，不靠运气。
    """
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda p: (build_tools_version(p) or (-1,), p.replace("\\", "/")),
    )


def find_aapt2():
    """找 SDK 的 aapt2（读 APK 本体的 versionCode/versionName）。找不到返回 None。

    取 build-tools **数值**版本最大的一档（判据见 [pick_aapt2]，别改回字符串 `max()`）。
    SDK 位置按 `ANDROID_HOME` > `ANDROID_SDK_ROOT` > `~/asdk` 顺序找（本机实测装在 `~/asdk`，AGENTS.md 没把它当共享根的一部分）。
    """
    candidates = []
    for root in (os.environ.get("ANDROID_HOME", ""),
                 os.environ.get("ANDROID_SDK_ROOT", ""),
                 os.path.expanduser("~") + "/asdk"):
        if root and os.path.isdir(root):
            candidates += glob.glob(os.path.join(root, "build-tools", "*", "aapt2.exe"))
            candidates += glob.glob(os.path.join(root, "build-tools", "*", "aapt2"))
    return pick_aapt2(candidates)


def version_from_apk_binary(apk_path):
    """从 APK 本体读 `(versionCode, versionName)`；读不出返回 None。"""
    aapt2 = find_aapt2()
    if not aapt2:
        return None
    try:
        proc = subprocess.run([aapt2, "dump", "badging", apk_path],
                              capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"versionCode='(\d+)'\s+versionName='([^']*)'", proc.stdout or "")
    if not m:
        return None
    return m.group(1), m.group(2)


def find_keytool():
    """找 JDK 的 keytool（读 APK 签名章用）。找不到返回 None。

    顺序：`JAVA_HOME` > PATH 里的 `keytool` > 共享根 `.tooling/jdk-*` > `~/.jdks`。
    为什么要有后两档：出包与发版经常在没导 `JAVA_HOME` 的 shell 里手敲，那种时候
    读不出章就等于清单里没有 `apk-cert-sha256` —— 客户端失去「装不上」的预检，
    而脚本照样全绿。宁可多找两处，也不要静默少一个字段。
    """
    exe = "keytool.exe" if os.name == "nt" else "keytool"
    java_home = os.environ.get("JAVA_HOME", "")
    if java_home:
        hit = os.path.join(java_home, "bin", exe)
        if os.path.isfile(hit):
            return hit
    for root in (os.environ.get("ERATW_ROOT", ""),
                 os.path.expanduser("~") + "/.jdks"):
        if root and os.path.isdir(root):
            hits = glob.glob(os.path.join(root, ".tooling", "jdk*", "bin", exe))
            hits += glob.glob(os.path.join(root, "jdk*", "bin", exe))
            if hits:
                return max(hits)
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        hit = os.path.join(entry, exe)
        if os.path.isfile(hit):
            return hit
    return None


def apk_cert_sha256(apk_path):
    """APK 签名证书的 SHA-256（小写、无冒号）；拿不到返回空串。

    写进清单是给客户端做**覆盖升级预检**的：章不一致的设备装不上新包，这件事必须在
    下载前就说破，不能让玩家下完几百 MiB 才发现。
    """
    keytool = find_keytool()
    if not keytool:
        return ""
    try:
        proc = subprocess.run([keytool, "-printcert", "-jarfile", apk_path],
                              capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=300)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    out = (proc.stdout or "") + (proc.stderr or "")
    m = _SHA256_COLON.search(out)
    return m.group(0).replace(":", "").lower() if m else ""


def cert_label(sha256_hex):
    """把指纹翻译成人话（发版时看得懂的那句）：发布章 / debug 章 / 未知章。"""
    if not sha256_hex:
        return "读不出"
    if sha256_hex == PINNED_RELEASE_SHA256:
        return "发布章（可与线上包互相覆盖升级）"
    if sha256_hex == PINNED_DEBUG_SHA256:
        return "debug 章（与发布章互斥：装过发布包的设备装不上它，反之亦然）"
    return "未知章（既不是钉住的发布章也不是 debug 章 —— 先确认这是你要的签名）"
