#!/usr/bin/env python3
"""鹰眼 v2（Hawkeye）—— 自研真机调试/验收工具。

主人诉求：不再让主人帮忙看画面、点按钮、确认安装。本工具把这几件事全自动化：

  部署   `deploy`：push + `pm install`（绕过 vivo 对 `adb install` 的确认弹框，实测静默）
  画面   `shot` / `yellow`（**底色带**判定，区分黄字与黄底）/ `diff`（闪烁检测）
  结构   `ui`：uiautomator a11y 树 → 文字/描述/边界/clickable/enabled
  交互   `click <文字>` / `tap x y` / `swipe` / `dev "<调试通道命令>"` / `scroll`
  断言   `audit`：视口行数、按钮可点性（点「设置」验证真进设置页）、黄底带、上滑位移
  证据   全部落 `.tmp/hawkeye/`，报告 `report.txt`

只依赖 adb（仓库自带）+ Pillow。不改 App 逻辑，复用 App 已有调试通道。

用法：
  python .tmp\\hawkeye.py deploy           # 一键部署（静默安装 + 冷启）
  python .tmp\\hawkeye.py audit            # 全量稽核，输出 PASS/FAIL
  python .tmp\\hawkeye.py yellow           # 黄底带检测（附裁图）
  python .tmp\\hawkeye.py ui               # 列 a11y 可点节点
  python .tmp\\hawkeye.py click 设置       # 按文字点击
  python .tmp\\hawkeye.py dev "submit 0"   # 走游戏调试通道
  python .tmp\\hawkeye.py scroll           # 上滑位移测量
  python .tmp\\hawkeye.py rows             # 视口行数/行高/字号
"""

from __future__ import annotations

import base64
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops

ROOT = r"D:\DSH\eratw-安卓移植计划"
ADB = os.path.join(ROOT, ".tooling", "android-sdk", "platform-tools", "adb.exe")
SERIAL = (os.environ.get("ERATW_SERIAL") or "10ACCD2B4Y004PK")  # 无线 adb 时用 ERATW_SERIAL=192.168.x.x:port 覆盖
PKG = "com.eratw.port.android"
DEV = "/sdcard/Android/data/com.eratw.port.android/files/eratw/dev"
OUT = os.path.join(ROOT, ".tmp", "hawkeye")
os.makedirs(OUT, exist_ok=True)

REPORT: list[str] = []


# ------------------------------------------------------------------ 基础
def adb(*args: str, binary: bool = False, timeout: int = 240):
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def log(text: str = "") -> None:
    print(text)
    REPORT.append(text)


def shot(name: str = "shot") -> str:
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    return path


def app_pid() -> str:
    return adb("shell", f"pidof {PKG}").strip()


def restart_app(wait_s: int = 0) -> None:
    adb("shell", f"am force-stop {PKG}")
    time.sleep(1.0)
    adb("shell", f"am start -W -n {PKG}/.MainActivity")
    if wait_s:
        time.sleep(wait_s)


# ------------------------------------------------------------------ 调试通道
def dev(cmd: str, timeout: float = 60.0) -> str:
    adb("shell", f"rm -f {DEV}/out.txt")
    b64 = base64.b64encode(cmd.encode()).decode("ascii")
    adb("shell", f"echo {b64} | base64 -d > {DEV}/cmd.txt")
    end = time.time() + timeout
    while time.time() < end:
        out = adb("shell", f"cat {DEV}/out.txt 2>/dev/null").strip()
        if out:
            return out
        time.sleep(0.25)
    return "(超时：调试通道无响应)"


# ------------------------------------------------------------------ a11y
def ui_nodes() -> list[dict]:
    adb("shell", "uiautomator dump /sdcard/hk.xml >/dev/null 2>&1")
    xml = adb("exec-out", "cat /sdcard/hk.xml")
    if not xml.strip().startswith("<"):
        return []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        text = (n.get("text") or "").strip()
        desc = (n.get("content-desc") or "").strip()
        if not text and not desc:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        l, t, r, b = (int(v) for v in m.groups())
        out.append(
            {
                "text": text,
                "desc": desc,
                "bounds": (l, t, r, b),
                "center": ((l + r) // 2, (t + b) // 2),
                "clickable": n.get("clickable") == "true",
                "enabled": n.get("enabled") == "true",
            }
        )
    return out


def ui_find(text: str):
    nodes = ui_nodes()
    for n in nodes:
        if text in n["text"] or text in n["desc"]:
            return n
    return None


# ------------------------------------------------------------------ 画面判定
def yellow_bands(path: str, min_cov: float = 0.22):
    """检测**黄色底色带**：按扫描行统计黄像素覆盖率，连续超高覆盖的行段合并成带。

    区分「黄色文字」（一行只占几个点）与「黄色底色」（一行横贯大半）—— 主人反馈的是后者。
    返回 (全屏黄像素占比%, [(y0, y1, 最大覆盖率), ...])。
    """
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    step = 2
    rows = []
    total_hits = 0
    for y in range(0, h, step):
        hits = 0
        n = 0
        for x in range(0, w, step):
            r, g, b = px[x, y]
            n += 1
            if r > 185 and g > 185 and b < 95:
                hits += 1
        total_hits += hits
        rows.append((y, hits / n if n else 0.0))
    bands = []
    cur = None
    for y, cov in rows:
        if cov >= min_cov:
            if cur is None:
                cur = [y, y, cov]
            else:
                cur[1] = y
                cur[2] = max(cur[2], cov)
        else:
            if cur is not None:
                bands.append((cur[0], cur[1], cur[2]))
                cur = None
    if cur is not None:
        bands.append((cur[0], cur[1], cur[2]))
    merged = []
    for b in bands:
        if merged and b[0] - merged[-1][1] <= 4:
            prev = merged[-1]
            merged[-1] = (prev[0], b[1], max(prev[2], b[2]))
        else:
            merged.append(b)
    pct = 100.0 * total_hits / ((h // step) * (w // step))
    return pct, merged


def colours(path: str) -> int:
    im = Image.open(path).convert("RGB")
    return len(set(im.resize((32, 14)).getdata()))


def img_diff_pct(a: str, b: str) -> float:
    ia = Image.open(a).convert("L")
    ib = Image.open(b).convert("L")
    d = ImageChops.difference(ia, ib)
    px = list(d.getdata())
    return 100.0 * sum(1 for v in px if v > 12) / len(px)


# ------------------------------------------------------------------ 子命令
def wait_running(timeout: float = 150.0) -> bool:
    """等 App 真正进入运行态（调试通道 state: isRunning=true）并出现标题页按钮。

    踩过的坑：装载要 15-30s，早测按钮只会得到「点了没反应」的假 FAIL（2026-10-24）。
    """
    end = time.time() + timeout
    while time.time() < end:
        st = dev("state", timeout=8)
        if "isRunning=true" in st:
            nodes = ui_nodes()
            names = " ".join((n["text"] or n["desc"]) for n in nodes)
            if "开始游戏" in names or "设置" in names:
                return True
        time.sleep(2)
    return False



# ---------------------------------------------------------------- 无人值守安装
# vivo 系 ROM 新装/覆盖安装会弹「外部来源应用」确认框，流程是
#   ① 勾选「已了解应用的风险检测结果」 ② 点「继续安装」
# 不点就装不完（`adb install` 会一直挂着）。下面这套看门狗把 ①② 自动做掉，
# 主人手不碰手机也能部署（2026-10-24 真机实测：735 MB 游戏包一次 Success）。
CONFIRM_WORDS = ["继续安装", "确认安装", "立即安装", "安装", "确定", "允许", "同意", "继续"]
RISK_WORDS = ["已了解", "了解应用", "风险检测", "风险", "同意", "已阅读", "协议", "勾选"]


def _dialog_nodes() -> list[dict]:
    return ui_nodes()


def _try_dismiss_dialog(nodes: list[dict]) -> bool:
    """勾选风险提示 + 点「继续安装」。返回是否动过手。"""
    acted = False
    # ① 勾选框：优先 checkable/CheckBox，其次文字里带风险/同意字样
    for n in nodes:
        if (n.get("checkable") or n.get("cls") == "CheckBox") and not n.get("checked"):
            adb("shell", f"input tap {n['center'][0]} {n['center'][1]}")
            log(f"  → 自动勾选 {n['text'] or n['desc'] or n['cls']!r}")
            acted = True
            time.sleep(0.7)
            break
    if not acted:
        for n in nodes:
            label = n["text"] or n["desc"]
            if label and any(w in label for w in RISK_WORDS):
                adb("shell", f"input tap {n['center'][0]} {n['center'][1]}")
                log(f"  → 自动勾选 {label!r}")
                acted = True
                time.sleep(0.7)
                break
    # ② 确认按钮（取最靠下的匹配项 = 主按钮）
    cands = [n for n in nodes if n.get("clickable") and any(w in (n["text"] or n["desc"]) for w in CONFIRM_WORDS)]
    if cands:
        target = max(cands, key=lambda n: n["bounds"][1])
        adb("shell", f"input tap {target['center'][0]} {target['center'][1]}")
        log(f"  → 自动点确认 {target['text'] or target['desc']!r}")
        acted = True
    return acted


def _wake_and_unlock() -> None:
    """安装前先点亮屏幕并解掉简易锁屏：息屏时 a11y 树是锁屏，勾选/点按都落空。"""
    adb("shell", "input keyevent KEYCODE_WAKEUP")
    time.sleep(0.4)
    adb("shell", "wm dismiss-keyguard")
    time.sleep(0.4)
    adb("shell", "input swipe 1200 900 1200 300 120")


def _watch_install(proc: "subprocess.Popen", timeout_s: int) -> str:
    """盯着安装子进程，每一轮都尝试处置 vivo 确认框（勾选 + 继续安装）。

    不看「弹框签名是否变化」——2026-10-25 实测：弹框刚出现的第一帧，勾选框还没进入
    clickable 状态，那一轮点不动；若按签名短路，后续轮次直接被跳过，最终
    `INSTALL_FAILED_ABORTED: User rejected permissions`。逐轮重试才是对的。
    """
    end = time.time() + timeout_s
    tries = 0
    while time.time() < end and proc.poll() is None:
        time.sleep(1.5)
        nodes = _dialog_nodes()
        if not nodes:
            continue
        tries += 1
        if _try_dismiss_dialog(nodes):
            time.sleep(1.0)
    if proc.poll() is None:
        try:
            proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            proc.kill()
    out = proc.stdout.read().decode("utf-8", "replace") if proc.stdout else ""
    if tries:
        log(f"  （看门狗尝试处置确认框 {tries} 轮）")
    return out


def install_unattended(apk: str, remote: str, timeout_s: int = 420) -> str:
    """后台跑 adb install，同时自动处置 vivo 的确认框；失败回退 push + pm install（同样带看门狗）。"""
    _wake_and_unlock()
    proc = subprocess.Popen(
        [ADB, "-s", SERIAL, "install", "-r", "-d", "-g", apk],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    out = _watch_install(proc, timeout_s)
    if "Success" not in out:
        log("adb install 未成功 → 回退 push + pm install（同样自动处置确认框）")
        adb("push", apk, remote)
        _wake_and_unlock()
        proc2 = subprocess.Popen(
            [ADB, "-s", SERIAL, "shell", f"pm install -r -d -g {remote}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        out = _watch_install(proc2, timeout_s)
        adb("shell", f"rm -f {remote}")
    return out


def c_deploy(_arg: str = "") -> None:
    apk = os.path.join(ROOT, "eratw-android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    remote = "/data/local/tmp/eratw.apk"
    stamp = time.strftime("%H:%M:%S", time.localtime(os.path.getmtime(apk)))
    log(f"部署 {os.path.basename(apk)} {os.path.getsize(apk):,} bytes（{stamp}）")
    # 无人值守安装（自动勾选「已了解应用的风险检测结果」+ 点「继续安装」）
    log("install → " + install_unattended(apk, remote).strip().replace("\n", " / "))
    log("设备安装时间 → " + adb("shell", f"dumpsys package {PKG} | grep lastUpdateTime").strip())
    adb("logcat", "-c")
    restart_app()
    log(f"冷启完成 PID={app_pid()}")


SAVE_FIXTURE_DIR = os.path.join(ROOT, "eratw-android", "fixtures", "saves")
APP_SAV_DIR = "/data/user/0/com.eratw.port.android/files/eratw/sav"


def c_pushsave(arg: str = "") -> None:
    """把 fixtures/saves 里的测试存档推进 app 内部存档目录（`run-as`），用于「载入真存档看真实界面」。

    用法：`hawkeye.py pushsave [文件名或序号]`；不给参数就推 save03.sav。
    为什么走 `run-as`：存档在 app 内部存储（`/data/user/0/<pkg>/files/eratw/sav`），
    adb 直接 push 没有写权限；debug 包可 `run-as` 以 app uid 写。
    """
    name = (arg or "save03.sav").strip()
    if not name.endswith(".sav"):
        name = f"save0{name}.sav" if len(name) == 1 else f"{name}.sav"
    src = os.path.join(SAVE_FIXTURE_DIR, name)
    if not os.path.exists(src):
        log(f"找不到存档：{src}")
        return
    remote_tmp = f"/data/local/tmp/{name}"
    adb("push", src, remote_tmp)
    adb("shell", f"run-as com.eratw.port.android mkdir -p files/eratw/sav")
    out = adb("shell", f"run-as com.eratw.port.android cp {remote_tmp} files/eratw/sav/{name}")
    adb("shell", f"rm -f {remote_tmp}")
    listing = adb("shell", "run-as com.eratw.port.android ls -l files/eratw/sav")
    log(f"已推入存档 {name}（{os.path.getsize(src):,} bytes）")
    if listing.strip():
        log("设备存档目录：\n" + listing.strip())
    else:
        log("设备存档目录读不到（run-as 失败？）" + (f"：{out}" if out else ""))


def c_shot(_arg: str = "") -> None:
    log(f"截图 → {shot('shot')}")


def c_yellow(_arg: str = "") -> None:
    p = shot("yellow")
    pct, bands = yellow_bands(p)
    log(f"黄色像素 {pct:.4f}%；底色带 {len(bands)} 段")
    for y0, y1, cov in bands:
        log(f"  带 y={y0}..{y1} 高 {y1 - y0 + 2}px 覆盖 {cov * 100:.0f}%")
    if bands:
        im = Image.open(p)
        y0 = max(0, bands[0][0] - 20)
        y1 = min(im.height, bands[-1][1] + 20)
        cp = os.path.join(OUT, "yellow-bands.png")
        im.crop((0, y0, im.width, y1)).save(cp)
        log(f"  裁图 → {cp}")
    else:
        log("  PASS 无黄色底色带（黄字不算）")


def c_ui(_arg: str = "") -> None:
    nodes = ui_nodes()
    log(f"a11y 节点 {len(nodes)} 个：")
    for i, n in enumerate(nodes):
        tag = n["text"] or f"[desc]{n['desc']}"
        log(f"  #{i:<2} {tag!r:<26} bounds={n['bounds']} clickable={n['clickable']} enabled={n['enabled']}")


def c_click(text: str = "") -> None:
    n = ui_find(text)
    if not n:
        log(f"FAIL 找不到 {text!r}（先跑 ui）")
        return
    x, y = n["center"]
    adb("shell", f"input tap {x} {y}")
    log(f"OK 点击 {text!r} @({x},{y}) bounds={n['bounds']}")


def c_tap(arg: str = "") -> None:
    x, y = (int(float(v)) for v in arg.replace(",", " ").split()[:2])
    adb("shell", f"input tap {x} {y}")
    log(f"OK tap({x},{y})")


def c_swipe(arg: str = "") -> None:
    v = [int(float(x)) for x in arg.replace(",", " ").split()]
    ms = v[4] if len(v) > 4 else 350
    adb("shell", f"input swipe {v[0]} {v[1]} {v[2]} {v[3]} {ms}")
    log(f"OK swipe {v[:4]} {ms}ms")


def c_dev(arg: str = "") -> None:
    log(f"dev[{arg}] → {dev(arg)[:400]}")


def c_rows(_arg: str = "") -> None:
    lines = [l for l in adb("logcat", "-d", "-s", "EratwView").splitlines() if "rebuildScreen" in l]
    if not lines:
        log("WARN 无 EratwView 读数（需冷启一次触发 onSizeChanged）")
        return
    log("rebuildScreen（最近 3 条）：")
    for l in lines[-3:]:
        log("  " + l.split("rebuildScreen", 1)[-1].strip())


def c_gest(_arg: str = "") -> None:
    lines = [l for l in adb("logcat", "-d", "-s", "EratwGest").splitlines() if "EratwGest" in l]
    log(f"手势日志 {len(lines)} 条（最近 8）：")
    for l in lines[-8:]:
        log("  " + l.split("EratwGest", 1)[-1].strip())


def c_scroll(_arg: str = "") -> None:
    a = shot("scroll-before")
    for _ in range(2):
        adb("shell", "input swipe 1200 250 1200 800 350")
        time.sleep(0.4)
    time.sleep(0.3)
    b = shot("scroll-after")
    log(f"上滑位移 {img_diff_pct(a, b):.3f}%（{a} / {b}）")


def c_probe(_arg: str = "") -> None:
    """逐个真实 UI 按钮「点一下 → 断言画面有变化 → 复位」。

    复位规则（踩过的坑）：**不能用 BACK 复位**——输入栏没开时 BACK 会直接退出 Activity，
    后续按钮就全都「找不到」了（2026-10-24 实测）。改为：
      * 输入栏类（键盘/抓手）：点控制台空白处收起；
      * 开关类（选项栏）：再点一次切回；
      * 设置：放最后测，测完 BACK 回游戏。
    """
    log("=" * 74)
    log(f"按钮实测 {time.strftime('%H:%M:%S')}")
    log("=" * 74)
    adb("shell", f"am start -W -n {PKG}/.MainActivity")  # 保证在前台
    if not wait_running():
        log("FAIL  等不到运行态（装载失败或还没好）")
        return
    time.sleep(0.8)

    targets = [("选项栏", 0.9, "toggle"), ("键盘", 1.8, "tap-elsewhere"), ("底边抓手", 1.5, "tap-elsewhere"), ("设置", 2.5, "back")]
    ok = 0
    for name, settle, restore in targets:
        node = ui_find(name)
        if not node:
            log(f"FAIL  {name}: a11y 找不到（语义标签缺失）")
            continue
        x, y = node["center"]
        before = shot("probe-" + name + "-before")
        adb("shell", f"input tap {x} {y}")
        time.sleep(settle)
        after = shot("probe-" + name + "-after")
        diff = img_diff_pct(before, after)
        passed = diff > 0.3
        log(("PASS  " if passed else "FAIL  ") + f"{name} @({x},{y}) 画面变化 {diff:.2f}%")
        ok += 1 if passed else 0
        # 复位
        if restore == "toggle":
            adb("shell", f"input tap {x} {y}")
            time.sleep(0.8)
        elif restore == "tap-elsewhere":
            adb("shell", "input tap 700 900")   # 控制台空白：收起输入栏
            time.sleep(0.8)
        else:
            adb("shell", "input keyevent KEYCODE_BACK")
            time.sleep(1.2)
    log("-" * 74)
    log(f"按钮实测 {ok}/{len(targets)} 生效")


def c_diff(_arg: str = "") -> None:
    a = shot("diff-a")
    time.sleep(1.0)
    b = shot("diff-b")
    log(f"1 秒内画面差异 {img_diff_pct(a, b):.3f}%（静止页应≈0 = 无闪烁）")


# ------------------------------------------------------------------ 稽核
def c_audit(_arg: str = "") -> None:
    log("=" * 74)
    log(f"鹰眼稽核 {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log("=" * 74)
    verdict = []

    adb("logcat", "-c")
    restart_app()
    running = wait_running()
    verdict.append(("App 冷启并进入运行态", running, dev("state", timeout=8)[:90]))
    time.sleep(1.0)

    c_rows()
    joined = "\n".join(REPORT[-4:])
    # EratwView 的 rebuildScreen 行格式（2026-10-24 实测）：rows=45 cols=202 lineH=24.0 font=22.0 contentH=1080
    # 旧正则找的是 rowsPerPage=，那条字段早已不在日志里 ⇒ 稽核恒报「无读数」。
    m = re.search(r"rows=(\d+).*?lineH=([\d.]+).*?font=([\d.]+).*?contentH=(\d+)", joined)
    if m:
        rows, lh, font, ch = m.groups()
        fit = round(int(ch) / float(lh))
        verdict.append(("视口行数 == 屏幕能放下的行数", abs(int(rows) - fit) <= 1, f"rows={rows} fit={fit} lineH={lh} font={font} contentH={ch}"))
    else:
        verdict.append(("视口行数读数", False, "无读数（logcat 里没有 EratwView rebuildScreen 行）"))

    # 按钮可点性：点「设置」→ 设置页应出现（有「保存」）
    before = {n["text"] or n["desc"] for n in ui_nodes()}
    n = ui_find("设置")
    if not n:
        verdict.append(("a11y 能找到「设置」按钮", False, f"节点 {len(before)} 个"))
    else:
        x, y = n["center"]
        adb("shell", f"input tap {x} {y}")
        time.sleep(2.5)
        after = {n2["text"] or n2["desc"] for n2 in ui_nodes()}
        entered = any("保存" in t or "重置" in t for t in after)
        verdict.append(("点「设置」真能进设置页", entered, f"节点 {len(before)}→{len(after)}"))
        if entered:
            adb("shell", "input keyevent KEYCODE_BACK")
            time.sleep(1.5)

    p = shot("audit-yellow")
    pct, bands = yellow_bands(p)
    verdict.append(("无黄色底色带", len(bands) == 0, f"黄像素 {pct:.4f}% 带 {len(bands)}"))

    for _ in range(3):
        dev("submit 1", timeout=20)
        time.sleep(1.2)
    a = shot("audit-scroll-a")
    for _ in range(3):
        adb("shell", "input swipe 1200 250 1200 800 320")
        time.sleep(0.35)
    time.sleep(0.4)
    b = shot("audit-scroll-b")
    diff = img_diff_pct(a, b)
    verdict.append(("上滑能翻到更早的行", diff > 1.0, f"位移 {diff:.3f}%"))

    log("-" * 74)
    ok = 0
    for name, passed, extra in verdict:
        log(f"{'PASS' if passed else 'FAIL'}  {name}" + (f"  —— {extra}" if extra else ""))
        ok += 1 if passed else 0
    log("-" * 74)
    log(f"合计 {ok}/{len(verdict)}；证据在 {OUT}")
    rp = os.path.join(OUT, "report.txt")
    with open(rp, "w", encoding="utf-8") as f:
        f.write("\n".join(REPORT))
    log(f"报告 → {rp}")


# ------------------------------------------------------------------ DBG2 全量函数清扫（sweep）
# 装置面：app/src/androidTest/kotlin/com/eratw/port/android/dbg/（DBG2 波，台账 schema 以
# 任务书 §3 为准）。本命令 = 组参 → am instrument → 轮询台账行数 → pull → 状态计数摘要。
SWEEP_CLASS = "com.eratw.port.android.dbg.DeviceSweepTest"


def _pin_keepalive() -> None:
    """拉起 DBG 钉进程活动（防 Cached App Freezer 冻死 instrumentation 装载，2026-10-25 实锤）。

    am instrument 的目标进程无前台组件会被 vivo freezer 归入 /dev/freezer/frozen——
    装载 erbc 的 mmap 缺页永久 D 态。shell 特权 am start 一个全透明空 Activity 把进程顶到
    TOP 即解冻免疫；instrumentation 若重启进程，本函数在轮询循环里反复补拉。
    """
    adb("shell", "am start -n com.eratw.port.android/.dbg.DevKeepAliveActivity >/dev/null 2>&1")

SWEEP_REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files/eratw/dbg"
SWEEP_TIMEOUT_S = 30 * 60  # adb 侧总超时：设备端挂死时 kill，保留半份台账
SWEEP_POLL_S = 15


def _sweep_runner() -> str:
    """读 app/build.gradle.kts 的 testInstrumentationRunner 现值（不硬编码，防 runner 换名后串包）。

    优先读本脚本自己所在的检出（ROOT 是共享根、可能滞后于被测分支）；都不在再回落 ROOT。
    """
    here = os.path.dirname(os.path.abspath(__file__))          # <检出>/eratw-android/devtools
    candidates = [
        os.path.join(here, "..", "app", "build.gradle.kts"),
        os.path.join(ROOT, "eratw-android", "app", "build.gradle.kts"),
    ]
    gradle = next((p for p in candidates if os.path.isfile(p)), None)
    if gradle is None:
        raise SystemExit(f"FAIL 找不到 app/build.gradle.kts（试过 {candidates}）")
    with open(gradle, encoding="utf-8") as f:
        m = re.search(r'testInstrumentationRunner\s*=\s*"([^"]+)"', f.read())
    if not m:
        raise SystemExit(f"FAIL 在 {gradle} 找不到 testInstrumentationRunner")
    return m.group(1)


def _sweep_out_name(shard: int, nshard: int) -> str:
    """设备侧 DeviceSweepTest.defaultOutPath 的镜像（多 shard 分文件，并发写同一文件必坏）。"""
    return "report.jsonl" if nshard <= 1 else f"report.s{shard}of{nshard}.jsonl"


def _sweep_line_count(remote: str) -> int:
    out = adb("shell", f"wc -l < '{remote}' 2>/dev/null").strip()
    try:
        return int(out.split()[0])
    except (IndexError, ValueError):
        return -1  # 文件还没出现 / 权限 / 中途截断


def c_sweep(arg: str = "") -> None:
    """真机全量函数清扫（DBG2）：am instrument 跑 DeviceSweepTest，拉回台账并给摘要。

    用法：
      python hawkeye.py sweep [--shard i n] [--steps N] [--only re]
                              [--pull-dir .tmp/hawkeye/sweep] [--commit h]
                              [--erbc p] [--game p] [--out p] [--selftest]

    am instrument 可能被设备端挂死卡住：总超时 30min，到点 kill 本地 adb 并
    `am force-stop` 停掉设备端 instrumentation，已写出的台账（半份）照常 pull 回来 ——
    台账末行 status=RUNNING 的 fn 即肇事者（装置逐函数先写占位行再覆写终态）。
    """
    toks = arg.split()
    two_val = {"--shard"}
    one_val = ("--steps", "--only", "--pull-dir", "--commit", "--erbc", "--game", "--out")
    vals: dict[str, object] = {}
    flags: set[str] = set()
    i = 0
    while i < len(toks):
        t = toks[i]
        if t == "--selftest":
            flags.add(t)
            i += 1
        elif t in two_val:
            if i + 2 >= len(toks):
                raise SystemExit(f"FAIL {t} 需要 i n 两个值")
            vals[t] = (toks[i + 1], toks[i + 2])
            i += 3
        elif t in one_val:
            if i + 1 >= len(toks):
                raise SystemExit(f"FAIL {t} 缺值")
            vals[t] = toks[i + 1]
            i += 2
        else:
            raise SystemExit(f"FAIL 未知 sweep 参数 {t!r}")

    shard, nshard = (0, 1)
    if "--shard" in vals:
        si, sn = vals["--shard"]
        shard, nshard = int(si), int(sn)
        if not (0 <= shard < max(nshard, 1)):
            raise SystemExit(f"FAIL shard {shard} 越界（须 0 <= i < n，n={nshard}）")

    es = ["-e", "class", SWEEP_CLASS]
    if "--selftest" in flags:
        es += ["-e", "selftest", "1"]
    for key, name in (("--erbc", "erbc"), ("--game", "game"), ("--out", "out"),
                      ("--steps", "steps"), ("--only", "only"), ("--commit", "commit")):
        if key in vals:
            es += ["-e", name, str(vals[key])]
    if "--shard" in vals:
        es += ["-e", "shard", str(shard), "-e", "nshard", str(nshard)]

    runner = _sweep_runner()
    _pin_keepalive()
    remote = "am instrument -w " + " ".join(shlex.quote(x) for x in es) + f" {PKG}.test/{runner}"
    remote_out = str(vals["--out"]) if "--out" in vals else f"{SWEEP_REMOTE_DIR}/{_sweep_out_name(shard, nshard)}"
    pull_dir = str(vals.get("--pull-dir") or os.path.join(OUT, "sweep"))

    log("=" * 74)
    log(f"DBG2 sweep shard={shard}/{nshard} → {remote_out}")
    log(f"  {remote}")
    log("=" * 74)

    proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", remote],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    deadline = time.time() + SWEEP_TIMEOUT_S
    last_count, stable_at = -1, time.time()
    timed_out = False
    while proc.poll() is None:
        if time.time() > deadline:
            timed_out = True
            log(f"TIMEOUT {SWEEP_TIMEOUT_S // 60}min：kill 本地 adb + 设备端 force-stop（保留半份台账）")
            proc.kill()
            adb("shell", f"am force-stop {PKG}")
            break
        time.sleep(SWEEP_POLL_S)
        _pin_keepalive()
        count = _sweep_line_count(remote_out)
        if count != last_count:
            log(f"  台账 {count} 行 @ {time.strftime('%H:%M:%S')}")
            last_count = count
            stable_at = time.time()
    am_out = proc.stdout.read().decode("utf-8", "replace") if proc.stdout else ""
    proc.wait(timeout=60)
    for tail in [l for l in am_out.strip().splitlines() if l.strip()][-4:]:
        log("  am| " + tail)
    if time.time() - stable_at > SWEEP_POLL_S * 4 and not timed_out:
        log(f"  （台账自 {time.strftime('%H:%M:%S', time.localtime(stable_at))} 起行数未变）")

    stamp = time.strftime("%Y%m%d-%H%M%S")
    sub = stamp if nshard <= 1 else f"{stamp}-s{shard}of{nshard}"
    local_dir = os.path.join(pull_dir, sub)
    os.makedirs(local_dir, exist_ok=True)
    pull_targets = [remote_out]
    if remote_out.endswith(".jsonl"):
        pull_targets.append(remote_out[: -len(".jsonl")] + ".selftest.txt")
    for remote_path in pull_targets:
        r = adb("shell", f"test -f '{remote_path}' && echo yes").strip()
        if r == "yes":
            adb("pull", remote_path, local_dir)
            log(f"pull → {os.path.join(local_dir, os.path.basename(remote_path))}")

    # 摘要：按 status 计数（regex 解析，不引 json 依赖）；末行 RUNNING = 挂死肇事者。
    local_file = os.path.join(local_dir, os.path.basename(remote_out))
    if not os.path.isfile(local_file):
        log(f"FAIL 台账没拉回来（{local_file}）——设备端没写出文件？先跑 --selftest 自证装置")
        return
    counts: dict[str, int] = {}
    total = 0
    culprit = None
    meta_line = None
    with open(local_file, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if '"meta":"device-sweep"' in line:
                meta_line = line
                continue
            m = re.search(r'"status":"([A-Z_]+)"', line)
            fn = re.search(r'"fn":"((?:[^"\\]|\\.)*)"', line)
            if not m:
                counts["<parse-error>"] = counts.get("<parse-error>", 0) + 1
                continue
            st = m.group(1)
            counts[st] = counts.get(st, 0) + 1
            total += 1
            if st == "RUNNING":
                culprit = fn.group(1) if fn else "?"
    log("-" * 74)
    if meta_line:
        log(f"meta: {meta_line}")
    log(f"台账 {total} 行 @ {local_file}" + ("（半份：am instrument 被超时击杀）" if timed_out else ""))
    for st in sorted(counts, key=lambda s: counts[s], reverse=True):
        log(f"  {st:<12} {counts[st]}")
    if culprit is not None:
        log(f"** 末行 RUNNING（挂死肇事者）：fn={culprit}")
    elif counts.get("RUNNING"):
        log("** 台账含 RUNNING 行但不在末行 —— 多 shard 并发写同一文件？检查 --out 分文件")
    log(f"汇总完成 @ {time.strftime('%H:%M:%S')}")


# ------------------------------------------------------------------ DBG3 剧本推进（play）
# 装置面：app/src/androidTest/.../dbg/PlayChainTest.kt（剧本格式 = 一行一步
# `提交文本 ;; 期望子串`，特殊 token：$any=空提交放行、$skip=mesSkip 提交）。
PLAY_CLASS = "com.eratw.port.android.dbg.PlayChainTest"
PLAY_REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files/eratw/dbg"


def c_play(arg: str = "") -> None:
    """真机剧本推进（DBG3）：push 剧本 → am instrument 跑 PlayChainTest → 拉回台账并摘要。

    用法：
      python hawkeye.py play <剧本文件> [--steps N] [--seconds S] [--waitms W]
                              [--pull-dir .tmp/hawkeye/play] [--commit h] [--selftest]
    """
    toks = arg.split()
    if not toks or toks[0].startswith("--"):
        raise SystemExit("FAIL 用法: play <剧本文件> [--steps N] [--seconds S] [--waitms W] [--commit h] [--selftest]")
    script = toks[0]
    one_val = ("--steps", "--seconds", "--waitms", "--pull-dir", "--commit")
    vals: dict[str, str] = {}
    flags: set[str] = set()
    i = 1
    while i < len(toks):
        t = toks[i]
        if t == "--selftest":
            flags.add(t)
            i += 1
        elif t in one_val:
            if i + 1 >= len(toks):
                raise SystemExit(f"FAIL {t} 缺值")
            vals[t] = toks[i + 1]
            i += 2
        else:
            raise SystemExit(f"FAIL 未知 play 参数 {t!r}")
    if not os.path.isfile(script):
        raise SystemExit(f"FAIL 剧本不存在: {script}")

    remote_script = f"{PLAY_REMOTE_DIR}/script.txt"
    remote_out = f"{PLAY_REMOTE_DIR}/play.jsonl"
    adb("shell", f"mkdir -p {PLAY_REMOTE_DIR}")
    adb("push", os.path.abspath(script), remote_script)

    es = ["-e", "class", PLAY_CLASS, "-e", "script", remote_script, "-e", "out", remote_out]
    if "--selftest" in flags:
        es += ["-e", "selftest", "1"]
    for key, name in (("--steps", "steps"), ("--seconds", "seconds"), ("--waitms", "waitms"), ("--commit", "commit")):
        if key in vals:
            es += ["-e", name, vals[key]]
    runner = _sweep_runner()
    _pin_keepalive()
    remote = "am instrument -w " + " ".join(shlex.quote(x) for x in es) + f" {PKG}.test/{runner}"

    budget_s = int(vals.get("--seconds") or 600)
    timeout_s = budget_s + 300  # 装置自带预算；adb 侧给 5 分钟余量

    log("=" * 74)
    log(f"DBG3 play script={script}（预算 {budget_s}s）")
    log(f"  {remote}")
    log("=" * 74)
    proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", remote],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    deadline = time.time() + timeout_s
    timed_out = False
    while proc.poll() is None:
        if time.time() > deadline:
            timed_out = True
            log(f"TIMEOUT {timeout_s}s：kill 本地 adb + 设备端 force-stop（保留半份台账）")
            proc.kill()
            adb("shell", f"am force-stop {PKG}")
            break
        time.sleep(5)
        _pin_keepalive()
    am_out = proc.stdout.read().decode("utf-8", "replace") if proc.stdout else ""
    proc.wait(timeout=60)
    for tail in [l for l in am_out.strip().splitlines() if l.strip()][-4:]:
        log("  am| " + tail)

    stamp = time.strftime("%Y%m%d-%H%M%S")
    local_dir = os.path.join(vals.get("--pull-dir") or os.path.join(OUT, "play"), stamp)
    os.makedirs(local_dir, exist_ok=True)
    for remote_path in [remote_out, remote_script]:
        remote_path2 = remote_out[: -len(".jsonl")] + ".selftest.txt" if remote_path.endswith(".jsonl") else remote_path
        if adb("shell", f"test -f '{remote_path2}' && echo yes").strip() == "yes":
            adb("pull", remote_path2, local_dir)
            log(f"pull → {os.path.join(local_dir, os.path.basename(remote_path2))}")

    local_file = os.path.join(local_dir, os.path.basename(remote_out))
    if not os.path.isfile(local_file):
        log(f"FAIL 台账没拉回来（{local_file}）——先跑 --selftest 自证装置")
        return
    counts: dict[str, int] = {}
    with open(local_file, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.search(r'"status":"([A-Z_]+)"', line)
            if m:
                counts[m.group(1)] = counts.get(m.group(1), 0) + 1
    log("-" * 74)
    log(f"台账 → {local_file}" + ("（半份：被超时击杀）" if timed_out else ""))
    for st in sorted(counts, key=lambda s: counts[s], reverse=True):
        log(f"  {st:<12} {counts[st]}")
    log(f"play 汇总完成 @ {time.strftime('%H:%M:%S')}")


COMMANDS = {
    "deploy": c_deploy,
    "shot": c_shot,
    "yellow": c_yellow,
    "ui": c_ui,
    "click": c_click,
    "tap": c_tap,
    "swipe": c_swipe,
    "dev": c_dev,
    "rows": c_rows,
    "gest": c_gest,
    "scroll": c_scroll,
    "diff": c_diff,
    "probe": c_probe,
    "pushsave": c_pushsave,
    "audit": c_audit,
    "sweep": c_sweep,
    "play": c_play,
}


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        return
    COMMANDS[sys.argv[1]](" ".join(sys.argv[2:]))


if __name__ == "__main__":
    main()

