"""T2-63 真机采集的探测/驱动小工具。

只做「看」和「点」两类原子动作，全部走 hawkeye 的现成封装
（PowerShell 会破坏二进制/编码，所以一律在 Python 里落地）。

子命令：
  shot <name>             截图到 hawkeye 的 OUT 目录
  log [n]                 抓最近 n 行关心的 logcat
  ui                      打印当前可点选项（序号 / 文字 / 中心坐标 / bounds）
  pick <num|文字>          点匹配的选项（先 ui 再点，找不到就报错退出）
  blank [x y]             点画布空白（缺省 1200,760），走 BlankTapPolicy 补回车
  key <code>              input keyevent
  tap <x> <y>             裸点
  wait <sec>              睡一段时间

坐标口径：`dumpsys window displays` 的第二条 `cur=2400x1080` 才是应用窗口尺寸，
`wm size` 报的 1080x2400 是竖屏物理尺寸，**不能用**。按钮坐标一律取自 uiautomator 的 bounds。
"""

from __future__ import annotations

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hawkeye as hk  # noqa: E402

DIAG = Path(r"D:\DSH\t63-diag")
DIAG.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class Item:
    idx: int          # 屏幕内顺序（1 起），用于 `pick #3`
    num: str          # 按钮编号；纯按钮（如 `└ 初始剧本`、`[退出]`）为空串
    text: str
    cx: int
    cy: int
    bounds: str

    def __str__(self) -> str:
        tag = f"[{self.num}] " if self.num else "#" + str(self.idx) + " "
        return f"{tag}{self.text}  @({self.cx},{self.cy})  {self.bounds}"


def _bounds_center(b: str) -> tuple[int, int]:
    m = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b.strip())
    if not m:
        raise ValueError(f"看不懂的 bounds: {b}")
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


# 选项栏里有两类节点形态（真机实测，2026-10-27）：
#   (a) `162 □ 能力 □`  —— 数字与文字拆成两个 TextView，数字是纯数字；
#   (b) `[9999] 设定完毕` —— 整体一个 TextView，数字带方括号。
# 早先只认 (a)，导致 `[2000]`/`[9999]` 这类「收束型」按钮全军覆没，滚到底也 pick 不到。
_NUM_RE = re.compile(r"^\s*\[?\s*(\d+)\s*\]?\s*")

# 壳层固定控件（键盘开关 / 选项栏开关 / 设置 / 底边抓手）不是游戏选项，一律不碰。
_SKIP_DESC = ("键盘", "选项栏", "底边抓手")


def read_items() -> list[Item]:
    """读当前屏幕的可点选项（壳层选项栏，按 Compose 语义树）。

    `uiautomator dump` 偶发返回空文件/半截 XML（服务侧竞态，2026-10-27 夜间推进
    实测半小时撞到一次）——这里容错返回空表，让上层轮询逻辑自然重试，而不是崩掉。
    """
    try:
        hk.adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = hk.adb("shell", "cat", "/sdcard/ui.xml", binary=True)
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", "replace")
        root = ET.fromstring(raw)
    except ET.ParseError:
        return []
    out: list[Item] = []
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        desc = (node.get("desc") or "").strip()
        if any(k in desc for k in _SKIP_DESC):
            continue
        kids = list(node.iter("node"))
        texts = [(k.get("text") or "").strip() for k in kids if (k.get("text") or "").strip()]
        if not texts:
            continue
        joined = " ".join(texts)
        if joined == "设置":
            continue
        num_node = next((t for t in texts if t.isdigit()), None)
        if num_node is not None:
            num = num_node
            label = " ".join(t for t in texts if t != num_node)
        else:
            m = _NUM_RE.match(joined)
            num = m.group(1) if m else ""
            label = joined[m.end():].strip() if m else joined
        if not label:
            continue
        cx, cy = _bounds_center(node.get("bounds") or "")
        out.append(Item(len(out) + 1, num, label, cx, cy, node.get("bounds") or ""))
    # 同一节点可能既是 clickable 又被子节点重复计入 —— 按 bounds 去重，保留先到的
    seen: set[str] = set()
    uniq: list[Item] = []
    for it in out:
        if it.bounds in seen:
            continue
        seen.add(it.bounds)
        uniq.append(Item(len(uniq) + 1, it.num, it.text, it.cx, it.cy, it.bounds))
    return uniq


def cmd_ui() -> None:
    items = read_items()
    if not items:
        print("(当前屏幕没有可点选项)")
    for it in items:
        print(it)


def cmd_pick(want: str) -> None:
    items = read_items()
    if not items:
        raise SystemExit("当前屏幕没有可点选项，pick 失败")
    hit = None
    if want.startswith("#") and want[1:].isdigit():
        hit = next((i for i in items if i.idx == int(want[1:])), None)
    elif want.isdigit():
        hit = next((i for i in items if i.num == want), None)
    if hit is None:
        hit = next((i for i in items if want in i.text), None)
    if hit is None:
        print("候选：")
        for it in items:
            print("  " + str(it))
        raise SystemExit(f"没找到匹配 {want!r} 的选项")
    print(f"pick {hit}")
    hk.adb("shell", "input", "tap", str(hit.cx), str(hit.cy))


def cmd_shot(name: str) -> None:
    print("shot ->", hk.shot(name if name.endswith(".png") else name + ".png"))


def cmd_log(n: int) -> None:
    r = subprocess.run([hk.ADB, "-s", hk.SERIAL, "logcat", "-d", "-t", str(n), "-v", "time"],
                       capture_output=True)
    text = r.stdout.decode("utf-8", "replace")
    keep = [ln for ln in text.splitlines()
            if any(k in ln for k in ("EratwGest", "eratw", "AndroidRuntime", "FATAL"))]
    print("\n".join(keep[-n:]))
    print(f"--- {len(keep)} / {len(text.splitlines())} 行命中 ---")


def cmd_auto(n: int, wait_s: float) -> None:
    """连续「点空白 → 等 → 探测」，一旦出现可点选项就停下。

    只用于摸路径：真机实测 `input keyevent 66` 在本包上不推进（输入框抢焦点），
    而点画布空白会走 `BlankTapPolicy` 的补回车分支，是唯一可靠的推进方式。
    """
    for i in range(1, n + 1):
        hk.adb("shell", "input", "tap", "900", "700")
        time.sleep(wait_s)
        items = read_items()
        print(f"--- 第 {i} 轮：{len(items)} 项可点 ---")
        if items:
            for it in items:
                print("  " + str(it))
            return
    print(f"连续 {n} 轮后仍无可点选项")


def cmd_grind(n: int, wait_s: float, stop: str) -> None:
    """「有选项就选、没选项就点空白」的粗略推进器，只用于把流程走通。

    `stop` 命中任一选项文本即停（例如 `stop=移动` 走到移动指令那一步）。
    """
    for i in range(1, n + 1):
        items = read_items()
        if items:
            if stop:
                hit = next((it for it in items if stop in it.text), None)
                if hit is not None:
                    print(f"--- 第 {i} 轮命中 stop={stop!r}，停下 ---")
                    for it in items:
                        print("  " + str(it))
                    return
            it = items[0]
            print(f"--- 第 {i} 轮 pick {it} ---")
            hk.adb("shell", "input", "tap", str(it.cx), str(it.cy))
        else:
            print(f"--- 第 {i} 轮 blank ---")
            hk.adb("shell", "input", "tap", "900", "700")
        time.sleep(wait_s)
    print(f"跑满 {n} 轮")


def main() -> None:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "ui"
    if cmd == "ui":
        cmd_ui()
    elif cmd == "grind":
        cmd_grind(int(sys.argv[2]) if len(sys.argv) > 2 else 20,
                  float(sys.argv[3]) if len(sys.argv) > 3 else 6.0,
                  sys.argv[4] if len(sys.argv) > 4 else "")
    elif cmd == "auto":
        cmd_auto(int(sys.argv[2]) if len(sys.argv) > 2 else 10,
                 float(sys.argv[3]) if len(sys.argv) > 3 else 6.0)
    elif cmd == "pick":
        cmd_pick(sys.argv[2])
    elif cmd == "shot":
        cmd_shot(sys.argv[2] if len(sys.argv) > 2 else "diag")
    elif cmd == "log":
        cmd_log(int(sys.argv[2]) if len(sys.argv) > 2 else 80)
    elif cmd == "blank":
        x = int(sys.argv[2]) if len(sys.argv) > 2 else 1200
        y = int(sys.argv[3]) if len(sys.argv) > 3 else 760
        print(f"blank {x},{y}")
        hk.adb("shell", "input", "tap", str(x), str(y))
    elif cmd == "key":
        print("key", sys.argv[2])
        hk.adb("shell", "input", "keyevent", sys.argv[2])
    elif cmd == "tap":
        print("tap", sys.argv[2], sys.argv[3])
        hk.adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
    elif cmd == "wait":
        time.sleep(float(sys.argv[2]))
        print("waited", sys.argv[2])
    else:
        raise SystemExit(f"未知子命令 {cmd}")


if __name__ == "__main__":
    main()
