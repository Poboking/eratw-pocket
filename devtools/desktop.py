#!/usr/bin/env python3
"""desktop.py —— 桌面 vmdbg 的唯一入口（弹窗免疫编排器）。

事故背景（2026-10-25）：裸 `java ... sweep --shard i/8 &` 在「无控制台的父进程」里拉起时
（PowerShell Start-Process / 双击 bat / 部分 agent 执行器），Win11 默认终端 = Windows Terminal
会给**每个 JVM 进程**弹一个可见终端窗（标题 `Default: <java.exe 全路径>`）——8 路分片就是
8 连弹。控制台分配发生在父进程 CreateProcess 一侧，java 自身无法拒绝，唯一正解是拉起时带
CREATE_NO_WINDOW。本脚本把这件事做掉：桌面 sweep 分片、一切 vmdbg 单发命令，一律从这里走。

用法（在 eratw-android 检出根下）：
  python devtools/desktop.py cmd call SHOP_MAIN            # 任意 vmdbg 子命令（单进程）
  python devtools/desktop.py cmd var DAY
  python devtools/desktop.py cmd ledger diff --base a.jsonl --head b.jsonl
  python devtools/desktop.py sweep --shards 8 --out build/dbg/sweep8
  python devtools/desktop.py sweep --only '^BATTLE' --shards 4 --out build/dbg/probe

  sweep 旋钮全部透传 vmdbg：--only/--skip-methods/--resume/--wall-ms/--steps/--timeout-min（本脚本自留）。

解析顺序：--erbc/--game 显式参数 > 环境变量 ERATW_ERBC_DIR/ERATW_GAMEDIR > 按主机名的缺省。
java：JAVA_HOME > $ERATW_ROOT/.tooling/jdk-17.0.20.1+1 > PATH。
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent            # eratw-android 检出根
VMDBG_CP = str(ANDROID_ROOT / "tools" / "vmdbg" / "build" / "install" / "vmdbg" / "lib" / "*")
MAIN_KT = "com.eratw.port.vmdbg.MainKt"

CREATE_NO_WINDOW = 0x08000000
STATUS_ORDER = ["PASS", "CODEEE", "STEP_LIMIT", "STACK_OVERFLOW", "WALL_LIMIT", "ERROR", "RUNNING"]


def eratw_root() -> str:
    """与 AGENTS §1 / SKILL §1 同源的主机名解析（前缀通配，未知主机不猜）。"""
    env = os.environ.get("ERATW_ROOT")
    if env:
        return env
    host = socket.gethostname()
    if host.lower().startswith("haruki"):
        return "C:/DSH/eratw-安卓移植计划"
    if host.upper().startswith("HOMEJIA"):
        return "D:/DSH/eratw-安卓移植计划"
    sys.exit(f"未知主机 {host}：请设 ERATW_ROOT 环境变量后重跑")


def resolve_java() -> str:
    jh = os.environ.get("JAVA_HOME")
    for cand in ([jh] if jh else []) + [eratw_root() + "/.tooling/jdk-17.0.20.1+1"]:
        exe = Path(cand) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def resolve_paths(argv: list[str], names: dict[str, str]) -> list[str]:
    """给 argv 补缺的 --erbc/--game（显式已给则原样透传）。names = {旗标: 缺省}。"""
    out = list(argv)
    for flag, default in names.items():
        if flag not in out:
            out += [flag, default]
    return out


def default_erbc() -> str:
    env = os.environ.get("ERATW_ERBC_DIR")
    if env:
        return env
    root = eratw_root()
    if root.startswith("D:") or root.startswith("d:"):
        return "D:/DSH/erbc-perf3/erbc.v1"
    sys.exit("erbc 产物路径未知（Haruki 机无缺省）：请 --erbc <erbc.v1 目录> 或设 ERATW_ERBC_DIR")


def default_game() -> str:
    return os.environ.get("ERATW_GAMEDIR") or eratw_root() + "/eratw-画蛇添足版eratw4.980"


def java_argv(extra: list[str]) -> list[str]:
    if not Path(ANDROID_ROOT / "tools" / "vmdbg" / "build" / "install" / "vmdbg" / "lib").is_dir():
        sys.exit("vmdbg 未安装：先在 eratw-android 下跑 ./gradlew --configure-on-demand :tools:vmdbg:installDist")
    return [resolve_java(), "-Xmx3g", "-cp", VMDBG_CP, MAIN_KT, *extra]


# ------------------------------------------------------------------ cmd：单发命令
def do_cmd(rest: list[str]) -> int:
    rest = resolve_paths(rest, {"--erbc": default_erbc(), "--game": default_game()})
    argv = java_argv(rest)
    p = subprocess.run(argv, capture_output=True, creationflags=CREATE_NO_WINDOW if os.name == "nt" else 0)
    if p.stdout:
        sys.stdout.buffer.write(p.stdout)
    if p.stderr:
        sys.stdout.buffer.write(p.stderr)
    sys.stdout.buffer.flush()
    return p.returncode


# ------------------------------------------------------------------ sweep：分片编排
def do_sweep(rest: list[str]) -> int:
    shards = 8
    out_dir: str | None = None
    timeout_min = 0.0
    passthrough: list[str] = []
    i = 0
    while i < len(rest):
        t = rest[i]
        if t == "--shards":
            shards = int(rest[i + 1]); i += 2
        elif t == "--out":
            out_dir = rest[i + 1]; i += 2
        elif t == "--timeout-min":
            timeout_min = float(rest[i + 1]); i += 2
        else:
            passthrough.append(t); i += 1
    if out_dir is None:
        sys.exit("sweep 需要 --out <输出目录>（分片落 <out>/s<i>/sweep.jsonl）")
    base = Path(out_dir)
    (base / "logs").mkdir(parents=True, exist_ok=True)

    procs: list[tuple[int, subprocess.Popen, Path]] = []
    for s in range(shards):
        shard_args = resolve_paths(
            [*passthrough, "--out", str(base / f"s{s}"), "--shard", f"{s}/{shards}"],
            {"--erbc": default_erbc(), "--game": default_game()},
        )
        log_path = base / "logs" / f"s{s}.log"
        log_file = open(log_path, "wb")
        p = subprocess.Popen(
            java_argv(["sweep", *shard_args]), stdout=log_file, stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            creationflags=CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        procs.append((s, p, log_path))
        print(f"[sweep] shard {s}/{shards} 已拉起（pid={p.pid}，日志 {log_path}，无窗口）")

    deadline = time.monotonic() + timeout_min * 60 if timeout_min > 0 else None
    rc_max = 0
    for s, p, log_path in procs:
        remaining = None
        if deadline is not None:
            remaining = max(0.0, deadline - time.monotonic())
        try:
            rc = p.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            p.kill()
            print(f"[sweep] shard {s} 超时被击杀（--timeout-min {timeout_min}）", file=sys.stderr)
            rc = 1
        rc_max = max(rc_max, rc)
        if rc != 0:
            tail = log_path.read_bytes()[-800:].decode("utf-8", "replace")
            print(f"[sweep] shard {s} 退出码 {rc}，日志尾：\n{tail}", file=sys.stderr)
    print(f"[sweep] 全部 {shards} 片结束（最大退出码 {rc_max}），聚合读数：")
    tally_sweep(base, shards)
    return rc_max


def tally_sweep(base: Path, shards: int) -> None:
    counts: dict[str, int] = {}
    total = 0
    for s in range(shards):
        j = base / f"s{s}" / "sweep.jsonl"
        if not j.is_file():
            print(f"  shard {s}: 缺 {j}")
            continue
        # utf-8-sig：读侧剥 BOM（与 JVM 侧 Jsonl.parseLine 同款容忍）。分片文件由 JVM 写、
        # 本不带 BOM，但若有人把 PS `Out-File -Encoding utf8` 合并过的台账指进来，首条
        # 不该被 json.loads 的 BOM 异常静默跳过（2026-10-25 ledger diff 同款缺陷）。
        with j.open(encoding="utf-8-sig", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                st = str(rec.get("status", "?"))
                counts[st] = counts.get(st, 0) + 1
                total += 1
    parts = [f"{k}={counts[k]}" for k in STATUS_ORDER if k in counts]
    for k in sorted(counts):
        if k not in STATUS_ORDER:
            parts.append(f"{k}={counts[k]}")
    print(f"  合计 {total} 条：{' '.join(parts)}")
    if "RUNNING" in counts:
        print("  警告：存在 RUNNING 残留行 = 对应函数把进程挂死（见 SKILL §6 台账末行坑）")
    print(f"  每片明细：{base}/s<i>/summary.md；逐片台账 {base}/s<i>/sweep.jsonl（ledger 收文件路径）")


def main() -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass
    argv = sys.argv[1:]
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        return 0
    mode, rest = argv[0], argv[1:]
    if mode == "cmd":
        return do_cmd(rest)
    if mode == "sweep":
        return do_sweep(rest)
    sys.exit(f"未知子命令 {mode}：只有 sweep / cmd（cmd 后接任意 vmdbg 子命令）")


if __name__ == "__main__":
    sys.exit(main())
