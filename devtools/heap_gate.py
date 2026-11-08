#!/usr/bin/env python3
"""Java 堆驻留闸（obs-heap512）—— 512 MiB 挡位真产物浸泡的可判红闸。

## 为什么要有它

玩家真机（ART `growth limit 536870912` = 512 MiB）长时间游玩后 OOM，事故形状是「慢页伴随
堆顶」（`turn 2ms -> 58515ms` 且 `used=510/512 MiB`）。既有桌面装置跑在 3g，量不到这个挡位；
`devtools/soak.py --xmx` 现在能把挡位透传下来，但「跑一次」不是闸。本脚本把
**挡位自证 + 驻留上限 + 慢页上限**三件事钉成机器可判的退出码，以后任何让 512m 曲线抬头的
改动都应先在这里变红，而不是等玩家上报。

## 判据（全部有上限常数）

单次运行 = `devtools/soak.py --xmx 512m --soak-min 8 --idle-min-ms 100 --idle-max-ms 500
--seed 20260226`（同 seed、同剧本、同参数；实测两臂步数一致）。跑完后读 `<out>/soak.txt`
与 `<out>/soak-steps.jsonl`：

1. 挡位字节级自证 —— 起跑前先用 `desktop.py cmd --xmx <挡位> heap` 让**子进程自己**报
   `jvmMaxHeapBytes`（512m = 536870912）；`--from-soak` 那一支改读产物里的挡位读数；
2. 浸泡进程退出码必须 0 —— OOM / 装配失败 / 墙钟异常**不吞**成「跑完了」；
3. 步数 `n >= min_steps` —— 防止「0 步仍全绿」（语料缺失 / 立即收工）；
4. `堆 used 峰值 <= max_peak_mib`；
5. `slow(>=5000ms) <= max_slow_steps`，且 `max <= max_step_ms`；
6. `末读数 - 首读数 <= max_growth_mib` —— 驻留增量上限（负增长是 GC 回吐，算好事）；
7. 堆采样条数必须等于步数 —— 采样面没接线时不当成绿。

## 两代读数键名（闸要同时判，否则标定值无法复核）

上限常数是在 2026-11-01 那一臂（obs-heap512 车道自己的构建）标出来的，那代产物写的是
`heapSamples=… heapMaxMiB=… heapPeakUsedMiB=… heapFirstMiB=… heapLastMiB=…` 一行加
`[堆读数] jvmMaxHeapBytes=…`；并行的 obs-soak512 并进 main 后，同一件事换成
`heapCeilingMi=` + `heapPeakMi=`（soak.txt）与逐步台账里的 `heapUsedMi`（soak-steps.jsonl）。
本脚本两种都认（先按当代键名，取不到再回退上一代），**判的是同一个数**，不另起口径；
首末水位与采样条数当代从 jsonl 现算，上一代直接读那行。

## 上限常数的标定（不是拍脑袋）

标定条件（写死，换条件必须重标并同步改这里）：
- 机：开发机；JVM：Temurin 17.0.20.1+1（HotSpot，默认 G1）；日期 2026-11-01；
- 产物：那次工作树 `:tools:erbcc:compileCorpus` 输出（`program.erbc` 329,722,436 字节，
  sha256 6de3938f…），语料 3,971 文件；
- 参数：`--xmx 512m --soak-min 8 --idle-min-ms 100 --idle-max-ms 500 --seed 20260226`；
- 实测（n=1480，两臂步数一致）：
  - 512m：p50=15ms p95=32ms max=70ms slow=0 heapPeak=486 heapFirst=471 heapLast=433；
  - 3g（对照）：p50=15ms p95=33ms max=136ms slow=0 heapPeak=805。
- 据此：峰值上限 500 MiB（实测 486 + 14）；慢页上限 0 条（实测 0/1480）；单页上限 2000 ms
  （实测 70/136）；驻留增量上限 +50 MiB（实测 -38）；最小步数 1000（实测 1480）。

## 口径限制（不许拿「桌面绿了」冒充「真机没事」）

桌面是 HotSpot + 无 ART native 位图堆 + 无 SystemClock / 渲染线程压力；本闸里的 `used`
是 `totalMemory - freeMemory` 的即时读数（不触发 GC 校正），与真机 `dumpsys` 的
`Dalvik Heap Alloc` 同族但**不同栈**。因此本闸只能回答「Java 堆一侧在这组随机操作下是否
顶穿/劣化」，**不能**外推真机的 native 位图堆、渲染线程与系统内存压力。

## 用法（显式档，不进常规测试）

```
# 跑一次并判定（约 8 分钟 + 起跑前的挡位自证）
python devtools/heap_gate.py

# 只判既有产物（不重跑、不拉 JVM）
python devtools/heap_gate.py --from-soak build/dbg-heap-gate

# 故意把峰值上限调低一档，验证闸真的会红（取证用）
python devtools/heap_gate.py --max-peak-mib 400 --from-soak build/dbg-heap-gate
```

退出码：`0` 全过 · `3` 有判据越界 · `1` 环境/运行失败（挡位读不到、产物缺失、进程非 0）。

需要环境：`ERATW_ERBC_DIR`（或 `--erbc`）指 erbc.v1 目录、`ERATW_GAMEDIR`（或 `--game`）
指游戏根；`JAVA_HOME` 指向 Temurin 17（与 desktop.py 同源解析）。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SOAK = SCRIPT_DIR / "soak.py"
DESKTOP = SCRIPT_DIR / "desktop.py"

# 标定常数（见模块 docstring「上限常数的标定」；改条件必须重标并改这里）。
DEFAULT_XMX = "512m"
DEFAULT_SOAK_MIN = "8"
DEFAULT_IDLE_MIN_MS = "100"
DEFAULT_IDLE_MAX_MS = "500"
DEFAULT_SEED = "20260226"
DEFAULT_MIN_STEPS = 1000
DEFAULT_MAX_PEAK_MIB = 500
DEFAULT_MAX_SLOW_STEPS = 0
DEFAULT_MAX_STEP_MS = 2000
DEFAULT_MAX_GROWTH_MIB = 50

# 挡位 → 期望的 Runtime.maxMemory() 字节数（字节级自证；未知挡位只打印不比对）。
EXPECTED_MAX_HEAP_BYTES = {
    "512m": 512 * 1024 * 1024,
    "1g": 1024 * 1024 * 1024,
    "3g": 3 * 1024 * 1024 * 1024,
    "6g": 6 * 1024 * 1024 * 1024,
}

MIB = 1024 * 1024


def _force_utf8_streams() -> None:
    """Windows 控制台缺省 cp936：判据行里的中文（「离挡位余量」这类）会在打印时炸或落成乱码，
    出事时读不到证据。与 devtools/emit_update.py 同一处理。"""
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass


_force_utf8_streams()


def parse_kv_line(text: str, prefix: str) -> dict[str, str]:
    """从 soak.txt 里取以 [prefix] 开头的那一行，按 `k=v` 拆（值不含空格）。"""
    for line in text.splitlines():
        if line.startswith(prefix):
            body = line[len(prefix):]
            out: dict[str, str] = {}
            for token in body.split():
                if "=" in token:
                    k, v = token.split("=", 1)
                    out[k] = v
            return out
    return {}


def int_of(text: str, pattern: str) -> int:
    """在全文里按正则取第一个整数；取不到 = -1（调用方按「缺读数」判红，不许当 0 蒙过去）。"""
    m = re.search(pattern, text)
    return int(m.group(1)) if m else -1


def read_jsonl_heap(path: Path) -> list[int]:
    """逐步台账里的堆水位（MiB），按文件顺序；没有这个文件（上一代产物）返回空表。"""
    if not path.is_file():
        return []
    values: list[int] = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            row = json.loads(raw)
        except ValueError:
            continue
        for key in ("heapUsedMi", "heapUsedMiB"):
            if key in row:
                try:
                    values.append(int(row[key]))
                except (TypeError, ValueError):
                    pass
                break
    return values


def read_soak(out_dir: Path) -> dict[str, int]:
    """读两代读数，归成一个口径的 dict：ceilingMiB / peakMiB / firstMiB / lastMiB / samples。

    缺文件 = 环境失败（退出码 1），不是「判据越界」。
    """
    path = out_dir / "soak.txt"
    if not path.is_file():
        sys.exit(f"[gate] 找不到 {path}：浸泡没有产出读数（先跑 soak.py 或检查 --out）")
    text = path.read_text(encoding="utf-8", errors="replace")
    heap_line = parse_kv_line(text, "heapSamples=")
    steps = read_jsonl_heap(out_dir / "soak-steps.jsonl")

    ceiling = int_of(text, r"^heapCeilingMi=(\d+)")
    if ceiling < 0:
        # 上一代没有 heapCeilingMi，只有字节级那行。
        bytes_ = int_of(text, r"jvmMaxHeapBytes=(\d+)")
        ceiling = bytes_ // MIB if bytes_ > 0 else -1

    peak = int_of(text, r"^heapPeakMi=(\d+)")
    if peak < 0:
        peak = int(heap_line.get("heapPeakUsedMiB", "-1"))

    samples = len(steps)
    first = steps[0] if steps else int(heap_line.get("heapFirstMiB", "-1"))
    last = steps[-1] if steps else int(heap_line.get("heapLastMiB", "-1"))
    if not steps and "heapSamples" in heap_line:
        samples = int(heap_line["heapSamples"])

    return {
        "ceilingMiB": ceiling,
        "peakMiB": peak,
        "firstMiB": first,
        "lastMiB": last,
        "samples": samples,
        "bytes": ceiling * MIB if ceiling >= 0 else -1,
    }


def probe_tier(xmx: str) -> int:
    """起跑前让**子进程自己**报挡位字节数（`desktop.py cmd --xmx X heap`）。取不到 = -1。"""
    argv = [sys.executable, str(DESKTOP), "cmd", "--xmx", xmx, "heap"]
    print("[gate] 挡位自证：" + " ".join(argv), flush=True)
    try:
        done = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"[gate] 挡位自证起不来：{e}", file=sys.stderr)
        return -1
    m = re.search(r"jvmMaxHeapBytes=(\d+)", done.stdout or "")
    return int(m.group(1)) if m else -1


def run_soak(args: argparse.Namespace) -> int:
    argv = [
        sys.executable, str(SOAK),
        "--xmx", args.xmx,
        "--soak-min", str(args.soak_min),
        "--idle-min-ms", str(args.idle_min_ms),
        "--idle-max-ms", str(args.idle_max_ms),
        "--seed", str(args.seed),
        "--out", args.out,
    ]
    print("[gate] " + " ".join(argv), flush=True)
    return subprocess.run(argv).returncode


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="512 MiB 挡位 Java 堆驻留闸（obs-heap512）")
    ap.add_argument("--xmx", default=DEFAULT_XMX, help="JVM 挡位（本闸标定值 512m）")
    ap.add_argument("--soak-min", default=DEFAULT_SOAK_MIN)
    ap.add_argument("--idle-min-ms", default=DEFAULT_IDLE_MIN_MS)
    ap.add_argument("--idle-max-ms", default=DEFAULT_IDLE_MAX_MS)
    ap.add_argument("--seed", default=DEFAULT_SEED)
    ap.add_argument("--out", default="build/dbg-heap-gate")
    ap.add_argument("--from-soak", default="", help="只判既有产物目录，不重跑")
    ap.add_argument("--min-steps", type=int, default=DEFAULT_MIN_STEPS)
    ap.add_argument("--max-peak-mib", type=int, default=DEFAULT_MAX_PEAK_MIB)
    ap.add_argument("--max-slow-steps", type=int, default=DEFAULT_MAX_SLOW_STEPS)
    ap.add_argument("--max-step-ms", type=int, default=DEFAULT_MAX_STEP_MS)
    ap.add_argument("--max-growth-mib", type=int, default=DEFAULT_MAX_GROWTH_MIB)
    ap.add_argument("--skip-tier-probe", action="store_true",
                    help="不拉子进程自证挡位（只用于判历史产物；正常跑不许关）")
    args = ap.parse_args(argv)

    out_dir = Path(args.from_soak or args.out).resolve()
    rc = 0
    if not args.from_soak:
        rc = run_soak(args)
        if rc != 0:
            # OOM / 装配失败 / 墙钟异常：不吞、不重试、不改挡位蒙混。
            print(f"[gate] FAIL 浸泡进程退出码 {rc}（OOM/装配失败/墙钟异常；见 {out_dir}）", file=sys.stderr)
            return 1

    readings = read_soak(out_dir)
    summary = parse_kv_line((out_dir / "soak.txt").read_text(encoding="utf-8", errors="replace"), "summary=")
    text = (out_dir / "soak.txt").read_text(encoding="utf-8", errors="replace")

    violations: list[str] = []

    def check(ok: bool, label: str, actual: str) -> None:
        print(f"[gate] {'PASS' if ok else 'FAIL'} {label}: {actual}")
        if not ok:
            violations.append(label)

    # 1. 挡位自证。重跑那一支用子进程实时读数（最强：证明 --xmx 真落进了被测 JVM）；
    #    --from-soak 那一支只能信产物里钉下的挡位。
    expected = EXPECTED_MAX_HEAP_BYTES.get(args.xmx.lower())
    measured_bytes = -1
    if not args.from_soak and not args.skip_tier_probe:
        measured_bytes = probe_tier(args.xmx)
    if measured_bytes < 0:
        measured_bytes = readings["bytes"]
        source = "产物读数"
    else:
        source = "子进程实时自证"
    if expected is not None:
        check(measured_bytes == expected,
              f"挡位 {args.xmx} 的 jvmMaxHeapBytes（{source}）",
              f"实测 {measured_bytes}（期望 {expected}）")
    else:
        print(f"[gate] INFO 挡位 {args.xmx} 无期望字节数，跳过字节级比对（实测 {measured_bytes}）")

    # 2. 读数完整性：步数、采样条数。
    n = int(summary.get("n", "0"))
    check(n >= args.min_steps, "步数下限", f"n={n}（下限 {args.min_steps}）")
    check(readings["samples"] == n, "堆采样覆盖步数", f"采样 {readings['samples']} 条 / n={n}")

    # 3. 驻留峰值 / 增量。
    peak = readings["peakMiB"]
    check(0 <= peak <= args.max_peak_mib,
          "堆 used 峰值上限", f"peak={peak}MiB（上限 {args.max_peak_mib}）")
    first, last = readings["firstMiB"], readings["lastMiB"]
    growth = last - first if first >= 0 and last >= 0 else -999
    # 只封顶「增长」；负增长（GC 回吐）是好事，不该判红。首末读数缺失才算红。
    check(growth != -999 and growth <= args.max_growth_mib,
          "驻留增量上限（末-首）", f"last-first={growth}MiB（上限 +{args.max_growth_mib}）")

    # 4. 慢页 / 单页墙钟。慢页键 `slow(>=5000ms)=N` 自带 `>=`（等于号会让通用 k=v 拆分错位），
    #    因此按原始行整句正则取。
    slow_m = re.search(r"slow\(>=\d+ms\)=(\d+)", text)
    slow = int(slow_m.group(1)) if slow_m else -1
    # `max=70ms` 自带单位；只取数字部分（单位缺省 = 毫秒，与 soak.txt 口径一致）。
    worst_m = re.match(r"\d+", summary.get("max", "0"))
    worst = int(worst_m.group(0)) if worst_m else -1
    check(slow >= 0 and slow <= args.max_slow_steps,
          "慢页条数上限（>=5000ms）", f"slow={slow}（上限 {args.max_slow_steps}）")
    check(0 <= worst <= args.max_step_ms, "单页墙钟上限", f"max={worst}ms（上限 {args.max_step_ms}）")

    if violations:
        print(f"[gate] FAIL 越界项 {len(violations)} 条：{', '.join(violations)}", file=sys.stderr)
        return 3
    print(f"[gate] PASS（{out_dir}；挡位 {args.xmx}，n={n}，peak={peak}MiB，growth={growth}MiB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
