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
python devtools/pack_release.py                  # 全量包（带资源），上报开
python devtools/pack_release.py --skip-assets    # 小包（复用设备上已解包的数据）
python devtools/pack_release.py --offline        # 明确出「不上报」的包（会大声警告）
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
   也要点名；
4. **签名章** —— 回读 APK 里签名证书的 SHA-256 指纹，与 `GRADLE_USER_HOME/gradle.properties`
   里那把 keystore 的证书指纹对账。`app/build.gradle.kts` 的 `signingConfigs.releaseLocal`
   在没配 `-PeratwRelease*` 时**静默退回 debug keystore**（跟上报开关同一个失败形状），
   所以这一项必须验；要刻意出 debug 章的内测包传 `--allow-debug-sign`；
5. **端点活体** —— 拿包里的令牌去 `GET /reports`：回 403 = 令牌与端点不符（这种包上报
   等于关着，前两步却全绿），回 200 = 这条链真通；连不上（无网/没代理）只警告不阻断；
6. **INTERNET 权限** —— 配了端点就必须能在合并后的 manifest 里看到
   `android.permission.INTERNET`。2026-10-27 首个真机包就是栽在这：串都进包了、五步全绿，
   但没这条权限，真机上一上报就 SecurityException（被 runCatching 吞成一行 logcat），
   端点一份也收不到，玩家侧零痕迹。

1-2 不过 = 这个包不会上报；3 不过 = 包的形状与预期不符；4 不过 = 章不对，老包不能覆盖升级；
5 回 403 = 令牌错，别发；6 不过 = 装上去也发不出来。

出完把 APK 复制一份到 `build/release-artifacts/eratw-<versionName>+<versionCode>-<git7>-
<full|noassets|skipassets>-<sha前8>.apk` 并配 `.sha256.txt`（gradle 输出目录会被下次构建
覆盖，归档名里带版本与产物指纹才认得出哪个包是哪个提交出的）。`--no-archive` 跳过复制。

本脚本只做「带参数 + 体检 + 归档」，不碰构建逻辑、不改任何 gradle 配置。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
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

# 发布章的四个键（与 app/build.gradle.kts 的 signingConfigs.releaseLocal 同名）。
# 取值顺序与 gradle 一致：命令行 -P > GRADLE_USER_HOME/gradle.properties > 工程 gradle.properties。
SIGN_KEYS = ("eratwReleaseStoreFile", "eratwReleaseStorePassword",
             "eratwReleaseKeyAlias", "eratwReleaseKeyPassword")

# 上报端点缺省值：**不在代码里写死**，从 local.report.properties 或环境变量取
# （ERATW_REPORT_ENDPOINT / ERATW_REPORT_TOKEN，或 local.report.properties 的
# endpoint / token 两键）。两者都缺时出包照常、上报链关闭并大声警告。
DEFAULT_ENDPOINT = ""


def eratw_root() -> str:
    """解析本地工具链/语料的共享根。

    优先读 `ERATW_ROOT` 环境变量；兼容本机的历史布局（按主机名），两机之外交给
    调用方判断（只在需要工具链/语料时才用得上）。
    """
    env_root = os.environ.get("ERATW_ROOT")
    if env_root and os.path.isdir(env_root):
        return env_root
    host = os.environ.get("COMPUTERNAME") or os.environ.get("HOSTNAME") or ""
    host = host.lower()
    if host.startswith("haruki"):
        root = "C:/DSH/eratw-安卓移植计划"
    elif host.startswith("homejia"):
        root = "D:/DSH/eratw-安卓移植计划"
    else:
        return ""
    return root if os.path.isdir(root) else ""


def toolchain_env() -> dict:
    """补 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / ERATW_GAMEDIR —— 交互 shell 里 source 过 env.sh 就不动它。

    `ERATW_GAMEDIR` 不是可选项：`app/build.gradle.kts` 用「`-PeratwGameRoot` 或环境变量
    ERATW_GAMEDIR」决定**要不要把游戏资源搬进包**。缺了它不报错，只是静默退化成
    「只随包 erbc.v1」的小包（376 MiB vs 完整包 743 MiB）—— 2026-10-27 实测踩过。
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
    if root and not env.get("ERATW_GAMEDIR"):
        game = os.path.join(root, "eratw-画蛇添足版eratw4.980")
        if os.path.isdir(game):
            env["ERATW_GAMEDIR"] = game
            env.setdefault("ERATW_CORPUS", os.path.join(game, "ERB"))
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


# ---------------------------------------------------------------- 构建

def gradle_cmd(tasks: list) -> list:
    """Windows 下 .bat 必须经 cmd 拉起；其余平台直接跑 wrapper（AGENTS §5：统一 ./gradlew）。"""
    wrapper = os.path.join(APP_ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    if os.name == "nt":
        return ["cmd", "/c", wrapper] + tasks
    return [wrapper] + tasks


def run_build(endpoint: str, token: str, skip_assets: bool, dry_run: bool = False,
              game_root: str = "") -> int:
    tasks = ["--configure-on-demand", ":app:assembleRelease"]
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
    cmd = gradle_cmd(tasks)
    printable = " ".join(cmd).replace(token, "***") if token else " ".join(cmd)
    print(f"[pack] {printable}")
    if dry_run:
        print("[pack] --dry-run：只打印命令，不构建、不体检。")
        return 0
    started = time.time()
    proc = subprocess.run(cmd, cwd=APP_ROOT, env=toolchain_env())
    print(f"[pack] 构建耗时 {time.time() - started:.0f}s，退出码 {proc.returncode}")
    return proc.returncode


# ---------------------------------------------------------------- 体检

def verify_res_values(endpoint: str, token: str) -> list:
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
    return problems


def verify_apk(endpoint: str, token: str) -> list:
    """APK 内的 resources.arsc：两个串真的进了包（进包的直接证据）。"""
    if not os.path.isfile(APK_PATH):
        return [f"找不到 APK：{APK_PATH}"]
    try:
        with zipfile.ZipFile(APK_PATH) as zf:
            arsc = zf.read("resources.arsc")
    except (KeyError, zipfile.BadZipFile) as exc:
        return [f"读不出 APK 里的 resources.arsc：{exc}"]
    problems = []
    if endpoint and endpoint.encode("utf-8") not in arsc:
        problems.append(f"APK 的 resources.arsc 里找不到端点串：{endpoint}")
    if token and token.encode("utf-8") not in arsc:
        problems.append("APK 的 resources.arsc 里找不到令牌串（上报会整体关闭）")
    return problems


def read_package_report() -> dict:
    """读 APK 里 assets/erbc.v1/package.report（出包自述），返回 key→value。"""
    if not os.path.isfile(APK_PATH):
        return {}
    try:
        with zipfile.ZipFile(APK_PATH) as zf:
            raw = zf.read("assets/erbc.v1/package.report").decode("utf-8", "replace")
    except (KeyError, zipfile.BadZipFile):
        return {}
    out = {}
    for line in raw.splitlines():
        if " " in line:
            key, value = line.split(" ", 1)
            out[key.strip()] = value.strip()
    return out


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
        return ["读不出 assets/erbc.v1/package.report（包不完整？）"]
    game_root = rep.get("game-root", "-")
    print(f"[pack] 包型 game-root={game_root} · erbc-source={rep.get('erbc-source', '?')}")
    freshness = rep.get("erbc-freshness", "?")
    print(f"[pack]      staged-entries={rep.get('staged-entries', '?')} · "
          f"staged-bytes={rep.get('staged-bytes', '?')} · "
          f"erbc-freshness={freshness.split(',')[0]}")
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
    return problems


def report_apk() -> None:
    if not os.path.isfile(APK_PATH):
        return
    size = os.path.getsize(APK_PATH)
    mtime = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(APK_PATH)))
    print(f"[pack] APK  {APK_PATH}")
    print(f"[pack] 体积 {size:,} B（{size / 1024 / 1024:.0f} MiB） · mtime {mtime}")
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
    """第 4 步：APK 的章 == 配置里的发布章。

    `signingConfigs.releaseLocal` 没配四个 `-PeratwRelease*` 时**静默**退回
    `~/.android/debug.keystore`（与上报开关同一个失败形状：编译全绿、包照出、装照跑）。
    章错了的代价是事后才知道的：换章 ⇒ 老包不能覆盖升级，只能卸载，存档跟着没。
    """
    env = toolchain_env()
    props = gradle_props()
    store = props.get("eratwReleaseStoreFile", "")
    alias = props.get("eratwReleaseKeyAlias", "")

    apk_fp, apk_dn = apk_signer(env)
    if not apk_fp:
        return ["读不出 APK 的签名证书（keytool -printcert -jarfile 失败：包没 v1 签名？）"]
    print(f"[pack] 签名章 {apk_dn or '(无 DN)'} · SHA-256 {apk_fp[:16]}…")

    if not store:
        if allow_debug_sign:
            print("[pack] 注意：没配发布章，本次按 --allow-debug-sign 放行 = 这是 **debug 章的内测包**，"
                  "不许发给外部玩家。")
            return []
        return [
            "APK 签的是 debug keystore：gradle.properties 里没有 eratwReleaseStoreFile"
            "（`signingConfigs.releaseLocal` 缺省静默退回 ~/.android/debug.keystore）。\n"
            f"     修法：把四个 {', '.join(SIGN_KEYS)} 写进 "
            f"{os.path.join(os.environ.get('GRADLE_USER_HOME') or '~/.gradle', 'gradle.properties')}"
            "（仓外，口令不进命令行）；确实要出 debug 章内测包就加 --allow-debug-sign。"
        ]
    ks_fp, ks_dn = keystore_signer(env, store, alias)
    if not ks_fp:
        return [f"读不出发布章的证书：{store}（口令或 alias 不对？）"]
    if ks_fp != apk_fp:
        return [f"APK 的章与配置的发布章不一致：包 {apk_fp[:16]}…（{apk_dn}） != keystore "
                f"{ks_fp[:16]}…（{ks_dn}）—— 构建没吃到 -PeratwRelease*，或用了别的章"]
    print(f"[pack]      与发布章一致：{store}")
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
    名字里带 git7 与产物 sha8：主人问「这个包是哪个提交出的」时不用回来翻日志。
    """
    name_v, code_v = version_from_gradle()
    sha = apk_digest()
    base = f"eratw-{name_v}+{code_v}-{git_head()}-{kind}-{sha[:8]}"
    dst_dir = out_dir or ARTIFACT_DIR
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, base + ".apk")
    shutil.copy2(APK_PATH, dst)
    with open(os.path.join(dst_dir, base + ".apk.sha256.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"{sha}  {base}.apk\n")
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
    parser.add_argument("--verify-only", action="store_true", help="不构建，只体检已有 APK")
    parser.add_argument("--dry-run", action="store_true", help="只打印将要执行的 gradle 命令")
    parser.add_argument("--allow-debug-sign", action="store_true",
                        help="放行「没配发布章 ⇒ 签成 debug keystore」的包（内测专用，不许外发）")
    parser.add_argument("--allow-stale-erbc", action="store_true",
                        help="放行「随包 erbc 不是当前编译器/当前语料产物」的包（内测专用）")
    parser.add_argument("--no-archive", action="store_true",
                        help="不把 APK 复制进 build/release-artifacts/（省一份 743 MiB 磁盘）")
    parser.add_argument("--out", default="", help="覆盖归档目录（缺省 build/release-artifacts）")
    args = parser.parse_args(argv)

    endpoint, token, origin = resolve_report_config(args)

    if args.dry_run:
        # dry-run 不出包，所以不逼它配齐令牌：配不齐也要能看见命令长什么样。
        run_build(endpoint, token, args.skip_assets, True, args.game_root)
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

    if not args.verify_only:
        code = run_build(endpoint, token, args.skip_assets, False, args.game_root)
        if code != 0:
            print("[pack] 构建失败，没有可发的包。", file=sys.stderr)
            return 1

    kind_problems = report_package_kind(args.skip_assets, args.allow_stale_erbc)
    report_apk()

    problems = [] if args.offline else (
        verify_res_values(endpoint, token) + verify_apk(endpoint, token) +
        verify_internet_permission()
    )
    if problems:
        print("\n[pack] 体检不通过 —— **这个包不会上报，别发出去**：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 3

    # 端点活体：包里那一对「端点 + 令牌」真配上，上报才叫通（前两步只证明串进了包）。
    if not args.offline:
        live = probe_endpoint(endpoint, token)
        if live:
            print("\n[pack] 端点活体检不通过 —— 装上去上报会全灭，这个包别发：", file=sys.stderr)
            for item in live:
                print(f"  - {item}", file=sys.stderr)
            return 3

    sign_problems = verify_signing(args.allow_debug_sign)
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
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
