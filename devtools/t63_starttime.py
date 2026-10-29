"""T2-63 A/B 的端到端读数：从 `am start` 到「标题页出现可点选项」的墙钟时间。

为什么不用 `am start -W` 的 TotalTime 收工：那只到 Activity 首帧，而 baseline profile
的收益主要落在**装载期**（解析 erbc、建对象图）和**首屏 ERB 执行**上，这一段全在首帧之后。
这里用 uiautomator 轮询「开始游戏」按钮出现作为「装载完成」的判据 —— 与玩家看到可点
选项的时刻一致，且 A/B 走同一条轮询路径，dump 自身的开销在两侧等量。

用法：python t63_starttime.py <轮数> <标签>
"""

from __future__ import annotations

import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hawkeye as hk  # noqa: E402
import t63probe  # noqa: E402

PKG = hk.PKG


def sh(cmd: str) -> str:
    out = hk.adb("shell", cmd, timeout=180)
    return out if isinstance(out, str) else out.decode("utf-8", "replace")


def one_round(limit: float = 240.0) -> float | None:
    sh(f"am force-stop {PKG}")
    time.sleep(2.0)
    t0 = time.perf_counter()
    sh(f"am start -n {PKG}/.MainActivity")
    while time.perf_counter() - t0 < limit:
        try:
            items = t63probe.read_items()
        except Exception:
            items = []
        if any("开始游戏" in it.text or "继续游戏" in it.text for it in items):
            return time.perf_counter() - t0
        time.sleep(0.2)
    return None


def main() -> None:
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    tag = sys.argv[2] if len(sys.argv) > 2 else "?"
    vals = []
    for i in range(1, rounds + 1):
        dt = one_round()
        if dt is None:
            print(f"  第 {i} 轮：超时没等到标题页")
            continue
        vals.append(dt)
        print(f"  第 {i} 轮：{dt:.2f}s")
    if vals:
        print(f"[{tag}] 读数 {', '.join(f'{v:.2f}' for v in vals)}  "
              f"中位数 {statistics.median(vals):.2f}s")


if __name__ == "__main__":
    main()
