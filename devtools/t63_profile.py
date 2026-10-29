"""T2-63 真机 ART Baseline Profile 采集/转换/A-B 驱动。

## 为什么是这个形态（2026-10-27 真机实测定案）

一开始的设计是「用 `DevToolsHost` 调试通道驱动 release 进程、读 `turnlog` 算耗时」，
但 `DevToolsHost.startOnce` 在非 debuggable 包上直接 return（`DevToolsHost.kt:492`），
而 debug 变体又不产 `assets/dexopt/baseline.prof`（AGP 只给非 debuggable 变体打）、
且 dex 布局不同（debug 7 个 / release 3 个）——**采集必须用 release 包，release 没有调试通道**。
所以驱动方式回到最朴素也最真实的一条：`adb shell input tap` 点屏幕，与玩家手指同一条路径。

立绘更新那一问（`UPDATE_MAIN.ERB:988`，2026-10-27 主人拍板）：**选「否」= 缄默更新就不会崩**；
崩的只是「渲染 60 张立绘」分支，那是原游戏设计里自己都警告「可能导致崩溃」的用法，不是移植 bug。
所以推进循环遇到 是/否 二选一时一律点「否」——既绕开渲染 OOM，也是采集期更保守的分支。

样本通道用 `am profile`，靠清单里的 `<profileable android:shell="true" />` 对
非 debuggable 包生效。产出的文件 magic 是 `SLOW`（ART method-trace），不是
`pro\\0`（ART Profile），所以转换走 `trace2profile.py` 而不是 profgen。

## 子命令

    collect          重启 → 走真实路径 → 采 trace（进程 OOM 崩溃也不影响，文件是流式写的）
    convert          把 trace 转成 baseline-prof.txt
    ab               同 APK 上做 AOT 开/关两轮对比（`cmd package compile`）

## 坐标口径

`wm size` 报的 1080x2400 是竖屏物理尺寸，应用窗口是横屏 `cur=2400x1080`
（`dumpsys window displays` 第二条）。所有坐标都取自 uiautomator 的 bounds，不手算。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hawkeye as hk  # noqa: E402
import t63probe  # noqa: E402

PKG = hk.PKG
DEVICE_TRACE = "/data/local/tmp/t63.prof"
LOCAL_PROF_DIR = Path(r"D:\DSH\t63-diag\prof")


def sh(cmd: str, timeout: float = 120.0) -> str:
    out = hk.adb("shell", cmd, timeout=timeout)
    return out if isinstance(out, str) else out.decode("utf-8", "replace")


def ensure_running(restart: bool) -> int:
    if restart:
        sh(f"am force-stop {PKG}")
        time.sleep(1.5)
        sh(f"am start -W -n {PKG}/.MainActivity")
        time.sleep(10)
    pid = sh(f"pidof {PKG}").strip()
    if not pid:
        raise SystemExit(f"{PKG} 没在跑")
    return int(pid.split()[0])


def profile_start(sampling_us: int) -> None:
    # --streaming：直接落盘。进程被 OOM 打死时内存里的缓冲会丢，这个不会。
    sh(f"am profile start --sampling {sampling_us} --streaming {PKG} {DEVICE_TRACE}")


def profile_stop() -> None:
    # 进程已经崩了的话 stop 会报 Unknown process，忽略即可（文件已经写完了）
    print(sh(f"am profile stop {PKG}").strip() or "(stop 无输出)")


def wait_for(text: str, timeout: float, poll: float = 2.5) -> bool:
    """轮询直到屏幕上出现匹配选项（按文本包含匹配）。返回是否等到。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        items = t63probe.read_items()
        hit = next((it for it in items if text in it.text), None)
        if hit is not None:
            print(f"  等到了 {text!r}: {hit}")
            return True
        time.sleep(poll)
    print(f"  !! 超时没等到 {text!r}")
    return False


def tap_text(text: str, timeout: float = 120.0) -> bool:
    deadline = time.time() + timeout
    last: list[t63probe.Item] = []
    while time.time() < deadline:
        items = t63probe.read_items()
        last = items
        hit = next((it for it in items if text in it.text), None)
        if hit is not None:
            print(f"  点 {hit.text} @({hit.cx},{hit.cy})")
            hk.adb("shell", "input", "tap", str(hit.cx), str(hit.cy))
            return True
        time.sleep(2.5)
    print(f"  !! 超时没找到 {text!r}；当时屏上 {len(last)} 项：")
    for it in last[:12]:
        print("     " + str(it))
    return False


def tap_xy(x: int, y: int, note: str = "") -> None:
    print(f"  点 ({x},{y}) {note}")
    hk.adb("shell", "input", "tap", str(x), str(y))


def blank(times: int = 1, gap: float = 6.0) -> None:
    """点画布空白 = 原版「左键补回车」（BlankTapPolicy）。

    `input keyevent 66` 在本包上推不动：输入框持有焦点时 Enter 被它消费，
    `dispatchKeyEvent` 的补沿变分支进不去。点空白是唯一可靠的推进方式。
    """
    for i in range(times):
        print(f"  空白推进 {i + 1}/{times}")
        tap_xy(900, 700)
        time.sleep(gap)


_YES_NO_LABELS = {"是", "否", "是。", "否。", "YES", "NO"}


def yes_no_pick(answer: str = "否") -> bool:
    """选项栏恰好是 是/否 二选一时点 `answer`（缺省「否」= 缄默更新）。

    立绘更新那一问（`UPDATE_MAIN.ERB:988`）的选项就是「是」「否」两项；问题文本画在
    自绘 Canvas 上，uiautomator 看不到，所以只能靠「恰好两项且文本都是 是/否」来认。
    **必须先于空白推进调用**：上轮 OOM 就是空白补的回车把二选一按默认确认、
    触发了「渲染 60 张立绘」分支（原游戏设计缺陷，选「否」= 缄默就不渲染）。
    """
    items = t63probe.read_items()
    labels = {it.text.strip() for it in items}
    if len(items) == 2 and labels <= _YES_NO_LABELS and answer in labels:
        hit = next(it for it in items if it.text.strip() == answer)
        print(f"  是/否二选一 → 点「{hit.text.strip()}」 @({hit.cx},{hit.cy})")
        hk.adb("shell", "input", "tap", str(hit.cx), str(hit.cy))
        return True
    return False


# 契约 §2.7 点名的四条路径在 TITLE 之后的定向覆盖点。按钮文案是中文语料的常见写法，
# **真机现场可能要校准**：tap_text 超时会打印「当时屏上 N 项」，按那个输出改关键词即可。
#   chara      日常主循环的「移动」指令面（CHARA_MOVEMENT）
#   aftertrain 进调教 → 立刻结束调教，走 AFTER_TRAIN 的结算面（入口文案现场摸）
#   save       系统菜单的保存/读取（SAVEDATA）
ROUTES: dict[str, list[str]] = {
    "chara": ["移动"],
    "aftertrain": ["调教"],
    "save": ["保存", "存档", "读取"],
}


def walk_route(name: str, timeout: float = 90.0) -> None:
    """把一条路径的入口按序点进去（文案现场校准用，找不到只打印候选不中断）。"""
    for kw in ROUTES[name]:
        print(f"-- route {name}: 等 {kw!r} --")
        if not tap_text(kw, timeout):
            print(f"   （{kw!r} 没出现，route {name} 未完成——按上面的屏上清单校准 ROUTES）")


def scroll_rail_to_bottom(times: int = 14) -> None:
    """把右侧选项栏滚到底。

    收束型按钮（`[9999] 设定完毕`、`［退出］`）只在列表末尾出现，而选项栏是
    Compose 的可滚动列表，一屏只挂 9 项（真机实测）。次数按「口上页 38 项」取。
    """
    for _ in range(times):
        hk.adb("shell", "input", "swipe", "2200", "950", "2200", "150", "220")
        time.sleep(0.3)


def cmd_collect(args: argparse.Namespace) -> None:
    pid = ensure_running(restart=not args.keep)
    print(f"pid={pid}，开始采样（{args.sampling}us）")
    profile_start(args.sampling)
    LAT = LOCAL_PROF_DIR / args.trace_out
    LOCAL_PROF_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    try:
        if not args.skip_title:
            # ---- TITLE：标题页 → 新游戏确认 → 模式 → 角色创建 ----
            tap_text("开始游戏", 120)
            tap_text("那当然", 180)
            tap_text("START", 240)
            blank(1, 15)
            scroll_rail_to_bottom()
            tap_text("设定完毕", 120)
            tap_text("是", 120)
            # ---- 口上剧本页：38 项的中间页，滚到底才有「退出」 ----
            # 2026-10-27 首次采集就卡在这里：漏了这一步，脚本一直在等下一秒才出现的「经典」。
            scroll_rail_to_bottom()
            tap_text("退出", 120)
            # ---- 地图 / 住处 ----
            tap_text("经典", 240)
            tap_text("博丽神社", 120)
            tap_text("决定", 120)
        else:
            print("（--skip-title：从当前画面直接开始推进）")
        # ---- 契约路径（可选）：TITLE 之外的三条按序定向走 ----
        for r in [s.strip() for s in args.route.split(",") if s.strip()]:
            if r in ROUTES:
                walk_route(r)
            else:
                print(f"!! 未知 route {r!r}（可选：{'/'.join(ROUTES)}），跳过")
        # ---- 游戏开始：一段很长的演出与初始化 ----
        for _ in range(args.advance):
            # 先看是不是 是/否 二选一：空白补回车会把它按默认确认，
            # 立绘更新那一问就会走进「渲染 60 张立绘」分支（原游戏设计缺陷）→ OOM。
            if yes_no_pick(args.yes_no):
                time.sleep(args.gap)
                continue
            blank(1, args.gap)
            if not sh(f"pidof {PKG}").strip():
                print("  !! 进程已退出，停止推进")
                break
    finally:
        elapsed = time.time() - t0
        profile_stop()
        sh(f"ls -l {DEVICE_TRACE}")
        hk.adb("pull", DEVICE_TRACE, str(LAT))
        size = LAT.stat().st_size if LAT.exists() else 0
        print(f"采集用时 {elapsed:.1f}s，落盘 {LAT}（{size} B）")


def cmd_convert(args: argparse.Namespace) -> None:
    src = LOCAL_PROF_DIR / args.trace
    out = Path(args.out)
    cmd = [
        sys.executable,
        str(Path(__file__).resolve().parent / "trace2profile.py"),
        str(src), "-o", str(out),
        "--top", str(args.top),
        "--only-prefix", args.only_prefix,
    ]
    if args.stats:
        cmd.append("--stats")
    subprocess.run(cmd, check=True)


def cmd_ab(args: argparse.Namespace) -> None:
    """AOT 开/关两轮对比。

    `cmd package compile -m verify -f` 只是把状态清成「仅校验」，等价于「没有 profile
    生效」；`-m speed-profile -f` 用随包 `baseline.prof` 编译热点。同一个 APK 只改 AOT
    状态，比打两个包干净（实测本机 vivo user build 两条都返回 Success）。
    """
    for tag, mode in (("off", ["verify"]), ("on", ["speed-profile"])):
        print(f"=== {tag}: cmd package compile -m {' '.join(mode)} -f ===")
        print(sh(f"cmd package compile -m {' '.join(mode)} -f {PKG}", timeout=600).strip())


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("collect")
    c.add_argument("--sampling", type=int, default=500, help="采样间隔（微秒），缺省 500")
    c.add_argument("--advance", type=int, default=40, help="进入游戏后最多推进几次")
    c.add_argument("--gap", type=float, default=6.0, help="每次推进后的等待秒数")
    c.add_argument("--keep", action="store_true", help="不重启 App（从当前画面接着走）")
    c.add_argument("--skip-title", action="store_true",
                   help="跳过 TITLE 固定步骤（配合 --keep：从主循环等已走到的画面直接推进）")
    c.add_argument("--trace-out", default="t63.prof")
    c.add_argument("--route", default="",
                   help="逗号分隔的契约路径（chara/aftertrain/save），在演出推进前按序走；"
                        "空 = 只走 TITLE + 演出（2026-10-27 的既有行为）")
    c.add_argument("--yes-no", default="否",
                   help="是/否二选一自动点的项（缺省「否」= 缄默更新，绕开原游戏渲染 OOM）")
    c.set_defaults(func=cmd_collect)

    v = sub.add_parser("convert")
    v.add_argument("trace")
    v.add_argument("--out", default=str(Path("app/src/main/baseline-prof.txt")))
    v.add_argument("--top", type=int, default=3000)
    v.add_argument("--only-prefix", default="com.eratw.port")
    v.add_argument("--stats", action="store_true")
    v.set_defaults(func=cmd_convert)

    a = sub.add_parser("ab")
    a.set_defaults(func=cmd_ab)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
