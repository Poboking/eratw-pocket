#!/usr/bin/env python3
"""soak.py —— explore 浸泡档的一行配方（obs-soak 车道；随机操作 + 随机等待跑满墙钟）。

只做一件事：把「随机游玩几十分钟、把偶发冻结变成可复跑读数」这条命令收成一个可粘贴入口，
内部原样转给 `desktop.py cmd explore --soak-min ...`（唯一无窗口拉 java 的入口，见 SKILL §2）。

用法（在 eratw-android 检出根下）：
  python devtools/soak.py                     # 缺省：浸泡 12 分钟，随机停顿 200–6000ms，慢步阈值 5s
  python devtools/soak.py --soak-min 20       # 跑满 20 分钟
  python devtools/soak.py --idle-max-ms 15000 # 拉长「玩家犹豫」的随机间隔上限
  python devtools/soak.py --boot my.txt --seed 20260226
  python devtools/soak.py --soak-min 8 --step-wall-ms 15000   # obs-midhang：单步墙钟闸
  python devtools/soak.py --xmx 512m --soak-min 10            # obs-soak512：512MiB 挡位堆压浸泡

旋钮全部透传 vmdbg explore（见 CmdExplore）：--soak-min/--idle-min-ms/--idle-max-ms/
--slow-step-ms/--wall-min/--seed/--boot/--seed-snap/--resume/--erbc/--game/--out ...
另有 --step-wall-ms（缺省 0 = 关闭）：**单段落墙钟闸**，与每局墙钟 --wall-min、
引导共用的步数闸 --steps 三者独立。玩家报的「中局卡死」是一段纯计算不回等待点，
per-episode 墙钟吃不到它；带上本闸后超时点会把 VM 中断，抛点的函数名/行号/pc/
调用链/段指令数落进 soak.txt 的「单步墙钟闸命中」专节与 findings.jsonl
（类别 STEP_WALL_LIMIT），肇事函数因此可直接定名。

另有 --xmx <量>（**不进 vmdbg，由 desktop.py 消费**）：拉起 java 的 -Xmx 挡位，
缺省 3g 逐字不变；obs-soak512 用 `--xmx 512m` 把装置压进真机同款的堆贴顶相位。

产物（全部落 --out，默认 build/dbg-soak，不入 git）：
  soak.txt           聚合 n/p50/p95/max + 慢步专节（函数名:pc / 局 / 步序 / 动作 / 耗时）
                     + 单步墙钟闸命中专节（最内层函数 / 行号 / pc / 段指令数 / 调用链）
  soak-steps.jsonl   逐条「提交 → 到点」耗时台账（一行一步）
  boot-steps.jsonl   引导逐步台账（obs-bootrecipe）：每个引导等待点一行——步序 / 输入 / 期望子串 /
                     实际动作 / 是否任意键面 / 栈顶函数 / **本次新增的屏上行**；引导失败与引导到
                     种子各写一份。boot-fail.json 只说「第 k 步没命中」，本表说的是「那一步究竟
                     落在了哪一面」，两者同目录对照着看。
  explore.jsonl / findings.jsonl / replay/ / coverage.txt / seed.dbg  既有 explore 台账全套
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DESKTOP = SCRIPT_DIR / "desktop.py"

DEFAULTS = {
    "--soak-min": "12",
    "--idle-min-ms": "200",
    "--idle-max-ms": "6000",
    "--slow-step-ms": "5000",
}


def main() -> int:
    rest = sys.argv[1:]
    # 缺省旋钮在前、用户旋钮在后（vmdbg 同名取后者）；用户点过的缺省对整体跳过，避免残留裸值。
    given = {t for t in rest if t in DEFAULTS}
    defaults: list[str] = []
    for flag, val in DEFAULTS.items():
        if flag not in given:
            defaults += [flag, val]
    if "--out" not in rest:
        defaults += ["--out", "build/dbg-soak"]
    argv = [sys.executable, str(DESKTOP), "cmd", "explore", *defaults, *rest]
    print("[soak] " + " ".join(argv), flush=True)
    return subprocess.run(argv).returncode


if __name__ == "__main__":
    sys.exit(main())
