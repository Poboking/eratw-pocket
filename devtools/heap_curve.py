#!/usr/bin/env python3
"""装载堆曲线探针 —— 把「峰值」从口头感觉变成一条可复跑的读数。

为什么要有这个：2026-10-28 首个真机包在 vivo（largeHeap 上限 512 MiB）上装载期 OOM。
复盘时发现我们**从来没有**量过装载峰值 —— 构建绿、单测绿、失败屏文案齐全，唯独没人
回答「解码那一刻到底占多少堆」。这类问题只能靠一条曲线暴露：干净进程基线、装载峰值、
稳定态，三个数摆在一起才看得出余量是 103 MiB 还是 -4 MiB。

它做的事：（可选）装包 → 冷启 → 按秒轮询 `dumpsys meminfo` 的 `Dalvik Heap` 行
（committed 与 alloc 两个数）→（可选）走一遍「设置 → 保存并重启」再量第二次装载 →
打印曲线、峰值、稳定态，以及**重启时上一局让出堆之后的 alloc 低点**（那个低点是不是
干净进程的 14-15 MiB，就是「有没有东西还攥着旧会话」的直接证据）。

退出码：`0` committed 峰值在预算内 · `1` 设备/流程不健康（没起来、崩了、取不到读数）·
`3` committed 峰值超预算（`--peak-budget-mib`，缺省 460 MiB = 512 MiB 机型留 50 MiB 余量）。

坐标口径：`--tap-settings` 是**游戏画布上画的**按钮（不是真 View，uiautomator 找不到），
缺省值按 2400x1080 横屏模拟器量出来的；换设备要一起换。设置页里的按钮走 uiautomator
按文字定位，不受分辨率影响。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
DEFAULT_PACKAGE = "com.eratw.port.android"
READY_MARK = "图形通路已接"          # GameHost 装载完成的日志锚点
CLOSED_MARK = "输入队列已关闭"       # 旧会话 VM 线程退出的日志锚点
DEFAULT_APK = os.path.join(APP_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")


def find_adb() -> str:
    """找 adb：环境变量 > 本机 SDK > PATH（AGENTS §1：工具链在共享根之外）。"""
    for cand in (os.environ.get("ADB"),
                 os.path.expanduser("~/asdk/platform-tools/adb.exe"),
                 os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb.exe")):
        if cand and os.path.isfile(cand):
            return cand
    return "adb"


class Device:
    def __init__(self, adb: str, serial: str):
        self.adb, self.serial = adb, serial

    def shell(self, cmd: str, timeout: int = 60) -> str:
        args = [self.adb] + (["-s", self.serial] if self.serial else []) + ["shell", cmd]
        return subprocess.run(args, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout).stdout

    def run(self, args: list, timeout: int = 600) -> tuple:
        full = [self.adb] + (["-s", self.serial] if self.serial else []) + args
        proc = subprocess.run(full, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

    def heap_kib(self, pkg: str) -> tuple:
        """从 `dumpsys meminfo` 的 `Dalvik Heap` 行取 (Heap Size, Heap Alloc) KB。

        为什么不是 App Summary 里的 `Java Heap:` 那一行：那是**按类别聚合的 Pss**
        （Dalvik Heap + Dalvik Other…），能超过 ROM 的堆上限，读它会把「committed 顶到
        天花板」这件真正要盯的事糊掉。8 列表的 `Dalvik Heap` 行才是 ART 自己的口径：

            Dalvik Heap  Pss PrivDirty PrivClean SwapDirty Rss  HeapSize  HeapAlloc  HeapFree

        `Heap Size` = 已提交的堆（对应 OOM 消息里的 `target footprint`），
        `Heap Alloc` = 活物 + 未回收垃圾（对应 `used`）。取不到返回 (-1, -1)。
        """
        out = self.shell(f"dumpsys meminfo {pkg}")
        for line in out.splitlines():
            if line.strip().startswith("Dalvik Heap"):
                nums = re.findall(r"\d+", line)
                if len(nums) >= 8:
                    return int(nums[5]), int(nums[6])
        return (-1, -1)

    def pidof(self, pkg: str) -> str:
        return self.shell(f"pidof {pkg}").strip()


def wait_ready(dev: Device, pkg: str, deadline_s: int, samples: list, interval: float) -> bool:
    """轮询到「装载完成」或进程消失/超时；期间把 (Heap Size, Heap Alloc) 记进 samples。"""
    deadline = time.time() + deadline_s
    while time.time() < deadline:
        size, alloc = dev.heap_kib(pkg)
        samples.append((time.strftime("%H:%M:%S"), size, alloc))
        if ready_in_log(dev, pkg):
            return True
        if not dev.pidof(pkg):
            print(f"[heap] 进程没了（size={size}KB alloc={alloc}KB）—— 崩溃或被杀，看 logcat",
                  file=sys.stderr)
            return False
        time.sleep(interval)
    return False


def ready_in_log(dev: Device, pkg: str) -> bool:
    out = dev.shell("logcat -d -v brief")
    return READY_MARK in out


def find_bounds(dev: Device, text: str) -> tuple:
    """uiautomator 按文字定位一个节点的中心点（设置页里的按钮走这条路，分辨率无关）。"""
    dev.shell("uiautomator dump /sdcard/heap_probe.xml")
    xml = dev.shell("cat /sdcard/heap_probe.xml")
    try:
        root = ET.fromstring(xml[xml.index("<hierarchy"):])
    except (ValueError, ET.ParseError):
        return (-1, -1)
    for node in root.iter("node"):
        if node.attrib.get("text", "").strip().startswith(text):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = (int(v) for v in m.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return (-1, -1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="eratw 装载堆曲线探针（装机实测，不看估算）")
    ap.add_argument("--serial", default=os.environ.get("ERATW_SERIAL", ""))
    ap.add_argument("--package", default=DEFAULT_PACKAGE)
    ap.add_argument("--apk", default="", help="给了就先 `adb install -r`（同章可覆盖，存档不丢）")
    ap.add_argument("--restart", action="store_true", help="再走一遍「设置 → 保存并重启」量第二次装载")
    ap.add_argument("--tap-settings", default="2310,70", help="游戏画布上「设置」按钮坐标（横屏像素）")
    ap.add_argument("--interval", type=float, default=2.0)
    ap.add_argument("--boot-budget", type=int, default=240, help="等首次装载完成的秒数")
    ap.add_argument("--restart-budget", type=int, default=240, help="等重启后第二次装载完成的秒数")
    ap.add_argument("--peak-budget-mib", type=int, default=460,
                    help="峰值预算（MiB）：512 MiB 机型留 50 MiB 余量 = 460")
    args = ap.parse_args(argv)

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    dev = Device(find_adb(), args.serial)
    if args.apk:
        code, out = dev.run(["install", "-r", args.apk])
        print(f"[heap] install rc={code} {out.strip().splitlines()[-1] if out.strip() else ''}")
        if code != 0:
            return 1

    dev.shell(f"am force-stop {args.package}")
    dev.run(["logcat", "-c"])
    dev.shell(f"am start -n {args.package}/.MainActivity")

    def peaks(samples: list) -> tuple:
        """(committed 峰值, alloc 峰值, 末值 committed, 末值 alloc, alloc 低点)。"""
        got = [(s, a) for _, s, a in samples if s > 0 and a > 0]
        if not got:
            return (-1, -1, -1, -1, -1)
        return (max(s for s, _ in got), max(a for _, a in got),
                got[-1][0], got[-1][1], min(a for _, a in got))

    def show(tag: str, pk: tuple) -> None:
        print(f"[heap] {tag} committed峰={pk[0] / 1024:.0f}MiB alloc峰={pk[1] / 1024:.0f}MiB "
              f"末值={pk[2] / 1024:.0f}/{pk[3]:.0f}MiB alloc低点={pk[4] / 1024:.0f}MiB")

    first: list = []
    print("[heap] 冷启动，等首次装载…")
    ok = wait_ready(dev, args.package, args.boot_budget, first, args.interval)
    p1 = peaks(first)
    show(f"首次装载 ready={ok} 采样={len(first)}点", p1)
    if not ok or p1[0] < 0:
        return 1

    worst = p1[0]
    report = {"first": p1}
    if args.restart:
        sx, sy = (int(v) for v in args.tap_settings.split(","))
        dev.shell(f"input tap {sx} {sy}")
        time.sleep(3)
        rx, ry = find_bounds(dev, "保存并重启")
        if rx < 0:
            print("[heap] 找不到「保存并重启」按钮（设置页没开？uiautomator 拿不到节点？）", file=sys.stderr)
            return 1
        dev.run(["logcat", "-c"])
        dev.shell(f"input tap {rx} {ry}")
        print(f"[heap] 已点「保存并重启」({rx},{ry})，等第二次装载…")
        second: list = []
        ok2 = wait_ready(dev, args.package, args.restart_budget, second, args.interval)
        p2 = peaks(second)
        closed = CLOSED_MARK in dev.shell("logcat -d -v brief")
        show(f"第二次装载 ready={ok2} 旧线程退出日志={closed} 采样={len(second)}点", p2)
        print("[heap] 曲线(秒:committed/alloc MiB): " + " ".join(
            f"{i * args.interval:.0f}:{s / 1024:.0f}/{a / 1024:.0f}"
            for i, (_, s, a) in enumerate(second) if s > 0))
        # alloc 低点 = 上一局有没有真的让出堆：干净进程实测 14-15 MiB，
        # 若这里读到 100+ MiB，说明还有东西攥着旧会话（2026-10-28 那次就是 276 MiB）。
        worst = max(worst, p2[0])
        report["restart"] = p2
        if not ok2:
            return 1

    verdict = "通过" if worst / 1024 <= args.peak_budget_mib else "**超预算**"
    print(f"[heap] 预算（committed）{args.peak_budget_mib}MiB · 实测最坏 {worst / 1024:.0f}MiB ⇒ {verdict}")
    print(json.dumps({k: {"committed_peak_mib": round(v[0] / 1024), "alloc_peak_mib": round(v[1] / 1024),
                          "steady_mib": round(v[2] / 1024), "alloc_low_mib": round(v[4] / 1024)}
                      for k, v in report.items()}, ensure_ascii=False))
    return 0 if worst / 1024 <= args.peak_budget_mib else 3


if __name__ == "__main__":
    sys.exit(main())
