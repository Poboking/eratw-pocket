#!/usr/bin/env python3
"""P0.5 语义夹具：JVM（JDK 17）基线 vs Rust 实现的全空间对拍（CONTRACT-RUSTFULL §6.2）。

为什么需要这套东西：erbcc 的产物要求**逐字节相等**，而字节相等依赖一批「编译器默认当成
理所当然、其实来自 JVM/标准库」的语义：Unicode 大写表、字符串比较序、Shift-JIS(MS932)
字节映射、浮点文案、整数溢出与除零。这些差异都不会在编译时报错，只会在某个罕见字符出现
时把产物悄悄改掉。故先做全空间对拍，再写编译器。

用法（在仓根或任意目录，脚本自己找路径）：

    python devtools/erbcc_semantics_diff.py all
    python devtools/erbcc_semantics_diff.py upper sjis cmp float
    python devtools/erbcc_semantics_diff.py upper --regen-patch

环境：
- JDK：优先 `JAVA_HOME`，否则 `<ERATW_ROOT>/.tooling/jdk-17.0.20.1+1`；本机自建
  的 ASCII junction 请自己用 `JAVA_HOME` 指进来（仓里不留本机绝对路径）。
- Rust：`cargo` 在 PATH（rustup shim）；首次拉依赖需要走 Clash（见 AGENTS §1.1）。
- javac 必须显式 `-encoding UTF-8`：Windows 默认 platform encoding 是 GBK，源码里的中文
  注释会直接判「不可映射字符」。

公开分仓布局：`tools/erbcc-rs` 在姐妹引擎仓 `../Kotera`，本仓不再带；
可用环境变量 `ERATW_KCORE_ROOT` 覆盖（与 devtools 其余脚本同口径）。
"""

from __future__ import annotations

import os
import random
import shutil
import struct
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
# 公开分仓布局：引擎侧 `tools/erbcc-rs` 在姐妹仓 Kotera；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = Path(os.environ.get("ERATW_KCORE_ROOT") or (ANDROID.parent / "Kotera"))
CRATE = KCORE_ROOT / "tools" / "erbcc-rs"
JAVA_SRC = HERE / "semantics" / "DumpTables.java"
OUT = CRATE / "build" / "semantics"
PATCH_RS = CRATE / "src" / "java_unicode_upper.rs"

MODES = ("upper", "sjis", "float", "cmp")


def find_jdk() -> Path:
    for cand in (
        os.environ.get("JAVA_HOME"),
        str(ANDROID.parent / ".tooling" / "jdk-17.0.20.1+1"),
    ):
        if not cand:
            continue
        p = Path(cand)
        if (p / "bin" / "javac.exe").exists() or (p / "bin" / "javac").exists():
            return p
    sys.exit("找不到 JDK（设 JAVA_HOME，或确认 .tooling/jdk-17.0.20.1+1 存在）")


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    printable = " ".join(str(c) for c in cmd)
    print(f"$ {printable}", flush=True)
    return subprocess.run(cmd, **kw)


def cargo_bin() -> Path:
    exe = "semdiff.exe" if os.name == "nt" else "semdiff"
    p = CRATE / "target" / "release" / exe
    if not p.exists():
        run(["cargo", "build", "--release", "--bin", "semdiff"], cwd=CRATE, check=True)
    return p


def build_java(jdk: Path) -> Path:
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    javac = jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    run([str(javac), "-encoding", "UTF-8", "-d", str(classes), str(JAVA_SRC)], check=True)
    return classes


def java(jdk: Path, classes: Path, args: list[str], stdout_path: Path) -> None:
    exe = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
    with open(stdout_path, "w", encoding="utf-8", newline="\n") as f:
        run([str(exe), "-cp", str(classes), "DumpTables", *args], stdout=f, check=True)


def gen_float_bits(path: Path, rows: int = 200_000) -> None:
    """边界值 + 固定种子随机位型：随机种子写死，保证两台机器样本一致。"""
    random.seed(20261127)
    vals = [
        0.0, -0.0, 1.0, -1.0, 0.1, 0.5, 1.5, 1e-3, 1e-4, 1e7, 1e8, 123456.789,
        1.0 / 3, 2.0 / 3, 1e300, 1e-300, 5e-324, 1e-323,
        2.2250738585072014e-308, 1.7976931348623157e308, 3.14159265358979,
        float("inf"), float("-inf"), float("nan"), 9007199254740993.0,
    ]
    bits = [struct.pack(">d", v).hex() for v in vals]
    bits += [format(random.getrandbits(64), "016x") for _ in range(rows)]
    path.write_text("\n".join(bits) + "\n", encoding="utf-8")


def gen_cmp_pairs(path: Path) -> None:
    """覆盖 BMP 边界、非 BMP（emoji/增补平面）与随机组合：分叉点全在这里。"""
    pool = ["a", "A", "\u3042", "\u30a2", "\u9fff", "\ufffd", "\uffff",
            "\U0001F600", "\U0001F601", "\U00020000", "", "0", "~", "\ue000", "\uac00"]
    random.seed(7)
    lines = [f"{hx(a)}\t{hx(b)}" for a in pool for b in pool]
    for _ in range(3000):
        lines.append(f"{hx(rand_str())}\t{hx(rand_str())}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def hx(s: str) -> str:
    return " ".join("%04X" % ord(c) for c in s)


def rand_str() -> str:
    cps = [
        random.choice(
            [
                random.randint(0x20, 0x7E),
                random.randint(0x3000, 0x9FFF),
                random.randint(0xE000, 0xFFFF),
                random.randint(0x10000, 0x1FFFF),
            ]
        )
        for _ in range(random.randint(0, 3))
    ]
    return "".join(chr(c) for c in cps)


def regen_patch(java_upper: Path) -> None:
    """把 upper 差异折成 Rust 侧补丁表（只写入单字符映射，实测 95 条全是单字符）。"""
    rust_upper = OUT / "upper-rust.tsv"
    run([str(cargo_bin()), "--dump", "upper", str(rust_upper)], check=True)
    jvm = dict(line.rstrip("\n").split("\t") for line in java_upper.read_text(encoding="utf-8").splitlines() if line)
    rust = dict(line.rstrip("\n").split("\t") for line in rust_upper.read_text(encoding="utf-8").splitlines() if line)
    diffs = sorted((int(k, 16), jvm[k]) for k in jvm if jvm.get(k, "") != rust.get(k, ""))
    rows = []
    for cp, up in diffs:
        target = up.split()[0] if up else format(cp, "04X")
        ch = chr(cp) if cp >= 0x20 else "C0"
        rows.append(f"    (0x{cp:04X}, 0x{int(target, 16):04X}), // {ch}")
    text = PATCH_RS.read_text(encoding="utf-8")
    start = text.index("const OVERRIDES: &[(u32, u32)] = &[")
    head_end = text.index("\n", start) + 1
    end = text.index("];", head_end)
    new = text[:head_end] + "\n".join(rows) + "\n" + text[end:]
    PATCH_RS.write_text(new, encoding="utf-8", newline="\n")
    print(f"regen patch: {len(rows)} 条 -> {PATCH_RS}")


def main(argv: list[str]) -> int:
    want = [m for m in argv if m in MODES] or list(MODES)
    regen = "--regen-patch" in argv
    skip_build = "--no-build" in argv

    jdk = find_jdk()
    OUT.mkdir(parents=True, exist_ok=True)
    classes = build_java(jdk)
    if not skip_build:
        cargo_bin()

    results: dict[str, tuple[int, int]] = {}

    if "upper" in want:
        java_upper = OUT / "upper.tsv"
        java(jdk, classes, ["upper"], java_upper)
        if regen:
            regen_patch(java_upper)
        results["upper"] = parse(run([str(cargo_bin()), "upper", "--compare", str(java_upper)], check=False, capture_output=True, text=True, encoding="utf-8", errors="replace"))

    if "sjis" in want:
        java_sjis = OUT / "sjis.tsv"
        java(jdk, classes, ["sjis"], java_sjis)
        results["sjis"] = parse(run([str(cargo_bin()), "sjis", "--compare", str(java_sjis)], check=False, capture_output=True, text=True, encoding="utf-8", errors="replace"))

    if "float" in want:
        bits = OUT / "float-bits.txt"
        gen_float_bits(bits)
        java_float = OUT / "float.tsv"
        java(jdk, classes, ["float", str(bits)], java_float)
        results["float"] = parse(run([str(cargo_bin()), "float", "--compare", str(java_float)], check=False, capture_output=True, text=True, encoding="utf-8", errors="replace"))

    if "cmp" in want:
        pairs = OUT / "cmp-pairs.txt"
        gen_cmp_pairs(pairs)
        java_cmp = OUT / "cmp.tsv"
        java(jdk, classes, ["cmp", str(pairs)], java_cmp)
        results["cmp"] = parse(run([str(cargo_bin()), "cmp", "--compare", str(java_cmp)], check=False, capture_output=True, text=True, encoding="utf-8", errors="replace"))

    print("\n== P0.5 语义对拍（JVM=JDK17 基线）==")
    bad = 0
    for k, (total, mism) in results.items():
        print(f"{k:6} rows={total:>8} mismatches={mism}")
        if mism:
            bad += 1
    if bad:
        print("\n有差异：看上面的 DIFF 行；upper 可 `--regen-patch` 折成补丁表。")
    return 0 if bad == 0 else 1


def parse(proc: subprocess.CompletedProcess) -> tuple[int, int]:
    total = mism = 0
    for line in (proc.stdout or "").splitlines():
        if line.startswith("rows="):
            parts = dict(p.split("=") for p in line.split())
            total, mism = int(parts["rows"]), int(parts["mismatches"])
    return total, mism


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
