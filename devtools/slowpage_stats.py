#!/usr/bin/env python3
"""真机慢页发生率 p̂：把「若 p≈3%，100 步就该命中」里那个 p 实测出来。

为什么要有这把尺子
------------------
玩家投诉「玩着玩着卡死 / 点屏幕没反应」已定性为**单线程脚本 VM 被一整页计算独占**
（决定性一份 = OPPO PLD110 / 0.2.34 / 构建 166863d，连续几页 turn 2ms → 2342ms →
15192ms → 58515ms，同期 used=505~511MiB max=512MiB）。两路在办的装置车道（obs-soak512 /
obs-compat512）要靠「真机慢页发生率 p」决定跑多少步、跑哪个堆挡位，而这个数目前是拍的。
桌面 3g 臂 n=369 零命中只给出上界 ≈0.81%，说明缺的是**机制（堆压相位）**而不是样本 ——
这句话成不成立取决于真机 p̂。本工具只读 `.tmp/inbox` 里已拉全的报告原文，产出一张带分母与
区间的数表；**不改产品代码、不改装置、不改 `triage_reports.py`**（口径直接复用它：
去重 / 旧版单列 / 0.2.31 脏退出误报单列 / 当前口径）。

口径（逐条钉死）
--------------
1. 统计对象：报告 `[补充现场]` 里的轨迹行 `t=…ms used=… native=… <phase>`。
   只有 phase 形如 `turn <N>ms` 的行是**一页**（一次回合结算的独占时长）；
   `pause` / `boot` / `trim=*` 不是页、不进分布。
2. 「慢页」判据：单页 `N ≥ --threshold`（缺省 5000ms = 玩家体感「点屏幕没反应」的量级）。
   另出 2s / 3s / 10s 的敏感性对照，避免阈值本身被挑口径。
3. 样本单位**两种都给**：
   - **页级**（一份报告的全部 `turn <N>ms` 当独立样本）：n 大、区间窄，但**同机同局强相关**
     （一份报告往往一次长会话连吃 24 页，慢页会成串出现）⇒ 它的朴素二项区间偏乐观，
     所以另配**按报告整份重抽的 cluster bootstrap** 区间（承认个体内相关，这是主区间）。
   - **报告级**（每份报告取其 max 页，命中 = 这份里至少出现一次慢页）：更保守，
     回答的是「十次事故里几次见过慢页」，不是「十分之几的页是慢页」。
   **建议装置车道引用页级 p̂ + cluster bootstrap 区间**（装置跑的正是「页/步」）。
4. 纳入哪些报告：只有 `triage_reports.analyze()` 的 **current 桶**（0.2.31 / 0.2.32 及更新，
   去掉重复 POST、去掉 0.2.0 / 0.2.3 旧包、去掉 0.2.31「脏退出误报」那一批）。
   旧版误报单独一列给数，**绝不混进 p̂**。
5. 两套人群口径**都出数**：
   - **甲（死在屏前）**：末行 phase 是 `turn` ⇒ 玩家盯着卡住的画面把应用划掉，正是「卡死」投诉；
   - **乙（全样本）**：current 桶里所有带 ≥1 页轨迹的报告，不看末行。
   差异逐条写进读数说明。
6. **末行失真**：轨迹落盘此前有撕裂窗口（UI 与 VM 并发整份覆盖 `trail.txt`，旧快照能盖回新快照），
   刚由 `fix-trailtear`（写侧 monitor + 序号跳旧快照）修好，但**历史报告无法追认**。
   因此：凡依赖**末行** phase 的计数（甲口径的人群划分、`pause`/`stopped` 的归类）都带这层不确定性，
   不许当逐字真值；**页级/报告级的慢页计数不受它影响**（撕裂改的是末行是谁，不伪造 `turn <N>ms`
   里的 N），所以 p̂ 主数走乙口径的页级读数。被撕裂影响的份数在报告里用「末行为 pause/trim/boot
   且正文写了『上一轮的收尾标记没被写下』」这类冲突信号单列。
7. 分位数用**最近秩**（ceil(q·n/100)，1 基，与产品侧 TurnPercentiles 同口径）。
8. 区间法：**Wilson**（页级点值的朴素对照区间）与 **Clopper–Pearson 精确区间**（报告级小样本用）
   都给，主区间是 cluster bootstrap 的等尾 95%。缺省置信 95%。
9. 水位分层：按**这一页自己那行**的 `used*100//max` 分 贴顶 ≥90% / 70–90% / <70% 三层，
   逐层给页级 p̂ 与区间，正面回答「慢页是不是只在堆贴顶时出现」；另给报告级峰值水位分层做对照
   （两者区别：瞬时水位 vs 这一局到过的水位）。
10. 反推：≥95% 概率至少命中一次慢页需要的步数 `n = ln(0.05)/ln(1-p̂)`，
    用**页级 p̂ 的点值 / 区间下界 / 区间上界**各算一个数（下界给的步数才是安全的预算）。

用法（Windows 控制台是 GBK，一律写文件再读）：

    python devtools/slowpage_stats.py --dir .tmp/inbox --out .tmp/inbox/slowpage
    python devtools/slowpage_stats.py --selftest

只读 `--dir`（递归找已拉全的 `raw/*.txt`，另可用 `list.json` 校验份数）；所有产物写 `--out`。
"""

from __future__ import annotations

import argparse
import copy
import csv
import math
import random
import re
import sys
from collections import Counter, OrderedDict, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from triage_reports import (  # noqa: E402  口径复用：不改它，只 import
    LEGACY_VERSIONS,
    Report,
    TrailSample,
    analyze,
    load_dir,
)

DEFAULT_THRESHOLD_MS = 5000
BOOT_REPS = 4000
BOOT_SEED = 20261101
CONF = 0.95
Z95 = 1.959963984540054
WATER_TOP = 90
WATER_MID = 70
# 落盘轨迹的滚动上限：现版报告正文写的是「最多 24 行」（旧版曾留 64 行）——
# 满 24 行的那份意味着**看不到更早的页**，观测窗口天然偏向死亡前那一段。
TRAIL_CAP_LINES = 24

TURN_MS = re.compile(r"^turn\s+(\d+)ms\b")

# 撕裂信号：正文说「没写下收尾标记」，末行却是 pause/trim/boot（二者只能有一个是真的）。
NO_CLOSE_MARK = "上一轮的收尾标记没被写下"


# ----------------------------------------------------------------------
# 统计小工具（纯 stdlib）
# ----------------------------------------------------------------------


def nearest_rank(sorted_values: list[int], q: float) -> int:
    """最近秩分位（ceil(q·n/100)，1 基）；空集返回 0。"""
    if not sorted_values:
        return 0
    idx = math.ceil(q * len(sorted_values) / 100.0)
    idx = min(max(idx, 1), len(sorted_values))
    return sorted_values[idx - 1]


def wilson_interval(hits: int, total: int, z: float = Z95) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    phat = hits / total
    denom = 1 + z * z / total
    center = (phat + z * z / (2 * total)) / denom
    half = (z / denom) * math.sqrt(phat * (1 - phat) / total + z * z / (4 * total * total))
    return (max(0.0, center - half), min(1.0, center + half))


def _betacf(a: float, b: float, x: float) -> float:
    """不完全贝塔的分式展开（Lentz 连分式），Clopper–Pearson 用。"""
    tiny = 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c = 1.0
    d = 1.0 - qab * x / qap
    if abs(d) < tiny:
        d = tiny
    d = 1.0 / d
    h = d
    for m in range(1, 200):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < tiny:
            d = tiny
        c = 1.0 + aa / c
        if abs(c) < tiny:
            c = tiny
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < tiny:
            d = tiny
        c = 1.0 + aa / c
        if abs(c) < tiny:
            c = tiny
        d = 1.0 / d
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < 1e-12:
            break
    return h


def _betai(a: float, b: float, x: float) -> float:
    """正则化不完全贝塔 I_x(a,b)。"""
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    ln_beta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log1p(-x)
    front = math.exp(ln_beta)
    if x < (a + 1.0) / (a + b + 2.0):
        return front * _betacf(a, b, x) / a
    return 1.0 - math.exp(
        math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + b * math.log1p(-x) + a * math.log(x)
    ) * _betacf(b, a, 1.0 - x) / b


def _invert_beta(p: float, a: float, b: float) -> float:
    lo, hi = 0.0, 1.0
    for _ in range(200):
        mid = (lo + hi) / 2.0
        if _betai(a, b, mid) < p:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2.0


def clopper_pearson_interval(hits: int, total: int, conf: float = CONF) -> tuple[float, float]:
    """二项比例的精确（保守）区间。"""
    if total <= 0:
        return (0.0, 0.0)
    alpha = 1.0 - conf
    low = 0.0 if hits == 0 else _invert_beta(alpha / 2.0, hits, total - hits + 1)
    high = 1.0 if hits == total else _invert_beta(1.0 - alpha / 2.0, hits + 1, total - hits)
    return (low, high)


def cp_lower_one_sided(hits: int, total: int, conf: float = CONF) -> float:
    """单侧 95% 置信下界（步数预算用它：双侧下界在簇数少时会退化到 0）。"""
    if total <= 0 or hits == 0:
        return 0.0
    return _invert_beta(1.0 - conf, hits, total - hits + 1)


def cluster_bootstrap(hits_per_cluster: list[tuple[int, int]], conf: float, reps: int, seed: int) -> tuple[float, float]:
    """把「一份报告」当一簇整份重抽，回算合成页级 p̂ 的等尾分位区间。

    页级朴素区间假设每页独立，而同一份报告的 24 页来自同机同局（慢页成串出现）⇒ 朴素区间偏窄。
    按簇重抽把个体内相关吃进去；只有 1 簇时退化到 0/0（如实报「区间不可估」，不硬造）。"""
    clusters = [(h, n) for (h, n) in hits_per_cluster if n > 0]
    if not clusters:
        return (0.0, 0.0)
    rng = random.Random(seed)
    k = len(clusters)
    boots: list[float] = []
    for _ in range(reps):
        h = n = 0
        for _ in range(k):
            hi, ni = clusters[rng.randrange(k)]
            h += hi
            n += ni
        if n > 0:
            boots.append(h / n)
    if len(boots) < 2:
        return (0.0, 0.0)
    boots.sort()
    tail = (1.0 - conf) / 2.0
    lo_i = max(0, min(len(boots) - 1, math.floor(tail * len(boots))))
    hi_i = max(0, min(len(boots) - 1, math.ceil((1.0 - tail) * len(boots)) - 1))
    return (boots[lo_i], boots[hi_i])


def steps_for(p: float, target: float = 0.95) -> float:
    """至少命中一次的概率到 target 需要的独立试验数；p<=0 给 inf，p>=1 给 1。"""
    if p <= 0.0:
        return math.inf
    if p >= 1.0:
        return 1.0
    return math.log(1.0 - target) / math.log(1.0 - p)


def fmt_ci(lo: float, hi: float) -> str:
    return f"[{lo * 100:.2f}%, {hi * 100:.2f}%]"


# ----------------------------------------------------------------------
# 建模
# ----------------------------------------------------------------------


@dataclass
class Page:
    dur_ms: int
    used_mib: int
    max_mib: int
    native_mib: int
    used_pct: int
    line_no: int


@dataclass
class Unit:
    """一份报告 = 一簇页。"""

    key: str
    device: str
    version: str
    stamp: str
    kind: str
    time: str
    last_phase: str
    last_is_turn: bool
    pages: list[Page] = field(default_factory=list)
    trail_conflict: bool = False  # 正文「没写收尾标记」但末行是 pause/trim/boot
    body_flag: str = ""

    @property
    def n_pages(self) -> int:
        return len(self.pages)

    @property
    def peak_used(self) -> int:
        return max((p.used_mib for p in self.pages), default=0)

    @property
    def peak_max(self) -> int:
        return max((p.max_mib for p in self.pages), default=0)

    @property
    def peak_pct(self) -> int:
        return max((p.used_pct for p in self.pages), default=0)

    @property
    def n_trail_lines(self) -> int:
        return getattr(self, "_n_lines", 0)


def phase_of(sample: TrailSample) -> str:
    return sample.phase.split()[0] if sample.phase else ""


def build_unit(report: Report) -> Unit | None:
    pages: list[Page] = []
    for i, sample in enumerate(report.trail):
        m = TURN_MS.match(sample.phase)
        if not m:
            continue
        pct = sample.used_mib * 100 // sample.max_mib if sample.max_mib > 0 else 0
        pages.append(Page(int(m.group(1)), sample.used_mib, sample.max_mib, sample.native_mib, pct, i))
    if not pages:
        return None
    last = report.trail[-1]
    last_phase = phase_of(last)
    conflict = bool(getattr(report, "no_close_marker", False) and last_phase != "turn")
    unit = Unit(
        key=report.path or report.signature,
        device=report.device,
        version=report.version,
        stamp=report.stamp,
        kind=report.kind,
        time=report.time,
        last_phase=last_phase,
        last_is_turn=last_phase == "turn",
        pages=pages,
        trail_conflict=conflict,
        body_flag=report.err_type,
    )
    unit._n_lines = len(report.trail)
    return unit


def _with_pages(unit: Unit, pages: list[Page]) -> Unit:
    """同一份报告的**页子集**（条件口径用）：簇身份保留，bootstrap 才仍认个体内相关。"""
    clone = copy.copy(unit)
    clone.pages = pages
    return clone


def water_band(pct: int) -> str:
    if pct >= WATER_TOP:
        return f">={WATER_TOP}%"
    if pct >= WATER_MID:
        return f"{WATER_MID}-{WATER_TOP}%"
    return f"<{WATER_MID}%"


# ----------------------------------------------------------------------
# 计算
# ----------------------------------------------------------------------


@dataclass
class Rate:
    hits: int
    total: int
    p_hat: float
    naive_wilson: tuple[float, float]
    cluster: tuple[float, float] | None = None
    cp: tuple[float, float] | None = None
    n_clusters: int = 0

    @property
    def label(self) -> str:
        if self.total == 0:
            return "0/0  n/a"
        base = f"{self.hits}/{self.total} = {self.p_hat * 100:.2f}%"
        parts = [f"Wilson(页级朴素){fmt_ci(*self.naive_wilson)}"]
        if self.cluster is not None:
            parts.append(f"cluster-bootstrap(主区间){fmt_ci(*self.cluster)}")
        if self.cp is not None:
            parts.append(f"Clopper-Pearson(报告级){fmt_ci(*self.cp)}")
        return base + "  " + " | ".join(parts)


def page_rate(clusters: list[list[bool]]) -> Rate:
    hits = sum(sum(1 for x in c if x) for c in clusters)
    total = sum(len(c) for c in clusters)
    p = hits / total if total else 0.0
    boot = cluster_bootstrap([(sum(1 for x in c if x), len(c)) for c in clusters], CONF, BOOT_REPS, BOOT_SEED)
    return Rate(hits, total, p, wilson_interval(hits, total), boot, None, len(clusters))


def report_rate(clusters: list[list[bool]], threshold_note: bool = True) -> Rate:
    hits = sum(1 for c in clusters if any(c))
    total = len(clusters)
    p = hits / total if total else 0.0
    return Rate(hits, total, p, wilson_interval(hits, total), None, clopper_pearson_interval(hits, total), total)


# ----------------------------------------------------------------------
# 渲染
# ----------------------------------------------------------------------


def per_report_rows(units: list[Unit], threshold: int) -> list[dict]:
    rows = []
    for u in sorted(units, key=lambda r: (-r.n_pages, r.device, r.time)):
        vals = sorted(p.dur_ms for p in u.pages)
        slow = [v for v in vals if v >= threshold]
        rows.append(
            {
                "device": u.device,
                "version": u.version,
                "build": u.stamp,
                "kind": u.kind,
                "time": u.time,
                "last_phase": u.last_phase,
                "last_is_turn": "yes" if u.last_is_turn else "no",
                "trail_lines": u.n_trail_lines,
                "peak_used_mib": u.peak_used,
                "max_mib": u.peak_max,
                "peak_used_pct": u.peak_pct,
                "n_turn_pages": u.n_pages,
                "p50_ms": nearest_rank(vals, 50),
                "p95_ms": nearest_rank(vals, 95),
                "max_ms": vals[-1] if vals else 0,
                "n_slow": len(slow),
                f"has_ge_{threshold}ms": "yes" if slow else "no",
                "trail_conflict": "yes" if u.trail_conflict else "no",
                "key": Path(u.key).name if u.key else "",
            }
        )
    return rows


def threshold_sensitivity(units: list[Unit], thresholds: list[int]) -> list[str]:
    lines = ["", f"-- 阈值敏感性（页级 p̂ 随判据怎么动；样本=乙口径全样本 {len(units)} 份） --"]
    for t in thresholds:
        cl = [[p.dur_ms >= t for p in u.pages] for u in units]
        rate = page_rate(cl)
        rrep = report_rate(cl)
        lines.append(f"  >= {t:>6}ms  页级 {rate.label}")
        lines.append(f"              报告级（至少出现一次） {rrep.hits}/{rrep.total} = {rrep.p_hat * 100:.2f}% "
                     f"Clopper-Pearson {fmt_ci(*rrep.cp)}")
    return lines


def water_strata(units: list[Unit], threshold: int) -> tuple[list[str], list[dict]]:
    """按**这一页自己那行**的 used/max 分层 —— 本车道最重要的一条。"""
    by_band: dict[str, list[list[bool]]] = defaultdict(list)
    csv_rows: list[dict] = []
    per_band_counts: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    for u in units:
        # 每份报告在每个层里只留它自己那部分页（簇结构保留，bootstrap 才认个体内相关）
        grouped: dict[str, list[bool]] = defaultdict(list)
        for p in u.pages:
            band = water_band(p.used_pct)
            grouped[band].append(p.dur_ms >= threshold)
            per_band_counts[band][0] += 1
            per_band_counts[band][1] += 1 if p.dur_ms >= threshold else 0
        for band, flags in grouped.items():
            by_band[band].append(flags)
    lines = ["", f"-- 水位分层（瞬时口径：这一页自己那行的 used*100//max） --"]
    order = [f">={WATER_TOP}%", f"{WATER_MID}-{WATER_TOP}%", f"<{WATER_MID}%"]
    for band in order:
        cl = by_band.get(band, [])
        if not cl:
            lines.append(f"  {band:>8}  样本 0 页 —— 无从判定")
            csv_rows.append({"stratum": band, "pages": 0, "slow_pages": 0, "reports": 0,
                             "p_hat": "", "wilson": "", "cluster": ""})
            continue
        rate = page_rate(cl)
        pages_n = sum(len(c) for c in cl)
        lines.append(f"  {band:>8}  {rate.label}")
        lines.append(f"             簇（份）{rate.n_clusters} · 页 {pages_n} · 慢页 {rate.hits}")
        csv_rows.append({"stratum": band, "pages": pages_n, "slow_pages": rate.hits,
                         "reports": rate.n_clusters, "p_hat": round(rate.p_hat, 6),
                         "wilson_lo": round(rate.naive_wilson[0], 6), "wilson_hi": round(rate.naive_wilson[1], 6),
                         "cluster_lo": round(rate.cluster[0], 6) if rate.cluster else "",
                         "cluster_hi": round(rate.cluster[1], 6) if rate.cluster else ""})
    slow_only_top = sum(
        sum(1 for f in c if f)
        for band, cl in by_band.items()
        if band != f">={WATER_TOP}%"
        for c in cl
    )
    total_slow = sum(v[1] for v in per_band_counts.values())
    lines.append(
        f"  判读：慢页共 {total_slow} 页，其中落在水位 <{WATER_TOP}% 的有 {slow_only_top} 页 ⇒ "
        f"「慢页只在堆贴顶时出现」{'成立（本样本内一页都不在低水位）' if slow_only_top == 0 else '不成立（低水位也有慢页，见上）'}。"
    )
    lines.append(
        "  各层页数/慢页数： " + " · ".join(f"{b}: {per_band_counts[b][1]}/{per_band_counts[b][0]}" for b in order)
    )
    return lines, csv_rows


def peak_water_strata(units: list[Unit], threshold: int) -> list[str]:
    """对照口径：按**这一局到过的峰值水位**分层（报告级）。"""
    by_band: dict[str, list[list[bool]]] = defaultdict(list)
    for u in units:
        by_band[water_band(u.peak_pct)].append([p.dur_ms >= threshold for p in u.pages])
    lines = ["", f"-- 水位分层（对照口径：每份报告的峰值 used/max） --"]
    for band in [f">={WATER_TOP}%", f"{WATER_MID}-{WATER_TOP}%", f"<{WATER_MID}%"]:
        cl = by_band.get(band, [])
        if not cl:
            lines.append(f"  {band:>8}  样本 0 份")
            continue
        rate = page_rate(cl)
        rep = report_rate(cl)
        lines.append(f"  {band:>8}  份数 {len(cl)} · 页 {sum(len(c) for c in cl)} · 页级 {rate.label}")
        lines.append(f"             报告级 {rep.hits}/{rep.total} = {rep.p_hat * 100:.2f}% CP {fmt_ci(*rep.cp)}")
    return lines


def device_version_strata(units: list[Unit], threshold: int) -> tuple[list[str], list[dict], list[str], list[dict]]:
    out: list[str] = []
    csv_rows: list[dict] = []
    for title, keyf in (("按机型", lambda u: u.device or "(未知机型)"), ("按版本", lambda u: u.version or "(未知版本)")):
        groups: dict[str, list[Unit]] = defaultdict(list)
        for u in units:
            groups[keyf(u)].append(u)
        lines = ["", f"-- {title}分层（页级 p̂ + 报告级命中份数；样本小就如实说小） --"]
        rows = []
        for name, group in sorted(groups.items(), key=lambda it: (-sum(len(u.pages) for u in it[1]), it[0])):
            cl = [[p.dur_ms >= threshold for p in u.pages] for u in group]
            rate = page_rate(cl) if len(cl) > 1 else None
            rep = report_rate(cl)
            pages_n = sum(len(c) for c in cl)
            slow_n = sum(sum(1 for f in c if f) for c in cl)
            if rate is None:
                est = f"簇只有 1 份 ⇒ 区间不可估（页级点值 {slow_n}/{pages_n}）"
            else:
                est = f"页级 {rate.label}"
            lines.append(f"  {name:<24} 份 {len(group):>2} 页 {pages_n:>3} 慢 {slow_n:>2} · {est}")
            rows.append({
                "stratum": name, "reports": len(group), "pages": pages_n, "slow_pages": slow_n,
                "p_hat": round(slow_n / pages_n, 6) if pages_n else "",
                "report_level_hits": rep.hits,
                "cp_lo": round(rep.cp[0], 6) if rep.cp else "",
                "cp_hi": round(rep.cp[1], 6) if rep.cp else "",
            })
        for r in rows:
            r["dataset"] = title
        lines.append("  样本提示：单组份数 <5 时区间只是装饰，别拿来下机型结论。")
        out.extend(lines)
        csv_rows.extend(rows)
    return out, csv_rows


def back_solve(rate: Rate, threshold: int, extra: list[tuple[str, Rate]] | None = None) -> list[str]:
    lines = ["", f"-- 反推：桌面浸泡臂跑多少步才有 >=95% 概率命中一次 >= {threshold}ms 的慢页 --"]
    lines.append("   n = ln(0.05) / ln(1 - p̂)（页=步；用页级 p̂；双侧区间取 cluster bootstrap）")
    for tag, p in (
        ("点值 p̂", rate.p_hat),
        ("双侧下界(保守)", rate.cluster[0] if rate.cluster else rate.naive_wilson[0]),
        ("单侧 95% 下界(预算用)", cp_lower_one_sided(rate.hits, rate.total)),
        ("双侧上界(乐观)", rate.cluster[1] if rate.cluster else rate.naive_wilson[1]),
    ):
        if p <= 0:
            lines.append(f"  {tag:<26} p̂=0 ⇒ 步数无穷（这套样本给不出有限预算）")
            continue
        need = steps_for(p)
        lines.append(f"  {tag:<26} p̂={p * 100:.2f}%  ⇒  n = {math.ceil(need):>6,} 步")
    lines.append(
        "   （双侧 cluster 下界会是 0：15 簇里 12 簇零慢页，整簇重抽能把慢簇全抽走 ⇒ 这是承认"
        "个体内相关的诚实代价，不是 bug；预算改报单侧 95% 下界那一行。）"
    )
    for name, sub in extra or []:
        lines.append(f"  条件口径「{name}」：页级 {sub.label}")
        for tag, p in (("  点值", sub.p_hat),
                       ("  双侧下界", sub.cluster[0] if sub.cluster else 0.0),
                       ("  单侧 95% 下界", cp_lower_one_sided(sub.hits, sub.total)),
                       ("  双侧上界", sub.cluster[1] if sub.cluster else 0.0)):
            if p <= 0:
                lines.append(f"    {tag:<12} p̂=0 ⇒ 步数无穷")
            else:
                lines.append(f"    {tag:<12} p̂={p * 100:.2f}% ⇒ n = {math.ceil(steps_for(p)):>6,} 步")
    return lines


def soak_cross_check(b_units: list[Unit], threshold: int, soak_steps: int) -> list[str]:
    """和桌面 3g 臂（n=369 连续流零慢页）对表：本表的 p̂ 能不能解释那个零。

    桌面那 369 页的堆水位（live≈677 MiB / 挡位 3072 MiB ≈ 22%）落在本表的**低水位层**，
    所以正确的对照是「<70% 层的上界」而不是合成 p̂ —— 这一条决定「缺的是机制还是样本量」。
    """
    low = [p for u in b_units for p in u.pages if p.used_pct < WATER_MID]
    hits = sum(1 for p in low if p.dur_ms >= threshold)
    rate = wilson_interval(hits, len(low))
    cp = clopper_pearson_interval(hits, len(low))
    lines = ["", f"-- 与桌面浸泡臂（n={soak_steps} 页、零慢页、水位 ~22%）的相容性检验 --"]
    lines.append(
        f"  本表 <{WATER_MID}% 水位层：{hits}/{len(low)} 页慢页，95% 上界 Wilson {rate[1] * 100:.2f}% / "
        f"Clopper-Pearson {cp[1] * 100:.2f}%"
    )
    for name, p in (("层点值", hits / len(low) if low else 0.0), ("层 Wilson 上界", rate[1]), ("层 CP 上界", cp[1])):
        prob0 = (1.0 - p) ** soak_steps
        lines.append(f"  若桌面 {soak_steps} 页真按 {name} p={p * 100:.2f}% 抽样 ⇒ P({soak_steps} 页全快) = {prob0:.2e}")
    synth = page_rate([[p.dur_ms >= threshold for p in u.pages] for u in b_units])
    lines.append(
        f"  合成 p̂={synth.p_hat * 100:.2f}% 下 P({soak_steps} 页零慢页) = {(1 - synth.p_hat) ** soak_steps:.2e}"
        f"；连低水位层的 CP 上界 {(cp[1]) * 100:.2f}% 下也是 {(1 - cp[1]) ** soak_steps:.2e} "
        "⇒ 桌面那个「零」**用真机任何一层都解释不了**（不是样本量不够，是桌面那 369 页根本不在真机会慢的那些页上）。"
        "结论倾向「缺机制」：桌面臂要先把堆压进真机的贴顶/GC thrash 相位，再谈命中概率。"
    )
    return lines


def conclusions(b_units: list[Unit], rate_b: Rate, rep_b: Rate, pressure: Rate, threshold: int) -> list[str]:
    lines = ["", "-- 结论（装置车道引用这几句，逐句带分母） --"]
    lines.append(
        f"  1. 页级 p̂(单页 >= {threshold}ms) = {rate_b.hits}/{rate_b.total} = {rate_b.p_hat * 100:.2f}%"
        f"（主区间 cluster-bootstrap {fmt_ci(*(rate_b.cluster or (0.0, 0.0)))}；Wilson 朴素 {fmt_ci(*rate_b.naive_wilson)}；"
        f"单侧 95% 下界 {cp_lower_one_sided(rate_b.hits, rate_b.total) * 100:.2f}%）。"
    )
    lines.append(
        f"  2. 报告级（每份一个 max，命中=这一局至少一次）= {rep_b.hits}/{rep_b.total} = "
        f"{rep_b.p_hat * 100:.2f}%，Clopper-Pearson {fmt_ci(*rep_b.cp)} —— 「十局里约二成见过慢页」。"
    )
    lines.append(
        f"  3. 条件口径（只看水位 >= {WATER_MID}% 的页）= {pressure.hits}/{pressure.total} = "
        f"{pressure.p_hat * 100:.2f}%，cluster-bootstrap {fmt_ci(*(pressure.cluster or (0.0, 0.0)))} —— "
        "这一条是装置臂真能对齐的数：先让堆进 70%+ 相位，再按这个 p̂ 估步数。"
    )
    low_pages = [p for u in b_units for p in u.pages if p.used_pct < WATER_MID]
    low_hits = sum(1 for p in low_pages if p.dur_ms >= threshold)
    top_pages = [p for u in b_units for p in u.pages if p.used_pct >= WATER_TOP]
    mid_pages = [p for u in b_units for p in u.pages if WATER_MID <= p.used_pct < WATER_TOP]
    ceiling_but_fast = [
        u for u in b_units
        if u.peak_pct >= WATER_TOP and not any(p.dur_ms >= threshold for p in u.pages)
    ]
    lines.append(
        f"  4. 慢页在 <{WATER_MID}% 水位层是 {low_hits}/{len(low_pages)} 页；而 {WATER_MID}-{WATER_TOP}% 层"
        f"（{sum(1 for p in mid_pages if p.dur_ms >= threshold)}/{len(mid_pages)}）与 >= {WATER_TOP}% 层"
        f"（{sum(1 for p in top_pages if p.dur_ms >= threshold)}/{len(top_pages)}）的 p̂ 几乎相等"
        f" ⇒ 「只在贴顶（>= {WATER_TOP}%）才慢」不成立，**门槛在 {WATER_MID}% 左右而不是 {WATER_TOP}%**。"
        f"但贴顶也不必然慢：峰值 >= {WATER_TOP}% 却零慢页的局有 {len(ceiling_but_fast)}/{len(b_units)} 份。"
    )
    lines.append(
        f"  5. 建议引用：**页级 p̂ + cluster 区间**（装置跑的是步/页）。保守预算按单侧 95% 下界报，"
        "乐观按双侧上界报；机型分层只有 1-3 份/组，不足以判「低端机更容易」，别引。"
    )
    return lines


def render(units_cur: list[Unit], units_fp: list[Unit], excluded: list[tuple[str, str, int]],
           threshold: int, in_files: int, soak_steps: int = 369) -> tuple[str, dict[str, list[dict]]]:
    bar = "=" * 78
    lines = [bar, f"真机慢页发生率 p̂（判据：单页 >= {threshold}ms）", bar]
    lines.append(f"输入 .txt {in_files} 份 → 去重/分桶后带轨迹的可用份数如下（分桶口径同 triage_reports.py）。")
    for tag, why, count in excluded:
        lines.append(f"  排除 {tag:<28} {count:>3} 份 —— {why}")
    lines.append(f"  纳入 当前口径且有 >=1 页轨迹      {len(units_cur):>3} 份（页合计 {sum(u.n_pages for u in units_cur)}）")
    lines.append(f"  单列 0.2.31 脏退出误报            {len(units_fp):>3} 份（只给数，不进 p̂）")

    # 末行失真
    conflict = [u for u in units_cur if u.trail_conflict]
    last_phase = Counter(u.last_phase for u in units_cur)
    lines += ["", "-- 末行 phase（甲口径的人群划分；fix-trailtear 之前的落盘有撕裂窗口 ⇒ 不可当逐字真值） --"]
    for ph, count in last_phase.most_common():
        lines.append(f"  {count:>3}  末行 {ph}")
    lines.append(f"  撕裂冲突信号（正文『收尾标记没被写下』但末行非 turn）：{len(conflict)}/{len(units_cur)} 份 ⇒ "
                 f"甲口径的分母本身带 ±{len(conflict)} 份的不确定性；乙口径与页级计数不受影响。")

    a_units = [u for u in units_cur if u.last_is_turn]
    b_units = units_cur

    datasets: dict[str, list[dict]] = {}
    datasets["per_report"] = per_report_rows(units_cur, threshold)

    cl_b = [[p.dur_ms >= threshold for p in u.pages] for u in b_units]
    cl_a = [[p.dur_ms >= threshold for p in u.pages] for u in a_units]

    lines += ["", "-- p̂(>=阈值)：两套人群口径 × 两种样本单位 --"]
    rate_b = page_rate(cl_b) if cl_b else Rate(0, 0, 0, (0, 0), None, None, 0)
    lines.append(f"  乙（全样本，不看末行）· 页级   {rate_b.label}")
    rep_b = report_rate(cl_b)
    lines.append(f"  乙（全样本，不看末行）· 报告级 {rep_b.label}")
    if cl_a:
        rate_a = page_rate(cl_a)
        lines.append(f"  甲（末行=turn，死在屏前）· 页级 {rate_a.label}  （份 {len(a_units)} / 页 {sum(len(c) for c in cl_a)}）")
        rep_a = report_rate(cl_a)
        lines.append(f"  甲（末行=turn，死在屏前）· 报告级 {rep_a.label}")
    else:
        rate_a = rate_b
        lines.append("  甲（末行=turn）：样本 0 份（历史报告末行全是 pause/boot/trim 或撕裂）")
    lines.append("  差异说明：甲只留「玩家盯着卡住的画面把应用划掉」的份；乙把所有带轨迹的当前口径都算进来。")
    lines.append("            甲的样本单位依赖末行（撕裂会改末行是谁），乙不依赖 ⇒ 主数取乙口径的页级 p̂。")

    if units_fp:
        cl_fp = [[p.dur_ms >= threshold for p in u.pages] for u in units_fp]
        r = page_rate(cl_fp)
        lines += ["", "-- 0.2.31 脏退出误报那批（单独一列，绝不混进 p̂） --", f"  页级 {r.label}"]

    lines += threshold_sensitivity(b_units, [2000, 3000, threshold, 10000])
    wl, wcsv = water_strata(b_units, threshold)
    lines += wl
    datasets["strata_water_page"] = wcsv
    lines += peak_water_strata(b_units, threshold)
    dv, dvcsv = device_version_strata(b_units, threshold)
    lines += dv
    datasets["strata_device_version"] = dvcsv

    # 慢页现场（逐条列出，便于对着 C#/轨迹复核）
    slow_pages = [(u, p) for u in b_units for p in u.pages if p.dur_ms >= threshold]
    # 条件口径：只看「已经进了堆压相位」的那些页（used/max >= 70%）—— 装置臂真正能对齐的就是这一层
    pressure_units = [
        _with_pages(u, [p for p in u.pages if p.used_pct >= WATER_MID]) for u in b_units
    ]
    pressure_units = [u for u in pressure_units if u.n_pages]
    rate_pressure = page_rate([[p.dur_ms >= threshold for p in u.pages] for u in pressure_units])
    lines += ["", f"-- 条件口径 p̂（只看水位 >= {WATER_MID}% 的页：装置臂真能对齐的那一层） --"]
    lines.append(
        f"  份 {len(pressure_units)} · 页 {rate_pressure.total} · 慢页 {rate_pressure.hits} · {rate_pressure.label}"
    )
    conditionals = [(f"水位 >= {WATER_MID}% 的页", rate_pressure)]

    lines += back_solve(rate_b, threshold, extra=conditionals)
    if cl_a:
        lines += ["  （甲口径同式对照）"]
        for tag, p in (("甲 点值", rate_a.p_hat),
                       ("甲 双侧下界", rate_a.cluster[0] if rate_a.cluster else 0.0),
                       ("甲 单侧 95% 下界", cp_lower_one_sided(rate_a.hits, rate_a.total)),
                       ("甲 双侧上界", rate_a.cluster[1] if rate_a.cluster else 0.0)):
            lines.append(f"    {tag:<16} p̂={p * 100:.2f}% ⇒ n = {'inf' if p <= 0 else format(math.ceil(steps_for(p)), ',d')}")

    # 集中度 + 截断：这两条决定「p̂ 能不能当总体率用」
    lines += ["", "-- 样本结构的两条硬话（读数前先看完） --"]
    dist: Counter[str] = Counter()
    for u, _ in slow_pages:
        dist[f"{u.device} / {u.version} / {u.stamp}"] += 1
    for name, count in dist.most_common():
        lines.append(f"  慢页来源 {count:>2}/{len(slow_pages)} 页  ←  {name}")
    if slow_pages:
        top = dist.most_common(1)[0][1]
        lines.append(
            f"  集中度：单份会话最多贡献 {top}/{len(slow_pages)} 页（{top / len(slow_pages) * 100:.0f}%）"
            "⇒ p̂ 的**点值**被这一两局撑着，别当机型常数引用；"
            f"报告级 {rep_b.hits}/{rep_b.total} 那条（{rep_b.p_hat * 100:.0f}% 的局见过至少一次）才是稳健读数。"
        )
    capped = sum(1 for u in b_units if u.n_trail_lines >= TRAIL_CAP_LINES)
    lines.append(
        f"  截断偏差：{capped}/{len(b_units)} 份的轨迹是满档（>= {TRAIL_CAP_LINES} 行）⇒ 观测到的页"
        "只是**死亡前那一段**（落盘环形表只留最后若干行），不是整局随机抽页。堆随会话时长单调爬，"
        "这段窗口的天然水位比「一整局里随机一页」高 ⇒ 本 p̂ 对「随机页」是**上偏**的，"
        "对「浸泡臂真跑进堆压相位后的页」才可比。装置车道引用时要连这句一起引用。"
    )

    lines += ["", f"-- 慢页逐条（>= {threshold}ms，共 {len(slow_pages)} 页） --"]
    for u, p in sorted(slow_pages, key=lambda it: -it[1].dur_ms)[:40]:
        lines.append(
            f"  {u.time} {u.device} | {u.version} {u.stamp} | 第 {p.line_no + 1}/{u.n_trail_lines} 行 "
            f"turn {p.dur_ms}ms used={p.used_mib}/{p.max_mib}MiB ({p.used_pct}%) native={p.native_mib}MiB "
            f"末行={u.last_phase}"
        )
    if len(slow_pages) > 40:
        lines.append(f"  …（其余 {len(slow_pages) - 40} 页见 CSV）")

    # 慢页水位画像
    if slow_pages:
        pct_slow = sorted(p.used_pct for _, p in slow_pages)
        pct_all = sorted(p.used_pct for u in b_units for p in u.pages)
        lines += [
            "",
            "-- 慢页 vs 全部页的水位画像（正面回答「是不是只在贴顶才慢」） --",
            f"  慢页 used/max:  min {pct_slow[0]}%  p50 {nearest_rank(pct_slow, 50)}%  max {pct_slow[-1]}%  "
            f"贴顶(>= {WATER_TOP}%) 占比 {sum(1 for x in pct_slow if x >= WATER_TOP)}/{len(pct_slow)}",
            f"  全部页 used/max: p50 {nearest_rank(pct_all, 50)}%  贴顶占比 "
            f"{sum(1 for x in pct_all if x >= WATER_TOP)}/{len(pct_all)}",
        ]
    lines += soak_cross_check(b_units, threshold, soak_steps)
    lines += conclusions(b_units, rate_b, rep_b, rate_pressure, threshold)
    lines += [bar, "读数结束（分母/区间随每条 p̂ 给出；无区间者已注明）", ""]
    return "\n".join(lines), datasets


def write_csv(path: Path, rows: list[dict], key_order: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("(无行)\n", encoding="utf-8")
        return
    cols = key_order or list(rows[0].keys())
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


# ----------------------------------------------------------------------
# selftest（合成报告钉口径）
# ----------------------------------------------------------------------


def _fake(kind: str, version: str, device: str, trail: tuple[str, ...], time: str,
          stamp: str = "abc1234", no_close: bool = False) -> str:
    lines = [
        "================================================================",
        "eraTW 安卓移植版 错误报告",
        "================================================================",
        f"报告类型：{kind}",
        f"报告时间：{time}",
        "----------------------------------------------------------------",
        "[版本与设备]",
        f"app 版本：{version} (versionCode 9)",
        f"设备：{device}",
        "----------------------------------------------------------------",
        "[构建与产物标识]",
        f"构建标识：{stamp}",
        "----------------------------------------------------------------",
        "[错误]",
        "类型：（无异常对象，见补充现场）",
        "----------------------------------------------------------------",
        "[补充现场]",
    ]
    if no_close:
        lines.append(NO_CLOSE_MARK)
    lines.append("运行轨迹（旧在前，最多 24 行）：")
    lines.extend(trail)
    lines.append("----------------------------------------------------------------")
    lines.append("报告结束")
    return "\n".join(lines) + "\n"


def _t(ms: int, used: int, mx: int = 512, native: int = 140, tail: str = "") -> str:
    sep = " " if tail else ""
    return f"t={ms}ms used={used}MiB max={mx}MiB native={native}MiB turn {ms}ms{sep}{tail}"


def selftest() -> int:
    checks = 0

    def expect(cond: bool, label: str) -> None:
        nonlocal checks
        if not cond:
            raise AssertionError(f"selftest 失败：{label}")
        checks += 1

    expect(nearest_rank([1, 2, 3, 4], 50) == 2, "最近秩 p50（偶数取第 ceil(0.5n) 个）")
    expect(nearest_rank([1, 2, 3, 4], 95) == 4, "最近秩 p95")
    expect(nearest_rank([], 95) == 0, "空集合分位")
    lo, hi = wilson_interval(0, 100)
    expect(lo < 1e-3 and 0.0 < hi < 0.05, "Wilson 零命中给 (≈0, >0)")
    lo, hi = clopper_pearson_interval(0, 369)
    expect(lo == 0.0 and abs(hi - (1 - 0.025 ** (1 / 369))) < 1e-6, "CP 零命中上界 = 1-(alpha/2)^(1/n)")
    lo, hi = clopper_pearson_interval(3, 10)
    expect(0.05 < lo < 0.3 and 0.3 < hi < 0.9, "CP 常规区间落在合理带")
    expect(_betai(1.0, 1.0, 0.37) == 0.37, "I_x(1,1)=x")
    expect(cp_lower_one_sided(11, 128) > 0.03 and cp_lower_one_sided(11, 128) < 0.06, "单侧 95% 下界比双侧宽松")
    expect(cp_lower_one_sided(0, 50) == 0.0, "零命中没有单侧下界（如实给 0）")
    expect(steps_for(0.03) > 90 and steps_for(0.03) < 110, "p=3% ⇒ 约 99 步到 95%")
    expect(math.isinf(steps_for(0.0)), "p=0 ⇒ 步数无穷")

    expect(water_band(90) == f">={WATER_TOP}%", "90% 归贴顶（>= 取到边界）")
    expect(water_band(89) == f"{WATER_MID}-{WATER_TOP}%", "89% 归中间带")
    expect(water_band(69) == f"<{WATER_MID}%", "69% 归低带")

    corpus = [
        # 当前口径：末行 turn（死在屏前）+ 两个慢页，都在贴顶水位
        _fake("上次异常退出", "0.2.34", "OPPO PLD110",
              (_t(2342, 505), _t(15192, 511), _t(58515, 511)), "2026-10-31 10:00:00", no_close=True),
        # 当前口径：末行 pause（后台回收），没有慢页，水位低
        _fake("上次异常退出", "0.2.32", "samsung SM-G9860",
              (_t(12, 120, 576), _t(9, 130, 576), "t=900ms used=131MiB max=576MiB native=18MiB pause"),
              "2026-10-31 10:01:00"),
        # 同一份被重复 POST（正文一致）⇒ 必须被合并
        _fake("上次异常退出", "0.2.32", "samsung SM-G9860",
              (_t(12, 120, 576), _t(9, 130, 576), "t=900ms used=131MiB max=576MiB native=18MiB pause"),
              "2026-10-31 10:01:00"),
        # 0.2.31 脏退出误报 ⇒ 单独一列，不进 p̂
        _fake("上次异常退出", "0.2.31", "vivo V2199A",
              (_t(7000, 500), "t=9ms used=394MiB max=512MiB native=118MiB trim=uiHidden freed=0MiB"),
              "2026-10-30 23:29:52"),
        # 旧版 ⇒ 排除
        _fake("启动失败", "0.2.3", "Xiaomi 25113PN0EC", (_t(8000, 500),), "2026-10-30 17:12:29"),
    ]
    reports = [parse_text(t) for t in corpus]
    scan = analyze(reports)
    expect(scan.duplicates == 1, "重复 POST 合并")
    expect(len(scan.legacy) == 1, "0.2.3 单列")
    expect(len(scan.false_positive) == 1, "0.2.31 误报单列")

    units = [u for u in (build_unit(r) for r in scan.current) if u is not None]
    fp_units = [u for u in (build_unit(r) for r in scan.false_positive) if u is not None]
    expect(len(units) == 2, "当前口径里带轨迹 2 份")
    expect(units[0].last_is_turn and not units[1].last_is_turn, "末行 turn/pause 分得开")
    expect(units[0].trail_conflict is False, "末行 turn 时不算撕裂冲突")
    expect(fp_units[0].n_pages == 1, "误报桶的页不进 p̂ 样本")

    cl = [[p.dur_ms >= 5000 for p in u.pages] for u in units]
    rate = page_rate(cl)
    expect(rate.hits == 2 and rate.total == 5, "页级分母=全部 turn 页（含非慢页）")
    expect(rate.cluster is not None, "页级给 cluster bootstrap 区间")
    rep = report_rate(cl)
    expect(rep.hits == 1 and rep.total == 2, "报告级=每份一个 max 再数命中")
    expect(rep.cp is not None and rep.cluster is None, "报告级用 Clopper-Pearson，不再叠 bootstrap")

    # 条件口径：页子集保留簇身份（同一份报告只留贴顶那些页）
    pressure = [u for u in (_with_pages(x, [p for p in x.pages if p.used_pct >= WATER_MID]) for x in units) if u.n_pages]
    expect(len(pressure) == 1 and pressure[0].n_pages == 3, "条件口径只留高水位页且仍是一簇")
    expect(pressure[0].key == units[0].key, "簇身份（哪一份报告）不变")
    expect(water_band(72) == f"{WATER_MID}-{WATER_TOP}%", "72% 归 70-90 带（HONOR 那四页的落点）")

    # 撕裂冲突：正文说没写收尾标记、末行却是 pause ⇒ 计数要带不确定性
    conflict_corpus = [
        _fake("上次异常退出", "0.2.32", "realme RMX3800",
              (_t(100, 460), "t=200ms used=461MiB max=512MiB native=139MiB pause"),
              "2026-10-31 11:51:12", no_close=True),
    ]
    cu = build_unit(parse_text(conflict_corpus[0]))
    expect(cu.trail_conflict is True, "撕裂冲突信号能抓到")
    expect(cu.last_phase == "pause" and cu.last_is_turn is False, "冲突份仍按末行归类")

    # 水位分层：慢页只在贴顶
    text = render(units, fp_units, [("旧版 0.2.0/0.2.3", "问题早已修复", len(scan.legacy))],
                  DEFAULT_THRESHOLD_MS, len(corpus))[0]
    expect("水位分层" in text, "输出带水位分层")
    expect("末行 phase" in text, "输出带末行 phase 一节")
    expect("撕裂冲突信号" in text, "输出带末行失真计数")
    expect("反推" in text, "输出带步数反推")
    expect("相容性检验" in text, "输出带桌面零命中的相容性检验")
    expect("结论（装置车道引用这几句" in text, "输出带结论节")
    expect("条件口径" in text, "输出带条件（高水位）口径")
    expect("0.2.31 脏退出误报" in text, "误报单列仍出现在输出里")
    print(f"selftest ok ({checks} checks)")
    return 0


def parse_text(text: str, path: str = "") -> Report:
    """复用 triage_reports 的解析，另把「正文说没写收尾标记」这个撕裂信号挂在对象上。"""
    from triage_reports import parse_report

    report = parse_report(text, path)
    report.no_close_marker = NO_CLOSE_MARK in text  # type: ignore[attr-defined]
    return report


# ----------------------------------------------------------------------
# 装载
# ----------------------------------------------------------------------


def load_inbox(directory: Path) -> tuple[list[Report], int]:
    """优先读 `<dir>/raw/*.txt`（拉全的原文），退化到 `<dir>/**/*.txt`。"""
    raw = directory / "raw"
    target = raw if raw.is_dir() else directory
    files = sorted(p for p in target.rglob("*.txt") if p.name != "triage.txt")
    reports = [r for r in (load_report(p) for p in files) if r is not None]
    return reports, len(files)


def load_report(path: Path) -> Report | None:
    text = path.read_text(encoding="utf-8", errors="replace")
    if "eraTW 安卓移植版" not in text:
        return None
    return parse_text(text, str(path))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="真机慢页发生率 p̂（只读收件箱，产出数表）")
    parser.add_argument("--dir", type=Path, default=Path(".tmp/inbox"), help="收件箱目录（读 raw/*.txt）")
    parser.add_argument("--out", type=Path, default=Path(".tmp/inbox/slowpage"), help="数表输出目录（写）")
    parser.add_argument("--threshold", type=int, default=DEFAULT_THRESHOLD_MS, help="慢页判据（ms）")
    parser.add_argument("--selftest", action="store_true", help="用合成报告自检统计口径")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    reports, in_files = load_inbox(args.dir)
    if not reports:
        raise SystemExit(f"{args.dir} 里没有可读报告（期望 raw/*.txt）")
    scan = analyze(reports)

    units = [u for u in (build_unit(r) for r in scan.current) if u is not None]
    fp_units = [u for u in (build_unit(r) for r in scan.false_positive) if u is not None]
    no_trail = sum(1 for r in scan.current if not any(TURN_MS.match(s.phase) for s in r.trail))
    excluded = [
        ("重复 POST（已合并）", "端点时间戳不同、正文相同", scan.duplicates),
        ("无报告类型（端点探针/截断）", "不能算事故", len(scan.ignored)),
        (f"旧版 {'/'.join(sorted(LEGACY_VERSIONS))}", "问题早已修复，不往新包头上算", len(scan.legacy)),
        ("0.2.31 脏退出误报", "按 Home 被回收被误认成异常退出，单独一列", len(scan.false_positive)),
        ("当前口径但无 turn 页", "启动失败/崩溃类多半没带轨迹", no_trail),
    ]

    text, datasets = render(units, fp_units, excluded, args.threshold, in_files)

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "slowpage.txt").write_text(text, encoding="utf-8")
    write_csv(args.out / "per_report.csv", datasets["per_report"])
    write_csv(args.out / "strata_water_page.csv", datasets["strata_water_page"])
    write_csv(args.out / "strata_device_version.csv", datasets["strata_device_version"])
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(text)
    print(f"\n[已写出] {args.out}/slowpage.txt · per_report.csv · strata_water_page.csv · strata_device_version.csv",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
