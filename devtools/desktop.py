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
  python devtools/desktop.py cmd --xmx 512m explore --soak-min 10   # 堆压挡位（见下）
  python devtools/desktop.py sweep --shards 8 --out build/dbg/sweep8
  python devtools/desktop.py sweep --only '^BATTLE' --shards 4 --out build/dbg/probe

  sweep 旋钮全部透传 vmdbg：--only/--skip-methods/--resume/--wall-ms/--steps；
  本脚本自留 --shards/--out/--timeout-min/--xmx（argparse 校验：坏值给用法错，不是 traceback；
  --xmx 逐片透给拉起 JVM 的 -Xmx，缺省 3g 逐字不变）。
  `--shard` **不许**自带：它由本脚本按 s/N 逐片注入，自带会和注入的那条撞车（vmdbg 取最后一次
  出现值 ⇒ 每片跑成同一份切片），撞车当场报错退出。

JVM 挡位（本脚本自留，**不进 vmdbg argv**）：
  --xmx <量>   拉起 java 的 -Xmx 挡位；缺省 3g（不传 = 与既有行为逐字一致）。
               取值形状 = 纯数字（字节）或数字+k/m/g（可带 b，如 512m / 3g / 2048k / 512mb）；
               非法值在本脚本侧给可读错误，**绝不把 java 拉起来才炸**。
               obs-soak512：真机是 512MiB largeHeap 封顶，桌面 3g 从没进入「堆贴顶」相位——
               堆压浸泡用 `cmd --xmx 512m explore ...`（soak.py 原样透传本旗标）。

解析顺序：--erbc/--game 显式参数 > 环境变量 ERATW_ERBC_DIR/ERATW_GAMEDIR > 按主机名的缺省。
java：JAVA_HOME > $ERATW_ROOT/.tooling/jdk-17.0.20.1+1 > PATH。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
from contextlib import ExitStack
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent            # eratw-pocket 检出根
# 公开分仓布局：引擎侧 vmdbg 在姐妹仓 eraKcore；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = Path(os.environ.get("ERATW_KCORE_ROOT") or (ANDROID_ROOT.parent / "eraKcore"))
VMDBG_CP = str(KCORE_ROOT / "tools" / "vmdbg" / "build" / "install" / "vmdbg" / "lib" / "*")
MAIN_KT = "com.eratw.port.vmdbg.MainKt"

CREATE_NO_WINDOW = 0x08000000
STATUS_ORDER = ["PASS", "CODEEE", "STEP_LIMIT", "STACK_OVERFLOW", "WALL_LIMIT", "ERROR", "RUNNING"]

# JVM 挡位（obs-soak512）：缺省 3g 逐字保持既有拉起行；--xmx 只在 python 侧消费，不进 vmdbg argv。
DEFAULT_XMX = "3g"
# java -Xmx 的合法量词形状：纯数字（字节）/ 数字 + k|m|g（大小写随意，可带 b，如 512m、3g、512MB）。
XMX_RE = re.compile(r"^\d+([kKmMgG][bB]?)?$")


def validate_xmx(val: str) -> str:
    """挡位字符串校验：非法值给可读错误、当场退出（不拉 java）。"""
    if not XMX_RE.match(val):
        sys.exit(
            f"--xmx 取值非法: '{val}'（要是 512m / 3g / 2048k / 纯数字字节 这类形状，"
            "量词 k/m/g 大小写随意、可带 b；纯数字按字节）"
        )
    return val


def extract_xmx(rest: list[str]) -> tuple[str, list[str]]:
    """从 argv 摘出 --xmx <量>（含 --xmx=<量> 同形），返回 (挡位, 摘掉后的 rest)。

    未给 = 缺省挡位 [DEFAULT_XMX]（既有行为逐字不变）。vmdbg 的 Args 会把不认识的
    `--xmx` 记成布尔旗标、把挡位值落进位置参数——所以本旗标必须在 python 侧摘干净。
    """
    out: list[str] = []
    xmx = DEFAULT_XMX
    i = 0
    while i < len(rest):
        t = rest[i]
        if t == "--xmx":
            val = rest[i + 1] if i + 1 < len(rest) else None
            if val is None:
                sys.exit("选项 --xmx 缺少参数（例：--xmx 512m）")
            xmx = validate_xmx(val)
            i += 2
        elif t.startswith("--xmx="):
            xmx = validate_xmx(t[len("--xmx="):])
            i += 1
        else:
            out.append(t)
            i += 1
    return xmx, out


def eratw_root() -> str:
    """共享根只从 ERATW_ROOT 取，公开仓不内置任何个人机器路径。"""
    env = os.environ.get("ERATW_ROOT")
    if env:
        return env
    sys.exit("共享根未知：请设 ERATW_ROOT 环境变量后重跑")


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
    return str(Path(eratw_root()) / "erbc.v1")


def default_game() -> str:
    return os.environ.get("ERATW_GAMEDIR") or str(Path(eratw_root()) / "game")


def java_argv(extra: list[str], xmx: str = DEFAULT_XMX) -> list[str]:
    if not Path(KCORE_ROOT / "tools" / "vmdbg" / "build" / "install" / "vmdbg" / "lib").is_dir():
        sys.exit("vmdbg 未安装：先在 eraKcore 下跑 ./gradlew --configure-on-demand :tools:vmdbg:installDist")
    return [resolve_java(), "-Xmx" + xmx, "-cp", VMDBG_CP, MAIN_KT, *extra]


# ------------------------------------------------------------------ cmd：单发命令
def do_cmd(rest: list[str]) -> int:
    xmx, rest = extract_xmx(rest)
    rest = resolve_paths(rest, {"--erbc": default_erbc(), "--game": default_game()})
    argv = java_argv(rest, xmx)
    p = subprocess.run(argv, capture_output=True, creationflags=CREATE_NO_WINDOW if os.name == "nt" else 0)
    if p.stdout:
        sys.stdout.buffer.write(p.stdout)
    if p.stderr:
        sys.stdout.buffer.write(p.stderr)
    sys.stdout.buffer.flush()
    return p.returncode


# ------------------------------------------------------------------ sweep：分片编排
def _positive_int(raw: str) -> int:
    """`--shards` 的取值判据：正整数。坏值走 argparse 用法错（不是裸 ValueError）。"""
    try:
        value = int(raw)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError(f"需要整数，收到 {raw!r}") from None
    if value < 1:
        raise argparse.ArgumentTypeError(f"需要 >= 1，收到 {value}（0 或负数会一片都不拉起）")
    return value


def _non_negative_float(raw: str) -> float:
    """`--timeout-min` 的取值判据：非负分钟数（0 = 不限，与 vmdbg 侧口径一致）。"""
    try:
        value = float(raw)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError(f"需要数字（分钟），收到 {raw!r}") from None
    if value < 0:
        raise argparse.ArgumentTypeError(f"需要 >= 0，收到 {value}（负数会让每一片都被立刻击杀）")
    return value


def sweep_parser() -> argparse.ArgumentParser:
    """本脚本自留的四个旋钮；其余一律 `parse_known_args` 透传给 vmdbg。

    为什么从手写循环换成 argparse（W5-misc 第 3 条之二）：`--shards abc`、`--timeout-min 半`
    这类坏值原来是**裸 ValueError 崩栈**，`--shards`（最后一个 token）缺值更是直接
    IndexError。调用这里的多是 agent 与命令行手敲， traceback 里看不见「该填什么」；
    argparse 给的是用法行 + `error: argument --shards: ...` + 退出码 2。

    `allow_abbrev=False` 是**必需**的，不是风格：默认的缩写匹配会把用户想透传给 vmdbg 的
    `--shard 3/8` 认成本脚本的 `--shards`（`--shard` 是它的前缀），于是报
    `invalid int value: '3/8'` —— 而正确行为是把它当透传参数、由下面的撞车检查点名拒绝。
    """
    p = argparse.ArgumentParser(
        prog="desktop.py sweep", add_help=False, allow_abbrev=False,
    )
    p.add_argument("--shards", type=_positive_int, default=8, metavar="N",
                   help="分片路数（>= 1；本脚本按 s/N 给每片注入 --shard）")
    p.add_argument("--out", required=True, metavar="DIR",
                   help="输出根目录（必填；分片落 <out>/s<i>/sweep.jsonl，日志落 <out>/logs/）")
    p.add_argument("--timeout-min", type=_non_negative_float, default=0.0, metavar="MIN",
                   help="整批墙钟上限（分钟，0 = 不限）；到点击杀仍在跑的片")
    p.add_argument("--xmx", type=_xmx_arg, default=DEFAULT_XMX, metavar="量",
                   help="每片 JVM 堆挡位（缺省 3g 逐字不变；obs-soak512 用 --xmx 512m 造堆压相位）")
    return p


def _xmx_arg(val: str) -> str:
    """`--xmx` 的 argparse 型校验：形状判据复用 [validate_xmx] 的正则，坏值走用法错（退出码 2）。"""
    if not XMX_RE.match(val):
        raise argparse.ArgumentTypeError(
            f"需要 512m / 3g / 2048k / 纯数字字节 这类形状（量词 k/m/g 大小写随意、可带 b）"
        )
    return val


def do_sweep(rest: list[str]) -> int:
    args, passthrough = sweep_parser().parse_known_args(rest)
    shards: int = args.shards
    timeout_min: float = args.timeout_min
    xmx: str = args.xmx
    base = Path(args.out)

    # 透传里不许自带 --shard（W5-misc 第 3 条之二）：本脚本按 s/N 注入那一条，若玩家也写了
    # `--shard 3/8`，vmdbg 侧的 Args 取**最后一次出现**的值 ⇒ 每一片都跑成同一个 3/8，
    # 而 --out 还是各片一个目录 —— 屏幕上是「8 片都跑完了」，实际 7 片在跑同一份空切片。
    # 这是「看起来全绿、其实什么都没测」的形状，必须当场挡住，不能靠后面的聚合读数发现。
    clash = [t for t in passthrough if t == "--shard" or t.startswith("--shard=")]
    if clash:
        sys.exit(
            "sweep 的 --shard 由本脚本按分片注入（0..<shards），请勿自带 --shard；"
            "要只跑某一片就请直接跑 vmdbg（例：./gradlew 装好的 installDist 里 "
            "java -cp ../eraKcore/tools/vmdbg/build/install/vmdbg/lib/'*' com.eratw.port.vmdbg.MainKt "
            "sweep --shard 3/8 --out <目录>），或在这里用 --only/--skip-methods 收窄清单。"
        )

    base.joinpath("logs").mkdir(parents=True, exist_ok=True)

    procs: list[tuple[int, subprocess.Popen, Path]] = []
    # 日志句柄由 ExitStack 收口（W5-misc 第 3 条之二）：旧写法 open 之后从不 close，
    # 而拉起循环中途会抛（`default_erbc()` 在未设共享根时 sys.exit、vmdbg 未安装时
    # sys.exit、子进程 fork 失败）—— 已打开的句柄全留在进程里，Windows 上父进程不退
    # 这些 .log 就一直被占着，随后的「读日志尾巴」与人工删除都撞拒绝访问。
    with ExitStack() as stack:
        for s in range(shards):
            shard_args = resolve_paths(
                [*passthrough, "--out", str(base / f"s{s}"), "--shard", f"{s}/{shards}"],
                {"--erbc": default_erbc(), "--game": default_game()},
            )
            log_path = base / "logs" / f"s{s}.log"
            log_file = stack.enter_context(log_path.open("wb"))
            p = subprocess.Popen(
                java_argv(["sweep", *shard_args], xmx), stdout=log_file, stderr=subprocess.STDOUT,
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
