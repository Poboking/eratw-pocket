#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""erbcc Rust 化波的双跑对拍闸 + 每台机基准重生成 + ERBCC_CLASSPATH 产出方。

RUST1-B `rust-gradle` 车道的入口脚本（内部契约文档未随公开仓发布）。

公开分仓布局：`core-vm` / `render` / `tools/erbcc*` / `fixtures` 都在姐妹仓 `../erak-engine`。
本脚本的跨仓路径默认指向 `../erak-engine`，可用环境变量 `ERATW_KCORE_ROOT` 覆盖。
Kotlin 侧/`src/*.rs` 一个字节都不碰：本脚本只是**编排 + 读数**，判据全部落在
「两侧产物文件的 sha256 逐字节相等」上。

## 子命令

```text
parity     同一份语料，Kotlin 腿与 Rust 腿各出一份 erbc.v1/，比四件套 sha256。
           --corpus/--out/--limit 透传给两腿；红则非零退出并打印首个差异
           （文件名 + 字节偏移 + 两侧 sha/大小）。--expect 可再叠一层
           「与登记 sha 对照」（闸 4 的复现判据）。
regen      在**当前机器**重跑并写回 tests/data/rust-manifest/ 、tests/data/rust-kernel/
           与 tests/data/rust-roundtrip/ 的基准，打印新旧 sha 对照表（并判定 manifest 差异
           是否仅 corpus-root 路径行、roundtrip 明细差异落在哪几条判据行上）。
           --lane rust-manifest|rust-kernel|rust-roundtrip|all，--dry-run 只看表不写盘。
           rust-roundtrip 档的产出方是 tests/roundtrip_parity.rs 里的 regen_roundtrip_baseline
           格（显式开关 ERBCC_RS_REGEN_ROUNDTRIP=1 才写盘、dry 只打印；未置位时该格自跳过，
           默认闸逐字节行为不变）—— 本脚本只调用 cargo 并校验落盘，基准字节全部出自真 Kotlin 腿。
           --lane rust-full 另录**本机**全量基准（../erak-engine/tools/erbcc-rs/build/baseline/，不入库，
           约 80 s）：全量 compileCorpus 腿 + Kotlin `check` 原始 stdout（自动剥结尾 \r\n，
           -Pout/-Pcorpus 一律绝对化）—— 改过 core-vm 之后不补这一档，
           freshness_parity 的全量第二读数就红在一个旧 digest 上。
classpath  ERBCC_CLASSPATH 的产出方：跑 :tools:erbcc:installDist（可 --no-build 跳过），
           把 lib 下的 jar 清单写成 classpath 文件（档 1 文件态，多行）或直接串
           （--mode direct，分隔符 ';'）。kernel.rs 的 resolve_classpath 两态都吃。
selftest   反向证明：造一个被篡改的「假 Rust 腿」与一条错的 --expect 登记，
           驱动必须非零退出并点名差异文件；全红得其所才退 0。
```

退出码：`0` 绿 · `1` 对拍/登记不一致（含 selftest 里驱动失明）·
`2` 用法或环境不满足（缺 java / classpath / erbcc.exe / 语料目录）。

## 口径（与已收口三路同源，不新造判据）

- 四件套 = `erbc.v1/{program.erbc,program.meta,program.stmt.idx,manifest.txt}`，
  全部按**文件原始字节** sha256，不做任何行尾/编码归一。
- `manifest.txt` 的 `corpus-root` 行来自两腿各自对同一个 `--corpus` 实参的绝对化
  （Kotlin `toAbsolutePath().normalize()`，Rust `absolutize`+反斜杠归一），
  所以驱动把语料/产物路径先 `os.path.abspath` 成 `C:\\` 风格再传参 —— 这是
  「双腿逐字节可比」的前提，不是 normalize 产物文本。
- JVM 子进程统一带 `-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`
  （`kernel.rs:50-54` 同款）；Rust CLI 恒 UTF-8。
- `regen` 写回 `expected/check-*.stdout.bin` 时的文本口径 = 既有 fixture 的形状：
  Kotlin `println` 的尾部 `\r\n` 折成无尾换行、内部行分隔保持 `\n`
  （`ArtifactFreshness.kt:89-95` 的 summary 就是 `\n` 连接）；这一条转换是**显式**
  的、写进每个档的读数行，不悄悄做。
- `GOLDEN_` 前缀被 `CorpusCompile.kt:198` 排除在编译输入外，故 rust-kernel 基准的
  语料是 13 个 fixture 改名 `PARITY_*.ERB` 的副本（与 `tests/compile_parity.rs:37-51,72-75`
  同法），旁边补空 `CSV/`（Gradle 把 `<gameRoot>/CSV` 声明成输入）。

## 环境（开发机，统一 ./gradlew）

`JAVA_HOME` 指到工具链 JDK；全量档 `--corpus` 用 `$ERATW_CORPUS`（必须指到 ERB 子树）。
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

# ---------------------------------------------------------------- 路径常量

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)                       # <worktree>/eratw-pocket
# 公开分仓布局：引擎侧工具/夹具在姐妹仓 erak-engine；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(APP_ROOT), "erak-engine"))
RS_DIR = os.path.join(KCORE_ROOT, "tools", "erbcc-rs")
DATA_DIR = os.path.join(RS_DIR, "tests", "data")
MANIFEST_DATA = os.path.join(DATA_DIR, "rust-manifest")
KERNEL_BASELINE = os.path.join(DATA_DIR, "rust-kernel", "parity-baseline-sha256.txt")
ROUNDTRIP_DATA = os.path.join(DATA_DIR, "rust-roundtrip")
ROUNDTRIP_TEST = "regen_roundtrip_baseline"   # tests/roundtrip_parity.rs 里的开关格（ASCII 名，--exact 免引号坑）
# 与 tests/roundtrip_parity.rs 的 REGEN_FILES 一一对应（截断档抛在解码前没有明细 ⇒ 只有两件）。
ROUNDTRIP_FILES = [
    "expected/green.tsv",
    "expected/green-limit3.tsv",
    "tamper/meta-program-sha256-changed.tsv",
    "tamper/meta-program-sha256-changed.program.meta",
    "tamper/meta-program-sha256-changed.expect.txt",
    "tamper/meta-private-dims-dropped.tsv",
    "tamper/meta-private-dims-dropped.program.meta",
    "tamper/meta-private-dims-dropped.expect.txt",
    "tamper/truncated-erbc.program.erbc",
    "tamper/truncated-erbc.expect.txt",
]
SMALL_CORPUS = os.path.join(MANIFEST_DATA, "corpus")
GOLDEN_DIR = os.path.join(KCORE_ROOT, "fixtures", "golden")
INSTALL_LIB = os.path.join(KCORE_ROOT, "tools", "erbcc", "build", "install", "erbcc", "lib")
GRADLEW_BAT = os.path.join(APP_ROOT, "gradlew.bat")
DRIVER_BUILD = os.path.join(RS_DIR, "build", "parity-driver")   # 已 gitignore（build/）

ERBC_DIR = "erbc.v1"
FOUR = ["program.erbc", "program.meta", "program.stmt.idx", "manifest.txt"]
THREE = FOUR[:3]

MAIN_CLASS = "com.eratw.port.erbcc.MainKt"
JVM_OPTS = ["-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"]
COMPILE_HEAP_OPT = "-Xmx6g"          # 与 ../erak-engine/tools/erbcc/build.gradle.kts:40 同尺
INSTALL_DIST_HINT = "cd ../erak-engine && ./gradlew --configure-on-demand :tools:erbcc:installDist"
CARGO_BUILD_HINT = "cd ../erak-engine/tools/erbcc-rs && cargo build --release --bin erbcc"

# 与 tests/compile_parity.rs:37-51 同一张表（改名规则 :72-75）
GOLDEN_NAMES = [
    "GOLDEN_00_ROOT.ERB", "GOLDEN_01_BASIC.ERB", "GOLDEN_02_PRINT.ERB",
    "GOLDEN_03_FLOW.ERB", "GOLDEN_04_CHARA.ERB", "GOLDEN_05_DATA.ERB",
    "GOLDEN_06_INPUT_AUTO.ERB", "GOLDEN_07_INPUT_MANUAL.ERB", "GOLDEN_08_IO.ERB",
    "GOLDEN_09_MISC.ERB", "GOLDEN_10_XMLMAP.ERB", "GOLDEN_11_GVIEW.ERB",
    "GOLDEN_12_SYMBOLS.ERB",
]

# 与 tests/freshness_parity.rs 的 build_case 同法（破坏方式 = Kotlin 用例同源）
CHECK_CASES = ["good", "source-changed", "truncated", "no-digest-line"]
SOURCE_CHANGED_BYTES = (
    b"@SYSTEM_TITLE\nPRINTL HELLO_CHANGED\nPRINTFORML C={G_CONST}"
)

SEPARATOR = ";" if os.name == "nt" else ":"


class EnvError(Exception):
    """环境不满足 → 退 2（不是对拍红，不许混进 1）。"""


class ParityFail(Exception):
    """对拍/登记不一致 → 退 1，消息里带首个差异。"""


def sha256_file(path: str) -> tuple[int, str]:
    with open(path, "rb") as f:
        data = f.read()
    return len(data), hashlib.sha256(data).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def log(msg: str = "") -> None:
    print(msg, flush=True)


def winpath(p: str) -> str:
    """绝对化并折成 `\\` 分隔（Windows），喂给两腿前的统一预处理。"""
    a = os.path.abspath(p)
    if os.name == "nt":
        a = a.replace("/", "\\")
    return a


def normpath_display(p: str) -> str:
    return p


# ---------------------------------------------------------------- 工具链解析

def resolve_java() -> str:
    home = os.environ.get("JAVA_HOME", "").strip()
    if home:
        exe = os.path.join(home, "bin", "java" + (".exe" if os.name == "nt" else ""))
        if not os.path.isfile(exe):
            raise EnvError(f"JAVA_HOME 设了但里面没有可执行文件：{exe}")
        return exe
    found = shutil.which("java")
    if not found:
        raise EnvError("找不到 java：设 JAVA_HOME（AGENTS.md §5 的工具链入口）或让 java 上 PATH。不猜路径。")
    return found


def read_classpath_spec(spec: str) -> str:
    """档 1 两态（kernel.rs:99-137 同语义）：指向存在的文件 ⇒ 逐行拼 `;`；否则当直接串。"""
    t = spec.strip()
    if not t:
        raise EnvError("classpath 规格为空")
    if os.path.isfile(t):
        with open(t, "r", encoding="utf-8", errors="surrogateescape") as f:
            text = f.read()
        entries = [e.strip() for e in re.split(r"[\n\r;]", text) if e.strip()]
        if not entries:
            raise EnvError(f"classpath 文件 {t} 里没有条目")
        return SEPARATOR.join(entries)
    return t


def resolve_classpath(explicit: str | None) -> tuple[str, str]:
    """(classpath 串, 来源说明)。优先级：--classpath/--classpath-file > ERBCC_CLASSPATH > install lib。"""
    if explicit:
        return read_classpath_spec(explicit), f"驱动入参（--classpath/--classpath-file）= {explicit}"
    env = os.environ.get("ERBCC_CLASSPATH", "").strip()
    if env:
        return read_classpath_spec(env), f"档 1：ERBCC_CLASSPATH = {env}"
    if os.path.isdir(INSTALL_LIB):
        jars = sorted(
            os.path.join(INSTALL_LIB, n)
            for n in os.listdir(INSTALL_LIB)
            if n.endswith(".jar") and os.path.isfile(os.path.join(INSTALL_LIB, n))
        )
        if not jars:
            raise EnvError(f"{INSTALL_LIB} 里没有 jar：先跑 {INSTALL_DIST_HINT}")
        return SEPARATOR.join(jars), f"档 2：install 目录逐 jar 列举（{len(jars)} 枚，字典序）"
    raise EnvError(
        f"取不到 erbcc 的 JVM classpath：ERBCC_CLASSPATH 未设且 {INSTALL_LIB} 不存在。\n"
        f"先跑 `{INSTALL_DIST_HINT}`，或 `python devtools/erbcc_rs_parity.py classpath` 现产一条。"
    )


def resolve_rust_exe(explicit: str | None) -> str:
    exe = explicit or os.path.join(
        RS_DIR, "target", "release", "erbcc" + (".exe" if os.name == "nt" else "")
    )
    if not os.path.isfile(exe):
        raise EnvError(f"Rust CLI 不存在：{exe}。先 `{CARGO_BUILD_HINT}`（Windows 产物带 .exe）。")
    return exe


# ---------------------------------------------------------------- 子进程

def run_capture(argv: list[str], cwd: str) -> subprocess.CompletedProcess:
    """不经 shell（Windows 下引号由 subprocess 自己拼；中文路径不经过 shell 就不会被吞）。"""
    return subprocess.run(argv, cwd=cwd, capture_output=True)


def decode(b: bytes) -> str:
    return b.decode("utf-8", errors="replace")


def tail(text: str, n: int = 12) -> str:
    lines = [l for l in text.splitlines() if l.strip()]
    return "\n".join(lines[-n:])


# ---------------------------------------------------------------- 两腿

def kotlin_compile_leg(java: str, classpath: str, corpus: str, out: str,
                       limit: str | None, tag: str) -> None:
    argv = [java, COMPILE_HEAP_OPT, *JVM_OPTS, "-cp", classpath, MAIN_CLASS,
            "compile", "--corpus", corpus, "--out", out]
    if limit:
        argv += ["--limit", limit]
    t0 = time.time()
    r = run_capture(argv, cwd=APP_ROOT)
    dt = time.time() - t0
    log(f"[{tag}] Kotlin 腿 rc={r.returncode} 用时 {dt:.1f}s")
    if r.returncode != 0:
        raise ParityFail(
            f"Kotlin 腿退出码 {r.returncode}（期望 0）。stderr 尾部 ↓\n{tail(decode(r.stderr))}"
        )


def rust_compile_leg(exe: str, classpath: str, corpus: str, out: str,
                     limit: str | None, tag: str) -> None:
    argv = [exe, "compile", "--corpus", corpus, "--out", out]
    if limit:
        argv += ["--limit", limit]
    env = dict(os.environ)
    env["ERBCC_CLASSPATH"] = classpath   # 档 1 直接串：两腿吃同一份 classpath 内容
    t0 = time.time()
    r = subprocess.run(argv, cwd=APP_ROOT, capture_output=True, env=env)
    dt = time.time() - t0
    log(f"[{tag}] Rust 腿 rc={r.returncode} 用时 {dt:.1f}s")
    if r.returncode != 0:
        raise ParityFail(
            f"Rust 腿退出码 {r.returncode}（期望 0）。stderr 尾部 ↓\n{tail(decode(r.stderr))}"
        )


def fake_compile_leg(fake_exe: str, corpus: str, out: str) -> None:
    """selftest 用：跑「假 Rust 腿」（bat → python 脚本，从 FAKE_RUST_LEG_SRC 拷贝产物），
    只验证整条 parity 命令链路不吃被篡改的腿。假腿脚本自己读 FAKE_RUST_LEG_SRC 环境变量。"""
    r = subprocess.run([fake_exe, "compile", "--corpus", corpus, "--out", out],
                       cwd=APP_ROOT, capture_output=True)
    if r.returncode != 0:
        raise ParityFail(f"假 Rust 腿自己没跑起来（rc={r.returncode}）：\n{tail(decode(r.stderr))}")


# ---------------------------------------------------------------- 比较

def four_piece(dir_: str) -> list[tuple[str, int, str, bytes | None]]:
    rows = []
    for name in FOUR:
        p = os.path.join(dir_, ERBC_DIR, name)
        if not os.path.isfile(p):
            rows.append((name, -1, f"<缺失 {p}>", None))
        else:
            size, sha = sha256_file(p)
            rows.append((name, size, sha, None))
    return rows


def first_byte_diff(a: bytes, b: bytes) -> int:
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n if len(a) != len(b) else -1


def compare_dirs(kotlin_dir: str, rust_dir: str, expect_file: str | None) -> None:
    """四件套逐个 sha256 全等 + 可选 --expect 登记对照；红则抛 ParityFail（点名首个差异）。"""
    ka_rows = four_piece(kotlin_dir)
    ra_rows = four_piece(rust_dir)
    log("四件套读数（Kotlin 腿 vs Rust 腿）：")
    log(f"  {'文件':<18} {'字节':>10}  sha256（腿A == 腿B？）")
    diffs: list[str] = []
    for (name, ksize, ksha, _), (_, rsize, rsha, _) in zip(ka_rows, ra_rows):
        eq = ksha == rsha and ksize == rsize
        log(f"  {name:<18} {ksize:>10}  {ksha[:16]}…  {'OK' if eq else f'DIFF 腿B={rsize} {rsha[:16]}…'}")
        if not eq:
            diffs.append(name)
    if diffs:
        first = diffs[0]
        kp = os.path.join(kotlin_dir, ERBC_DIR, first)
        rp = os.path.join(rust_dir, ERBC_DIR, first)
        detail = ""
        if os.path.isfile(kp) and os.path.isfile(rp):
            a = open(kp, "rb").read()
            b = open(rp, "rb").read()
            off = first_byte_diff(a, b)
            detail = f"；首个字节差在偏移 {off}（A 长 {len(a)} / B 长 {len(b)}）"
        raise ParityFail(
            f"双跑不一致：{len(diffs)}/4 件不等，首个差异 = {first}{detail}\n"
            f"  期望（Kotlin 腿）: {kp}\n  实际（Rust 腿）  : {rp}"
        )
    if expect_file:
        check_expect(expect_file, kotlin_dir)


def parse_expect(path: str) -> list[tuple[str, int | None, str]]:
    out = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) == 3:
                name, size, sha = parts
                out.append((name, int(size), sha))
            elif len(parts) == 2:
                out.append((parts[0], None, parts[1]))
            else:
                raise EnvError(f"--expect 行形状不认识（要 `name [bytes] sha256`）：{line}")
    return out


def check_expect(expect_file: str, leg_dir: str) -> None:
    """与登记 sha 对照（闸 4 复现判据）；对照的是「腿产物 vs 登记值」，两侧腿已先证明相等。"""
    bad = []
    for name, size, sha in parse_expect(expect_file):
        p = os.path.join(leg_dir, ERBC_DIR, name)
        if not os.path.isfile(p):
            bad.append(f"{name}: 产物缺失 {p}")
            continue
        got_size, got_sha = sha256_file(p)
        if got_sha != sha or (size is not None and got_size != size):
            bad.append(f"{name}: 登记 {size} {sha}，实际 {got_size} {got_sha}")
        else:
            log(f"  expect {name:<18} {got_size:>10}  {got_sha[:16]}…  OK（与登记一致）")
    if bad:
        raise ParityFail("与登记 sha 不一致：\n  " + "\n  ".join(bad))


# ---------------------------------------------------------------- parity 子命令

def cmd_parity(ns: argparse.Namespace) -> int:
    corpus = winpath(ns.corpus)
    if not os.path.isdir(corpus):
        raise EnvError(f"corpus 不是目录: {corpus}")
    base_out = winpath(ns.out) if ns.out else winpath(
        os.path.join(DRIVER_BUILD, time.strftime("%Y%m%d-%H%M%S"))
    )
    k_dir = os.path.join(base_out, "kotlin")
    r_dir = os.path.join(base_out, "rust")
    shutil.rmtree(base_out, ignore_errors=True)
    os.makedirs(k_dir)
    os.makedirs(r_dir)

    java = resolve_java()
    classpath, cp_origin = resolve_classpath(
        ns.classpath or ns.classpath_file or None
    )
    rust_exe = resolve_rust_exe(ns.rust_exe)
    log(f"语料 = {corpus}")
    log(f"classpath：{cp_origin}")
    log(f"Rust CLI = {rust_exe}")
    log(f"产物基准目录 = {base_out}（limit={ns.limit or '全量'}）")

    try:
        kotlin_compile_leg(java, classpath, corpus, k_dir, ns.limit, "parity")
        if ns.rust_exe and ns.rust_exe.lower().endswith(".bat"):
            fake_compile_leg(ns.rust_exe, corpus, r_dir)
        else:
            rust_compile_leg(rust_exe, classpath, corpus, r_dir, ns.limit, "parity")
        compare_dirs(k_dir, r_dir, ns.expect)
    finally:
        if ns.keep:
            log(f"--keep：产物留在 {base_out}")
        else:
            shutil.rmtree(base_out, ignore_errors=True)
            log(f"临时产物已删除：{base_out}")
    log("双跑对拍：绿（四件套 sha256 全等）")
    return 0


# ---------------------------------------------------------------- classpath 子命令

def run_gradle(args: list[str]) -> None:
    if not os.path.isfile(GRADLEW_BAT):
        raise EnvError(f"找不到 {GRADLEW_BAT}")
    argv = ["cmd.exe", "/c", GRADLEW_BAT, "--configure-on-demand", *args]
    log(f"[gradle] {' '.join(args)}")
    r = run_capture(argv, cwd=APP_ROOT)
    if r.returncode != 0:
        raise ParityFail(
            f"gradle 退出码 {r.returncode}：\n{tail(decode(r.stdout))}\n{tail(decode(r.stderr))}"
        )


def cmd_classpath(ns: argparse.Namespace) -> int:
    if not ns.no_build:
        run_gradle([":tools:erbcc:installDist"])
    if not os.path.isdir(INSTALL_LIB):
        raise EnvError(f"install 目录不存在：{INSTALL_LIB}（别加 --no-build，或先跑 {INSTALL_DIST_HINT}）")
    jars = sorted(
        os.path.join(INSTALL_LIB, n)
        for n in os.listdir(INSTALL_LIB)
        if n.endswith(".jar") and os.path.isfile(os.path.join(INSTALL_LIB, n))
    )
    if not jars:
        raise EnvError(f"{INSTALL_LIB} 里没有 jar：先跑 {INSTALL_DIST_HINT}")
    log(f"classpath 条目（{len(jars)} 枚，字典序）：")
    for j in jars:
        log(f"  {j}")
    if ns.mode == "direct":
        log("ERBCC_CLASSPATH 直接串（档 1 第二态）：")
        log(SEPARATOR.join(jars))
    else:
        out_file = ns.out_file or winpath(os.path.join(
            APP_ROOT, "build", "erbcc-rs-classpath.txt"))
        os.makedirs(os.path.dirname(out_file), exist_ok=True)
        with open(out_file, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(jars) + "\n")
        log(f"classpath 文件（档 1 第一态，多行）已写：{out_file}")
    log("接线：")
    if os.name == "nt":
        log('  cmd : set "ERBCC_CLASSPATH=' + (ns.out_file or winpath(os.path.join(
            APP_ROOT, "build", "erbcc-rs-classpath.txt"))) + '"'
            if ns.mode != "direct" else '  cmd : set "ERBCC_CLASSPATH=' + SEPARATOR.join(jars) + '"')
        log('  bash: export ERBCC_CLASSPATH="<上面那条的值>"')
    return 0


# ---------------------------------------------------------------- regen 子命令

def old_sha_map(paths: list[str]) -> dict[str, str]:
    m = {}
    for p in paths:
        if os.path.isfile(p):
            _, sha = sha256_file(p)
            m[p] = sha
    return m


def print_regen_table(rows: list[tuple[str, str, str, str]]) -> None:
    log()
    log(f"{'文件（相对 tests/data）':<58} {'旧 sha8':<10} {'新 sha8':<10} 判定")
    for rel, old, new, note in rows:
        status = "不变" if old == new else "已更新"
        log(f"{rel:<58} {old[:8]:<10} {new[:8]:<10} {status}{('；' + note) if note else ''}")
    log()


def manifest_path_only_change(old_text: str, new_text: str) -> tuple[bool, list[str]]:
    """新旧清单的行级差异是否只落在 `corpus-root` 这类含绝对路径的行上。"""
    old_lines = old_text.splitlines()
    new_lines = new_text.splitlines()
    changed = []
    for i in range(max(len(old_lines), len(new_lines))):
        a = old_lines[i] if i < len(old_lines) else "<无此行>"
        b = new_lines[i] if i < len(new_lines) else "<无此行>"
        if a != b:
            changed.append((i + 1, a, b))
    only_path = all(
        b.startswith("corpus-root ") or a.startswith("corpus-root ")
        for _, a, b in changed
    )
    return only_path, changed


def kotlin_check_leg(java: str, classpath: str, cwd: str, corpus: str,
                     out_dir: str) -> tuple[int, bytes, str]:
    argv = [java, *JVM_OPTS, "-cp", classpath, MAIN_CLASS,
            "check", "--corpus", corpus, "--out", out_dir]
    r = run_capture(argv, cwd=cwd)
    return r.returncode, r.stdout, decode(r.stderr)


def copy_tree(src: str, dst: str) -> None:
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(src):
        s = os.path.join(src, name)
        d = os.path.join(dst, name)
        if os.path.isdir(s):
            copy_tree(s, d)
        else:
            shutil.copy2(s, d)


def build_check_case(ws: str, case: str, erbc_src: str, corpus_src: str) -> None:
    """与 tests/freshness_parity.rs:55-97 的 build_case 同法（破坏方式是 Kotlin 用例的，不是自创）。"""
    cdir = os.path.join(ws, "cases", case)
    shutil.rmtree(cdir, ignore_errors=True)
    os.makedirs(cdir)
    copy_tree(erbc_src, os.path.join(cdir, ERBC_DIR))
    copy_tree(corpus_src, os.path.join(cdir, "corpus"))
    if case == "good":
        return
    if case == "source-changed":
        with open(os.path.join(cdir, "corpus", "01_main.ERB"), "wb") as f:
            f.write(SOURCE_CHANGED_BYTES)
        return
    if case == "truncated":
        p = os.path.join(cdir, ERBC_DIR, "program.erbc")
        data = open(p, "rb").read()
        keep = len(data) * 3 // 5
        with open(p, "wb") as f:
            f.write(data[:keep])
        log(f"  truncated：容器 {len(data)} 字节 → {keep} 字节（Kotlin 用例同式 size*3/5）")
        return
    if case == "no-digest-line":
        p = os.path.join(cdir, ERBC_DIR, "manifest.txt")
        text = open(p, "r", encoding="utf-8").read()
        kept = [l for l in text.splitlines()
                if not l.startswith("compiler-digest ") and not l.startswith("compiler-sources ")]
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(kept) + "\n")
        return
    raise EnvError(f"未知档位 {case}")


def fixture_from_stdout(raw: bytes, abs_out_dir: str, case: str) -> bytes:
    r"""Kotlin `println(summary)` 的原始 stdout → 入库 fixture 的形状（显式转换，逐条写明）：

    1. 尾部 `\r\n`（JVM println 的平台行分隔符）折成**无尾换行**；内部行分隔保持 `\n`
       （`ArtifactFreshness.kt:89-95` 的 summary 本就按 `\n` 连接）；
    2. 首行的 `<绝对 outDir>: ` 前缀替换为 `cases\\<档名>: ` —— 第一波入库档
       （如 `cases\good: 产物与语料/编译器一致…`）就是这么缩写的，让 fixture 不夹带
       产出机绝对路径；`freshness_parity.rs:107-129` 用 `split_once(": ")` 回读这段前缀
       再整体替换成实际目录，任何**只出现在首行开头**的串都能被它接受，这里沿用原约定。
       摘要正文（条数行、`KIND: detail`）不含路径，比对的是原文。
    """
    text = raw.decode("utf-8")
    text = text.replace("\r\n", "\n").rstrip("\n")
    prefix = os.path.normpath(os.path.abspath(abs_out_dir))
    if os.name == "nt":
        prefix = prefix.replace("/", "\\")
    head = prefix + ": "
    if not text.startswith(head):
        raise ParityFail(
            f"check 首行前缀不是「绝对 outDir: 」（Kotlin 侧行为变了？）：{text[:80]!r}"
        )
    text = os.path.join("cases", case) + ": " + text[len(head):]
    return text.encode("utf-8")


def regen_rust_manifest(java: str, classpath: str, dry: bool) -> list[tuple[str, str, str, str]]:
    """重生成 tests/data/rust-manifest：erbc.v1 四件套 + expected 八件套（Kotlin 真跑产出）。"""
    rows: list[tuple[str, str, str, str]] = []
    erbc_dir = os.path.join(MANIFEST_DATA, ERBC_DIR)
    exp_dir = os.path.join(MANIFEST_DATA, "expected")
    watched = [os.path.join(erbc_dir, n) for n in FOUR] + [
        os.path.join(exp_dir, f"check-{c}.{ext}")
        for c in CHECK_CASES for ext in ("stdout.bin", "rc")
    ]
    olds = old_sha_map(watched)

    work = os.path.join(DRIVER_BUILD, f"regen-manifest-{os.getpid()}")
    shutil.rmtree(work, ignore_errors=True)
    compile_out = os.path.join(work, "out")
    os.makedirs(compile_out)
    # ① 纯 Kotlin 腿真编译（基准必须是 Kotlin 工具链产的，Rust 腿不参与基准产出）
    kotlin_compile_leg(java, classpath, winpath(SMALL_CORPUS), winpath(compile_out), None, "regen-manifest")
    new_manifest_p = os.path.join(compile_out, ERBC_DIR, "manifest.txt")
    note = ""
    if os.path.isfile(new_manifest_p) and os.path.isfile(os.path.join(erbc_dir, "manifest.txt")):
        old_text = open(os.path.join(erbc_dir, "manifest.txt"), "r", encoding="utf-8").read()
        new_text = open(new_manifest_p, "r", encoding="utf-8").read()
        only_path, changed = manifest_path_only_change(old_text, new_text)
        if changed:
            first = changed[0]
            if only_path:
                note = f"差异 {len(changed)} 行，全部是 corpus-root 路径行"
            else:
                note = (f"差异 {len(changed)} 行且含非路径行（第一处第 {first[0]} 行："
                        f"旧 `{first[1][:60]}` → 新 `{first[2][:60]}`）—— 这是编译器真变了，不是换机")
    for name in FOUR:
        src = os.path.join(compile_out, ERBC_DIR, name)
        dst = os.path.join(erbc_dir, name)
        _, new_sha = sha256_file(src)
        rows.append((f"rust-manifest/{ERBC_DIR}/{name}", olds.get(dst, "<无>"), new_sha,
                     note if name == "manifest.txt" else ""))
        if not dry:
            os.makedirs(erbc_dir, exist_ok=True)
            shutil.copy2(src, dst)

    # ② expected 八件套：基于**刚落的** erbc.v1 重搭四档（与 freshness_parity 的 build_case 同源）
    ws = os.path.join(work, "workspace")
    os.makedirs(ws)
    src_erbc = os.path.join(compile_out, ERBC_DIR)   # 干跑时基准目录没动，新档一律出自临时产物
    for case in CHECK_CASES:
        build_check_case(ws, case, src_erbc, SMALL_CORPUS)
        case_dir = os.path.join(ws, "cases", case)
        rc, stdout_b, stderr_tail = kotlin_check_leg(
            java, classpath, cwd=ws,
            corpus=os.path.join("cases", case, "corpus"),
            out_dir=os.path.join("cases", case),
        )
        payload = fixture_from_stdout(stdout_b, case_dir, case)
        exp_bin = os.path.join(exp_dir, f"check-{case}.stdout.bin")
        exp_rc = os.path.join(exp_dir, f"check-{case}.rc")
        new_sha = sha256_bytes(payload)
        rows.append((f"rust-manifest/expected/check-{case}.stdout.bin",
                     olds.get(exp_bin, "<无>"), new_sha, f"rc={rc}"))
        rows.append((f"rust-manifest/expected/check-{case}.rc",
                     olds.get(exp_rc, "<无>"), sha256_bytes(f"{rc}\n".encode()), ""))
        if rc not in (0, 1):
            log(f"  警告：档 {case} 退出码 {rc} 不在 0/1 契约内（Main.kt:422），先看 stderr：{tail(stderr_tail)}")
        if not dry:
            os.makedirs(exp_dir, exist_ok=True)
            with open(exp_bin, "wb") as f:
                f.write(payload)
            with open(exp_rc, "w", encoding="utf-8", newline="\n") as f:
                f.write(f"{rc}\n")
    shutil.rmtree(work, ignore_errors=True)
    return rows


def regen_rust_kernel(java: str, classpath: str, dry: bool, use_cli: bool) -> list[tuple[str, str, str, str]]:
    """重生成 tests/data/rust-kernel/parity-baseline-sha256.txt（产法照该文件头注：compileCorpus）。"""
    rows: list[tuple[str, str, str, str]] = []
    olds = old_sha_map([KERNEL_BASELINE])
    work = os.path.join(DRIVER_BUILD, f"regen-kernel-{os.getpid()}")
    shutil.rmtree(work, ignore_errors=True)
    game = os.path.join(work, "game")
    erb = os.path.join(game, "ERB")
    os.makedirs(erb)
    os.makedirs(os.path.join(game, "CSV"))
    for name in GOLDEN_NAMES:
        s = os.path.join(GOLDEN_DIR, name)
        if not os.path.isfile(s):
            raise EnvError(f"金标 fixture 缺失：{s}")
        d = os.path.join(erb, name.replace("GOLDEN_", "PARITY_"))
        shutil.copy2(s, d)
        ss, ds = sha256_file(s)[1], sha256_file(d)[1]
        if ss != ds:
            raise ParityFail(f"副本 {name} 与 fixture 不同字节（对拍的是两份语料了）")
    out_dir = os.path.join(work, "out")
    os.makedirs(out_dir)
    if use_cli:
        kotlin_compile_leg(java, classpath, winpath(erb), winpath(out_dir), None, "regen-kernel(cli)")
    else:
        run_gradle([":tools:erbcc:compileCorpus",
                    f"-Pcorpus={winpath(erb)}", f"-Pout={winpath(out_dir)}"])
    new_lines = []
    for name in THREE:
        p = os.path.join(out_dir, ERBC_DIR, name)
        if not os.path.isfile(p):
            raise ParityFail(f"compileCorpus 产物缺 {p}")
        size, sha = sha256_file(p)
        new_lines.append(f"{ERBC_DIR}/{name} {size} {sha}")
    old_text = open(KERNEL_BASELINE, "r", encoding="utf-8").read() if os.path.isfile(KERNEL_BASELINE) else ""
    old_data = [l for l in old_text.splitlines() if l and not l.startswith("#")]
    for i, line in enumerate(new_lines):
        old_line = old_data[i] if i < len(old_data) else "<无>"
        old_sha = old_line.split()[-1] if old_line != "<无>" else "<无>"
        rows.append((f"rust-kernel/{line.split()[0]}", old_sha, line.split()[-1], ""))
    if not dry:
        header = [l for l in old_text.splitlines()
                  if l.startswith("#") and not l.startswith("# 最近一次重生成")]
        stamp = (f"# 最近一次重生成：{time.strftime('%Y-%m-%d %H:%M:%S')} · "
                 f"python devtools/erbcc_rs_parity.py regen --lane rust-kernel"
                 + ("（--use-cli-kotlin，未走 Gradle 腿）" if use_cli else ""))
        with open(KERNEL_BASELINE, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(header) + "\n" + stamp + "\n")
            f.write("\n".join(new_lines) + "\n")
    shutil.rmtree(work, ignore_errors=True)
    return rows


REGEN_LINE = re.compile(r"^ERBCC_RS_REGEN\|([^|]+)\|(\d+)\|([0-9a-f]{64})$")


def roundtrip_tsv_diff_note(old_bytes: bytes | None, new_bytes: bytes) -> str:
    """明细 TSV 的新旧行级差异判定（「仅 corpus-root 路径行」那套口径在本路的对应物）：
    五列全是判据名/读数/哈希 ⇒ 差异只可能来自编译器输出；按行点名差在哪几条判据上。"""
    if old_bytes is None:
        return "新增基准"
    old_lines = old_bytes.decode("utf-8", errors="replace").splitlines()
    new_lines = new_bytes.decode("utf-8", errors="replace").splitlines()
    names = []
    for i in range(max(len(old_lines), len(new_lines))):
        a = old_lines[i] if i < len(old_lines) else "<无此行>"
        b = new_lines[i] if i < len(new_lines) else "<无此行>"
        if a != b:
            cols = a.split("\t")
            names.append(cols[1] if len(cols) >= 2 else f"第 {i + 1} 行")
    if not names:
        return "行同级但字节差（行终止符/编码）"
    if all(n == "meta.program-sha256" for n in names):
        return "差异仅 meta.program-sha256 摘要行（容器字节随编译器变）"
    head = "、".join(names[:6])
    return f"差异判据 {len(names)} 行（{head}{' 等' if len(names) > 6 else ''}）—— 编译器输出真变了，不是换机"


def regen_rust_roundtrip(classpath: str, dry: bool) -> list[tuple[str, str, str, str]]:
    """重生成 tests/data/rust-roundtrip：点名跑 cargo 侧的开关格（真 Kotlin 腿现跑写盘），
    本函数只负责调用、解析读数行与**校验落盘** —— 基准字节一个都不由 python 拼装。"""
    cargo = shutil.which("cargo")
    if not cargo:
        raise EnvError("找不到 cargo：rust-roundtrip 档的产出方是 cargo test，需要 Rust 工具链在 PATH")
    test_src = os.path.join(RS_DIR, "tests", "roundtrip_parity.rs")
    if not os.path.isfile(test_src):
        raise EnvError(f"缺 {test_src}：rust-roundtrip 基准由该文件的 {ROUNDTRIP_TEST} 格现产")
    watched = [os.path.join(ROUNDTRIP_DATA, rel.replace("/", os.sep)) for rel in ROUNDTRIP_FILES]
    olds = old_sha_map(watched)
    old_texts = {}
    for rel, p in zip(ROUNDTRIP_FILES, watched):
        if os.path.isfile(p):
            old_texts[rel] = open(p, "rb").read()   # 写盘前先把旧字节留底，供差异判定

    env = dict(os.environ)
    env["ERBCC_RS_REGEN_ROUNDTRIP"] = "dry" if dry else "1"
    env["ERBCC_CLASSPATH"] = classpath             # 档 1 直接串（文件态会被 Kotlin 腿当主类路径，假红）
    argv = [cargo, "test", "--test", "roundtrip_parity", "--", "--exact", ROUNDTRIP_TEST, "--nocapture"]
    log(f"[regen-roundtrip] {' '.join(argv[:4])} … -- --exact {ROUNDTRIP_TEST} "
        f"（ERBCC_RS_REGEN_ROUNDTRIP={env['ERBCC_RS_REGEN_ROUNDTRIP']}）")
    # 不走 run_capture：它不透传 env，而 regen 开关必须随 cargo 进到测试子进程里
    r = subprocess.run(argv, cwd=RS_DIR, capture_output=True, env=env)
    out_text = decode(r.stdout) + decode(r.stderr)
    if r.returncode != 0:
        raise ParityFail(
            f"regen Kotlin 腿没跑成（cargo rc={r.returncode}）—— 半成品基准不落盘。输出尾部 ↓\n{tail(out_text, 25)}"
        )
    seen: dict[str, tuple[int, str]] = {}
    for line in out_text.splitlines():
        m = REGEN_LINE.match(line.strip())
        if not m:
            continue
        rel, size, sha = m.group(1), int(m.group(2)), m.group(3)
        if rel in seen:
            raise ParityFail(f"regen 读数行重复：{rel}")
        if size == 0:
            raise ParityFail(f"regen 防空转：{rel} 现跑产出 0 字节")
        seen[rel] = (size, sha)
    missing = [rel for rel in ROUNDTRIP_FILES if rel not in seen]
    extra = sorted(set(seen) - set(ROUNDTRIP_FILES))
    if missing or extra:
        raise ParityFail(
            f"regen 必须成套产出 10 件（缺 {missing or '无'}；多 {extra or '无'}）—— "
            f"三件套半套写盘禁止"
        )
    rows: list[tuple[str, str, str, str]] = []
    for rel in ROUNDTRIP_FILES:
        p = os.path.join(ROUNDTRIP_DATA, rel.replace("/", os.sep))
        size, sha = seen[rel]
        old = olds.get(p, "<无>")
        note = ""
        if not dry:
            if not os.path.isfile(p):
                raise ParityFail(f"cargo 报写了 {rel} 但盘上没有：{p}（落盘校验失败）")
            got_size, got_sha = sha256_file(p)
            if (got_size, got_sha) != (size, sha):
                raise ParityFail(
                    f"{rel} 落盘字节与 cargo 读数不等（打印 {size} {sha}，实际 {got_size} {got_sha}）"
                )
            if sha != old and rel.endswith(".tsv") and old_texts.get(rel) is not None:
                note = roundtrip_tsv_diff_note(old_texts[rel], open(p, "rb").read())
        rows.append((f"rust-roundtrip/{rel}", old, sha, note))
    log(f"rust-roundtrip：{len(rows)} 件基准全部出自真 Kotlin 腿"
        + ("（dry，未写盘）" if dry else "，cargo 侧写盘后 python 已逐件回读校验"))
    return rows


# 本机全量基准（在 `build/` 里，gitignore 面）：`tests/digest_parity.rs` /
# `tests/freshness_parity.rs` 的「第二读数」缺档时自动 SKIP，所以它红不红与入库闸无关，
# 但它钉的是 Kotlin 真跑的读数 —— 编译器一变就作废，作废就得重录。
FULL_BASELINE_DIR = os.path.join(RS_DIR, "build", "baseline", "full")
FULL_CHECK_BIN = os.path.join(RS_DIR, "build", "baseline", "expected-full-check.bin")


def regen_rust_full(java: str, classpath: str, dry: bool) -> list[tuple[str, str, str, str]]:
    """重录**本机**全量基准：`compileCorpus` 全量腿 + Kotlin `check` 的原始 stdout。

    两个实测坑在这里挡住（2026-11-01 收编 1101 挖虫波时各踩一次）：
    - `-Pout`/`-Pcorpus` 是**相对 `:tools:erbcc` 工程目录**解析的 —— 给相对 `-Pout` 会落成
      `../erak-engine/tools/erbcc/tools/erbcc-rs/build/...`，得手动搬。这里一律先 `abspath` 再 `winpath`。
    - Windows 上 Kotlin 的 stdout 结尾带 `\r\n`，而 `full_corpus_artifact_verdict_matches_kotlin`
      拿 `report.summary()`（无结尾换行）逐字节比 —— 不剥掉就差一个换行红。
    """
    corpus = os.environ.get("ERATW_CORPUS", "").strip()
    if not corpus:
        raise EnvError("全量档要 ERATW_CORPUS，且必须指到语料的 ERB 子树（指游戏根会把教程 "
                       "ERB/ERH 一起扫进清单，与基线 3971 口径不可比）")
    corpus_abs = winpath(os.path.abspath(corpus))
    if not os.path.isdir(corpus_abs):
        raise EnvError(f"ERATW_CORPUS 不是目录：{corpus_abs}")
    out_dir = winpath(FULL_BASELINE_DIR)
    bin_path = winpath(FULL_CHECK_BIN)
    recorded = [os.path.join(out_dir, ERBC_DIR, n) for n in FOUR] + [bin_path]
    olds = old_sha_map(recorded)
    if dry:
        for p in recorded:
            rel = display_rel(p)
            log(f"dry-run：{rel} 现值 {olds.get(p, '<无>')[:8]}（全量档要真跑 ~80 s，不预判）")
        return []
    run_gradle([":tools:erbcc:compileCorpus", f"-Pcorpus={corpus_abs}", f"-Pout={out_dir}"])
    rows: list[tuple[str, str, str, str]] = []
    for name in FOUR:
        p = os.path.join(out_dir, ERBC_DIR, name)
        if not os.path.isfile(p):
            raise ParityFail(f"全量腿没落出 {p}")
        size, sha = sha256_file(p)
        rows.append((display_rel(p), olds.get(p, "<无>"), sha, f"{size} 字节 · 本机基准，不入库"))
    rc, raw, err = kotlin_check_leg(java, classpath, APP_ROOT, corpus_abs, out_dir)
    if rc != 0:
        raise ParityFail(f"Kotlin check 全量腿 rc={rc}：\n{tail(err)}")
    raw = raw.rstrip(b"\r\n")        # 见 docstring：summary 无结尾换行
    sha = sha256_bytes(raw)
    os.makedirs(os.path.dirname(bin_path), exist_ok=True)
    with open(bin_path, "wb") as f:
        f.write(raw)
    rows.append((display_rel(bin_path), olds.get(bin_path, "<无>"), sha,
                 "Kotlin check 真跑 stdout（已剥结尾 \\r\\n）"))
    log(f"全量基准落点：{out_dir}")
    return rows


def display_rel(path: str) -> str:
    """表格里显示成相对 erak-engine `tools/erbcc-rs` 的路径（全量基准不在 tests/data 下）。"""
    try:
        return os.path.relpath(path, RS_DIR).replace("\\", "/")
    except ValueError:
        return path


def cmd_regen(ns: argparse.Namespace) -> int:
    java = resolve_java()
    classpath, cp_origin = resolve_classpath(ns.classpath or None)
    log(f"classpath：{cp_origin}")
    all_rows: list[tuple[str, str, str, str]] = []
    if ns.lane in ("all", "rust-manifest"):
        all_rows += regen_rust_manifest(java, classpath, ns.dry_run)
    if ns.lane in ("all", "rust-kernel"):
        all_rows += regen_rust_kernel(java, classpath, ns.dry_run, ns.use_cli_kotlin)
    if ns.lane in ("all", "rust-roundtrip"):
        all_rows += regen_rust_roundtrip(classpath, ns.dry_run)
    if ns.lane == "rust-full":
        all_rows += regen_rust_full(java, classpath, ns.dry_run)
    print_regen_table(all_rows)
    changed = [r for r in all_rows if r[1] != r[2]]
    if ns.dry_run and ns.lane == "rust-full":
        pass
    elif ns.dry_run:
        log(f"dry-run：{len(changed)} 个文件会与现状不同（未写盘）。")
    else:
        log(f"重生成完成：{len(all_rows)} 个基准文件写回，其中 {len(changed)} 个内容有变。")
        log("下一步（闸 2 的实质自证）：cd ../erak-engine/tools/erbcc-rs && cargo test --test digest_parity "
            "--test manifest_parity --test freshness_parity   # 期望 17/0，且上表全部「不变」或「仅路径行」")
        if ns.lane in ("all", "rust-roundtrip"):
            log("下一步（rust-roundtrip 自证）：cd ../erak-engine/tools/erbcc-rs && cargo test --test roundtrip_parity "
                "  # 期望 17/0（16 格入库闸 + 开关格未置位自跳过），且上表 tamper 三件套**成套**「不变」")
        if ns.lane in ("all", "rust-manifest", "rust-kernel", "rust-roundtrip"):
            log("提醒：`compiler-digest` 变了 ⇒ 本机全量基准（gitignore 面）同样作废，"
                "还要补跑 `regen --lane rust-full`（约 80 s，显式档，不在 all 里），否则 "
                "`freshness_parity::full_corpus_artifact_verdict_matches_kotlin` 差一个旧 digest 红。")
    return 0


# ---------------------------------------------------------------- selftest（反向证明）

FAKE_RUST_LEG_PY = """\
import os, shutil, sys
# 假 Rust 腿：把 FAKE_RUST_LEG_SRC/erbc.v1 整包拷进 --out/erbc.v1，退 0。
# 篡改在自测里**事前**做进源目录，本脚本只拷贝 —— 模拟「Rust 腿产出与 Kotlin 腿不同字节」的世界。
out = sys.argv[sys.argv.index("--out") + 1]
src = os.path.join(os.environ["FAKE_RUST_LEG_SRC"], "erbc.v1")
dst = os.path.join(out, "erbc.v1")
shutil.copytree(src, dst, dirs_exist_ok=True)
"""

FAKE_RUST_LEG_BAT = '@echo off\r\npython "%~dp0fake_rust_leg.py" %*\r\n'


def cmd_selftest(ns: argparse.Namespace) -> int:
    """反向证明：驱动必须「红得其所」——篡改腿 / 假登记 / 篡改语料三案都要被抓并点名差异。"""
    work = os.path.join(DRIVER_BUILD, f"selftest-{os.getpid()}")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    failures = []

    java = resolve_java()
    classpath, _ = resolve_classpath(None)
    corpus = winpath(SMALL_CORPUS)

    # 真跑一趟 Kotlin 腿当「干净参照」
    k_dir = os.path.join(work, "kotlin")
    os.makedirs(k_dir)
    kotlin_compile_leg(java, classpath, corpus, winpath(k_dir), None, "selftest")

    # 造一个被篡改的「Rust 腿产物源」（program.erbc 中间翻一个字节）
    t_src = os.path.join(work, "fake-rust-src")
    os.makedirs(t_src)
    copy_tree(os.path.join(k_dir, ERBC_DIR), os.path.join(t_src, ERBC_DIR))
    p = os.path.join(t_src, ERBC_DIR, "program.erbc")
    data = bytearray(open(p, "rb").read())
    data[len(data) // 2] ^= 0xFF
    open(p, "wb").write(bytes(data))

    # ① 整条 parity 命令挂假 Rust 腿：必须 rc=1 且点名 program.erbc
    if os.name == "nt":
        open(os.path.join(work, "fake_rust_leg.py"), "w", encoding="utf-8", newline="\n").write(
            FAKE_RUST_LEG_PY)
        open(os.path.join(work, "fake_rust_leg.bat"), "w", encoding="utf-8", newline="\r\n").write(
            FAKE_RUST_LEG_BAT)
        env = dict(os.environ)
        env["FAKE_RUST_LEG_SRC"] = t_src
        r = subprocess.run(
            [sys.executable, os.path.join(HERE, "erbcc_rs_parity.py"), "parity",
             "--corpus", corpus, "--out", winpath(os.path.join(work, "e2e-parity")),
             "--rust-exe", os.path.join(work, "fake_rust_leg.bat")],
            cwd=APP_ROOT, capture_output=True, env=env)
        out_text = decode(r.stdout) + decode(r.stderr)
        if r.returncode != 1:
            failures.append(f"反向证明①失明：假腿篡改了 program.erbc，parity 却退 {r.returncode}\n{tail(out_text)}")
        elif "program.erbc" not in out_text:
            failures.append(f"反向证明①没点名 program.erbc：\n{tail(out_text)}")
        else:
            log(f"反向证明①：parity 挂被篡改的假 Rust 腿 ⇒ rc=1 且点名 program.erbc ✔")
            log("  屏上原样文案：\n  " + "\n  ".join(out_text.splitlines()[-4:]))
    else:
        # 非 Windows：退化为直接调比较引擎（同样必须红）
        try:
            compare_dirs(k_dir, t_src, None)
            failures.append("反向证明①失明：program.erbc 被改一个字节，compare_dirs 竟然放行")
        except ParityFail as e:
            if "program.erbc" in str(e):
                log("反向证明①（非 NT 简化档）：比较引擎如实报红 ✔")
            else:
                failures.append(f"反向证明①没点名 program.erbc：{e}")

    # ② 登记 sha 造假：产物没变但 --expect 写错 ⇒ 必须红
    exp = os.path.join(work, "fake-expect.txt")
    good_sha = sha256_file(os.path.join(k_dir, ERBC_DIR, "manifest.txt"))[1]
    bad = ("a" * 64) if good_sha[0] != "a" else ("b" * 64)
    with open(exp, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"manifest.txt {bad}\n")
    try:
        check_expect(exp, k_dir)
        failures.append("反向证明②失明：--expect 登记被造假，驱动竟然放行")
    except ParityFail as e:
        log(f"反向证明②：驱动对假登记报红 ✔（{str(e).splitlines()[0]}）")

    # ③ 真端到端负样本：语料改一个字节后，产物与闸 4 登记必然不等 ⇒ 登记对照红
    tdir = os.path.join(work, "corpus-tampered")
    copy_tree(SMALL_CORPUS, tdir)
    with open(os.path.join(tdir, "01_main.ERB"), "wb") as f:
        f.write(SOURCE_CHANGED_BYTES)
    gate4 = os.path.join(DATA_DIR, "rust-gradle", "haruki-gate4-expected-sha256.txt")
    if os.path.isfile(gate4) and not ns.skip_e2e:
        t_out = os.path.join(work, "e2e")
        os.makedirs(t_out)
        kotlin_compile_leg(java, classpath, winpath(tdir), winpath(t_out), None, "selftest-e2e")
        try:
            check_expect(gate4, t_out)
            failures.append("反向证明③失明：改了字节的语料编译出的产物仍被当成闸 4 登记通过")
        except ParityFail as e:
            log(f"反向证明③：篡改语料的产物对不上闸 4 登记 ✔（{str(e).splitlines()[0]}）")
    else:
        log("反向证明③：跳过（缺闸 4 登记文件或 --skip-e2e）")

    shutil.rmtree(work, ignore_errors=True)
    if failures:
        for fmsg in failures:
            log(f"FAIL {fmsg}")
        return 1
    log("selftest：全绿 —— 三种坏输入都被驱动抓住")
    return 0


# ---------------------------------------------------------------- 入口

def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="erbcc_rs_parity.py",
        description="RUST1-B rust-gradle：双跑对拍闸 + 基准重生成 + ERBCC_CLASSPATH 产出方",
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    pp = sub.add_parser("parity", help="Kotlin 腿/Rust 腿双跑，四件套 sha256 全等才算绿")
    pp.add_argument("--corpus", default=SMALL_CORPUS, help="语料根（默认小档 rust-manifest/corpus）")
    pp.add_argument("--out", help="两腿产物的公共父目录（默认 build/parity-driver/<ts>/，跑完删）")
    pp.add_argument("--limit", help="--limit 透传给两腿（正整数）")
    pp.add_argument("--expect", help="登记 sha 文件（`name [bytes] sha256`），再叠一层对照")
    pp.add_argument("--classpath", help="classpath 直接串或 classpath 文件路径（档 1 两态）")
    pp.add_argument("--classpath-file", dest="classpath_file", help="同上，显式文件态")
    pp.add_argument("--rust-exe", help="覆盖 Rust CLI 路径（selftest 用它挂假腿）")
    pp.add_argument("--keep", action="store_true", help="保留临时产物（默认跑完删除）")
    pp.set_defaults(fn=cmd_parity)

    rp = sub.add_parser("regen", help="在当前机器重生成 tests/data 基准并打印新旧 sha 对照")
    rp.add_argument("--lane",
                    choices=["all", "rust-manifest", "rust-kernel", "rust-roundtrip", "rust-full"],
                    default="all",
                    help="all＝三路入库基准（manifest/kernel/roundtrip）；"
                         "rust-roundtrip＝expected 两档 + tamper 三件套（cargo 开关格现跑 Kotlin 腿）；"
                         "rust-full＝本机全量基准（build/baseline/，约 80 s，不入库，只此显式档）")
    rp.add_argument("--dry-run", action="store_true", help="只算不写")
    rp.add_argument("--classpath", help="classpath（同 parity）")
    rp.add_argument("--use-cli-kotlin", dest="use_cli_kotlin", action="store_true",
                    help="rust-kernel 档用 java CLI 腿替代 Gradle compileCorpus（两腿等价性已由闸 4 实测）")
    rp.set_defaults(fn=cmd_regen)

    cp = sub.add_parser("classpath", help="ERBCC_CLASSPATH 的产出方（installDist → classpath 文件或直接串）")
    cp.add_argument("--mode", choices=["file", "direct"], default="file")
    cp.add_argument("--out-file", dest="out_file", help="classpath 文件落点（默认本仓 build/erbcc-rs-classpath.txt）")
    cp.add_argument("--no-build", action="store_true", help="跳过 :tools:erbcc:installDist（已确知产物新鲜时）")
    cp.set_defaults(fn=cmd_classpath)

    sp = sub.add_parser("selftest", help="反向证明：篡改腿/假登记/篡改语料都要被驱动抓住")
    sp.add_argument("--skip-e2e", action="store_true", help="跳过第③条真编译负样本")
    sp.set_defaults(fn=cmd_selftest)
    return ap


def main(argv: list[str]) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ns = build_parser().parse_args(argv)
    if getattr(ns, "limit", None) is not None:
        try:
            n = int(ns.limit)
            if n <= 0:
                raise ValueError
        except ValueError:
            log("错误: --limit 需要正整数")
            return 2
    try:
        return ns.fn(ns)
    except EnvError as e:
        log(f"环境不满足（退 2）：{e}")
        return 2
    except ParityFail as e:
        log(f"红（退 1）：{e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
