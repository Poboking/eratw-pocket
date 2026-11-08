#!/usr/bin/env python3
"""慢页**画像与对照**（只读收件箱；obs-slowpageprofile 车道）。

上一路 `slowpage_stats.py` 已把发生率实测出来，并留下一个没答完的问题：
慢页不止出现在「Java 堆贴顶」层（70-90% 层也有 4 页），且两组慢页的「堆之外」
画像不同（贴顶组 native≈89 MiB / 70-90% 组 native≈179-224 MiB）。同一台机整局
native 基本恒定 ⇒ 「native 高」与「这台机慢」在数据里是缠在一起的。

本脚本**只做画像与对照**，产四节：
  1. 11 个慢页的逐页画像（机型/版本/构建/耗时/水位/native/轨迹位置/前后行 phase）
  2. native 分层，与已有水位层**并列摆**
  3. 机型内（=同一份会话内）对照：native 是否恒定 ⇒ 可分/不可分的判定
  4. 系统内存列（sys=/lmk=）在慢页与非慢页的分布对照

口径纪律（逐字复用，不另起一套）：
  - 读取 / 去重 / 旧版剔除 / 水位分桶 / turn 页识别**全部 import `slowpage_stats`
    与 `triage_reports`**，本脚本不重写任何正则；启动即自证三份基准数
    （15 份带轨迹 / 264 页 / 11 慢页）+ 水位三层页数，对不上就非零退出。
  - native 分桶边界：`>=180` / `120-179` / `<120` MiB，**含下界**
    （NATIVE_HI=180、NATIVE_MID=120）。选 180 是因为贴顶组常驻 88-94、
    70-90% 组常驻 179-224，180 大致落在两组之间；120 留一档「中间态」。
    这两刀**是本脚本自己的口径**，不属于 slowpage_stats 的既有口径，
    所以只作描述用，不当既有条款引用。
  - 分母只有 11 页 / 15 份会话。任何分层结论都带「几个慢页、来自几份会话」的原话；
    单份 OPPO 会话贡献 11 个慢页里的多数，**不许写成机型常数或机制定论**。
  - 与基准脚本重复的水位分层与 p̂ 只做**一致性自证**，不重算成新结论。

用法：
    python devtools/slowpage_profile.py [--dir <inbox>] [--out <dir>] [--slow-ms 5000] [--allow-drift]
数表落 `<out>/profile*.txt|csv`；stdout 只打路径 + 摘要（GBK 控制台不炸）。

`--allow-drift`：基准快照（15 份/264 页/11 慢页/水位 7-4-0）是 2026-11-01 那份 98 文件收件箱的读数，
原文不入 git；换了一批收件箱（现盘 92 份 ⇒ 13 份/271 页）时本脚本会按纪律非零退出。加这个旗标
则改为**打一行「口径漂移」并把数表照跑照出**，`profile_slowpage.txt` 首行会写明漂移 —— 只许作
「现盘口径」的画像，**不许引用基准数**。
"""

from __future__ import annotations

import argparse
import os
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import slowpage_stats as sps  # noqa: E402  口径复用：不改它，只 import
import triage_reports as tr  # noqa: E402

NATIVE_HI = 180
NATIVE_MID = 120

# 基准脚本（slowpage_stats.py）实测出的三份数；对不上就说明口径漂了，直接停。
BASE_REPORTS, BASE_PAGES, BASE_SLOW = 15, 264, 11
BASE_WATER = {f">={sps.WATER_TOP}%": (7, 81), f"{sps.WATER_MID}-{sps.WATER_TOP}%": (4, 47),
              f"<{sps.WATER_MID}%": (0, 136)}

# 轨迹滚动上限满档 ⇒ 看不到更早的页（读法上要标出来）。
CAP = sps.TRAIL_CAP_LINES


# ----------------------------------------------------------------------
# 装载（全部走 slowpage_stats 的读法）
# ----------------------------------------------------------------------


@dataclass
class Session:
    """一份带 turn 页的报告 = 一簇页 + 原始轨迹行（前后行 phase 要用到非 turn 行）。"""

    unit: sps.Unit
    trail: list[tr.TrailSample]

    @property
    def key(self) -> str:
        return os.path.basename(self.unit.key)

    @property
    def n_lines(self) -> int:
        return len(self.trail)

    def native_all(self) -> list[int]:
        """该会话**全部轨迹行**的 native（含 pause/trim 行）——判「整局是否恒定」用。"""
        return [s.native_mib for s in self.trail]

    def raw(self, idx: int) -> str:
        """轨迹第 idx 行的原样阶段名（越界给空串 = 该方向没有行）。"""
        if 0 <= idx < len(self.trail):
            return self.trail[idx].phase or "(无阶段名)"
        return ""


def default_inbox() -> Path:
    """默认收件箱：先本模块旁 `.tmp/inbox`，再**仓根** `.tmp/inbox`，最后退到 fixbug 收件箱。

    原始报告不入 git，物理上可能落在：`<repo>/.tmp/inbox/raw/`（拉全的原文）、
    `<repo>/.tmp/inbox/*.txt`（直接平铺），或旧克隆里。任一处有 `raw/` 或有 `*.txt` 就算可用。
    """
    def _usable(d: Path) -> bool:
        return (d / "raw").is_dir() or any(d.glob("*.txt"))

    module_inbox = Path(__file__).resolve().parents[1] / ".tmp" / "inbox"
    if _usable(module_inbox):
        return module_inbox
    repo_inbox = Path(__file__).resolve().parents[2] / ".tmp" / "inbox"
    if _usable(repo_inbox):
        return repo_inbox
    return repo_inbox


def load_sessions(directory: Path) -> tuple[list[Session], int, int]:
    """返回（带 turn 页的会话按文件名排序，扫描文件数，当前口径份数）。"""
    reports, in_files = sps.load_inbox(directory)
    if not reports:
        raise SystemExit(f"{directory} 里没有可读报告（期望 raw 目录下的 txt）")
    scan = tr.analyze(reports)
    sessions = []
    for r in scan.current:
        unit = sps.build_unit(r)
        if unit is not None:
            sessions.append(Session(unit, list(r.trail)))
    sessions.sort(key=lambda s: s.key)
    return sessions, in_files, len(scan.current)


def self_check(sessions: list[Session], threshold: int, in_files: int, n_current: int,
               allow_drift: bool = False) -> list[str]:
    """三份基准数 + 水位三层页数：与 slowpage_stats.py 逐项对上，对不上抛错。

    基准数（15 份 / 264 页 / 11 慢页 / 水位三层 7-4-0）只在**默认判据 5000ms** 下成立；
    换了 `--slow-ms` 就只对前两项（份数、页数，与阈值无关）硬校验，后两项改成「按当前阈值重算」
    并写明与基准不可比 —— 免得改判据时被硬停，也免得把非默认口径当成基准读数。

    [allow_drift] = true 时漂移不抛错，改为在首行打「口径漂移」并把实际读数写进数表
    （见模块文档的 `--allow-drift` 节；默认 false = 与原纪律逐字相同）。
    """
    drift: list[str] = []

    def _fail(msg: str) -> None:
        if allow_drift:
            drift.append(msg)
        else:
            raise SystemExit(msg)

    n_reports = len(sessions)
    n_pages = sum(s.unit.n_pages for s in sessions)
    n_slow = sum(1 for s in sessions for p in s.unit.pages if p.dur_ms >= threshold)
    if (n_reports, n_pages) != (BASE_REPORTS, BASE_PAGES):
        _fail(
            f"[口径漂移] 与 slowpage_stats.py 基准不符："
            f"份数 {n_reports}/{BASE_REPORTS} · 页数 {n_pages}/{BASE_PAGES}"
        )
    lines = []
    counts_ok = (n_reports, n_pages) == (BASE_REPORTS, BASE_PAGES)
    if threshold == sps.DEFAULT_THRESHOLD_MS:
        if n_slow != BASE_SLOW:
            _fail(f"[口径漂移] 慢页数 {n_slow}/{BASE_SLOW}（阈值 {threshold}ms）与基准不符")
        counts_ok = counts_ok and n_slow == BASE_SLOW
        lines.append(
            f"扫描文件 {in_files} 份 · 当前口径 {n_current} 份 · 带 turn 轨迹 {n_reports} 份 · "
            f"{n_pages} 页 · 慢页（>= {threshold}ms）{n_slow} 页 ⇒ "
            + ("与基准一致" if counts_ok else "**与基准不符（漂移，见首行）**")
        )
    else:
        lines.append(
            f"扫描文件 {in_files} 份 · 当前口径 {n_current} 份 · 带 turn 轨迹 {n_reports}（基准 {BASE_REPORTS}）· "
            f"{n_pages} 页（基准 {BASE_PAGES}）· 慢页（>= {threshold}ms）{n_slow} 页 ⇒ "
            + ("份数/页数与基准一致；" if counts_ok else "份数/页数**与基准不符（漂移，见首行）**；")
            + f"慢页数**非默认阈值**，与基准 {BASE_SLOW} 页不可比"
        )
    band: dict[str, Counter] = defaultdict(Counter)
    for s in sessions:
        for p in s.unit.pages:
            b = sps.water_band(p.used_pct)
            band[b]["pages"] += 1
            if p.dur_ms >= threshold:
                band[b]["slow"] += 1
    for b, (exp_slow, exp_pages) in BASE_WATER.items():
        got = (band[b]["slow"], band[b]["pages"])
        if threshold == sps.DEFAULT_THRESHOLD_MS:
            ok = "一致" if got == (exp_slow, exp_pages) else f"不一致（基准 {exp_slow}/{exp_pages}）"
            lines.append(f"  水位层 {b:<8}：{got[0]}/{got[1]} 页 ⇒ {ok}")
            if got != (exp_slow, exp_pages):
                _fail(lines[-1])
        else:
            lines.append(f"  水位层 {b:<8}：{got[0]}/{got[1]} 页（基准 {exp_slow}/{exp_pages} 是 5000ms 口径，此处不校验）")
    if drift:
        head = (f"[口径漂移·--allow-drift] 基准快照已不在盘，以下数表是**现盘口径**"
                f"（带 turn 轨迹 {n_reports} 份 / {n_pages} 页 / 慢页 {n_slow} 页），**不许引用基准数**："
                + "；".join(drift))
        return [head] + lines
    return lines


# ----------------------------------------------------------------------
# 第 1 节：慢页逐页画像
# ----------------------------------------------------------------------


def turn_ms_of(raw: str) -> int:
    m = sps.TURN_MS.match(raw)
    return int(m.group(1)) if m else -1


def phase_kind(raw: str) -> str:
    """前/后一行是什么形态：turn（带 ms）/ pause / trim / boot / 无行（轨迹边界外）。"""
    if not raw:
        return "无行"
    if sps.TURN_MS.match(raw):
        return "turn"
    head = raw.split()[0] if raw.split() else raw
    if head.startswith("trim"):
        return "trim"
    return head


def prev_label(raw: str, threshold: int) -> str:
    """给「前一行的 phase 与耗时」+ 一句形态归类。"""
    if not raw:
        return "无（该行是轨迹首行，前一行看不到）"
    k = phase_kind(raw)
    if k == "turn":
        ms = turn_ms_of(raw)
        tag = "快页（<1000ms）" if ms < 1000 else (f"亚慢（1000-{threshold - 1}ms）" if ms < threshold else "另一个慢页")
        return f"{raw}（{tag}）"
    return f"{raw}（非翻页行：{k}）"


def slow_page_rows(sessions: list[Session], threshold: int) -> list[dict]:
    rows: list[dict] = []
    for s in sessions:
        for p in s.unit.pages:
            if p.dur_ms < threshold:
                continue
            idx = p.line_no  # 该页在**原始轨迹**里的行下标（0 基）
            sample = s.trail[idx]
            prev_raw = s.raw(idx - 1)
            nxt_raw = s.raw(idx + 1)
            rows.append(
                {
                    "report": s.key,
                    "device": s.unit.device,
                    "version": s.unit.version,
                    "build": s.unit.stamp,
                    "dur_ms": p.dur_ms,
                    "used_mib": p.used_mib,
                    "max_mib": p.max_mib,
                    "used_pct": p.used_pct,
                    "water_band": sps.water_band(p.used_pct),
                    "native_mib": p.native_mib,
                    "native_band": native_band(p.native_mib),
                    "line_no": idx + 1,
                    "trail_lines": s.n_lines,
                    "from_end": s.n_lines - 1 - idx,
                    "trail_full": "yes" if s.n_lines >= CAP else "no",
                    "prev_phase": prev_raw,
                    "prev_kind": phase_kind(prev_raw),
                    "next_phase": nxt_raw,
                    "next_kind": phase_kind(nxt_raw),
                    "last_phase": s.unit.last_phase,
                    "sys_avail_mib": sample.sys_avail_mib,
                    "lmk_threshold_mib": sample.lmk_threshold_mib,
                }
            )
    rows.sort(key=lambda r: (-r["dur_ms"], r["report"]))
    return rows


def ci(hits: int, total: int) -> str:
    lo, hi = sps.wilson_interval(hits, total)
    return f"[{lo * 100:.2f}%, {hi * 100:.2f}%]"


def section1(rows: list[dict], threshold: int) -> list[str]:
    out = [
        "=" * 108,
        f"第 1 节 · {len(rows)} 个慢页逐页画像（判据 >= {threshold}ms；行号=该页在原始轨迹里的位置）",
        "=" * 108,
    ]
    for i, r in enumerate(rows, 1):
        sys_txt = "缺列" if r["sys_avail_mib"] is None else f"sys={r['sys_avail_mib']}MiB"
        lmk_txt = "缺列" if r["lmk_threshold_mib"] is None else f"lmk={r['lmk_threshold_mib']}MiB"
        full_txt = f"满档（>={CAP} 行，看不到更早的页）" if r["trail_full"] == "yes" else "未满档（能看到开局）"
        out.append(
            f"[{i:2d}] {r['device']:<20} {r['version']} {r['build']:<12} {r['dur_ms']:>6}ms"
        )
        out.append(
            f"     堆水位 used={r['used_mib']}/{r['max_mib']}MiB={r['used_pct']}%（{r['water_band']}）"
            f" · native={r['native_mib']}MiB（{r['native_band']}） · 系统内存 {sys_txt} · {lmk_txt}"
        )
        out.append(
            f"     位置：第 {r['line_no']}/{r['trail_lines']} 行，距轨迹末 {r['from_end']} 行，{full_txt}；"
            f"该份末行 phase={r['last_phase']}"
        )
        out.append(f"     前一行：{prev_label(r['prev_phase'], threshold)}")
        out.append(f"     后一行：{r['next_phase'] or '（该行就是轨迹末行）'}（{r['next_kind']}）")
    # 归类小结：前一行的形态按「翻页后紧跟」/「重页堆叠」/「非翻页行（pause/trim）」三档收
    kinds: Counter[str] = Counter()
    non_turn = []
    for r in rows:
        if r["prev_kind"] == "turn":
            ms = turn_ms_of(r["prev_phase"])
            if ms < 1000:
                kinds["前一行是快页 turn(<1000ms) ⇒ 慢页紧跟在一次正常翻页之后"] += 1
            elif ms < threshold:
                kinds["前一行本身是 1000-4999ms 的重页 ⇒ 慢页堆在连续重页里"] += 1
            else:
                kinds["前一行本身就是另一个慢页(>=5000ms) ⇒ 连续两发慢页"] += 1
        else:
            kinds["前一行是 " + r["prev_kind"] + "（非翻页行）⇒ 后台回收/裁剪后的第一发"] += 1
            non_turn.append(f"{r['report'][-20:-4]} 第{r['line_no']}行（{r['device']}）前一行={r['prev_phase']}")
    out.append("")
    out.append("【慢页的「前一行」形态分布 —— 判「是不是翻页后紧跟的那一发」】")
    for k, v in sorted(kinds.items(), key=lambda kv: (-kv[1], kv[0])):
        out.append(f"  {v} 页 —— {k}")
    if non_turn:
        out.append("  其中「前一行为非翻页行」的逐个点名（含义与紧跟翻页完全不同）：")
        out.extend("    " + x for x in non_turn)
    pos = Counter("距末 0-3 行（死亡前那一段）" if r["from_end"] <= 3 else "距末 >=4 行" for r in rows)
    out.append("【慢页在轨迹里的位置分布】")
    for k, v in sorted(pos.items(), key=lambda kv: (-kv[1], kv[0])):
        out.append(f"  {v} 页 —— {k}")
    full = sum(1 for r in rows if r["trail_full"] == "yes")
    out.append(
        f"【观测窗口偏差】这 {len(rows)} 个慢页里，出自满档（{CAP} 行）会话 {full} 页、"
        f"未满档会话 {len(rows) - full} 页 ⇒ 窗口仍偏向死亡前那一段。"
    )
    caps = Counter(r["max_mib"] for r in rows)
    out.append("【慢页的堆上限 max 分布 —— used% 相同不等于绝对余量相同】")
    for cap, cnt in sorted(caps.items(), key=lambda kv: -kv[1]):
        free = [r["max_mib"] - r["used_mib"] for r in rows if r["max_mib"] == cap]
        devs = sorted({f"{r['device']}({r['version']})" for r in rows if r["max_mib"] == cap})
        out.append(
            f"  max={cap}MiB 的慢页 {cnt} 页 · 剩余 (max-used) {min(free)}-{max(free)}MiB · 机型 {'/'.join(devs)}"
        )
    if len(caps) > 1:
        out.append(
            "  ⇒ 读法：水位百分比把 256MiB 上限的机与 512MiB 上限的机算进了同一层，"
            "同层的「96%」对应的绝对余量差一倍 ⇒ 分层看水位时要把 max 一起看。"
        )
    src = Counter(f"{r['device']} {r['version']}" for r in rows)
    out.append(f"【慢页出处（分母只有 {len(rows)} 页；单份会话的多数不许当机型常数）】")
    top = None
    for k, v in sorted(src.items(), key=lambda kv: (-kv[1], kv[0])):
        reps = {r["report"] for r in rows if f"{r['device']} {r['version']}" == k}
        out.append(f"  {v} 页 —— {k} —— 来自 {len(reps)} 份会话")
        if top is None or v > top[1]:
            top = (k, v)
    if top:
        out.append(
            f"  ⇒ 最集中的一档：{top[0]} 独占 {top[1]}/{len(rows)} 页（且只出自 1 份会话）"
            "——不许据此写成机型常数或机制定论。"
        )
    return out


# ----------------------------------------------------------------------
# 第 2 节：native 分层（与水位层并列摆）
# ----------------------------------------------------------------------


def native_band(native: int) -> str:
    if native >= NATIVE_HI:
        return f">={NATIVE_HI}"
    if native >= NATIVE_MID:
        return f"{NATIVE_MID}-{NATIVE_HI - 1}"
    return f"<{NATIVE_MID}"


def band_sort_key(band: str):
    """层的展示顺序：按各层的下界从高到低（`>=X` 视作最高层，`<X` 视作最低层）。

    同时吃 native 层（`>=180` / `120-179` / `<120`）与水位层（`>=90%` / `70-90%` / `<70%`）两种写法。
    """
    b = band.rstrip("%")
    if b.startswith(">="):
        return int(b[2:]) + 100000
    if b.startswith("<"):
        return -1
    return int(b.split("-")[0])


def stratum_rows(sessions: list[Session], threshold: int, bander) -> list[dict]:
    """通用分层：bander(page) -> 层名；给 页数/慢页数/页级 p̂ + Wilson/来自几份会话。"""
    pages: dict[str, list] = defaultdict(list)
    all_reps: dict[str, set] = defaultdict(set)
    hit_reps: dict[str, set] = defaultdict(set)
    devs: dict[str, set] = defaultdict(set)
    for s in sessions:
        for p in s.unit.pages:
            b = bander(p)
            pages[b].append(p)
            all_reps[b].add(s.key)
            devs[b].add(s.unit.device)
            if p.dur_ms >= threshold:
                hit_reps[b].add(s.key)
    rows = []
    for b in sorted(pages, key=band_sort_key, reverse=True):
        hits = sum(1 for p in pages[b] if p.dur_ms >= threshold)
        total = len(pages[b])
        lo, hi = sps.wilson_interval(hits, total)
        rows.append({
            "stratum": b, "pages": total, "slow_pages": hits,
            "p_hat": round(hits / total, 6) if total else 0.0,
            "wilson_lo": round(lo, 6), "wilson_hi": round(hi, 6),
            "reports": len(all_reps[b]), "slow_reports": len(hit_reps[b]),
            "devices": " ".join(sorted(devs[b])),
        })
    return rows


def table_header(caption: str) -> str:
    return (f"  {caption + ' 层':<10}{'页数':>6}{'慢页':>6}{'页级 p̂':>10}"
            f"{'Wilson(页级朴素)':>24}{'会话':>6}{'含慢页会话':>11}  机型")


def table_row(r: dict) -> str:
    return (
        f"  {r['stratum']:<10}{r['pages']:>6}{r['slow_pages']:>6}{r['p_hat'] * 100:>9.2f}%"
        f"{ci(r['slow_pages'], r['pages']):>24}{r['reports']:>6}{r['slow_reports']:>11}  {r['devices']}"
    )


def section2(sessions: list[Session], threshold: int) -> tuple[list[str], list[dict], list[dict]]:
    nat_rows = stratum_rows(sessions, threshold, lambda p: native_band(p.native_mib))
    wat_rows = stratum_rows(sessions, threshold, lambda p: sps.water_band(p.used_pct))
    out = [
        "=" * 108,
        f"第 2 节 · native 分层（边界含下界：>= {NATIVE_HI} / {NATIVE_MID}-{NATIVE_HI - 1} / < {NATIVE_MID} MiB）",
        "=" * 108,
        "  ※ 分桶边界是本脚本自己的口径，不属于 slowpage_stats 的既有条款；只作描述用。",
        "",
        table_header("native"),
    ]
    for r in nat_rows:
        out.append(table_row(r))
        if r["slow_pages"] >= 2 and r["slow_reports"] == 1:
            out.append(
                f"    ⚠ 该层 {r['slow_pages']} 个慢页**全部出自同 1 份会话** ⇒ 页级 Wilson 把区间压窄了"
                "（独立单位是会话不是页），这一层的 p̂ 只能读成「这一局的表现」。"
            )
    out.append("")
    out.append("  与已有水位层并列摆（水位层逐字复用 water_band；只做一致性对照，不重算成新结论）：")
    for r in wat_rows:
        out.append(table_row(r))
    out.append("")
    out.append("  交叉表（native 层 × 水位层）—— 慢页到底站在哪一格，一眼看完：")
    out.append(f"  {'native 层':<12}{'水位层':<10}{'页数':>6}{'慢页':>6}{'会话':>6}  会话（机型）")
    cross: dict[tuple, list] = defaultdict(lambda: [0, 0, set(), set()])
    for s in sessions:
        for p in s.unit.pages:
            key = (native_band(p.native_mib), sps.water_band(p.used_pct))
            cross[key][0] += 1
            cross[key][2].add(s.key)
            cross[key][3].add(s.unit.device)
            if p.dur_ms >= threshold:
                cross[key][1] += 1
    for key in sorted(cross, key=lambda k: (band_sort_key(k[0]), band_sort_key(k[1])), reverse=True):
        total, hits, reps, dv = cross[key]
        out.append(f"  {key[0]:<12}{key[1]:<10}{total:>6}{hits:>6}{len(reps):>6}  {' '.join(sorted(dv))}")
    return out, nat_rows, wat_rows


# ----------------------------------------------------------------------
# 第 3 节：机型内（会话内）对照 —— 混杂因子的关键一刀
# ----------------------------------------------------------------------


def section3(sessions: list[Session], threshold: int) -> tuple[list[str], list[dict]]:
    out = [
        "=" * 108,
        "第 3 节 · 会话内对照：同一份会话里 native 是否基本恒定 ⇒ 「native 高致慢」与「这台机常驻 native 高」可否分",
        "=" * 108,
    ]
    with_both = [s for s in sessions
                 if any(p.dur_ms >= threshold for p in s.unit.pages)
                 and any(p.dur_ms < threshold for p in s.unit.pages)]
    rows: list[dict] = []
    for s in sorted(with_both, key=lambda x: (-x.unit.n_pages, x.key)):
        slow_p = [p for p in s.unit.pages if p.dur_ms >= threshold]
        fast_p = [p for p in s.unit.pages if p.dur_ms < threshold]
        nat_all = s.native_all()
        rows.append({
            "report": s.key, "device": s.unit.device, "version": s.unit.version, "build": s.unit.stamp,
            "trail_lines": s.n_lines, "pages": s.unit.n_pages, "slow_pages": len(slow_p),
            "native_min_all": min(nat_all), "native_max_all": max(nat_all),
            "native_spread_all": max(nat_all) - min(nat_all),
            "native_min_slow": min(p.native_mib for p in slow_p),
            "native_max_slow": max(p.native_mib for p in slow_p),
            "native_min_fast": min(p.native_mib for p in fast_p),
            "native_max_fast": max(p.native_mib for p in fast_p),
            "water_min_slow": min(p.used_pct for p in slow_p),
            "water_max_slow": max(p.used_pct for p in slow_p),
            "water_min_fast": min(p.used_pct for p in fast_p),
            "water_max_fast": max(p.used_pct for p in fast_p),
            "native_band_overlap": "yes" if {native_band(p.native_mib) for p in slow_p} & {native_band(p.native_mib) for p in fast_p} else "no",
            "water_band_overlap": "yes" if {sps.water_band(p.used_pct) for p in slow_p} & {sps.water_band(p.used_pct) for p in fast_p} else "no",
        })
    for r in rows:
        out.append(f"  {r['report']} · {r['device']} {r['version']} {r['build']}")
        out.append(f"    轨迹 {r['trail_lines']} 行 / {r['pages']} 页，其中慢页 {r['slow_pages']} 页")
        out.append(
            f"    native（全部轨迹行）min={r['native_min_all']} max={r['native_max_all']} "
            f"极差={r['native_spread_all']}MiB；慢页 {r['native_min_slow']}-{r['native_max_slow']}、"
            f"快页 {r['native_min_fast']}-{r['native_max_fast']} ⇒ 慢/快同层={r['native_band_overlap']}"
        )
        out.append(
            f"    水位：慢页 {r['water_min_slow']}%-{r['water_max_slow']}%、"
            f"快页 {r['water_min_fast']}%-{r['water_max_fast']}% ⇒ 慢/快同层={r['water_band_overlap']}"
        )
    n = len(rows)
    nat_ov = sum(1 for r in rows if r["native_band_overlap"] == "yes")
    wat_ov = sum(1 for r in rows if r["water_band_overlap"] == "yes")
    tight = sum(1 for r in rows if r["native_spread_all"] < NATIVE_HI - NATIVE_MID)
    out.append("")
    out.append(f"  同时有慢页与快页的会话：{n} 份（会话内对照的分母只有这些）。")
    out.append(
        f"  会话内 native 极差 < {NATIVE_HI - NATIVE_MID} MiB（一整局跨不出相邻层）：{tight}/{n} 份；"
        f"慢页与快页落在**同一 native 层**：{nat_ov}/{n} 份。"
    )
    out.append(f"  慢页与快页落在**同一水位层**：{wat_ov}/{n} 份。")
    # 会话内「同水位层同时有慢有快」的格子数：水位能否在同一台机内挑出慢页
    cells = 0
    cell_detail: list[str] = []
    for s in with_both:
        by: dict[str, list] = defaultdict(lambda: [0, 0])
        for p in s.unit.pages:
            b = sps.water_band(p.used_pct)
            if p.dur_ms >= threshold:
                by[b][0] += 1
            else:
                by[b][1] += 1
        for b, (hs, fs) in sorted(by.items()):
            if hs and fs:
                cells += 1
                cell_detail.append(f"{os.path.basename(s.key)[:16]}…/{b} 慢 {hs} 快 {fs}")
    out.append(f"  「同一会话、同一水位层」里同时有慢页与快页的格子：{cells} —— {'；'.join(cell_detail) or '无'}")
    by_sess_native_cells = 0
    for s in with_both:
        by = defaultdict(lambda: [0, 0])
        for p in s.unit.pages:
            b = native_band(p.native_mib)
            if p.dur_ms >= threshold:
                by[b][0] += 1
            else:
                by[b][1] += 1
        by_sess_native_cells += sum(1 for hs, fs in by.values() if hs and fs)
    out.append(f"  「同一会话、同一 native 层」里同时有慢页与快页的格子：{by_sess_native_cells}")
    # 会话内「严格分离」检验：该会话的**每一个**慢页水位是否都高于**每一个**快页水位
    strict_wat = 0
    strict_nat = 0
    for r in rows:
        strict_wat += int(r["water_min_slow"] > r["water_max_fast"])
        strict_nat += int(r["native_min_slow"] > r["native_max_fast"])
        out.append(
            f"    {r['report'][-21:-4]}：慢页最低水位 {r['water_min_slow']}% vs 快页最高水位 {r['water_max_fast']}%"
            f" ⇒ 会话内水位严格分离={'是' if r['water_min_slow'] > r['water_max_fast'] else '否'}"
            f"；native：慢最低 {r['native_min_slow']} vs 快最高 {r['native_max_fast']}"
            f" ⇒ 严格分离={'是' if r['native_min_slow'] > r['native_max_fast'] else '否'}"
        )
    out.append(f"  ⇒ 会话内「慢页全部高于快页」的分离度：水位 {strict_wat}/{n} 份、native {strict_nat}/{n} 份。")
    worst = max(rows, key=lambda r: r["native_spread_all"]) if rows else None
    out.append("")
    out.append(
        f"  ⇒ 判读：会话内 native 基本恒定 —— 极差跨不出相邻层（<{NATIVE_HI - NATIVE_MID}MiB）的 {tight}/{n} 份；"
        + (
            f"极差最大的 {worst['device']}（{worst['native_min_all']}-{worst['native_max_all']}MiB，"
            f"跨度 {worst['native_spread_all']}MiB、已跨过 {NATIVE_HI} 边界）里慢页（{worst['native_min_slow']}-"
            f"{worst['native_max_slow']}）与快页（{worst['native_min_fast']}-{worst['native_max_fast']}）仍落在同一层。"
            if worst else ""
        )
        + f"两个因子的会话内严格分离都是 {strict_wat}/{n}、{strict_nat}/{n} ⇒ 「native 高致慢」与「这台机常驻 native 高」"
        "在这些数据里**不可分**；"
        "跨层的 p̂ 差只是机型/版本之间的差（会话才是独立单位，页不是）。"
    )
    lo_s = min((r["water_min_slow"] for r in rows), default=0)
    hi_f = max((r["water_max_fast"] for r in rows), default=0)
    out.append(
        "  ⇒ 能分开的部分：只剩「跨会话的水位层」这一条，且它在同一台机内也不成立 —— "
        f"会话内水位严格分离的 {strict_wat}/{n} 份（{n - strict_wat} 份里慢页与快页的水位区间互相重叠："
        f"最低慢页 {lo_s}% 反而低于最高快页 {hi_f}%）⇒ 水位在同一台机内挑不出慢页。"
    )
    return out, rows


# ----------------------------------------------------------------------
# 第 4 节：系统内存列（sys= / lmk=）
# ----------------------------------------------------------------------


def _dist(vals: list[int]) -> str:
    if not vals:
        return "无数据"
    vs = sorted(vals)
    return f"n={len(vs)} min={vs[0]} 中位={vs[len(vs) // 2]} max={vs[-1]}MiB"


def section4(sessions: list[Session], threshold: int) -> tuple[list[str], list[dict]]:
    out = [
        "=" * 108,
        "第 4 节 · 系统内存列（轨迹行的 sys=可用内存 / lmk=阈值）：慢页 vs 非慢页分布对照；缺列如实报缺",
        "=" * 108,
    ]
    slow_sys, fast_sys, slow_lmk, fast_lmk = [], [], [], []
    slow_sess_sys, fast_sess_sys = set(), set()
    have_lines = 0
    total_pages = 0
    trim_lines = 0
    slow_after_trim = 0
    # (机型, 版本) -> [缺 sys 列页数, 有 sys 列页数, 会话集合]
    seen: dict[tuple, list] = defaultdict(lambda: [0, 0, set()])
    for s in sessions:
        key = (s.unit.device, s.unit.version)
        for p in s.unit.pages:
            total_pages += 1
            sm = s.trail[p.line_no]
            slow = p.dur_ms >= threshold
            if sm.sys_avail_mib is None:
                seen[key][0] += 1
            else:
                seen[key][1] += 1
                have_lines += 1
                (slow_sys if slow else fast_sys).append(sm.sys_avail_mib)
                (slow_sess_sys if slow else fast_sess_sys).add(s.key)
            if sm.lmk_threshold_mib is not None:
                (slow_lmk if slow else fast_lmk).append(sm.lmk_threshold_mib)
            seen[key][2].add(s.key)
            if slow and s.raw(p.line_no - 1).startswith("trim"):
                slow_after_trim += 1
        for t in s.trail:
            if t.phase.startswith("trim"):
                trim_lines += 1
    n_trim_sess = sum(1 for s in sessions if any(t.phase.startswith("trim") for t in s.trail))
    out.append(f"  带轨迹的页总数：{total_pages}；其中读到 sys= 列的：{have_lines} 页 ⇒ **缺列 {total_pages - have_lines} 页**")
    out.append(f"  sys=（系统可用内存）：慢页 {_dist(slow_sys)} · 非慢页 {_dist(fast_sys)}")
    out.append(f"  lmk=（LMK 阈值）    ：慢页 {_dist(slow_lmk)} · 非慢页 {_dist(fast_lmk)}")
    out.append(f"  trim 行：{n_trim_sess} 份会话里出现过，合计 {trim_lines} 行；其中慢页紧跟在 trim 之后的：{slow_after_trim} 页")
    out.append("")
    out.append(f"  {'机型':<22}{'版本':<10}{'会话':>6}{'列状态':>10}  页数（有列/缺列）")
    dev_rows: list[dict] = []
    for key in sorted(seen):
        miss, hit, reps = seen[key]
        state = "全有列" if miss == 0 else ("全缺列" if hit == 0 else "部分有列")
        out.append(f"  {key[0]:<22}{key[1]:<10}{len(reps):>6}{state:>10}  （有 {hit} / 缺 {miss}）")
        dev_rows.append({"device": key[0], "version": key[1], "reports": len(reps),
                         "sys_present_pages": hit, "sys_missing_pages": miss, "column_state": state})
    if not fast_sys and not slow_sys:
        out.append("  ⇒ 系统内存列在本批轨迹里整体缺列，无法做慢/非慢对照（如实报缺，不猜）。")
    else:
        out.append("  ⇒ 有列的页可做对照；读法注意：列的有无是按**版本/构建**决定的（见上表），"
                   "所以「慢页 vs 非慢页」的 sys 对照只在同一机型内才不被缺列混杂。")
    slow_sys_by_dev = defaultdict(list)
    fast_sys_by_dev = defaultdict(list)
    for s in sessions:
        for p in s.unit.pages:
            sm = s.trail[p.line_no]
            if sm.sys_avail_mib is None:
                continue
            (slow_sys_by_dev if p.dur_ms >= threshold else fast_sys_by_dev)[s.unit.device].append(sm.sys_avail_mib)
    both = set(slow_sys_by_dev) & set(fast_sys_by_dev)
    both_sess = slow_sess_sys & fast_sess_sys
    out.append(f"  同机型内既有慢页又有非慢页且都有 sys 列的机型：{len(both)} —— {', '.join(sorted(both)) or '无'}")
    out.append(f"  ⇒ 换算成会话分母：只有 {len(both_sess)} 份会话同时提供慢页与非慢页的 sys 读数"
               f"（慢页侧 {len(slow_sys)} 页 / 非慢页侧 {len(fast_sys)} 页）")
    for d in sorted(both):
        out.append(f"    {d}：慢页 {_dist(slow_sys_by_dev[d])} · 非慢页 {_dist(fast_sys_by_dev[d])}")
    lmk_vals = sorted(set(slow_lmk) | set(fast_lmk))
    if len(lmk_vals) <= 1 and lmk_vals:
        out.append(f"  lmk= 在全体有列的页里恒为 {lmk_vals[0]}MiB ⇒ **该列在本批数据里没有变异，做不了对照**。")
    if len(both_sess) <= 1:
        miss_vers = sorted({r["version"] for r in dev_rows if r["sys_present_pages"] == 0})
        out.append("  ⇒ 系统内存列的最终读数：现有数据**分不出**「系统可用内存低致慢」——"
                   f"有列的只有 {len(both_sess)} 份会话，且这 {len(both_sess)} 份里慢页与非慢页的 sys 区间几乎重叠；"
                   f"其余版本（{'/'.join(miss_vers)}）整列缺失，无法补齐同一台机的对照。")
    return out, dev_rows


# ----------------------------------------------------------------------
# 裁定（四选一，规则写死，带分母）
# ----------------------------------------------------------------------


def verdict(rows: list[dict], sess_rows: list[dict], nat_rows: list[dict],
            wat_rows: list[dict], n_sess: int) -> list[str]:
    """四选一的裁定：规则写死在数据上，分母与不可分之处同时给出。

    规则（先查「会话内能不能把两个因子分开」，分不开就不许点机制名）：
      - 会话内对照的三份里，慢页与快页同时落在同一 native 层 **且** 同一水位层（重叠率都过半）
        ⇒ `现有数据不足以裁定`（跨层差异只是机型/版本之间的差）。
      - 只有当会话内能把 native 分开（慢/快不同层）、而水位仍呈单调 ⇒ 才敢写 `堆机制`。
      - 只有当会话内 native 单调而水位不单调 ⇒ 才敢写 `native 机制`。
      - 两层各自都出现「跨层单调、层内可分」的独立证据 ⇒ `两者各有门槛`。
    """
    n_slow = len(rows)
    slow_sess = len({r["report"] for r in rows})
    n = len(sess_rows)
    nat_ov = sum(1 for r in sess_rows if r["native_band_overlap"] == "yes")
    wat_ov = sum(1 for r in sess_rows if r["water_band_overlap"] == "yes")
    inseparable = n > 0 and nat_ov * 2 >= n and wat_ov * 2 >= n
    low = next((r for r in wat_rows if r["stratum"] == f"<{sps.WATER_MID}%"), None)
    hi_native = next((r for r in nat_rows if r["stratum"] == f">={NATIVE_HI}"), None)
    # 机型贡献最大的一档（用于「不许当机型常数」的原话）
    src = Counter((r["device"], r["version"]) for r in rows)
    top_dev, top_n = (max(src.items(), key=lambda kv: kv[1]) if src else ("(无)", 0))
    if inseparable:
        call = "现有数据不足以裁定"
    elif low and low["slow_pages"] == 0 and nat_ov == 0:
        call = "堆机制"
    elif hi_native and hi_native["slow_pages"] and wat_ov == 0:
        call = "native 机制"
    else:
        call = "两者各有门槛"
    out = [
        "=" * 108,
        "裁定（四选一：堆机制 / native 机制 / 两者各有门槛 / 现有数据不足以裁定）",
        "=" * 108,
        f"  ⇒ **{call}**",
        "",
        f"  分母：{n_slow} 个慢页、出自 {slow_sess}/{n_sess} 份会话；会话内对照只有同时含慢页与快页的 {n} 份。",
    ]
    if low:
        out.append(
            f"  能说的（跨会话相关性，非因果）：水位 <{sps.WATER_MID}% 层 {low['slow_pages']}/{low['pages']} 页"
            " ⇒ 「低于该水位未见慢页」；但这一层的会话与另外两层的会话不是同一批机（机型/版本同时变化）。"
        )
    for r in sorted(nat_rows, key=lambda x: band_sort_key(x["stratum"]), reverse=True):
        out.append(
            f"  native 层 {r['stratum']:<9} {r['slow_pages']}/{r['pages']} 页、"
            f"出自 {r['slow_reports']}/{r['reports']} 份会话（机型：{r['devices']}）"
        )
    out.append(
        f"  不可分之处：会话内 native 基本恒定，慢/快落在同一 native 层的会话 {nat_ov}/{n}、"
        f"同一水位层的会话 {wat_ov}/{n} ⇒ 「这台机 native 高」与「这台机慢」在数据里缠在一起；"
        "跨层的 p̂ 差只是机型/版本之间的差（会话才是独立单位，页不是）。"
    )
    out.append(
        f"  不许外推：最集中的一档 {top_dev[0]} {top_dev[1]} 独占 {top_n}/{n_slow} 页且只出自 1 份会话 "
        "⇒ 是一簇页，不是机型常数，更不是机制定论；native 三层边界（"
        f">={NATIVE_HI} / {NATIVE_MID}-{NATIVE_HI - 1} / <{NATIVE_MID}）是本脚本自定口径，只作描述。"
    )
    mid = next((r for r in wat_rows if r["stratum"] == f"{sps.WATER_MID}-{sps.WATER_TOP}%"), None)
    mid_slow = [r for r in rows if r["water_band"] == f"{sps.WATER_MID}-{sps.WATER_TOP}%"]
    mid_sess = len({r["report"] for r in mid_slow})
    mid_devs = sorted({r["device"] for r in mid_slow})
    out.append(
        "  要把它变成机制结论还缺：同一台机内的 native 阶梯（同机不同 native 水位都跑到），"
        f"以及第二份 {sps.WATER_MID}-{sps.WATER_TOP}% 层的会话"
        f"（现该层 {mid['slow_pages'] if mid else 0} 个慢页出自 {mid_sess} 份会话、机型只有 {'/'.join(mid_devs)}）。"
    )
    return out


def _force_utf8_streams() -> None:
    """GBK 控制台不许炸：数表本身按 UTF-8 落文件，stdout 只打路径 + 摘要。"""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def main(argv: list[str]) -> int:
    _force_utf8_streams()
    parser = argparse.ArgumentParser(description="真机慢页画像与混杂因子对照（只读收件箱，产出数表）")
    parser.add_argument("--dir", type=Path, default=None, help="收件箱目录（读其 raw 子目录下的 txt）")
    parser.add_argument("--out", type=Path, default=None, help="数表输出目录（写 profile*）")
    parser.add_argument("--slow-ms", dest="slow_ms", type=int, default=sps.DEFAULT_THRESHOLD_MS,
                        help="慢页判据（ms），默认与 slowpage_stats 同")
    parser.add_argument("--allow-drift", dest="allow_drift", action="store_true",
                        help="基准快照不在盘时改为打「口径漂移」照跑（数表只作现盘口径，不许引用基准数）")
    args = parser.parse_args(argv)

    inbox = args.dir or default_inbox()
    out_dir = args.out or (inbox / "slowpage")

    sessions, in_files, n_current = load_sessions(inbox)
    check = self_check(sessions, args.slow_ms, in_files, n_current, args.allow_drift)

    slow_rows = slow_page_rows(sessions, args.slow_ms)
    s1 = section1(slow_rows, args.slow_ms)
    s2, nat_rows, wat_rows = section2(sessions, args.slow_ms)
    s3, sess_rows = section3(sessions, args.slow_ms)
    s4, dev_rows = section4(sessions, args.slow_ms)
    s5 = verdict(slow_rows, sess_rows, nat_rows, wat_rows, len(sessions))

    text = "\n".join(check + [""] + s1 + [""] + s2 + [""] + s3 + [""] + s4 + [""] + s5) + "\n"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "profile_slowpage.txt").write_text(text, encoding="utf-8")
    sps.write_csv(out_dir / "profile_slow_pages.csv", slow_rows)
    sps.write_csv(out_dir / "profile_native_strata.csv", nat_rows)
    sps.write_csv(out_dir / "profile_water_strata_recheck.csv", wat_rows)
    sps.write_csv(out_dir / "profile_session_native.csv", sess_rows)
    sps.write_csv(out_dir / "profile_sysmem_by_device.csv", dev_rows)

    print("\n".join(check))
    print(f"慢页 {len(slow_rows)} 页 / 带轨迹会话 {len(sessions)} 份 / 当前口径 {n_current} 份 / 扫描 {in_files} 份")
    print(s5[3])           # ⇒ 裁定（四选一）
    print("\n".join(s5[5:7]))  # 分母 + 跨会话相关性那条能说的
    print(f"\n[已写出] {out_dir}/profile_slowpage.txt · profile_slow_pages.csv · profile_native_strata.csv · "
          f"profile_water_strata_recheck.csv · profile_session_native.csv · profile_sysmem_by_device.csv")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
