#!/usr/bin/env python3
"""真机装载内存采样装置（mem-4981-peak device lane / C-DEVICE-AB@1）。

用途
====
在固定真机上复现「冷启动首次进入装载链」，并按固定间隔采三列读数，
落成可复读的读数产物，供基线与优化后 A/B 对比（T5 产出 `C-DEVICE-AB@1`，T7 复用）。

三列读数（口径与 plan §2.2 `C-DEVICE-AB@1` 逐条对齐）
----------------------------------------------------
① `LoadTrace` 的 `load.peak used=/max= native= at= elapsed= series=`：release 装机包
   （`isDebuggable()==false`）里 `LoadTrace.logEnabled=false`，日志静默；真机 vivo
   又实测会丢应用 logcat。因此本装置**不依赖**该行日志，改以 ③ 的 ART OOM 行与屏幕
   截图旁证峰值；若该行确实出现在 logcat 里（debug 包），照原样收进 `logcat-hits.txt`。
② `adb shell dumpsys meminfo <pkg>`：`Java Heap`/`Dalvik Heap` PSS、`TOTAL PSS`/
   `TOTAL RSS`/`Graphics`。原始输出整份落盘，解析值进 `samples.jsonl`。
③ `adb logcat -d` 过滤 `OutOfMemoryError|FATAL EXCEPTION|lowmemorykiller|EratwRun|EratwLoad`
   （另加 `GameHost` 标签与 ART 的 `art`/`am_kill`/`lmkd`，因为 LoadTrace 在 debug 包里
   用 `GameHost` 标签，而 release 的失败/预检走 `Log.e(TAG)` 也是该标签）。

启动口径（与组长基线同）
------------------------
1. `adb shell am force-stop <pkg>`（确保冷启）
2. `adb logcat -c`（清旧日志）
3. `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`（触发首次进入）
4. 每 `--interval` 秒采一次，直到 `--duration` 秒或进程消失后再多采 `--post-mortem` 秒

采样是**短轮询**：每次 adb 调用独立、阻塞在单次调用上，不靠长 sleep 堆叠。

用法
====
    python devtools/device_mem_probe.py --label baseline --duration 45
    python devtools/device_mem_probe.py --label ab-run2 --duration 45 --out .local/device-mem/ab
    python devtools/device_mem_probe.py --no-launch --label sample-only --duration 20

退出时**一定** `force-stop`，不留残留进程。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import os
import shutil
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path

# ---- 默认口径（与 plan §2.2 C-DEVICE-AB@1 冻结值一致）----
# adb 缺省路径不写死个人机器：优先 ERATW_ADB，其次 ANDROID_HOME / ANDROID_SDK_ROOT，最后 PATH。
DEFAULT_ADB = os.environ.get("ERATW_ADB", "")
DEFAULT_SERIAL = "10ACCD2B4Y004PK"
DEFAULT_PKG = "com.eratw.port.android"


def default_adb_path() -> str:
    """adb 缺省解析：ERATW_ADB → ANDROID_HOME / ANDROID_SDK_ROOT → PATH；都落空退 "adb"。

    公开仓不内置个人机器路径；需要固定读数口径时显式给 --adb 或 ERATW_ADB。
    """
    candidates = [
        DEFAULT_ADB,
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb.exe"),
        os.path.join(os.environ.get("ANDROID_SDK_ROOT", ""), "platform-tools", "adb.exe"),
    ]
    for cand in candidates:
        if cand and os.path.isfile(cand):
            return cand
    found = shutil.which("adb")
    return found or DEFAULT_ADB or "adb"

# ③ 列判定正则（组长口径 + GameHost 标签 + ART/lmkd 兜底）
LOG_HIT_RE = re.compile(
    r"(OutOfMemoryError|FATAL EXCEPTION|lowmemorykiller|\bEratwRun\b|\bEratwLoad\b"
    r"|\bGameHost\b|\bload\.peak\b|\bload\.preflight\b|\bam_kill\b|\blmkd\b"
    r"|giving up on allocation|target footprint)"
)


@dataclass
class Sample:
    """一次采样（三列读数的一行）。"""

    idx: int
    wall: str                       # 墙钟 ISO 时间
    t_rel_s: float                  # 距 monkey 启动的相对秒
    pid: str                        # pidof 结果（空 = 进程已不在）
    java_heap_pss_kb: int | None = None
    dalvik_heap_pss_kb: int | None = None
    graphics_kb: int | None = None
    total_pss_kb: int | None = None
    total_rss_kb: int | None = None
    log_hits: list[str] = field(default_factory=list)
    dumpsys_path: str = ""
    screen_path: str = ""

    def mib(self, kb: int | None) -> str:
        return "n/a" if kb is None else f"{kb / 1024.0:.1f}"


def run_adb(adb: str, serial: str, args: list[str], timeout: float = 30.0,
            text: bool = True) -> subprocess.CompletedProcess:
    """调用 adb（参数以列表传入，避免 MSYS/Windows shell 对 `/sdcard` 一类路径做转换）。

    用参数列表 + 不设 shell，是本装置能在 git-bash / cmd 下都跑对的关键：
    MSYS 的 `MSYS_NO_PATHCONV` 只在 bash 层面起作用，Python 直接 CreateProcess
    不经那层，因此 `/data/...`、`/sdcard/...` 原样传给 adb.exe。
    """
    cmd = [adb, "-s", serial, *args]
    return subprocess.run(
        cmd, capture_output=True, timeout=timeout,
        text=text, encoding="utf-8", errors="replace",
    )


def parse_meminfo(text: str) -> dict:
    """从 `dumpsys meminfo <pkg>` 全文抽 ② 列读数（找不到就留 None，不以 0 充数）。

    Android 14 的 `dumpsys meminfo` 同时有两段：进程表（`Dalvik Heap` 行的 Pss Total）
    与 `App Summary`（`Java Heap:`/`Graphics:`/`TOTAL:`）。两段的列名不同，分开匹配。
    """
    out: dict = {
        "java_heap_pss_kb": None,
        "dalvik_heap_pss_kb": None,
        "graphics_kb": None,
        "total_pss_kb": None,
        "total_rss_kb": None,
    }
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r"^Java Heap:\s+(\d+)", line)
        if m:
            out["java_heap_pss_kb"] = int(m.group(1))
            continue
        m = re.match(r"^Dalvik Heap\s+(\d+)", line)
        if m:
            out["dalvik_heap_pss_kb"] = int(m.group(1))
            continue
        m = re.match(r"^Graphics:\s+(\d+)", line) or re.match(r"^Graphics\s+(\d+)", line)
        if m:
            out["graphics_kb"] = int(m.group(1))
            continue
        # App Summary 的收尾行把三个 TOTAL 拼在一行：
        # `TOTAL PSS:   319283   TOTAL RSS:   430412   TOTAL SWAP PSS:  78`
        # 因此 PSS/RSS 必须用 search（不锚定行首），否则只会命中表段的 `TOTAL PSS:`。
        m = re.search(r"TOTAL PSS:\s+(\d+)", line) or re.search(r"^TOTAL:\s+(\d+)", line)
        if m and out["total_pss_kb"] is None:
            out["total_pss_kb"] = int(m.group(1))
        m = re.search(r"TOTAL RSS:\s+(\d+)", line)
        if m and out["total_rss_kb"] is None:
            out["total_rss_kb"] = int(m.group(1))
    return out


def stable_unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    res: list[str] = []
    for it in items:
        if it not in seen:
            seen.add(it)
            res.append(it)
    return res


def take_screenshot(adb: str, serial: str, dest: Path) -> bool:
    """截屏落盘（失败不致命：release 包无 SD 权限写 /sdcard 时改用 exec-out 流式）。"""
    try:
        cp = subprocess.run(
            [adb, "-s", serial, "exec-out", "screencap -p"],
            capture_output=True, timeout=30.0,
        )
        if cp.returncode == 0 and cp.stdout:
            dest.write_bytes(cp.stdout)
            return True
    except Exception:
        pass
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description="真机装载内存采样装置（C-DEVICE-AB@1）")
    ap.add_argument("--adb", default=default_adb_path())
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--interval", type=float, default=5.0, help="采样间隔秒（冻结契约：5 s）")
    ap.add_argument("--duration", type=float, default=45.0, help="总采样时长秒")
    ap.add_argument("--post-mortem", type=float, default=10.0,
                    help="进程消失后再多采的秒数（收尾确认进程确已退出）")
    ap.add_argument("--out", default=".local/device-mem/baseline",
                    help="产物根目录（相对仓库根或绝对路径）")
    ap.add_argument("--label", default="run", help="本轮标签（会话子目录名）")
    ap.add_argument("--no-launch", action="store_true",
                    help="不 force-stop / 不 monkey 启动，仅对当前前台进程采样（T7 复用）")
    ap.add_argument("--screenshot", action="store_true", default=True,
                    help="每次采样顺带截屏（默认开，便于看失败屏）")
    ap.add_argument("--no-screenshot", dest="screenshot", action="store_false")
    args = ap.parse_args()

    adb, serial, pkg = args.adb, args.serial, args.pkg
    out_root = Path(args.out)
    session = out_root / f"{datetime.now():%Y%m%d-%H%M%S}-{args.label}"
    dumpsys_dir = session / "dumpsys"
    screen_dir = session / "screen"
    for d in (out_root, session, dumpsys_dir, screen_dir):
        d.mkdir(parents=True, exist_ok=True)

    commands: list[str] = []

    def log_cmd(c: str) -> None:
        commands.append(c)
        print(f"[cmd] {c}", flush=True)

    print(f"[probe] session = {session}", flush=True)

    # 装机包体检（D4 旁证：非调试包 ⇒ run-as 不可用 ⇒ 只能靠日志/屏推断 preflight）
    log_cmd(f"{adb} -s {serial} shell dumpsys package {pkg}")
    pkg_dump = run_adb(adb, serial, ["shell", "dumpsys", "package", pkg])
    (session / "package-dump.txt").write_text(pkg_dump.stdout or "", encoding="utf-8")

    log_cmd(f"{adb} -s {serial} shell pm path {pkg}")
    pm_path = run_adb(adb, serial, ["shell", "pm", "path", pkg])
    (session / "pm-path.txt").write_text(pm_path.stdout or "", encoding="utf-8")

    if not args.no_launch:
        log_cmd(f"{adb} -s {serial} shell am force-stop {pkg}")
        run_adb(adb, serial, ["shell", "am", "force-stop", pkg])
        log_cmd(f"{adb} -s {serial} logcat -c")
        run_adb(adb, serial, ["logcat", "-c"])

    if not args.no_launch:
        launch_cmd = (f"{adb} -s {serial} shell monkey -p {pkg} "
                      f"-c android.intent.category.LAUNCHER 1")
        log_cmd(launch_cmd)
        t0 = time.time()
        monkey = run_adb(adb, serial, ["shell", "monkey", "-p", pkg,
                                       "-c", "android.intent.category.LAUNCHER", "1"])
        (session / "monkey.txt").write_text(
            (monkey.stdout or "") + "\n[stderr]\n" + (monkey.stderr or ""), encoding="utf-8")
    else:
        t0 = time.time()
        print("[probe] --no-launch：假定目标已在前台装载", flush=True)

    samples: list[Sample] = []
    all_log_hits: list[str] = []
    idx = 0
    deadline = t0 + args.duration
    # 进程消失后的收尾窗口
    dead_since: float | None = None

    while True:
        now = time.time()
        if now > deadline:
            break
        if dead_since is not None and (now - dead_since) > args.post_mortem:
            break

        t_rel = now - t0
        wall = datetime.now().strftime("%Y-%m-%d %H:%M:%S") + f".{datetime.now().microsecond // 1000:03d}"

        pid_cp = run_adb(adb, serial, ["shell", "pidof", pkg])
        pid = (pid_cp.stdout or "").strip()
        if not pid:
            if dead_since is None:
                dead_since = now
                print(f"[probe] t+{t_rel:.1f}s 进程消失（pidof 空）", flush=True)

        s = Sample(idx=idx, wall=wall, t_rel_s=round(t_rel, 2), pid=pid)

        if pid:
            # 装载到贴边时进程在 Thrash，`dumpsys meminfo` 偶发 `Got IoException! Timeout`
            # 而被截断（原始输出里 App Summary 缺失 ⇒ Java Heap/TOTAL 读数为空）。这里重试
            # 最多 3 次并在重试间短歇，尽量把峰值那一拍采全；仍失败就如实留空（不以 0 充数）。
            mem_text = ""
            for attempt in range(3):
                mem_cp = run_adb(adb, serial, ["shell", "dumpsys", "meminfo", pkg], timeout=40.0)
                mem_text = mem_cp.stdout or ""
                if "Got IoException" not in mem_text and "TOTAL PSS:" in mem_text:
                    break
                time.sleep(0.4)
            dp = dumpsys_dir / f"t{t_rel:06.1f}.txt"
            dp.write_text(mem_text, encoding="utf-8")
            s.dumpsys_path = str(dp)
            for k, v in parse_meminfo(mem_text).items():
                setattr(s, k, v)

        # ③ 列：本采样窗口的命中行（-t 1200 限制体量，按正则过滤）
        lg = run_adb(adb, serial, ["logcat", "-d", "-v", "time", "-t", "1200"])
        hits = [ln.strip() for ln in (lg.stdout or "").splitlines() if LOG_HIT_RE.search(ln)]
        s.log_hits = stable_unique(hits)
        all_log_hits.extend(s.log_hits)

        if args.screenshot and pid:
            sp = screen_dir / f"t{t_rel:06.1f}.png"
            if take_screenshot(adb, serial, sp):
                s.screen_path = str(sp)

        samples.append(s)
        print(
            f"[sample {idx:02d}] t+{t_rel:5.1f}s pid={pid or '-':>8s} "
            f"java={s.mib(s.java_heap_pss_kb)}MiB dalvik={s.mib(s.dalvik_heap_pss_kb)}MiB "
            f"pss={s.mib(s.total_pss_kb)}MiB rss={s.mib(s.total_rss_kb)}MiB "
            f"gfx={s.mib(s.graphics_kb)}MiB hits={len(s.log_hits)}",
            flush=True,
        )

        idx += 1
        # 短轮询：按固定间隔推进到下一个采样点（不堆叠长 sleep）
        nxt = t0 + idx * args.interval
        sleep_for = nxt - time.time()
        if sleep_for > 0:
            time.sleep(sleep_for)

    # 收尾：全量 logcat（判定 ③ 列的最终来源）
    log_cmd(f"{adb} -s {serial} logcat -d -v time")
    full_lg = run_adb(adb, serial, ["logcat", "-d", "-v", "time"], timeout=60.0)
    (session / "logcat-full.txt").write_text(full_lg.stdout or "", encoding="utf-8")

    (session / "logcat-hits.txt").write_text(
        "\n".join(stable_unique(all_log_hits)) + "\n", encoding="utf-8")

    with (session / "samples.jsonl").open("w", encoding="utf-8") as fh:
        for s in samples:
            fh.write(json.dumps(asdict(s), ensure_ascii=False) + "\n")

    # 峰值摘要
    def peak(attr: str):
        vals = [getattr(s, attr) for s in samples if getattr(s, attr) is not None]
        return max(vals) if vals else None

    peak_lines = [
        f"session        {session}",
        f"pkg            {pkg}",
        f"serial         {serial}",
        f"duration       {args.duration}s interval={args.interval}s samples={len(samples)}",
        f"peak Java Heap PSS   {peak('java_heap_pss_kb')} KB "
        f"({(peak('java_heap_pss_kb') or 0) / 1024.0:.1f} MiB)",
        f"peak Dalvik Heap PSS {peak('dalvik_heap_pss_kb')} KB "
        f"({(peak('dalvik_heap_pss_kb') or 0) / 1024.0:.1f} MiB)",
        f"peak TOTAL PSS       {peak('total_pss_kb')} KB "
        f"({(peak('total_pss_kb') or 0) / 1024.0:.1f} MiB)",
        f"peak TOTAL RSS       {peak('total_rss_kb')} KB "
        f"({(peak('total_rss_kb') or 0) / 1024.0:.1f} MiB)",
        f"peak Graphics        {peak('graphics_kb')} KB "
        f"({(peak('graphics_kb') or 0) / 1024.0:.1f} MiB)",
        f"process died at      "
        f"{next((s.t_rel_s for s in samples if not s.pid), None)} (t_rel_s; None=仍存活)",
        f"log hits (unique)    {len(stable_unique(all_log_hits))}",
    ]
    (session / "summary.txt").write_text("\n".join(peak_lines) + "\n", encoding="utf-8")
    print("\n".join(peak_lines), flush=True)

    (session / "commands.txt").write_text("\n".join(commands) + "\n", encoding="utf-8")

    # 收尾清场：不留残留进程
    log_cmd(f"{adb} -s {serial} shell am force-stop {pkg}")
    run_adb(adb, serial, ["shell", "am", "force-stop", pkg])
    return 0


if __name__ == "__main__":
    sys.exit(main())