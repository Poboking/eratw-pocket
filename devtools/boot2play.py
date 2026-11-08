# boot2play.py —— 「开机 → 可交互」墙钟计时（回答「整体进游戏界面有没有变慢」）
#
# 为什么需要它：`loadtrace` 的 `ready` 只盖到装载链结束，之后还有一段
# 「开局脚本首轮执行 + 首帧渲染」是真机进游戏的真实大头（2026-10-26 实测 ready=17.2s、
# 可交互=31.3s ⇒ 14.1s 在黑箱里）。vivo(Origin OS) 会整条丢掉应用 logcat，
# 这段只能靠墙钟 + DevTools 通道测。
#
# 口径：
#   amStartTotal  = `am start -W` 的 TotalTime（点图标 → 装载页首帧）
#   readyMs       = loadtrace 里 `ready ... t=Nms`（装载链走完）
#   interactiveMs = 从 am start 起，到 DevTools `where` **首次给出新答复**（VM 能应答 = 开局跑到第一个等待点）
#
# 坑（已修）：dev 通道的 out.txt 是**上一条**命令的答复；轮询前必须先删 out.txt，
# 否则第一个 500ms 就会读到上一轮的旧读数，interactive 直接算成 4 秒。
import argparse, os, re, subprocess, sys, time
from pathlib import Path

PKG = "com.eratw.port.android"
DEV = f"/sdcard/Android/data/{PKG}/files/eratw/dev"


def _default_adb() -> str:
    env = os.environ.get("ERATW_ADB")
    if env:
        return env
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        cand = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if cand.is_file():
            return str(cand)
    return "adb"


ADB = _default_adb()


def adb(serial, *args, timeout=60):
    p = subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode("utf-8", "replace")


def dev_cmd(serial, cmd, wait_s=3.0):
    """发一条 DevTools 命令并只返回**这次**的答复。"""
    adb(serial, "shell", f"rm -f {DEV}/out.txt")
    adb(serial, "shell", f"echo {cmd} > {DEV}/cmd.txt")
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        out = adb(serial, "shell", f"cat {DEV}/out.txt")
        if out.strip() and "No such file" not in out:
            return out
        time.sleep(0.08)
    return ""


def one_run(serial, settle, poll_s):
    adb(serial, "shell", f"am force-stop {PKG}")
    time.sleep(settle)
    t0 = time.monotonic()
    start_out = adb(serial, "shell", f"am start -W -n {PKG}/.MainActivity")
    m = re.search(r"TotalTime:\s*(\d+)", start_out)
    total = int(m.group(1)) if m else -1

    ready_ms = -1
    interactive_ms = -1
    while time.monotonic() - t0 < poll_s:
        out = dev_cmd(serial, "loadtrace", wait_s=1.2)
        m = re.search(r"ready used=\d+MiB t=(\d+)ms", out)
        if m and ready_ms < 0:
            ready_ms = int(m.group(1))
        w = dev_cmd(serial, "where", wait_s=1.2)
        if re.search(r"等待面|anyKey|revision|末行", w):
            interactive_ms = int((time.monotonic() - t0) * 1000)
            break
        time.sleep(0.15)
    return total, ready_ms, interactive_ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--settle", type=int, default=20)
    ap.add_argument("--poll", type=int, default=180)
    ap.add_argument("--label", default="boot")
    a = ap.parse_args()
    serial = os.environ.get("ERATW_SERIAL")
    if not serial:
        sys.exit("ERATW_SERIAL 未设置（串号只走环境变量，不落盘）")
    adb(serial, "shell", "svc power stayon true")
    adb(serial, "shell", "input keyevent KEYCODE_WAKEUP")
    for r in range(1, a.runs + 1):
        total, ready, inter = one_run(serial, a.settle, a.poll)
        print(f"[{a.label}#{r}] amStartTotal={total}ms ready={ready}ms "
              f"bootToInteractive={inter}ms blackboxAfterReady={inter - ready if ready > 0 and inter > 0 else -1}ms")


if __name__ == "__main__":
    main()
