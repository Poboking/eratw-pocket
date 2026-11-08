#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RUST1 `rust-staging` 车道：Rust 腿产物能否随包（CMP-66 / CMP-74 认账验收）。

要回答的唯一问题：用 `-PeratwErbcDir` 显式 pin 一份由 `erbcc-rs` 编排产出的 `erbc.v1/`，
`:app:stageGameAssets` 的 CMP-66 新鲜度对账（app/build.gradle.kts:257-430，显式 pin 走
`hard=true` ⇒ 任何不符直接 GradleException）与 CMP-74 清单↔随包对账（同文件 1166-1215）
是否**全认账**。

本脚本只**编排 + 读数**：
- 不碰任何 src 目录下的 rs、`app/build.gradle.kts`、`../eraKcore/tools/erbcc/build.gradle.kts`、Kotlin 侧；
- 判据本体一律由 Gradle 自己去算（本脚本只读 `package.report` / 随包 `manifest.txt` 并与磁盘字节复核）；
- 产腿复用已收口的双跑对拍驱动 `devtools/erbcc_rs_parity.py`（import，不重写）。

## 子命令

```text
produce  Rust 腿（可选 --with-kotlin 顺带双跑对拍）产出 erbc.v1/ 到
         ../eraKcore/tools/erbcc-rs/build/rust-staging/<ts>/rust/erbc.v1。
gate1    认账正例：pin 该目录跑 :app:stageGameAssets，要求
         A 档（不给游戏根，源 sha 走 manifest 的 corpus-root 兜底）与
         B 档（给游戏根，源 sha 走 -PeratwGameRoot/ERB 正路，并叠
         -PeratwRequireComplete=1 的正式包口径）都 BUILD SUCCESSFUL，
         `package.report` 的 `erbc-freshness` 以 `verified(` 开头，
         CMP-74 的随包文件集与 manifest `artifacts` 表 + `package` 段逐项对上。
         B 档缺省用**小占位游戏根**（`ERB/` 是被 pin 语料的逐字节副本，五类条目齐）；
         全量档加 `--corpus $ERATW_CORPUS --game-root $ERATW_GAMEDIR`（一趟 ≈30 s，
         产物 ~370 MiB，`--keep` 之外用完即删）。
gate2    反向证明五条：改 compiler-digest / 改某个 source 行的 sha / build-mode 改 limited /
         整行删掉 compiler-digest / 改随包语料根里一个字节 —— 每条都必须被 hard 档拒
         （GradleException，屏上原文回显 + rc）；该拒未拒 = 随包链缺陷，直接报红。
switches 开关纪律探针：`-PeratwAllowStaleErbc=1` 与 `-PeratwRequireComplete=1` 同给
         必须在**配置期**就拒（app/build.gradle.kts:958-963），证明本路读数没靠放行开关糊过去。
selfcheck 脚本保鲜体检（**不跑 Gradle**，秒级）：
         ①禁硬编码 —— 扫自身源码，不许出现 ≥32 位十六进制字面量：digest / sha /
           manifest-format 一律从 pin 的 `manifest.txt` 与 `app/build.gradle.kts` 现读
           （旧版写死过一次，换编译器就误判）；
         ②判据文案同源 —— 闸 2 的屏上片段逐条 grep `app/build.gradle.kts`：打包侧改了
           文案必须先在这里红，而不是等 gate2 拿旧文案去猜；
         ③现读自证 —— 拿入库夹具 `tests/data/rust-manifest/erbc.v1` 当 pin 目录跑
           `check_freshness_accept`：盘上现读的真值必须过，把 digest 前缀 / program sha
           各翻一位必须判红（钉死「比的是盘上字节，不是常量」）；
         ④反向证明档完整 —— `TAMPER_CASES` 恰 5 条、档名唯一、每条破坏面真能实现、
           每个屏上片段都登记了出处（app 文案 / 语料文件名）。
all      selfcheck + gate1 + gate2 + switches，并把读数写进 tests/data/rust-staging/。
```

**历史快照不覆盖**（收口纪律）：`--register` 默认写历史档名；若目标档里记录的 `compiler-digest`
与本机现读不同，或档里带「历史快照」标注 ⇒ 判红并要求 `--register-into <tag>` 另写现状档
（`haruki-staging-accept-small-corpus-<tag>.txt` / `haruki-staging-reject-<tag>.txt` /
`haruki-staging-switches-<tag>.txt`）。旧档正文一个字都不改写，只在头部加标注行。

退出码：`0` 全绿 · `1` 判据红（含该拒未拒 / 该认未认）· `2` 环境不满足（缺 java / classpath /
erbcc.exe / 语料 / gradlew）。

## 口径出处（判据不放宽，逐条带文件:行号）

- CMP-66 version：`app/build.gradle.kts:293-300`（单源 `Version.kt`，见 :168-176）。
- CMP-66 build-mode：`:301-307`（非 `full` 直接拒，受限产物不得随包）。
- CMP-66 compiler-digest：`:309-326`（缺行也拒；重算口径 :206-229 与 erbcc 的
  `ErbcCompilerDigest.kt:40-103` 同式）。
- CMP-66 container-version / 产物自证：`:327-361`。
- CMP-66 源 sha：`:363-427`（语料文件集增删 + 逐文件字节数 + 逐文件 sha256；
  语料根取调用方 `gameRoot/ERB`，取不到才退回 manifest 的 `corpus-root`，:387-395）。
- CMP-74 随包清单：`:1166-1215`（`package-artifact` 逐文件 / `container-artifact` 整容器 /
  `container-shards` + `shard` 逐片；`package.report` 的 `manifest-sha256` 是报告↔清单锚点）。
- 开关互斥：`:956-963`（配置期抛 GradleException）。

## 环境（开发机）

`JAVA_HOME` 指工具链 JDK；本脚本**主动从子进程 env 里剔除** `ERATW_GAMEDIR` / `ERATW_ERBC_DIR`，
免得 daemon 冻住的环境变量把「兜底目录/兜底游戏根」混进对账（那会让 A 档读数不可信）。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)                      # <worktree>/eratw-pocket
# 公开分仓布局：引擎侧工具/夹具在姐妹仓 eraKcore；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(APP_ROOT), "eraKcore"))
sys.path.insert(0, HERE)
import erbcc_rs_parity as parity                      # noqa: E402  已落地的双跑驱动，复用不重写

RS_BUILD = os.path.join(KCORE_ROOT, "tools", "erbcc-rs", "build", "rust-staging")
DATA_OUT = os.path.join(KCORE_ROOT, "tools", "erbcc-rs", "tests", "data", "rust-staging")
STAGING_ROOT = os.path.join(APP_ROOT, "app", "build", "generated", "gameAssets")
GRADLEW_BAT = os.path.join(APP_ROOT, "gradlew.bat")
SMALL_CORPUS = parity.SMALL_CORPUS
FOUR = parity.FOUR
ERBC_DIR_NAME = parity.ERBC_DIR

# 正式包要求的五类条目（app/build.gradle.kts:456-466 `stagedDataDirs`：CSV/resources/sound/
# plugins/bad）；B 档临时游戏根要凑齐它们，`-PeratwRequireComplete` 的完整度闸（:1313-1331）
# 才不是空转——缺 `bad/` 会被挡（实测 rc=1「随包形态不完整：缺 bad/」）。
# 内容是本脚本自造的占位字节，不碰语料/reference。
PLACEHOLDER_DATA_DIRS = ["CSV", "resources", "sound", "plugins", "bad"]

REJECT_PREFIX = "CMP-66 新鲜度对账失败："

APP_GRADLE = os.path.join(APP_ROOT, "app", "build.gradle.kts")
# 入库夹具（只读）：小档 erbc.v1 四件套齐、manifest 里的 compiler-digest 就是本机现读值，
# selfcheck 拿它当 pin 目录证明「判据读的是盘上字节」。
FIXTURE_ERBC = os.path.join(KCORE_ROOT, "tools", "erbcc-rs", "tests", "data",
                            "rust-manifest", "erbc.v1")
HISTORICAL_MARK = "历史快照"          # 旧读数档的标注词：见到就拒绝覆盖
SELF_PATH = os.path.abspath(__file__)
# ≥32 位的连续十六进制串 = sha256 / compiler-digest 的形状；本脚本正文里一条都不许出现
HARD_CODED_HEX_RE = re.compile(r"\b[0-9a-fA-F]{32,}\b")
MANIFEST_FORMAT_RE = re.compile(r"^val\s+eratwManifestFormat\s*=\s*(\d+)\s*$", re.M)
REGISTERED_DIGEST_RE = re.compile(r"(?:compiler-digest |compiler )([0-9a-fA-F]{12,64})")


class EnvFail(Exception):
    """环境不满足 → 退 2。"""


class GateFail(Exception):
    """判据红 → 退 1。"""


def log(msg: str = "") -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------- 现读（禁硬编码）


def live_manifest_format() -> str:
    """随包清单格式从 `app/build.gradle.kts` 的 `eratwManifestFormat` 现读。

    旧版在这里写死 `2`：打包侧升格式（app 的清单写出与 `manifest-format` 上限判据都改）
    时本脚本会「因为期望值还是旧的」而误判。取不到单源就是环境红，不许退回常量。
    """
    if not os.path.isfile(APP_GRADLE):
        raise EnvFail(f"找不到 {APP_GRADLE}：manifest-format 无从现读")
    text = open(APP_GRADLE, "r", encoding="utf-8").read()
    m = MANIFEST_FORMAT_RE.search(text)
    if not m:
        raise EnvFail("app/build.gradle.kts 里找不到 `val eratwManifestFormat = N` 单源"
                      "（manifest-format 判据换了？不许写死数字凑）")
    line_no = text[:m.start()].count("\n") + 1
    return m.group(1)  # 现读值；行号只用于读数行


def live_manifest_field(erbc_dir: str, name: str) -> str:
    """从 pin 目录的 manifest.txt 现读一个字段（digest / 容器版本 / 计数都走这里）。"""
    p = os.path.join(erbc_dir, "manifest.txt")
    if not os.path.isfile(p):
        raise EnvFail(f"pin 目录没有 manifest.txt：{p}")
    v = field_of(open(p, "r", encoding="utf-8").read().splitlines(), name)
    if v is None:
        raise GateFail(f"{p} 没有 {name} 行：无从现读，判据不许退回常量")
    return v


# ---------------------------------------------------------------- 语料 / 产物腿


def produce_rust(corpus: str, out_parent: str, classpath_origin: str | None,
                 tag: str) -> str:
    """Rust 腿真编译一趟，返回 `<out>/erbc.v1` 绝对路径（Windows 反斜杠口径同驱动）。"""
    java = parity.resolve_java()
    classpath, origin = parity.resolve_classpath(classpath_origin)
    exe = parity.resolve_rust_exe(None)
    corpus_w = parity.winpath(corpus)
    if not os.path.isdir(corpus_w):
        raise EnvFail(f"语料目录不存在：{corpus_w}")
    out_w = parity.winpath(out_parent)
    os.makedirs(out_w, exist_ok=True)
    log(f"[{tag}] Rust CLI = {exe}")
    log(f"[{tag}] classpath：{origin}")
    log(f"[{tag}] 语料 = {corpus_w} → 产物 = {out_w}\\{ERBC_DIR_NAME}")
    parity.rust_compile_leg(exe, classpath, corpus_w, out_w, None, tag)
    erbc = os.path.join(out_w, ERBC_DIR_NAME)
    if not os.path.isfile(os.path.join(erbc, "manifest.txt")):
        raise GateFail(f"Rust 腿没产清单：{erbc}")
    if not os.path.isfile(os.path.join(erbc, "program.erbc")):
        raise GateFail(f"Rust 腿没产容器：{erbc}")
    return erbc


def cross_check_kotlin(corpus: str, rust_erbc_parent: str, classpath: str | None) -> None:
    """顺手再跑一趟 Kotlin 腿，证明这次随包用的 Rust 产物与 Kotlin 逐字节相同（闸 4 同口径）。"""
    java = parity.resolve_java()
    cp, _ = parity.resolve_classpath(classpath)
    k_parent = rust_erbc_parent + "-kotlin"
    shutil.rmtree(k_parent, ignore_errors=True)
    os.makedirs(k_parent)
    parity.kotlin_compile_leg(java, cp, parity.winpath(corpus), parity.winpath(k_parent),
                              None, "staging")
    parity.compare_dirs(k_parent, rust_erbc_parent, None)


# ---------------------------------------------------------------- 临时游戏根


def make_temp_game_root(corpus: str, root_dir: str) -> str:
    """造一个**小**游戏根：`ERB/` 是 pin 产物登记的那份语料的逐字节副本，五类条目 + 配置占位齐。

    存在的意义：`verifyErbcFreshness` 的源 sha 优先用调用方给的 `<gameRoot>/ERB`
    （app/build.gradle.kts:387、1054），B 档因此走的是**真实随包路径**而不是 corpus-root 兜底；
    五类条目凑齐后 `-PeratwRequireComplete` 的完整度闸才真的执行（:1313-1331）。
    """
    game = os.path.join(root_dir, "game")
    erb = os.path.join(game, "ERB")
    shutil.rmtree(game, ignore_errors=True)
    os.makedirs(erb)
    src = parity.winpath(corpus)
    for name in sorted(os.listdir(src)):
        p = os.path.join(src, name)
        if os.path.isfile(p) and name.lower().endswith((".erb", ".erh")):
            shutil.copy2(p, os.path.join(erb, name))
    for name in PLACEHOLDER_DATA_DIRS:
        d = os.path.join(game, name)
        os.makedirs(d)
        with open(os.path.join(d, "placeholder.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write("rust-staging 占位：只为凑齐 -PeratwRequireComplete 的五类条目\n")
    with open(os.path.join(game, "emuera.config"), "w", encoding="utf-8", newline="\n") as f:
        f.write("rust-staging placeholder emuera.config\n")
    return game


# ---------------------------------------------------------------- Gradle 编排


def decode_gradle(raw: bytes) -> str:
    """Gradle 控制台在 Windows 上按平台码写（本机 cp936）；两种候选都试，认得出中文的那份为准。"""
    cands: list[str] = []
    for enc in ("utf-8", "gbk"):
        try:
            cands.append(raw.decode(enc))
        except Exception:
            try:
                cands.append(raw.decode(enc, errors="strict"))
            except Exception:
                pass
    for s in cands:
        if "对账" in s or "新鲜度" in s:
            return s
    if cands:
        return cands[0]
    return raw.decode("utf-8", errors="replace")


def gradle_stage(erbc_dir: str, game_root: str | None, extra_props: list[str],
                 log_path: str) -> tuple[int, str]:
    """跑一次 :app:stageGameAssets，返回 (rc, 原样文案)。

    - 一定带 `--configure-on-demand`（本机工程路径含非 ASCII，AGP 配置期会拒）；
    - 先删 staging 产物目录：否则命中 `UP-TO-DATE` 时 `doLast` 整个跳过，
      对账根本没跑（app/build.gradle.kts:994-1016 那段注释就是这个坑的案卷）；
    - 从 env 里剔掉 ERATW_GAMEDIR / ERATW_ERBC_DIR，读数只由 -P 参数决定。
    """
    if not os.path.isfile(GRADLEW_BAT):
        raise EnvFail(f"找不到 {GRADLEW_BAT}")
    shutil.rmtree(STAGING_ROOT, ignore_errors=True)
    argv = ["cmd.exe", "/c", GRADLEW_BAT, "--configure-on-demand", "--console=plain",
            ":app:stageGameAssets", f"-PeratwErbcDir={parity.winpath(erbc_dir)}"]
    if game_root is not None:
        argv.append(f"-PeratwGameRoot={parity.winpath(game_root)}")
    argv += extra_props
    env = dict(os.environ)
    env.pop("ERATW_GAMEDIR", None)
    env.pop("ERATW_ERBC_DIR", None)
    t0 = time.monotonic()
    r = subprocess.run(argv, cwd=APP_ROOT, capture_output=True, env=env)
    log(f"  stageGameAssets 墙钟 {elapsed(t0)}（rc={r.returncode}）")
    text = decode_gradle(r.stdout) + "\n" + decode_gradle(r.stderr)
    with open(log_path, "wb") as f:
        f.write(r.stdout)
        if r.stderr:
            f.write(b"\n---- stderr ----\n")
            f.write(r.stderr)
    if "UP-TO-DATE" in text and "stageGameAssets UP-TO-DATE" in text:
        raise GateFail(f"stageGameAssets 命中 UP-TO-DATE ⇒ 对账没执行，读数无效（{log_path}）")
    return r.returncode, text


def build_succeeded(text: str) -> bool:
    return "BUILD SUCCESSFUL" in text


def reject_lines(text: str) -> list[str]:
    """抓 CMP-66 拒绝文案（含 `* What went wrong` 段落，原样贴用）。"""
    out = []
    lines = text.splitlines()
    for i, l in enumerate(lines):
        if REJECT_PREFIX in l:
            out.append(l.rstrip())
            for j in range(i + 1, min(i + 6, len(lines))):
                s = lines[j].strip()
                if s.startswith("FAILURE:") or s.startswith("* Try:"):
                    break
                if s:
                    out.append("    " + s)
            break
    return out


def require_rejected(case: str, rc: int, text: str, expect_phrases: list[str],
                     log_path: str) -> str:
    """反向证明的判据：**该拒必须真拒**（rc≠0 + hard 档文案 + 点名该判据）。"""
    if build_succeeded(text):
        raise GateFail(
            f"反向证明「{case}」**没被拒**：BUILD SUCCESSFUL（rc={rc}）。"
            f"这是随包链的真实缺陷，不是可接受差异。日志：{log_path}"
        )
    if rc == 0:
        raise GateFail(f"反向证明「{case}」rc=0 却没有 BUILD SUCCESSFUL，读数不可信：{log_path}")
    if REJECT_PREFIX not in text:
        raise GateFail(
            f"反向证明「{case}」红了，但**不是** CMP-66 hard 档拒的（屏上没有「{REJECT_PREFIX}」）："
            f"见 {log_path} 尾部\n{_tail(text)}"
        )
    missing = [p for p in expect_phrases if p not in text]
    if missing:
        raise GateFail(
            f"反向证明「{case}」拒了但没点名预期判据，缺：{missing}\n屏上原样文案：\n"
            + "\n".join(reject_lines(text)) + f"\n日志：{log_path}"
        )
    return "\n".join(reject_lines(text))


def _tail(text: str, n: int = 15) -> str:
    lines = [l for l in text.splitlines() if l.strip()]
    return "\n".join(lines[-n:])


# ---------------------------------------------------------------- package.report / CMP-74


def read_report(path: str) -> dict[str, str]:
    if not os.path.isfile(path):
        raise GateFail(f"没有 package.report：{path}（stageGameAssets 没落盘？）")
    out: dict[str, str] = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            k, _, v = line.partition(" ")
            out[k] = v
    return out


def sha256_file(path: str) -> tuple[int, str]:
    with open(path, "rb") as f:
        data = f.read()
    return len(data), hashlib.sha256(data).hexdigest()


def field_of(lines: list[str], name: str) -> str | None:
    for l in lines:
        if l.startswith(name + " "):
            return l[len(name) + 1:].strip()
    return None


def check_cmp74(pin_erbc_dir: str, report: dict[str, str]) -> list[str]:
    """CMP-74 三方对账：构建清单的 artifacts 表 / 随包清单的 package 段 / 磁盘实际字节。

    判据本体 = app/build.gradle.kts:1166-1215（写）+ :90-95（manifest-format 上限）。
    本函数不放宽：行数、文件集、字节数、sha256 逐项相等才算认账。
    """
    notes: list[str] = []
    staged_dir = os.path.join(STAGING_ROOT, ERBC_DIR_NAME)
    staged_manifest = os.path.join(staged_dir, "manifest.txt")
    build_manifest = os.path.join(pin_erbc_dir, "manifest.txt")
    for p in (staged_manifest, build_manifest):
        if not os.path.isfile(p):
            raise GateFail(f"缺清单：{p}")
    staged_lines = open(staged_manifest, "r", encoding="utf-8").read().splitlines()
    build_lines = open(build_manifest, "r", encoding="utf-8").read().splitlines()

    # 1) manifest-format：随包清单必须是打包脚本认识的档（期望值现读，不写死）
    expect_fmt = live_manifest_format()
    mf = field_of(staged_lines, "manifest-format")
    if mf != expect_fmt:
        raise GateFail(f"随包清单 manifest-format = {mf}（期望 {expect_fmt}，"
                       f"取 app/build.gradle.kts 的 eratwManifestFormat 单源）")
    notes.append(f"随包清单 manifest-format {mf}（= app 侧 eratwManifestFormat 现读值 {expect_fmt}）")

    # 2) 构建清单的 artifacts 表 ↔ 随包文件集
    declared_artifacts = [l for l in build_lines if l.startswith("artifact ")]
    n = field_of(build_lines, "artifacts")
    if n is None or int(n) != len(declared_artifacts):
        raise GateFail(f"构建清单 artifacts 计数 {n} 与条目数 {len(declared_artifacts)} 不一致")
    staged_files = sorted(f for f in os.listdir(staged_dir)
                          if os.path.isfile(os.path.join(staged_dir, f)))
    for l in declared_artifacts:
        _, rel, size, sha = l.split(" ")
        name = os.path.basename(rel)
        fpath = os.path.join(staged_dir, name)
        if not os.path.isfile(fpath):
            raise GateFail(f"构建清单登记 {rel} 但随包目录没有它：{fpath}")
        got_size, got_sha = sha256_file(fpath)
        if got_size != int(size) or got_sha.lower() != sha.lower():
            raise GateFail(f"{rel} 随包字节与构建清单不符：清单 {size} {sha}，实际 {got_size} {got_sha}")
    notes.append(f"构建清单 artifacts={n}，逐项（字节 + sha256）与随包文件一致："
                 f"{[os.path.basename(l.split(' ')[1]) for l in declared_artifacts]}")

    # 3) package 段：manifest-source package / package-artifacts / package-artifact /
    #    container-artifact / container-shards（+ shard 逐片）
    if field_of(staged_lines, "manifest-source") != "package":
        raise GateFail("随包清单缺 `manifest-source package` 段（打包期没追加 package 块？）")
    pkg_lines = [l for l in staged_lines if l.startswith("package-artifact ")]
    pkg_n = field_of(staged_lines, "package-artifacts")
    # `physical` 在写 package.report **之前**取（app/build.gradle.kts:1186 取表、:1302 才落报告），
    # 所以随包清单的 package 段登记的是「除清单自身与报告自身外的随包文件」。
    expected_pkg = [f for f in staged_files if f not in ("manifest.txt", "package.report")]
    if pkg_n is None or int(pkg_n) != len(pkg_lines):
        raise GateFail(f"package-artifacts 计数 {pkg_n} 与 package-artifact 行数 {len(pkg_lines)} 不一致")
    if sorted(os.path.basename(l.split(" ")[1]) for l in pkg_lines) != expected_pkg:
        raise GateFail(
            f"package-artifact 文件集与随包实际文件集不一致：清单 "
            f"{sorted(os.path.basename(l.split(' ')[1]) for l in pkg_lines)}，磁盘 {expected_pkg}")
    for l in pkg_lines:
        _, rel, size, sha = l.split(" ")
        got_size, got_sha = sha256_file(os.path.join(staged_dir, os.path.basename(rel)))
        if got_size != int(size) or got_sha.lower() != sha.lower():
            raise GateFail(f"package-artifact {rel} 与磁盘不符：清单 {size} {sha}，实际 {got_size} {got_sha}")
    notes.append(f"package 段 package-artifacts={pkg_n}（= 随包除清单自身/报告自身外的文件集；"
                 f"报告在 :1302 才落盘，晚于 :1186 取表），逐条字节 + sha256 与磁盘一致：{expected_pkg}")

    cont = [l for l in staged_lines if l.startswith("container-artifact ")]
    if len(cont) != 1:
        raise GateFail(f"container-artifact 应恰 1 行，实际 {len(cont)}")
    _, rel, size, sha = cont[0].split(" ")
    program_path = os.path.join(staged_dir, os.path.basename(rel))
    got_size, got_sha = sha256_file(program_path)
    if got_size != int(size) or got_sha.lower() != sha.lower():
        raise GateFail(f"container-artifact 与 program.erbc 实际字节不符：清单 {size} {sha}，实际 {got_size} {got_sha}")
    build_prog = [l for l in build_lines
                  if l.startswith("artifact ") and os.path.basename(l.split(" ")[1]) == "program.erbc"]
    if len(build_prog) != 1:
        raise GateFail("构建清单里找不到唯一 program.erbc 的 artifact 行")
    if build_prog[0].split(" ")[3].lower() != sha.lower():
        raise GateFail(f"container-artifact 的 sha 与构建清单 artifact 行的 sha 不同源："
                       f"{sha} vs {build_prog[0]}")
    notes.append("container-artifact = 切分前整容器，且与构建清单的 program.erbc 行同 sha")

    shards_n = field_of(staged_lines, "container-shards")
    shard_lines = [l for l in staged_lines if l.startswith("shard ")]
    if shards_n is None or int(shards_n) != len(shard_lines):
        raise GateFail(f"container-shards 计数 {shards_n} 与 shard 行数 {len(shard_lines)} 不一致")
    notes.append(f"container-shards={shards_n}（本路缺省单条不分片，program-chunk-mb="
                 f"{report.get('program-chunk-mb', '-')}）")

    # 4) 报告 ↔ 清单 / 报告 ↔ 容器 锚点
    ms = report.get("manifest-sha256")
    if not ms:
        raise GateFail("package.report 缺 manifest-sha256 行")
    _, real_ms = sha256_file(staged_manifest)
    if ms.lower() != real_ms:
        raise GateFail(f"package.report 的 manifest-sha256 {ms} 与随包清单实际 {real_ms} 不符")
    ps = report.get("program-sha256")
    if not ps or ps.lower() != got_sha:
        raise GateFail(f"package.report 的 program-sha256 {ps} 与 program.erbc 实际 {got_sha} 不符")
    if int(report.get("program-parts", "0")) != len(shard_lines):
        raise GateFail("program.parts 数与 container-shards 数不一致")
    notes.append(f"报告锚点：manifest-sha256 {ms[:16]}… / program-sha256 {ps[:16]}… 均与磁盘字节一致")
    return notes


def check_freshness_accept(report: dict[str, str], pin_erbc_dir: str, expect_sources: int) -> str:
    """CMP-66 认账读数：`erbc-freshness` 必须写 `verified(...)` 且逐项自证。"""
    f = report.get("erbc-freshness")
    if f is None:
        raise GateFail("package.report 缺 erbc-freshness 行")
    if not f.startswith("verified("):
        raise GateFail(f"erbc-freshness 不是认账值：{f}")
    if report.get("erbc-dir-fallback") != "false":
        raise GateFail(f"erbc-dir-fallback 应为 false（本路是显式 pin）：{report.get('erbc-dir-fallback')}")
    m = re.search(r"erbcc (\S+?), compiler ([0-9a-f]{12}), container v(\d+), program ([0-9a-f]{64}), "
                  r"源 (\d+) 个文件来自 (.*)\)$", f)
    if not m:
        raise GateFail(f"erbc-freshness 文案形状不认识（判据缺项？）：{f}")
    ver, digest12, cver, prog_sha, n_src, corpus_used = m.groups()
    declared_ver = live_manifest_field(pin_erbc_dir, "erbcc")
    declared_digest = live_manifest_field(pin_erbc_dir, "compiler-digest")
    declared_cver = live_manifest_field(pin_erbc_dir, "container-version")
    if declared_ver != ver:
        raise GateFail(f"报告里的 erbcc {ver} 与清单 {declared_ver} 不同源")
    if not declared_digest.lower().startswith(digest12.lower()):
        raise GateFail(f"报告里的 compiler {digest12} 与清单 compiler-digest {declared_digest} 对不上")
    if int(cver) != int(declared_cver):
        raise GateFail(f"报告里的 container v{cver} 与清单 container-version {declared_cver} 不一致")
    _, real_prog = sha256_file(os.path.join(pin_erbc_dir, "program.erbc"))
    if prog_sha.lower() != real_prog:
        raise GateFail(f"报告里 program {prog_sha} 与 pin 目录实际 {real_prog} 不符")
    if int(n_src) != expect_sources:
        raise GateFail(f"源对账条数 {n_src} 与语料档登记的 {expect_sources} 不一致"
                       f"（source-files 行 = {field_of(manifest_lines, 'source-files')}）")
    if not os.path.isdir(corpus_used.strip()):
        raise GateFail(f"报告里写明的语料根在本机不存在：{corpus_used}")
    return (f"认账：erbcc {ver} / compiler {digest12}… / container v{cver} / "
            f"program {prog_sha[:16]}… / 源 {n_src} 个来自 {corpus_used.strip()}")


def source_count(erbc_dir: str) -> int:
    lines = open(os.path.join(erbc_dir, "manifest.txt"), "r", encoding="utf-8").read().splitlines()
    return int(field_of(lines, "source-files") or "0")


# ---------------------------------------------------------------- 登记物落点（历史快照不覆盖）


def registered_digests(path: str) -> list[str]:
    """从既有登记物里抠出它当时记录的 compiler-digest（前缀或全串），用于覆盖前对账。"""
    text = open(path, "r", encoding="utf-8").read()
    return sorted(set(REGISTERED_DIGEST_RE.findall(text)))


def register_target(filename: str, ns: argparse.Namespace, live_digest: str) -> str:
    """登记物落点：`--register-into <tag>` ⇒ 另写现状档；缺省写历史档名，但先做覆盖护栏。

    护栏（AGENTS/BOARD 收口纪律「历史读数不许覆盖」）：目标档已存在，且它记录的 digest
    与本机现读不同，或档里带「历史快照」标注 ⇒ 判红，要求改用 `--register-into`。
    现读值来自 pin 目录的 manifest.txt，本函数不含任何写死的十六进制。
    """
    os.makedirs(DATA_OUT, exist_ok=True)
    tag = getattr(ns, "register_into", None)
    if tag:
        stem, ext = os.path.splitext(filename)
        return os.path.join(DATA_OUT, f"{stem}-{tag}{ext}")
    p = os.path.join(DATA_OUT, filename)
    if os.path.isfile(p):
        text = open(p, "r", encoding="utf-8").read()
        if HISTORICAL_MARK in text:
            raise GateFail(
                f"{p} 已标注为「{HISTORICAL_MARK}」读数，缺省档名禁止覆盖；"
                f"现状读数请用 --register-into <tag>（写 {os.path.splitext(filename)[0]}-<tag>{os.path.splitext(filename)[1]}）")
        stale = [d for d in registered_digests(p) if not live_digest.lower().startswith(d.lower())]
        if stale:
            raise GateFail(
                f"{p} 记录的是旧编译器的读数（{', '.join(stale)}），本机现读 {live_digest[:12]}…；"
                f"历史读数不许覆盖 ⇒ 现状读数请用 --register-into <tag>，"
                f"并在旧档头部加一行标注（正文不改写）")
    return p


# ---------------------------------------------------------------- 计时


def elapsed(t0: float) -> str:
    return f"{time.monotonic() - t0:.1f}s"


def hardcoded_hex_hits(text: str) -> list[tuple[int, str]]:
    """禁硬编码检查本体：返回正文里 ≥32 位连续十六进制（sha256 / compiler-digest 的形状）。

    单独成函数是为了能拿「注入过一个 digest 的文本」反证这条断言真的会红（不是空转）。
    """
    return [(i + 1, l.strip()) for i, l in enumerate(text.splitlines())
            if HARD_CODED_HEX_RE.search(l)]


# ---------------------------------------------------------------- gate1 / gate2


def cmd_produce(ns: argparse.Namespace) -> int:
    out_parent = ns.out or parity.winpath(os.path.join(RS_BUILD, time.strftime("%Y%m%d-%H%M%S")))
    erbc = produce_rust(ns.corpus, out_parent, ns.classpath, "produce")
    log(f"Rust 腿产物：{erbc}")
    for name in FOUR:
        p = os.path.join(erbc, name)
        if os.path.isfile(p):
            size, sha = sha256_file(p)
            log(f"  {name:<18} {size:>10}  {sha}")
    if ns.with_kotlin:
        cross_check_kotlin(ns.corpus, out_parent, ns.classpath)
        log("双跑对照：Rust 腿 == Kotlin 腿（四件套 sha256 全等）")
    return 0


def cmd_gate1(ns: argparse.Namespace) -> int:
    base = parity.winpath(os.path.join(RS_BUILD, "gate1-" + time.strftime("%Y%m%d-%H%M%S")))
    os.makedirs(base, exist_ok=True)
    log(f"== 闸 1（认账正例）工作目录：{base}")
    erbc = produce_rust(ns.corpus, os.path.join(base, "rust"), ns.classpath, "gate1")
    if getattr(ns, "with_kotlin", False):
        cross_check_kotlin(ns.corpus, os.path.join(base, "rust"), ns.classpath)
        log("  随包用的这一份 = Kotlin 腿逐字节相同的 Rust 腿产物（四件套 sha256 全等）")
    n_src = source_count(erbc)
    readings: list[tuple[str, dict[str, str], list[str]]] = []

    # ---- A 档：只 pin 产物目录，源 sha 走 manifest 的 corpus-root 兜底 ----
    log()
    log("A 档：-PeratwErbcDir=<Rust 腿 erbc.v1>（不给游戏根 ⇒ 源 sha 走 corpus-root 兜底）")
    log_a = os.path.join(base, "stage-A.log")
    rc, text = gradle_stage(erbc, None, [], log_a)
    if not build_succeeded(text):
        raise GateFail(f"A 档没认账（rc={rc}）。屏上原样文案：\n"
                       + "\n".join(reject_lines(text)) + f"\n日志：{log_a}")
    report = read_report(os.path.join(STAGING_ROOT, ERBC_DIR_NAME, "package.report"))
    if report.get("game-root") != "-":
        raise GateFail(f"A 档不该有游戏根：{report.get('game-root')}")
    summary = check_freshness_accept(report, erbc, n_src)
    notes = check_cmp74(erbc, report)
    log(f"  BUILD SUCCESSFUL（rc={rc}）· package.report erbc-freshness = {report['erbc-freshness']}")
    log(f"  {summary}")
    for nn in notes:
        log("  CMP-74：" + nn)
    readings.append(("A（corpus-root 兜底档）", report, notes))

    # ---- B 档：给游戏根（源 sha 走 -PeratwGameRoot/ERB 正路）+ 正式包完整度闸 ----
    game = getattr(ns, "game_root", None)
    game = parity.winpath(game) if game else make_temp_game_root(ns.corpus, base)
    if not os.path.isdir(game):
        raise EnvFail(f"-PeratwGameRoot 指向的目录不存在：{game}")
    props = ["-PeratwRequireComplete=1"] if ns.require_complete else []
    log()
    log(f"B 档：-PeratwErbcDir + -PeratwGameRoot={game}"
        f"{' + -PeratwRequireComplete=1' if props else ''}")
    log_b = os.path.join(base, "stage-B.log")
    rc, text = gradle_stage(erbc, game, props, log_b)
    if not build_succeeded(text):
        raise GateFail(f"B 档没认账（rc={rc}）。屏上原样文案：\n"
                       + ("\n".join(reject_lines(text)) if reject_lines(text) else _tail(text))
                       + f"\n日志：{log_b}")
    report = read_report(os.path.join(STAGING_ROOT, ERBC_DIR_NAME, "package.report"))
    summary = check_freshness_accept(report, erbc, n_src)
    notes = check_cmp74(erbc, report)
    log(f"  BUILD SUCCESSFUL（rc={rc}）· package.report erbc-freshness = {report['erbc-freshness']}")
    log(f"  {summary}")
    for nn in notes:
        log("  CMP-74：" + nn)
    readings.append(("B（-PeratwGameRoot/ERB 正路档）", report, notes))

    log()
    log("闸 1 结论：Rust 腿产物被 CMP-66 与 CMP-74 全认账（A/B 两档）。")
    if ns.register:
        write_register(ns, erbc, readings)
    if not ns.keep:
        shutil.rmtree(STAGING_ROOT, ignore_errors=True)
        shutil.rmtree(base, ignore_errors=True)
        shutil.rmtree(base + "-kotlin", ignore_errors=True)
        log(f"临时产物已删除：{base}（staging 目录一并清理）")
    else:
        log(f"--keep：产物留在 {base} 与 {STAGING_ROOT}")
    return 0


def write_register(ns: argparse.Namespace, erbc_dir: str,
                   readings: list[tuple[str, dict[str, str], list[str]]]) -> None:
    """小档登记物：只写读数行（含本机绝对路径，换机须重跑刷新；口径同 rust-gradle 闸 4 登记）。

    档名按语料规模分开：小档（闸 1 的必测档）与全量档（可选第二档）互不覆盖。
    `--register-into <tag>` 时另写现状档，历史档正文一个字不动（见 `register_target`）。
    """
    slug = "full-corpus" if is_full_corpus(ns.corpus) else "small-corpus"
    live_digest = live_manifest_field(erbc_dir, "compiler-digest")
    p = register_target(f"haruki-staging-accept-{slug}.txt", ns, live_digest)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write("# erbcc-rs / rust-staging 车道登记：闸 1 认账读数（开发机实测）\n")
        f.write("# 产法：python devtools/verify_rust_erbc_staging.py gate1 --register"
                f"{' --corpus $ERATW_CORPUS --game-root $ERATW_GAMEDIR' if slug == 'full-corpus' else ''}"
                f"{f' --register-into {ns.register_into}' if ns.register_into else ''}\n")
        f.write(f"# 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}　语料：{parity.winpath(ns.corpus)}\n")
        f.write(f"# 编译器指纹（现读 pin 目录 manifest.txt）：compiler-digest {live_digest}　"
                f"compiler-sources {live_manifest_field(erbc_dir, 'compiler-sources')}\n")
        f.write("# 机相关：erbc-freshness / erbc-source / game-root 含产出机绝对路径；"
                "换机（或改 core-vm 源码树）后重跑本命令刷新，不放宽判据。\n")
        for name, report, _ in readings:
            f.write(f"\n## 档 {name}\n")
            for key in ("erbc-source", "erbc-dir-fallback", "erbc-freshness", "program-sha256",
                        "program-bytes", "program-parts", "manifest-sha256", "entries-erbc",
                        "bytes-erbc", "staged-entries", "staged-bytes", "program-mode"):
                if key in report:
                    f.write(f"{key} {report[key]}\n")
    log(f"登记物已写：{p}")


def is_full_corpus(corpus: str) -> bool:
    """语料文件数 > 64 判为全量档（本仓两档：3 文件小档 / 3,971 文件全量档）。

    旧版叫 `source_count_needed` 且调用处把三元写反了（全量档被登进 `-small-corpus.txt`、
    小档被登进 `-full-corpus.txt`），两档读数会互相盖掉 —— 本车道修正名与逻辑。
    """
    src = parity.winpath(corpus)
    n = 0
    for _root, _dirs, files in os.walk(src):
        n += len(files)
        if n > 64:
            return True
    return n > 64


# ---------------------------------------------------------------- 反向证明


# 语料侧篡改的靶子文件名（闸 0 断言它在被 pin 的语料里真存在，改名/换语料先红）
CORPUS_DRIFT_FILE = "01_main.ERB"

# (档名, 说明, 屏上必须出现的原样片段, 破坏面：manifest = 改清单 / corpus = 改语料)
TAMPER_CASES = [
    ("compiler-digest", "把 compiler-digest 行的十六进制改掉一位（容器与语料都不动）",
     ["产物由旧编译器产出", "compiler-digest"], "manifest"),
    ("source-sha", "把第一条 source 行的 sha256 改掉一位（文件集与容器都不动）",
     ["语料文件 sha256 变了", "语料改过但没重编"], "manifest"),
    ("build-mode", "把 build-mode 由 full 改成 limited（模拟 --limit 受限产物）",
     ["产物不是全量档", "受限产物"], "manifest"),
    ("no-digest-line", "整行删掉 compiler-digest / compiler-sources（模拟旧编译器产物）",
     ["manifest 没有 compiler-digest 行"], "manifest"),
    ("corpus-drift", "清单一个字不改，改随包语料根里那个 ERB（真「语料改过没重编」）",
     ["语料文件 sha256 变了", CORPUS_DRIFT_FILE, "语料改过但没重编"], "corpus"),
]


def _flip_hex(v: str) -> str:
    """末位十六进制翻一位：改后仍是合法的 64 位十六进制串，逼判据去真比而不是比形状。"""
    chars = list(v)
    chars[-1] = "0" if chars[-1] != "0" else "1"
    return "".join(chars)


def _rewrite_field(lines: list[str], name: str, fn) -> list[str]:
    out = []
    hit = False
    for l in lines:
        if l.startswith(name + " "):
            out.append(f"{name} {fn(l[len(name) + 1:].strip())}")
            hit = True
        else:
            out.append(l)
    if not hit:
        raise GateFail(f"清单里没有 {name} 行，反向证明无从下手")
    return out


def _tamper_source_sha(lines: list[str]) -> list[str]:
    """只改第一条 `source` 行的 sha256（末位翻一位）：路径与字节数照旧，逼判据落到 sha 比对。"""
    out = []
    hit = False
    for l in lines:
        if not hit and l.startswith("source "):
            rest = l[len("source "):]
            idx = rest.rfind(" ")
            sha = rest[idx + 1:]
            new_sha = sha[:-1] + ("0" if sha[-1] != "0" else "1")
            out.append("source " + rest[:idx + 1] + new_sha)
            hit = True
        else:
            out.append(l)
    if not hit:
        raise GateFail("清单里没有 source 行，反向证明无从下手")
    return out


def apply_tamper(case: str, lines: list[str]) -> list[str]:
    if case == "compiler-digest":
        return _rewrite_field(lines, "compiler-digest", _flip_hex)
    if case == "source-sha":
        return _tamper_source_sha(lines)
    if case == "build-mode":
        return _rewrite_field(lines, "build-mode", lambda v: "limited")
    if case == "no-digest-line":
        kept = [l for l in lines
                if not l.startswith("compiler-digest ") and not l.startswith("compiler-sources ")]
        if len(kept) == len(lines):
            raise GateFail("清单里没有 compiler-digest / compiler-sources 行，反向证明无从下手")
        return kept
    raise EnvFail(f"未知反向证明档：{case}")


TAMPER_EXPECT = {c[0]: c[2] for c in TAMPER_CASES}   # 档名 → 屏上必须出现的原样片段（登记表用）

# 屏上片段的出处登记：
#   app    = 必须逐字出现在 app/build.gradle.kts 里（打包侧改了文案 ⇒ selfcheck 先红）
#   corpus = 语料文件名（gate2 篡改的就是被 pin 语料里的那个文件）
# 出处没登记的片段算 selfcheck 失败：判据文案与期望值不许各说各话。
PHRASE_ORIGIN = {
    "产物由旧编译器产出": "app",
    "compiler-digest": "app",
    "语料文件 sha256 变了": "app",
    "语料改过但没重编": "app",
    "产物不是全量档": "app",
    "受限产物": "app",
    "manifest 没有 compiler-digest 行": "app",
    CORPUS_DRIFT_FILE: "corpus",
}

def cmd_gate2(ns: argparse.Namespace) -> int:
    base = parity.winpath(os.path.join(RS_BUILD, "gate2-" + time.strftime("%Y%m%d-%H%M%S")))
    os.makedirs(base, exist_ok=True)
    log(f"== 闸 2（反向证明）工作目录：{base}")
    erbc = produce_rust(ns.corpus, os.path.join(base, "rust"), ns.classpath, "gate2")
    # 现读一次就存下来：`finally` 里不 `--keep` 会把 base 删掉，登记时不能再回去读盘
    gate_digest = live_manifest_field(erbc, "compiler-digest")
    good_lines = open(os.path.join(erbc, "manifest.txt"), "r", encoding="utf-8").read().splitlines()
    results: list[str] = []
    try:
        for case, desc, expect, kind in TAMPER_CASES:
            log()
            log(f"档 {case}：{desc}")
            cdir = os.path.join(base, "tamper-" + case)
            shutil.rmtree(cdir, ignore_errors=True)
            os.makedirs(cdir)
            dst = os.path.join(cdir, ERBC_DIR_NAME)
            shutil.copytree(erbc, dst)
            game = make_temp_game_root(ns.corpus, cdir)
            if kind == "manifest":
                new_lines = apply_tamper(case, good_lines)
                with open(os.path.join(dst, "manifest.txt"), "w", encoding="utf-8", newline="\n") as f:
                    f.write("\n".join(new_lines) + "\n")
                changed = [i + 1 for i in range(len(new_lines))
                           if i >= len(good_lines) or new_lines[i] != good_lines[i]]
                how = (f"改的是 manifest.txt 第 {changed} 行（容器与语料字节都不动）"
                       if len(new_lines) == len(good_lines)
                       else f"删掉 {len(good_lines) - len(new_lines)} 行（第 {changed[0]} 行起，容器与语料字节都不动）")
            else:
                # 语料侧破坏：字节数**不变**、只翻一个字节 —— 否则先撞「大小变了」那条判据
                # （:419），验不到 sha 判据（:422-425）。清单一个字都不动。
                drift = os.path.join(game, "ERB", CORPUS_DRIFT_FILE)
                data = open(drift, "rb").read()
                with open(drift, "wb") as f:
                    f.write(data[:-1] + bytes([data[-1] ^ 0x01]))
                how = (f"改的是随包语料根 {drift} 的最后一个字节（{len(data)} B → "
                       f"{len(data)} B，清单与容器一字未动）")
            log(f"  篡改落点：{how}")
            log_p = os.path.join(base, f"stage-{case}.log")
            rc, text = gradle_stage(dst, game, [], log_p)
            msg = require_rejected(case, rc, text, expect, log_p)
            log(f"  被 hard 档拒绝：rc={rc}")
            log("  屏上原样文案：")
            for line in msg.splitlines():
                log("  " + line)
            results.append(f"### 档 {case}：{desc}\nrc={rc}\n{msg}")
    finally:
        if not ns.keep:
            shutil.rmtree(STAGING_ROOT, ignore_errors=True)
            shutil.rmtree(base, ignore_errors=True)
            log()
            log(f"临时产物已删除：{base}")
        else:
            log(f"--keep：产物留在 {base}")
    if ns.register:
        p = register_target("haruki-staging-reject.txt", ns, gate_digest)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write("# erbcc-rs / rust-staging 车道登记：闸 2 反向证明的拒绝文案原文\n")
            f.write("# 产法：python devtools/verify_rust_erbc_staging.py gate2 --register"
                    f"{f' --register-into {ns.register_into}' if ns.register_into else ''}\n")
            f.write(f"# 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}　"
                    f"编译器指纹（现读 pin 清单）：compiler-digest {gate_digest}\n")
            f.write("# 每条都必须 rc≠0 且屏上带「CMP-66 新鲜度对账失败：」"
                    "（app/build.gradle.kts 的 hard 档；档位由 selfcheck 断言恰 5 条）\n")
            for r in results:
                f.write("\n" + r + "\n")
        log(f"登记物已写：{p}")
    log()
    log(f"闸 2 结论：{len(TAMPER_CASES)} 条坏产物全被 CMP-66 的 hard 档拒绝（该拒真拒）。")
    return 0


def cmd_switches(ns: argparse.Namespace) -> int:
    """开关纪律：-PeratwAllowStaleErbc 与 -PeratwRequireComplete 同给 → 配置期就拒。"""
    base = parity.winpath(os.path.join(RS_BUILD, "switches-" + time.strftime("%Y%m%d-%H%M%S")))
    os.makedirs(base, exist_ok=True)
    erbc = produce_rust(ns.corpus, os.path.join(base, "rust"), ns.classpath, "switches")
    switches_digest = live_manifest_field(erbc, "compiler-digest")   # 删档前先现读
    log_p = os.path.join(base, "stage-mutex.log")
    rc, text = gradle_stage(erbc, None, ["-PeratwAllowStaleErbc=1", "-PeratwRequireComplete=1"], log_p)
    expect = "-PeratwAllowStaleErbc 与 -PeratwRequireComplete 互斥"
    if rc == 0 or expect not in text:
        raise GateFail(f"开关互斥探针不成立（rc={rc}，屏上{'有' if expect in text else '无'}「{expect}」）：\n"
                       + _tail(text) + f"\n日志：{log_p}")
    log(f"  rc={rc}，配置期原样文案：")
    for l in text.splitlines():
        if expect in l or "What went wrong" in l:
            log("  " + l.rstrip())
    if ns.register:
        p = register_target("haruki-staging-switches.txt", ns, switches_digest)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write("# erbcc-rs / rust-staging 车道登记：开关纪律探针（闸 3）\n")
            f.write("# 产法：python devtools/verify_rust_erbc_staging.py switches --register"
                    f"{f' --register-into {ns.register_into}' if ns.register_into else ''}\n")
            f.write(f"# 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}　"
                    f"编译器指纹（现读 pin 清单）：compiler-digest {switches_digest}\n")
            f.write(f"# rc={rc}（配置期抛 GradleException，app/build.gradle.kts 的开关互斥闸）\n")
            for l in text.splitlines():
                if expect in l:
                    f.write(l.strip() + "\n")
        log(f"登记物已写：{p}")
    if not ns.keep:
        shutil.rmtree(base, ignore_errors=True)
    log("  ⇒ 本路正式读数确实没走放行开关（互斥闸在配置期就挡死同给）。")
    return 0


# ---------------------------------------------------------------- 保鲜体检（不跑 Gradle）


def cmd_selfcheck(ns: argparse.Namespace) -> int:
    """脚本保鲜体检：①禁硬编码 ②判据文案与 app 同源 ③现读自证 ④反向证明档完整。

    秒级、零 Gradle：`all` 的第一站，也是改完脚本/换编译器后的第一道自查。
    """
    t0 = time.monotonic()
    problems: list[str] = []
    notes: list[str] = []
    log("== 闸 0（脚本保鲜体检，不跑 Gradle）")

    # ---- ① 禁硬编码：正文里一条 ≥32 位十六进制字面量都不许有 ----
    own_text = open(SELF_PATH, "r", encoding="utf-8").read()
    own = own_text.splitlines()
    hits = hardcoded_hex_hits(own_text)
    if hits:
        problems.append("硬编码禁词命中（digest/sha 必须从 pin 的 manifest.txt 与源码树现读）：\n"
                        + "\n".join(f"      第 {n} 行：{t}" for n, t in hits))
    else:
        # 防空转：在内存里给正文注一枚「现读来的 digest」，断言这条检查真抓得住
        probe = live_manifest_field(FIXTURE_ERBC, "compiler-digest")
        injected = own_text.replace('REJECT_PREFIX = "', probe + '\nREJECT_PREFIX = "', 1)
        if not hardcoded_hex_hits(injected):
            problems.append("禁硬编码这条断言在空转：注入一枚真 digest 后仍抓不到（正则坏了）")
        else:
            notes.append(f"①禁硬编码：自检 {len(own)} 行正文，≥32 位十六进制字面量 0 处；"
                         "注入真 digest 的反证抓到 ⇒ 断言不空转")

    # ---- ② 判据文案与 app/build.gradle.kts 同源；语料文件名档要在档里真存在 ----
    if not os.path.isfile(APP_GRADLE):
        raise EnvFail(f"找不到 {APP_GRADLE}：无法核对判据文案")
    app_text = open(APP_GRADLE, "r", encoding="utf-8").read()
    corpus_w = parity.winpath(ns.corpus)
    checked_app = 0
    for case, _desc, expects, _kind in TAMPER_CASES:
        for ph in expects:
            origin = PHRASE_ORIGIN.get(ph)
            if origin is None:
                problems.append(f"档 {case} 的屏上片段「{ph}」没登记出处（app 文案 / corpus 文件名）")
            elif origin == "app" and ph not in app_text:
                problems.append(f"档 {case} 的片段「{ph}」在 app/build.gradle.kts 里逐字找不到"
                                " ⇒ 打包侧判据文案换了，本脚本的期望值已过期（别放宽，改期望值要带行号证据）")
            elif origin == "app":
                checked_app += 1
            elif origin == "corpus" and not os.path.isfile(os.path.join(corpus_w, ph)):
                problems.append(f"档 {case} 要篡改的语料文件不在被 pin 的语料里：{os.path.join(corpus_w, ph)}")
    notes.append(f"②文案同源：{checked_app} 条判据片段逐字命中 app/build.gradle.kts，"
                 f"另有 {sum(1 for v in PHRASE_ORIGIN.values() if v == 'corpus')} 条语料文件名档已在盘上核实")

    # ---- ③ 现读自证：入库夹具当 pin 目录，真值必须过 / 翻一位必须红 ----
    fx_program = os.path.join(FIXTURE_ERBC, "program.erbc")
    if not (os.path.isfile(os.path.join(FIXTURE_ERBC, "manifest.txt")) and os.path.isfile(fx_program)):
        raise EnvFail(f"缺入库夹具，现读自证无从下手：{FIXTURE_ERBC}")
    digest = live_manifest_field(FIXTURE_ERBC, "compiler-digest")
    ver = live_manifest_field(FIXTURE_ERBC, "erbcc")
    cver = live_manifest_field(FIXTURE_ERBC, "container-version")
    root = live_manifest_field(FIXTURE_ERBC, "corpus-root")
    n_src = source_count(FIXTURE_ERBC)
    _, prog = sha256_file(fx_program)

    def fresh_line(d12: str, v: str, cv: str, psha: str) -> dict[str, str]:
        return {"erbc-dir-fallback": "false",
                "erbc-freshness": (f"verified(erbcc {v}, compiler {d12}, container v{cv}, program {psha}, "
                                   f"源 {n_src} 个文件来自 {root})")}

    good = fresh_line(digest[:12], ver, cver, prog)
    check_freshness_accept(good, FIXTURE_ERBC, n_src)     # 盘上真值必须认账，否则夹具坏了
    mutations = [
        ("compiler-digest 前缀翻一位", fresh_line(_flip_hex(digest[:12]), ver, cver, prog)),
        ("program sha 末位翻一位", fresh_line(digest[:12], ver, cver, _flip_hex(prog))),
        ("container 版本 +1", fresh_line(digest[:12], ver, str(int(cver) + 1), prog)),
        ("erbcc 版本串改掉", fresh_line(digest[:12], ver + "-x", cver, prog)),
    ]
    for label, rep in mutations:
        try:
            check_freshness_accept(rep, FIXTURE_ERBC, n_src)
        except GateFail:
            notes.append(f"③现读自证：{label} ⇒ 判红（读的是盘上字节，不是常量）")
        else:
            problems.append(f"现读自证失败：{label}后 check_freshness_accept 仍放过"
                            " ⇒ 该判据可能钉了常量或被放宽")
    notes.insert(len(notes),
                 f"（夹具现读：compiler-digest {digest[:12]}… / container v{cver} / 源 {n_src} 个）")

    # ---- ④ 反向证明档：恰 5 条、档名唯一、破坏面真能实现 ----
    names = [c[0] for c in TAMPER_CASES]
    if len(names) != 5:
        problems.append(f"反向证明档位应为 5 条，实际 {len(names)} 条（{names}）")
    if len(set(names)) != len(names):
        problems.append(f"反向证明档名有重复：{names}")
    fx_lines = open(os.path.join(FIXTURE_ERBC, "manifest.txt"), "r", encoding="utf-8").read().splitlines()
    kinds: list[str] = []
    for case, _desc, _expect, kind in TAMPER_CASES:
        kinds.append(kind)
        if kind == "manifest":
            try:
                tampered = apply_tamper(case, fx_lines)
            except GateFail as e:
                problems.append(f"档 {case} 在入库夹具上实现不了：{e}")
                continue
            if tampered == fx_lines:
                problems.append(f"档 {case} 的篡改没改动清单任何一行 ⇒ 判据无从触发")
        elif kind != "corpus":
            problems.append(f"档 {case} 的破坏面 kind={kind} 不认识（只许 manifest / corpus）")
    notes.append(f"④档完整性：{len(names)} 条档名唯一，破坏面清单 {kinds.count('manifest')} manifest + "
                 f"{kinds.count('corpus')} corpus，篡改在夹具上逐条可复现")
    expect_fmt = live_manifest_format()
    notes.append(f"另：manifest-format 期望值现读 app 侧 eratwManifestFormat = {expect_fmt}")

    if problems:
        raise GateFail("脚本保鲜体检红：\n" + "\n".join(f"  - {p}" for p in problems))
    for n in notes:
        log("  " + n)
    log(f"  闸 0 结论：判据全部现读、文案与 app 同源、{len(names)} 档反向证明齐备"
        f"（墙钟 {elapsed(t0)}）")
    return 0


def cmd_all(ns: argparse.Namespace) -> int:
    for fn in (cmd_selfcheck, cmd_gate1, cmd_gate2, cmd_switches):
        rc = fn(ns)
        if rc != 0:
            return rc
    return 0


# ---------------------------------------------------------------- 入口


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="verify_rust_erbc_staging.py",
        description="RUST1 rust-staging：Rust 腿 erbc.v1 的随包认账验收（CMP-66 / CMP-74）",
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
        p.add_argument("--corpus", default=SMALL_CORPUS,
                       help="语料根（缺省小档 ../eraKcore/tools/erbcc-rs/tests/data/rust-manifest/corpus）")
        p.add_argument("--classpath", help="ERBCC_CLASSPATH 的直接串或文件路径（缺省走 install 目录）")
        p.add_argument("--keep", action="store_true", help="保留临时产物与 staging（默认删除）")
        p.add_argument("--register", action="store_true",
                       help="把读数写进 ../eraKcore/tools/erbcc-rs/tests/data/rust-staging/（小档登记物）")
        p.add_argument("--register-into", dest="register_into", metavar="TAG",
                       help="读数写进带 -<TAG> 后缀的**现状档**（历史快照档禁止覆盖，见 register_target）")
        return p

    pp = common(sub.add_parser("produce", help="只跑 Rust 腿产出 erbc.v1"))
    pp.add_argument("--out", help="产物父目录（缺省 build/rust-staging/<ts>/rust）")
    pp.add_argument("--with-kotlin", dest="with_kotlin", action="store_true",
                    help="顺手 Kotlin 腿双跑对拍（四件套 sha256 全等）")
    pp.set_defaults(fn=cmd_produce)

    g1 = common(sub.add_parser("gate1", help="认账正例：pin Rust 腿产物跑 stageGameAssets"))
    g1.add_argument("--game-root", help="B 档用现成游戏根（全量档配 $ERATW_GAMEDIR 用；缺省造小占位根）")
    g1.add_argument("--with-kotlin", dest="with_kotlin", action="store_true",
                    help="B 档前先与 Kotlin 腿双跑对拍（证明认账的那份与 Kotlin 逐字节相同）")
    g1.add_argument("--no-require-complete", dest="require_complete", action="store_false",
                    help="B 档不叠 -PeratwRequireComplete=1（默认叠）")
    g1.set_defaults(fn=cmd_gate1, require_complete=True)

    g2 = common(sub.add_parser("gate2", help="反向证明：五种篡改档必须全被 hard 档拒"))
    g2.set_defaults(fn=cmd_gate2)

    sw = common(sub.add_parser("switches", help="开关互斥探针（证明没靠 AllowStaleErbc 糊）"))
    sw.set_defaults(fn=cmd_switches)

    sc = common(sub.add_parser("selfcheck", help="脚本保鲜体检（禁硬编码/文案同源/现读自证/档齐备），不跑 Gradle"))
    sc.set_defaults(fn=cmd_selfcheck)

    al = common(sub.add_parser("all", help="selfcheck + gate1 + gate2 + switches"))
    al.add_argument("--no-require-complete", dest="require_complete", action="store_false")
    al.add_argument("--with-kotlin", dest="with_kotlin", action="store_true")
    al.set_defaults(fn=cmd_all, require_complete=True)
    return ap


def main(argv: list[str]) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ns = build_parser().parse_args(argv)
    try:
        return ns.fn(ns)
    except EnvFail as e:
        log(f"环境不满足（退 2）：{e}")
        return 2
    except parity.EnvError as e:
        log(f"环境不满足（退 2）：{e}")
        return 2
    except GateFail as e:
        log(f"判据红（退 1）：{e}")
        return 1
    except parity.ParityFail as e:
        log(f"判据红（退 1）：{e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
