#!/usr/bin/env python3
"""上报收件箱分桶：构建戳 × 机型 × 异常类型。

为什么要有这把尺子：端点上的报告混着三个年代的包（0.2.0 / 0.2.3 的旧包、0.2.31 的
「脏退出误报卡」、以及当前包），还混着同一份报告被重复 POST 的副本。直接总数会同时
高估当前包的失败率、并让旧问题（早已修掉的 XML_GET / RAND / 设置页初始化）反复出现在
「新包又坏了」的错觉里。本工具按下面的口径分桶，输出可直接贴进汇报：

1. **去重**：同一份报告被重复上传（端点侧时间戳不同、正文完全相同）只算一条；
2. **旧版单列**：0.2.0 / 0.2.3 不计入当前口径；
3. **脏退出误报单列**：0.2.31 的「上次异常退出」是「按 Home 被系统回收」被误认成
   异常退出（background 态在 0.2.32 才补上），属于已知误报，单独一桶；
4. **当前口径**：其余（0.2.31 / 0.2.32 及更新）按 构建戳 × 机型 × 报告类型 × 异常类型分桶，
   并把新增的 OutOfMemoryError / CodeEE 逐条列出来（带设备与轨迹末行）；
5. **静默退出判读**：对当前口径的「上次异常退出」按 App 内 `RunDiagnostics` 同一套阈值
   （Java 堆 ≥ 85% / native ≥ 150 MiB / 系统可用内存跌破本机低内存线）判成四桶，
   回答「没有报告的那一类到底死在哪」。其中「系统内存不足」一桶只在轨迹带 `sys=` / `lmk=`
   两列（0.2.33 起）时判得出，旧轨迹会如实计入未判读行数，不拿绝对 MiB 硬猜。

2026-11-04 inbox 波补的四处瞎区（本轮 617 份是人肉去重出来的，成本记在这里）：

6. **内容指纹**：与端点 `tools/report-endpoint/worker.js` 同一套 sha256 口径（归一掉 `报告时间：`
   整行；正文没有「身份」—— 信息 / 脚本位置 / 轨迹行三者全无 —— 时把时间原值加回哈希），
   两侧用 `tools/report-endpoint/fingerprint-fixtures.json` 对拍。本地目录去重与端点去重从此同一个数。
7. **`ver=?` 桶**：`app 版本` 取不到（最小退化报告没这行、或取数失败写成「（未知）」）单独一桶，
   不再被当成「某个版本的问题」；这些份仍计入当前口径，只是不许给它们派版本。
8. **退化报告不参与签名**：带「最小退化报告」标记且 `信息` 缺失的份进不了签名簇
   （它们只有类型行，归并出来的是「类型相同」不是「缺陷相同」），改由「无签名桶」按版本如实报数。
9. **同机连发 vs 跨机同签名**：同签名簇里按机型 + 报告时间切「事件」——同机短时间连发是玩家在
   反复试同一招（份数虚高），跨机同签名才是面上问题。按版本再给一句话结论：本版还剩几个真缺陷。

用法（Windows 控制台是 GBK，一律写文件再读）：

    python devtools/triage_reports.py --dir <本地报告目录> --out triage.txt
    python devtools/triage_reports.py --fetch --out triage.txt [--keep <原文目录>]
    python devtools/triage_reports.py --stats --out stats.txt   # 只出聚合表，一份正文都不拉
    python devtools/triage_reports.py --stats-in <端点回包.json> --out stats.txt
    python devtools/triage_reports.py --selftest

`--fetch` 逐份最多重试 3 次（网络抖动只跳过该份并计数，不再打断整轮）；`--keep` 目录
同时是断点续拉缓存，已存在且带完整报告头的文件不会重拉。同签名聚合最多列
[SIGNATURE_TOP] 簇（当前 31 簇全列），不再把低频签名藏在「其余 N 个略」里。
`--stats` 读端点 `GET /stats`（带去重版 worker 才有的只读端点）出「签名簇 × 版本 × 机型数」，
不必把 617 份全拉下来；端点没这个面（旧版部署）就如实报错并提示回 `--fetch`。

令牌只从 `eratw-android/local.report.properties`（gitignore）读，本工具**从不打印**它。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, OrderedDict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# 与 app 内 RunDiagnostics 完全同一套阈值（改一处必须改两处，selftest 会盯住）。
HEAP_CEILING_PERCENT = 85
NATIVE_HIGH_MIB = 150

# 已经不在当前口径里的旧包（问题早已修复，不能算到新包头上）。
LEGACY_VERSIONS = {"0.2.0", "0.2.3"}

# 单份报告拉取的尝试次数与退避（秒）。2026-11-04 实测：500 份拉到 391 份时一次
# `SSL: UNEXPECTED_EOF_WHILE_READING` 直接把整轮分桶打断 —— 只 catch HTTPError 的
# 写法会让一次网络抖动画成「后面 109 份不存在」。逐份重试、仍失败才跳过并计数。
FETCH_ATTEMPTS = 3
FETCH_RETRY_BACKOFF_SECONDS = 0.5

# 同签名聚合最多列多少簇。2026-11-04 全量 617 份时有 31 簇，原先的 20 会让
# 「其余 11 个签名簇略」恰好藏住低频新问题；50 足够覆盖当前量级，超出仍如实计数。
SIGNATURE_TOP = 50

# 内容指纹的归一口径：与 tools/report-endpoint/worker.js 逐字同源，改一侧必须改两侧，
# fingerprint-fixtures.json + selftest 会把不一致钉红。
TIME_LINE_PREFIX = "报告时间："
MESSAGE_LINE_PREFIX = "信息："
SCRIPT_LINE_PREFIX = "脚本位置："
DEGRADED_MARK = "最小退化报告"
NO_SIGNATURE_CODE = "nosig"
NO_VERSION_SLUG = "q"
NO_DEVICE_CODE = "00000000"
CODE_CHARS = 8
VERSION_SLUG_MAX = 24
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
TRAIL_LINE_HEAD = re.compile(r"t=\d+ms")
ERROR_SECTION_HEADER = "[错误]"
SEPARATOR_LINE = re.compile(r"-{10,}")
# 与端点 lbl/ 的签名同口径：数字抹 N 的消息 + 原样脚本位置，用单元分隔符拼。
SIGNATURE_JOIN = "\x1f"

# 「同机同签名短时间连发」的事件窗口（分钟）：窗口内的同机同签名报告算同一次尝试的重复上报，
# 窗口外的才是新事件。取 30 分钟的依据 —— 2026-11-04 收件箱里那 128 份同形报告是同一台
# vivo 在两个半小时里反复触发同一招（间隔 1-20 分钟不等），15 分钟会把它们切成好几「次事件」，
# 60 分钟又会把两段明显不同的尝试并成一次。
BURST_WINDOW_MINUTES = 30
# 报告时间的两种真实形状：`2026-11-04 19:37:05`，以及最小退化报告里连格式化都失败时的裸毫秒
# （app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:552）。
REPORT_TIME_FORMAT = "%Y-%m-%d %H:%M:%S"

# 与端点 worker 对拍的夹具（正文取自 2026-11-04 收件箱的真实形状，见文件内 note）。
FINGERPRINT_FIXTURES = (
    Path(__file__).resolve().parents[1] / "tools" / "report-endpoint" / "fingerprint-fixtures.json"
)

# 「按 Home 被回收」被误认成异常退出的那一版（background 态在 0.2.32 才补上）。
FALSE_POSITIVE_VERSION = "0.2.31"
FALSE_POSITIVE_KIND = "上次异常退出"

SECTION_SEPARATOR = re.compile(r"^\s*-{10,}\s*$", re.M)
TRAIL_LINE = re.compile(
    r"^t=(\d+)ms used=(\d+)MiB max=(\d+)MiB native=(\d+)MiB\s*(.*)$", re.M
)
FREED_MIB = re.compile(r"freed=(\d+)MiB")
# 0.2.33 起 App 侧 formatLine 在阶段名后追加的两个键值对（系统可用内存 / 本机低内存线）。
SYS_AVAIL_MIB = re.compile(r"sys=(\d+)MiB")
LMK_THRESHOLD_MIB = re.compile(r"lmk=(\d+)MiB")

# ----------------------------------------------------------------------
# EP1/EP2 波新增的五处读数面（字面逐字对齐产品代码，出处见每条注释）
#
# 这些形状 0.2.36 之后才开始进收件箱 ⇒ 现盘多半一条都没有；解析口径先建好，
# 下一轮复盘直接读得出。缺的形状如实计入 0 行 / 「缺」，不拿「没数据」冒充读数。
# ----------------------------------------------------------------------

# 1) 冻结哨兵轨迹行：app/.../StallSentinel.kt:322 StallJudgment.trailPhase ——
#    `stallstack <verdictKey> uiFrozen=<n>ms vm=<n>ms|未采到 frames=<n> ui@<栈顶>[ vm@<栈顶>]`
#    （TRAIL_PHASE "stallstack" :157；vm 距今取不到写 HeartbeatJudgment.NOT_MEASURED
#    「未采到」Heartbeat.kt:189；栈顶帧取不到写 StallJudgment.NOT_CAPTURED「未取到」:163）。
STALLSTACK_RE = re.compile(
    r"^stallstack (\S+) uiFrozen=(\d+)ms vm=(?:(\d+)ms|未采到) frames=(\d+)"
)
# 报告小节头（逐字 StallJudgment.SCENE_HEADER，StallSentinel.kt:160）；一帧文本形状
# 「简单类名.方法名:行号」（frameLine :246）——抓到栈的判据就是小节里有这么一行。
STALL_SCENE_HEADER_PREFIX = "[主线程冻结现场"
STALL_FRAME_RE = re.compile(r"[\w.$]+\.[\w$<>$]+:-?\d+")

# 2) Java 堆分项轨迹行：app/.../GameHost.kt:3080 censusTrailLine ——
#    `heapcensus used=<used>MiB/<max> ` + LiveSetCensus.render()（core/.../diag/LiveSetCensus.kt:163，
#    render 单行、以 `Java 堆结构账面（…） ｜装载：函数 …` 起头）。
#    注意这一行里有两个 `used=`：行首 RunWatch 前缀的 `used=NNNMiB`（无斜杠）与
#    heapcensus 段自己的 `used=NNNMiB/NNN`（斜杠、max 不带 MiB）——两种列，分开取，别拍平。
HEAPCENSUS_RE = re.compile(r"^heapcensus used=(\d+)MiB/(\d+)")
HEAP_CENSUS_REPORT_PREFIX = "Java 堆结构账面"  # 内存节那行分项（memoryCensusReportLine 的行首）

# 3) 报错节流计数行：app/.../ErrorReportCollector.kt:1387 ErrorReportThrottle.pinPhase ——
#    `<tag> x<count> <signature.display>`，tag ∈ {errdup, errcap}，display = `位置｜消息`
#    （displayOf :1482，分隔符是全角 `｜`；PHASE_MAX_CHARS 96 只是截断，不影响列取数）。
ERRX_RE = re.compile(r"^err(dup|cap) x(\d+)")

# 4) native 收口账面行：app/.../GameHost.kt:3563 NativeTrimPlan.readoutLine ——
#    ` bitmap=<b>-><a>MiB view=<c>-><d>MiB native=<e>-><f>MiB[ deep]`，
#    挂在 MainActivity.kt:811 的 `trim=<档名> freed=<n>MiB` 之后。
#    这里的 `native=e->fMiB`（箭头形）与行首 RunWatch 的 `native=NNNMiB`（单值）是两列，
#    必须用带 `->` 的箭头正则单取，否则会把两个 native 读数混成一个（契约「不许拍平」的靶子）。
BITMAP_ARROW_RE = re.compile(r"bitmap=(\d+)->(\d+)MiB")
VIEW_ARROW_RE = re.compile(r"view=(\d+)->(\d+)MiB")
NATIVE_ARROW_RE = re.compile(r"native=(\d+)->(\d+)MiB")
# 命中「新 trim 分项」的总判据：三列箭头里任一到即算带上了 readoutLine 那截（不会误收老 trim）。
ARROW_READOUT_RE = re.compile(r"(?:bitmap|view|native)=\d+->\d+MiB")
TRIM_DEEP_SUFFIX = " deep"


# ----------------------------------------------------------------------
# 内容指纹（与端点 tools/report-endpoint/worker.js 同一套口径）
# ----------------------------------------------------------------------


def _sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def split_lines(text: str) -> list[str]:
    """CRLF 与裸 CR 一律折成 LF，行尾空白去掉；别的都不动，两侧可复现。"""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return [line.rstrip(" \t") for line in normalized.split("\n")]


def line_field(lines: list[str], label: str) -> str:
    for line in lines:
        if line.startswith(label):
            return line[len(label):].strip()
    return ""


def error_section_lines(lines: list[str]) -> list[str]:
    """`[错误]` 节名行到下一条分隔线为止的**行**（与 _error_section 同边界，只是不返字符串）。"""
    start = None
    for index, line in enumerate(lines):
        if line.strip() == ERROR_SECTION_HEADER:
            start = index
            break
    if start is None:
        return []
    section: list[str] = []
    for line in lines[start + 1:]:
        if SEPARATOR_LINE.fullmatch(line):
            break
        section.append(line)
    return section


def normalize_message_text(message: str) -> str:
    # 消息里的数字抹成 N（口径同产品侧 ReportSignature.normalize：数据不是身份）；
    # 位置里的 `行N` 不抹（行号就是身份，见 ErrorReportCollector.kt:1421）。
    return re.sub(r"\d+", "N", message).strip()


def has_identity_lines(lines: list[str]) -> bool:
    """正文里有没有能区分两次事故的东西：轨迹行 / 信息 / 脚本位置，三者有其一。

    没「身份」的正文（连信息都没写出来的最小退化报告）两次不同时刻的上传必须各留一条，
    所以它的指纹把 `报告时间：` 原值加回哈希 —— 判不准就宁可留双份。
    """
    if any(TRAIL_LINE_HEAD.match(line) for line in lines):
        return True
    for line in error_section_lines(lines):
        for label in (MESSAGE_LINE_PREFIX, SCRIPT_LINE_PREFIX):
            if line.startswith(label) and len(line) > len(label):
                return True
    return False


def content_fingerprint(text: str) -> tuple[str, bool]:
    """正文 → (sha256 指纹, 有没有身份)。归一规则逐字对齐端点的 fingerprintOf。

    归一三件事：折行折成 LF、行尾空白去掉、`报告时间：` 整行去掉（没身份时再当盐加回）；
    末尾空行不进哈希 —— 正文带不带尾换行由上传路径决定，不是事故的一部分。
    """
    lines = split_lines(text)
    kept = [line for line in lines if not line.startswith(TIME_LINE_PREFIX)]
    while kept and kept[-1] == "":
        kept.pop()
    time_line = next((line for line in lines if line.startswith(TIME_LINE_PREFIX)), "")
    identity = has_identity_lines(lines)
    hash_input = "\n".join(kept) if identity else "\n".join(kept) + "\n" + time_line
    return _sha256_hex(hash_input), identity


def version_from_lines(lines: list[str]) -> str:
    """`app 版本：0.2.39 (versionCode 15)` → `0.2.39`；没有这行 → 空串（进 ver=? 桶）。"""
    raw = line_field(lines, "app 版本：")
    match = re.match(r"(\S+)\s*\(versionCode\s*(\d+)\)", raw)
    return match.group(1) if match else raw


def kind_code_of(kind: str) -> str:
    """报告类型 → 单字符码（口径同端点 kindCodeOf；o = 其余）。"""
    if not kind:
        return "n"
    if "崩溃" in kind:
        return "c"
    if "启动" in kind:
        return "s"
    if "脚本" in kind:
        return "r"
    if "异常退出" in kind:
        return "x"
    return "o"


def version_slug_of(version: str) -> str:
    if not version:
        return NO_VERSION_SLUG
    slug = re.sub(r"[^0-9A-Za-z.]", "_", version)[:VERSION_SLUG_MAX]
    return slug or NO_VERSION_SLUG


def report_signature_code(message: str, script: str) -> str:
    if not message and not script:
        return NO_SIGNATURE_CODE
    return _sha256_hex(f"{normalize_message_text(message)}{SIGNATURE_JOIN}{script.strip()}")[:CODE_CHARS]


def device_code_of(device: str) -> str:
    return _sha256_hex(device)[:CODE_CHARS] if device else NO_DEVICE_CODE


def parse_report_time(raw: str) -> datetime | None:
    """`报告时间：` 后面那一段 → datetime；两种真实形状都吃，取不到返回 None（不拿「没读到」当最早）。

    裸毫秒那一形来自最小退化报告：连 SimpleDateFormat 都 OOM 时写的是 `str(now)`
    （app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:552）。
    """
    value = (raw or "").strip()
    if not value:
        return None
    try:
        return datetime.strptime(value, REPORT_TIME_FORMAT)
    except ValueError:
        pass
    if value.isdigit():
        try:
            return datetime.fromtimestamp(int(value) / 1000)
        except (OverflowError, OSError, ValueError):
            return None
    return None


# ----------------------------------------------------------------------
# 解析
# ----------------------------------------------------------------------


@dataclass
class TrailSample:
    elapsed_ms: int
    used_mib: int
    max_mib: int
    native_mib: int
    phase: str
    # 旧轨迹行没有这两列（2026-10-31 才加）⇒ None 表示「这一行没读到」，不是 0。
    sys_avail_mib: int | None = None
    lmk_threshold_mib: int | None = None

    @property
    def used_percent(self) -> int:
        return self.used_mib * 100 // self.max_mib if self.max_mib > 0 else 0


def parse_trail_sample(match: re.Match[str]) -> TrailSample:
    """一行轨迹 → 样本；`sys=` / `lmk=` 从阶段名里摘出去，阶段名保持原样可比。"""
    phase = match.group(5).strip()
    sys_match = SYS_AVAIL_MIB.search(phase)
    lmk_match = LMK_THRESHOLD_MIB.search(phase)
    if sys_match or lmk_match:
        phase = SYS_AVAIL_MIB.sub("", phase)
        phase = LMK_THRESHOLD_MIB.sub("", phase).strip()
    return TrailSample(
        int(match.group(1)),
        int(match.group(2)),
        int(match.group(3)),
        int(match.group(4)),
        phase,
        int(sys_match.group(1)) if sys_match else None,
        int(lmk_match.group(1)) if lmk_match else None,
    )


@dataclass
class Report:
    path: str
    kind: str = ""
    time: str = ""
    version: str = ""
    version_code: str = ""
    device: str = ""
    os_text: str = ""
    stamp: str = ""
    err_type: str = ""
    err_msg: str = ""
    script: str = ""
    trail: list[TrailSample] = field(default_factory=list)
    # EP1/EP2 波新增的两处报告小节（覆盖率用；缺节记「缺」，不当 0 读数）。
    has_stall_scene: bool = False  # [主线程冻结现场…] 小节在不在（StallJudgment.SCENE_HEADER）
    stall_scene_captured: bool = False  # 这一节里到底抓到栈没有（有一帧文本 = 抓到）
    has_heap_census_line: bool = False  # 内存节那行 Java 堆分项在不在（LiveSetCensus.render）
    # 2026-11-04 inbox 波：与端点同源的内容指纹与聚合维度（缺省值都取「读不到」那一形，不塞 0）。
    fp: str = ""
    identity: bool = False  # 正文里有没有「身份」（信息/脚本位置/轨迹行）——决定指纹归不归掉时间
    degraded: bool = False  # 带「最小退化报告」标记：完整报告生成失败时的最后一张网
    sig_code: str = NO_SIGNATURE_CODE
    device_code: str = NO_DEVICE_CODE
    version_slug: str = NO_VERSION_SLUG
    kind_code: str = "n"

    @property
    def signature(self) -> str:
        """去重键：正文里能区分「同一次事故」的字段（不看上传时间戳）。

        内容指纹落地后这条只作兜底（指纹算不出来的极端形状，如空正文走这条也能自成一簇）。
        """
        return "\x1f".join(
            (
                self.time,
                self.version,
                self.device,
                self.kind,
                self.err_type,
                re.sub(r"\d+", "N", self.err_msg).strip(),
                self.script,
            )
        )

    @property
    def dedupe_key(self) -> str:
        """本地去重键 = 端点内容指纹：同一份正文在这两侧永远算一条。"""
        return self.fp or self.signature


def _field(text: str, pattern: str) -> str:
    match = re.search(pattern, text, re.M)
    return match.group(1).strip() if match else ""


def _error_section(text: str) -> str:
    start = re.search(r"^\[错误\]\s*$", text, re.M)
    if not start:
        return ""
    tail = text[start.end() :]
    end = SECTION_SEPARATOR.search(tail)
    return tail[: end.start()] if end else tail


def _stall_scene_section(text: str) -> str:
    """`[主线程冻结现场…]` 小节正文（从节名行到下一个分隔线）；没有这一节返回空串。"""
    start = re.search(r"^\[主线程冻结现场.*$", text, re.M)
    if not start:
        return ""
    tail = text[start.end() :]
    end = SECTION_SEPARATOR.search(tail)
    return tail[: end.start()] if end else tail


def detect_report_sections(text: str, report: "Report") -> None:
    """就地填两处新小节的读数：在不在（缺节 = False，不当 0）+ 冻结现场抓到栈没有。

    - 冻结小节正文里只要有一帧「类.方法:行」形状的行就算抓到（`未取到` 那些不算）。
    - 内存节 Java 堆分项是**独立成行**的 `Java 堆结构账面…`（memoryCensusReportLine）；
      轨迹里那条 `t=… heapcensus used=… Java 堆结构账面…` 以 `t=` 打头，不会被算进来。
    """
    if STALL_SCENE_HEADER_PREFIX in text:
        report.has_stall_scene = True
        report.stall_scene_captured = bool(STALL_FRAME_RE.search(_stall_scene_section(text)))
    report.has_heap_census_line = any(
        line.startswith(HEAP_CENSUS_REPORT_PREFIX) for line in text.splitlines()
    )


def parse_report(text: str, path: str = "") -> Report:
    version_text = _field(text, r"^app 版本：\s*(.+)$")
    version_match = re.match(r"(\S+)\s*\(versionCode\s*(\d+)\)", version_text)
    error = _error_section(text)
    # 最小退化报告没有 `[错误]` 节名行（app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:561-568
    # 直接写 类型/信息/根因），所以判读侧在这种形状上退回「整份正文里取第一条」。
    # 只在**没有 `[错误]` 节**时退回，避免把补充现场/异常栈里的同名行当异常信息。
    # 端点侧的指纹与 identity **不**退这一步：退化报告缺版本缺机型，判不准就宁可留双份。
    error_source = error if error.strip() else text
    fingerprint, identity = content_fingerprint(text)
    message = _field(error_source, r"^信息：\s*(.*)$")
    script = _field(error_source, r"^脚本位置：\s*(.*)$")
    kind = _field(text, r"^报告类型：\s*(.*)$")
    device = _field(text, r"^设备：\s*(.*)$")
    version = version_match.group(1) if version_match else version_text
    report = Report(
        path=path,
        kind=kind,
        time=_field(text, r"^报告时间：\s*(.*)$"),
        version=version,
        version_code=version_match.group(2) if version_match else "",
        device=device,
        os_text=_field(text, r"^系统：\s*(.*)$"),
        stamp=_field(text, r"^构建标识：\s*(.*)$"),
        err_type=_field(error_source, r"^类型：\s*(.*)$"),
        err_msg=message,
        script=script,
        fp=fingerprint,
        identity=identity,
        degraded=DEGRADED_MARK in text,
        sig_code=report_signature_code(message, script),
        device_code=device_code_of(device),
        version_slug=version_slug_of(version),
        kind_code=kind_code_of(kind),
    )
    report.trail = [parse_trail_sample(m) for m in TRAIL_LINE.finditer(text)]
    detect_report_sections(text, report)
    return report


def dedupe(reports: list[Report]) -> tuple[list[Report], int]:
    seen: OrderedDict[str, Report] = OrderedDict()
    for report in reports:
        seen.setdefault(report.dedupe_key, report)
    return list(seen.values()), len(reports) - len(seen)


# ----------------------------------------------------------------------
# 静默退出判读（与 app 内 RunDiagnostics 同口径）
# ----------------------------------------------------------------------


@dataclass
class Diagnosis:
    mode: str
    headline: str
    peak_used: int = 0
    peak_max: int = 0
    peak_percent: int = 0
    peak_native: int = 0
    trim_events: int = 0
    trim_freed: int = 0
    min_sys_avail: int | None = None
    lmk_threshold: int | None = None


def diagnose(trail: list[TrailSample]) -> Diagnosis:
    if not trail:
        return Diagnosis("无轨迹", "轨迹 0 行")
    peak_percent = max(s.used_percent for s in trail if s.max_mib > 0) if any(
        s.max_mib > 0 for s in trail
    ) else 0
    peak_native = max(s.native_mib for s in trail)
    peak_used = max(s.used_mib for s in trail)
    peak_max = max(s.max_mib for s in trail)
    trims = [s for s in trail if FREED_MIB.search(s.phase)]
    trim_freed = sum(int(FREED_MIB.search(s.phase).group(1)) for s in trims)
    sys_values = [s.sys_avail_mib for s in trail if s.sys_avail_mib is not None]
    lmk_values = [s.lmk_threshold_mib for s in trail if s.lmk_threshold_mib is not None]
    min_sys = min(sys_values) if sys_values else None
    lmk = lmk_values[-1] if lmk_values else None
    # 与 App 内 RunDiagnostics.classify 同一优先级：堆 → native → 系统内存 → 排除法。
    if peak_percent >= HEAP_CEILING_PERCENT:
        mode = "Java 堆贴顶"
    elif peak_native >= NATIVE_HIGH_MIB:
        mode = "native 驻留偏高"
    elif min_sys is not None and lmk is not None and min_sys < lmk:
        mode = "系统内存不足（被 LMK 收掉）"
    else:
        mode = "两者都不高（映射或信号）"
    headline = (
        f"峰值 Java {peak_used}/{peak_max} MiB（{peak_percent}%）"
        f" · 峰值 native {peak_native} MiB"
    )
    if min_sys is not None:
        headline += (
            f" · 系统最低可用 {min_sys} MiB（本机低内存线 {lmk} MiB）"
            if lmk is not None
            else f" · 系统最低可用 {min_sys} MiB（未取到低内存线）"
        )
    headline += (
        f" · 轨迹 {len(trail)} 行"
        f" · trim {len(trims)} 次（共收回 {trim_freed} MiB）"
    )
    return Diagnosis(mode, headline, peak_used, peak_max, peak_percent, peak_native,
                     len(trims), trim_freed, min_sys, lmk)


# ----------------------------------------------------------------------
# 分桶
# ----------------------------------------------------------------------


@dataclass
class Scan:
    reports: list[Report]
    duplicates: int
    ignored: list[Report]
    legacy: list[Report]
    false_positive: list[Report]
    current: list[Report]
    # 2026-11-04 inbox 波的两处瞎区读数（都是 current 的子集，不改既有分桶的成员）。
    # ver=? —— `app 版本` 取不到（退化报告没这行 / 取数写成「（未知）」），不许派版本。
    unknown_version: list[Report] = field(default_factory=list)
    # 有报告类型但正文没「身份」（信息/脚本位置/轨迹全无）：端点侧它进无签名桶，这里如实单列。
    no_identity: list[Report] = field(default_factory=list)


UNKNOWN_VERSION_TEXTS = {"", "（未知）", "(未知)", "未知"}


def is_unknown_version(report: Report) -> bool:
    """`app 版本` 取不到的两种真实形状：整行没有（最小退化报告）、取数失败写成「（未知）」。

    出处 —— app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:699 的
    `getOrDefault("（未知）")`；退化报告整份不含 `[版本与设备]` 节，同文件 :562-568。
    """
    return report.version.strip() in UNKNOWN_VERSION_TEXTS


def version_label(report: Report) -> str:
    return "?" if is_unknown_version(report) else report.version


def analyze(reports: list[Report]) -> Scan:
    unique, duplicates = dedupe(reports)
    ignored = [r for r in unique if not r.kind]
    real = [r for r in unique if r.kind]
    legacy: list[Report] = []
    false_positive: list[Report] = []
    current: list[Report] = []
    for report in real:
        if report.version in LEGACY_VERSIONS:
            legacy.append(report)
        elif report.version == FALSE_POSITIVE_VERSION and report.kind == FALSE_POSITIVE_KIND:
            false_positive.append(report)
        else:
            current.append(report)
    # ver=? 与「无身份」都是 current 的子集：份数照常计入，只是不许派版本、不许进签名簇。
    unknown_version = [r for r in current if is_unknown_version(r)]
    no_identity = [r for r in current if not r.identity]
    return Scan(unique, duplicates, ignored, legacy, false_positive, current,
                unknown_version, no_identity)


def _last_trail_line(report: Report) -> str:
    if not report.trail:
        return "无轨迹"
    last = report.trail[-1]
    sys_text = "" if last.sys_avail_mib is None else f" sys={last.sys_avail_mib}MiB"
    lmk_text = "" if last.lmk_threshold_mib is None else f" lmk={last.lmk_threshold_mib}MiB"
    return (
        f"t={last.elapsed_ms}ms used={last.used_mib}MiB max={last.max_mib}MiB "
        f"native={last.native_mib}MiB {last.phase}{sys_text}{lmk_text}"
    ).strip()


def _bucket_lines(reports: list[Report]) -> list[str]:
    counter = Counter(
        (r.stamp, r.version, r.device, r.kind or "?", r.err_type or "（无异常对象）")
        for r in reports
    )
    lines = []
    for (stamp, version, device, kind, err_type), count in sorted(
        counter.items(), key=lambda item: (-item[1], item[0])
    ):
        lines.append(f"{count:4d}  {stamp or '-'} | {version or '-'} | {device or '-'} | {kind} | {err_type}")
    return lines


# ----------------------------------------------------------------------
# EP1/EP2 波：按新轨迹前缀分桶（每种前缀一行计数 + 关键数值列 min/median/max）
# ----------------------------------------------------------------------

# 前缀名 → 归一后的稳定标签（顺序即输出顺序）。
NEW_PREFIXES = ("stallstack", "heapcensus", "errdup", "errcap", "trim")


def trail_prefix_of(phase: str) -> str | None:
    """一行轨迹的阶段名 → 命中的新前缀（不命中返回 None，别硬塞进桶）。

    `trim` 是唯一的特例：老轨迹早有 `trim=<档名> freed=<n>MiB`，而 EP1/EP2 波新面是其后
    追加的 ` bitmap=a->b view=c->d native=e->f[ deep]` 分项（NativeTrimPlan.readoutLine）。
    只有带这截箭头分项的 trim 行才算命中——否则 0.2.36 之前的老 trim 会被读成「新面有样本」，
    违背「缺 ≠ 0」。其余四个前缀（stallstack/heapcensus/errdup/errcap）在旧包里根本不存在。
    """
    if phase.startswith("trim=") and ARROW_READOUT_RE.search(phase):
        return "trim"
    for prefix in ("stallstack", "heapcensus", "errdup", "errcap"):
        if phase == prefix or phase.startswith(prefix + " "):
            return prefix
    return None


def _fmt_num(value: float) -> str:
    return str(int(value)) if float(value).is_integer() else f"{value:.1f}"


def _min_med_max(values: list[int | float]) -> str:
    if not values:
        return "缺"
    return f"min{_fmt_num(min(values))}/中位{_fmt_num(statistics.median(values))}/max{_fmt_num(max(values))}"


def _prefix_columns(prefix: str, phase: str) -> "list[tuple[str, list[int]]]":
    """取这一前缀自己的数值列（每列一组整数读数）；缺的列如实留空，不塞 0。

    列是按形状单取的，不是一个正则把整行拍平：heapcensus 的 `used=<n>MiB/<max>`（斜杠）
    与 trim 的 `native=<e>-><f>MiB`（箭头）是两种列，混进一个正则就没法并排比。
    """
    if prefix == "stallstack":
        m = STALLSTACK_RE.match(phase)
        if not m:
            return []
        return [("uiFrozen", [int(m.group(2))]), ("frames", [int(m.group(4))])]
    if prefix == "heapcensus":
        m = HEAPCENSUS_RE.match(phase)
        if not m:
            return []
        used, mx = int(m.group(1)), int(m.group(2))
        cols = [("used", [used]), ("max", [mx])]
        if mx > 0:
            cols.append(("水位%", [used * 100 // mx]))
        return cols
    if prefix in ("errdup", "errcap"):
        m = ERRX_RE.match(phase)
        return [("x计数", [int(m.group(2))])] if m else []
    if prefix == "trim":
        cols: list[tuple[str, list[int]]] = []
        freed = FREED_MIB.search(phase)
        if freed:
            cols.append(("freed", [int(freed.group(1))]))
        for label, rx in (
            ("bitmap", BITMAP_ARROW_RE),
            ("view", VIEW_ARROW_RE),
            ("native", NATIVE_ARROW_RE),
        ):
            arrow = rx.search(phase)
            if arrow:
                cols.append((f"{label}前", [int(arrow.group(1))]))
                cols.append((f"{label}后", [int(arrow.group(2))]))
        return cols
    return []


def new_trail_bucket_lines(reports: list[Report]) -> tuple[list[str], int]:
    """跨当前口径的所有轨迹行按新前缀分桶；返回 (渲染行, 命中的新前缀轨迹行总数)。"""
    counts: "Counter[str]" = Counter()
    columns: "dict[str, dict[str, list[int]]]" = {p: {} for p in NEW_PREFIXES}
    deep_lines = 0
    for report in reports:
        for sample in report.trail:
            prefix = trail_prefix_of(sample.phase)
            if prefix is None:
                continue
            counts[prefix] += 1
            bucket = columns[prefix]
            for label, values in _prefix_columns(prefix, sample.phase):
                bucket.setdefault(label, []).extend(values)
            if prefix == "trim" and sample.phase.endswith(TRIM_DEEP_SUFFIX):
                deep_lines += 1

    lines: list[str] = []
    total = 0
    for prefix in NEW_PREFIXES:
        n = counts[prefix]
        total += n
        if n == 0:
            lines.append(f"{prefix}：0 行（缺——当前口径还没有这一形状的真样本）")
            continue
        parts = [f"{label} {_min_med_max(vals)}" for label, vals in columns[prefix].items()]
        detail = " · ".join(parts) if parts else "无数字列"
        tail = f" · 深档 {deep_lines}/{n} 行" if prefix == "trim" else ""
        lines.append(f"{prefix}：{n} 行 · {detail}{tail}")
    return lines, total


# ----------------------------------------------------------------------
# EP1/EP2 波：新报告小节覆盖率（缺节如实写「缺」，不许拿「没数据」当读数 0）
# ----------------------------------------------------------------------


def section_coverage_lines(reports: list[Report]) -> list[str]:
    total = len(reports)
    if total == 0:
        return ["（当前口径没有报告，覆盖率无从谈起）"]

    def pct(n: int) -> str:
        return f"{n}/{total}（{n * 100 // total}%）"

    with_scene = sum(1 for r in reports if r.has_stall_scene)
    captured = sum(1 for r in reports if r.stall_scene_captured)
    with_census = sum(1 for r in reports if r.has_heap_census_line)

    lines = [
        f"[主线程冻结现场] 小节：{'缺' if with_scene == 0 else pct(with_scene)}；"
        f"其中抓到栈的 {'缺' if captured == 0 else pct(captured)}"
        + ("（缺节 ≠ 抓到率 0：0.2.36 前根本没有这一节）" if with_scene == 0 else ""),
        f"内存节 Java 堆分项（`{HEAP_CENSUS_REPORT_PREFIX}…`）：{'缺' if with_census == 0 else pct(with_census)}"
        + ("（这一形状 0.2.36 起才随 heapcensus 进报告）" if with_census == 0 else ""),
    ]
    return lines


# ----------------------------------------------------------------------
# EP1/EP2 波：同签名聚合（`信息：…` + `@ 函数 行N` 归一成一行，输出压缩效果）
# ----------------------------------------------------------------------


@dataclass
class SignatureCluster:
    message: str
    script: str
    count: int
    machines: int
    earliest: str
    latest: str
    # 2026-11-04 inbox 波：把「一个人在反复试」和「面上都在犯」分开，退化份数如实标出来。
    episodes: int = 0  # 事件数 = 按机型 + 报告时间切出来的「几次独立发生」
    repeats: int = 0  # count - episodes：同机同签名在窗口内的连发份数（玩家反复试同一招）
    degraded: int = 0  # 这一簇里带「最小退化报告」标记的份数
    versions: list[str] = field(default_factory=list)  # 这一簇跨到的版本（ver=? 排最前）

    @property
    def face(self) -> str:
        """面上判定：跨机 = 面上问题；单机 = 有人在反复试同一招（不等于「问题更大」）。"""
        if self.machines >= 2:
            return "面上问题（跨机）"
        if self.machines == 1:
            return "单机反复（非面上）"
        return "机型未知"


def _normalize_message(msg: str) -> str:
    # 消息里的数字抹成 N（口径同产品侧 ReportSignature.normalize：数据不是身份）；
    # 位置里的 `行N` 不抹（行号就是身份，见 ErrorReportCollector.kt:1421）。
    return re.sub(r"\d+", "N", msg).strip()


def _report_signature_key(report: Report) -> "tuple[str, str] | None":
    message = _normalize_message(report.err_msg)
    script = report.script.strip()
    # 退化报告且 `信息` 缺失：这一份只有「类型 + 根因」，归并进签名簇得到的是「类型相同」不是
    # 「缺陷相同」——本轮 3 份最小退化报告就是这么被并成两个假簇的。它改走无签名桶。
    if report.degraded and not message:
        return None
    if not message and not script:
        return None
    return (message, script)


def _cluster_episodes(members: list[Report]) -> int:
    """按机型 + 报告时间切事件：同机同签名落在窗口内算同一次，窗口外或读不到时间/机型各算一次。

    时间取 `报告时间`（生成时刻，玩家每次触发都会新生成一份）而不是端点的入库毫秒 ——
    后者是补发时刻，同一份文件重发会被算成新事件。
    """
    by_device: "dict[str, list[Report]]" = {}
    episodes = 0
    for member in members:
        if member.device:
            by_device.setdefault(member.device, []).append(member)
        else:
            # 机型未知（最小退化报告没有设备行）：证明不了「同机连发」，各算一个事件。
            episodes += 1
    window = BURST_WINDOW_MINUTES * 60
    for group in by_device.values():
        timed = sorted(
            (t for t in (parse_report_time(m.time) for m in group) if t is not None),
            key=lambda when: when.timestamp(),
        )
        untimed = len(group) - len(timed)
        own = 0
        previous: float | None = None
        for when in timed:
            stamp = when.timestamp()
            if previous is None or stamp - previous > window:
                own += 1
            previous = stamp
        if not timed:
            own = len(group)
        # 读不出报告时间的份（探针 / 半截上传）也各算一个事件：同一次证明不了。
        episodes += own + untimed
    return episodes


def signature_clusters(reports: list[Report]) -> list[SignatureCluster]:
    grouped: "dict[tuple[str, str], list[Report]]" = {}
    for report in reports:
        key = _report_signature_key(report)
        if key is None:
            continue
        grouped.setdefault(key, []).append(report)
    clusters: list[SignatureCluster] = []
    for (message, script), members in grouped.items():
        times = sorted(m.time for m in members if m.time)
        episodes = _cluster_episodes(members)
        clusters.append(
            SignatureCluster(
                message=message,
                script=script,
                count=len(members),
                machines=len({m.device for m in members if m.device}),
                earliest=times[0] if times else "",
                latest=times[-1] if times else "",
                episodes=episodes,
                repeats=len(members) - episodes,
                degraded=sum(1 for m in members if m.degraded),
                versions=sorted({version_label(m) for m in members}),
            )
        )
    clusters.sort(key=lambda c: (-c.count, c.message, c.script))
    return clusters


def signature_cluster_lines(reports: list[Report]) -> tuple[list[str], int, int]:
    clusters = signature_clusters(reports)
    signaturaable = sum(c.count for c in clusters)
    lines: list[str] = []
    if not clusters:
        lines.append("（当前口径没有可归并的签名：都是无异常对象的静默退出）")
        return lines, 0, 0
    for c in clusters[:SIGNATURE_TOP]:
        where = f" @ {c.script}" if c.script else ""
        span = f"{c.earliest} ~ {c.latest}" if c.earliest else "无时间"
        versions = "、".join(c.versions)
        degraded_note = f" · 含退化 {c.degraded} 份" if c.degraded else ""
        lines.append(
            f"{c.count:4d} 份 · {c.machines} 台机 · {c.episodes} 次事件"
            f"（同机连发 {c.repeats} 份）· 版本 {versions}{degraded_note}"
            f" · {span} · {c.message or '（无消息）'}{where}"
        )
    if len(clusters) > SIGNATURE_TOP:
        lines.append(f"……其余 {len(clusters) - SIGNATURE_TOP} 个签名簇略")
    return lines, signaturaable, len(clusters)


def signature_face_lines(clusters: list[SignatureCluster]) -> list[str]:
    """把「一个人在反复试」和「面上都在犯」分成两句话 —— 份数排序会把前者顶到表首骗人。"""
    if not clusters:
        return ["（当前口径没有可归并的签名）"]
    face = [c for c in clusters if c.machines >= 2]
    single = [c for c in clusters if c.machines == 1]
    unknown = [c for c in clusters if c.machines == 0]
    lines = [
        f"面上问题（≥2 台机同签名）：{len(face)} 簇 / {sum(c.count for c in face)} 份"
        f"，其中独立事件 {sum(c.episodes for c in face)} 次",
        f"单机反复（只有 1 台机）：{len(single)} 簇 / {sum(c.count for c in single)} 份"
        f"，同机连发 {sum(c.repeats for c in single)} 份 —— 这是玩家在反复试同一招，不是面上扩散",
    ]
    if unknown:
        lines.append(
            f"机型未知（最小退化报告没有设备行）：{len(unknown)} 簇 / "
            f"{sum(c.count for c in unknown)} 份 —— 分不清跨机还是单机，别当成面上问题"
        )
    top = sorted(clusters, key=lambda c: (-c.episodes, -c.machines))[0]
    lines.append(
        f"独立事件最多的一簇：{top.episodes} 次 / {top.machines} 台机 · "
        f"{top.message or '（无消息）'}{f' @ {top.script}' if top.script else ''}"
    )
    return lines


# ----------------------------------------------------------------------
# 2026-11-04 inbox 波：ver=? 桶、无签名桶、按版本的一句话结论
# ----------------------------------------------------------------------


def unknown_version_lines(reports: list[Report]) -> list[str]:
    """`app 版本` 取不到的份：只报形状与条数，不许给它们派版本（本轮 5 份，全是最小退化报告）。"""
    if not reports:
        return ["（没有：每份报告都读得到 app 版本）"]
    lines = [
        f"共 {len(reports)} 份取不到 app 版本 —— 进 ver=? 桶，不参与「按版本还剩几个真缺陷」的账"
    ]
    counter = Counter((r.kind or "?", r.err_type or "（无异常对象）", r.device or "机型未知") for r in reports)
    for (kind, err_type, device), count in sorted(counter.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"{count:4d}  ver=? | {kind} | {err_type} | {device}")
    for report in reports:
        if report.degraded:
            lines.append(f"        退化：{report.time or '无时间'} | 有信息={'是' if report.err_msg else '否'}"
                         f" | 有轨迹={len(report.trail)} 行")
    return lines


def no_signature_bucket_lines(reports: list[Report]) -> list[str]:
    """签名归并不进去的份（静默退出 + 无信息的退化报告）单独一桶，按版本 × 机型数如实报。

    口径与端点 /stats 的 no_signature 桶不同处要说清：端点看「信息/脚本位置都空」，这里还多剔掉
    「退化且无信息」那一种（本轮新加的判据），所以两边的份数差 = 退化但带脚本位置的份。
    """
    grouped: "dict[str, list[Report]]" = {}
    for report in reports:
        if _report_signature_key(report) is not None:
            continue
        grouped.setdefault(version_label(report), []).append(report)
    if not grouped:
        return ["（没有：当前口径每份都归进了签名簇）"]
    lines: list[str] = []
    for version, members in sorted(grouped.items(), key=lambda item: (-len(item[1]), item[0])):
        devices = len({m.device for m in members if m.device})
        degraded = sum(1 for m in members if m.degraded)
        trailed = sum(1 for m in members if m.trail)
        lines.append(
            f"{len(members):4d}  ver={version} · {devices} 台机"
            f" · 带轨迹 {trailed} 份（静默退出，正常无签名）"
            f" · 退化 {degraded} 份（信息缺失，不参与签名）"
        )
    return lines


def version_defect_lines(scan: Scan) -> list[str]:
    """按版本的一句话结论：本版还剩几类真缺陷（签名簇），无签名/退化各占几份。

    「真缺陷」= 当前口径里该版本出现过的**不同签名簇**数；一行报告不代表一个缺陷，
    同签名跨版本复发也不重复计（那一列在簇行里已经写明版本）。
    """
    if not scan.current:
        return ["（当前口径没有报告）"]
    by_version: "dict[str, list[Report]]" = {}
    for report in scan.current:
        by_version.setdefault(version_label(report), []).append(report)
    lines: list[str] = []
    for version, members in sorted(
        by_version.items(), key=lambda item: (item[0] == "?", -len(item[1]), item[0])
    ):
        clusters = signature_clusters(members)
        defects = len(clusters)
        face = sum(1 for c in clusters if c.machines >= 2)
        no_sig = len(members) - sum(c.count for c in clusters)
        degraded = sum(1 for m in members if m.degraded)
        verdict = (
            f"结论：本版还剩 {defects} 类真缺陷签名（其中跨机 {face} 类）"
            f"；另 {no_sig} 份读不出签名（静默退出/退化，要按内存判读看）"
        )
        if version == "?":
            verdict = (
                f"结论：这 {len(members)} 份取不到版本，一类别下 —— 退化 {degraded} 份"
                "；要定版本得让产生侧把版本行写成必读（见交付报告的需组长接线）"
            )
        lines.append(
            f"{len(members):4d} 份  ver={version} · 机型 {len({m.device for m in members if m.device})} 台"
            f" · 退化 {degraded} 份\n        {verdict}"
        )
    return lines


def render(scan: Scan) -> str:
    lines: list[str] = []
    bar = "=" * 72
    lines += [
        bar,
        "上报收件箱分桶（构建戳 × 机型 × 异常类型）",
        bar,
        f"去重后 {len(scan.reports)} 份（输入 {len(scan.reports) + scan.duplicates} 份，"
        f"重复上传 {scan.duplicates} 份已合并）",
        f"已忽略 {len(scan.ignored)} 份（无报告类型：端点探针 / 截断上传）",
        f"当前口径 {len(scan.current)} 份 = 0.2.31 / 0.2.32 及更新的构建",
        f"已剔除：旧版（{'/'.join(sorted(LEGACY_VERSIONS))}）{len(scan.legacy)} 份；"
        f"{FALSE_POSITIVE_VERSION} 脏退出误报 {len(scan.false_positive)} 份",
        "",
        "-- 当前口径：构建 × 机型 × 报告类型 × 异常类型 --",
    ]
    lines += _bucket_lines(scan.current) or ["（没有）"]
    lines += ["", "-- 当前口径：按构建戳汇总 --"]
    by_stamp = Counter((r.stamp or "-", r.version or "-") for r in scan.current)
    for (stamp, version), count in sorted(by_stamp.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"{count:4d}  {stamp} | {version}")
    lines += ["", "-- 当前口径：按报告类型汇总 --"]
    for kind, count in Counter(r.kind or "?" for r in scan.current).most_common():
        lines.append(f"{count:4d}  {kind}")

    lines += ["", "-- 当前口径：OutOfMemoryError / CodeEE（新增问题重点） --"]
    focus = [
        r for r in scan.current
        if "OutOfMemoryError" in r.err_type or "CodeEE" in r.err_type
    ]
    if not focus:
        lines.append("（当前口径没有 OOM / CodeEE）")
    for report in sorted(focus, key=lambda r: (r.stamp, r.time)):
        lines.append(
            f"{report.time} {report.stamp} | {report.version} | {report.device} | "
            f"{report.kind} | {report.err_type} | {(report.err_msg or '')[:120]}"
        )
        if report.script:
            lines.append(f"        @ {report.script}")

    lines += ["", f"-- {FALSE_POSITIVE_VERSION} 脏退出误报（单独剔除：背景态在 0.2.32 才补上） --"]
    if not scan.false_positive:
        lines.append("（没有）")
    for report in sorted(scan.false_positive, key=lambda r: r.time):
        lines.append(f"{report.time} {report.device} | {report.stamp} | 末行 {_last_trail_line(report)}")

    lines += ["", "-- 旧版本（不计入当前口径） --"]
    if not scan.legacy:
        lines.append("（没有）")
    for (stamp, version, kind), count in sorted(
        Counter((r.stamp or "-", r.version or "-", r.kind or "?") for r in scan.legacy).items(),
        key=lambda item: (-item[1], item[0]),
    ):
        lines.append(f"{count:4d}  {stamp} | {version} | {kind}")

    silent = [r for r in scan.current if r.kind == FALSE_POSITIVE_KIND]
    lines += ["", "-- 静默退出判读（当前口径「上次异常退出」，阈值同 App 内 RunDiagnostics） --"]
    if not silent:
        lines.append("（没有）")
    else:
        diagnosed = [(r, diagnose(r.trail)) for r in silent]
        for mode, count in Counter(d.mode for _, d in diagnosed).most_common():
            lines.append(f"{count:4d}  {mode}")
        with_sys = sum(1 for _, d in diagnosed if d.min_sys_avail is not None)
        lines.append(
            f"（其中 {with_sys}/{len(diagnosed)} 份带 sys= 列；没有这一列的是 0.2.33 之前的轨迹，"
            f"判不出「系统内存不足」，只能落进「两者都不高」——别把那一桶当结论）"
        )
        lines.append("")
        for report, diag in sorted(diagnosed, key=lambda pair: (pair[0].stamp, pair[0].time)):
            lines.append(
                f"{report.time} {report.stamp} | {report.version} | {report.device} | "
                f"{diag.mode} | {diag.headline}"
            )

    # ---- 以下三节为 EP1/EP2 波新增（旧表文本到此为止、逐字未改） ----
    lines += ["", "-- 新增轨迹前缀分桶（新面；旧表读数不受影响） --"]
    prefix_lines, prefix_total = new_trail_bucket_lines(scan.current)
    lines += prefix_lines
    if prefix_total == 0:
        lines.append(
            "（这批报告都在 0.2.36 之前：stallstack/heapcensus/errdup/errcap/新 trim 形状还没进包，"
            "缺 ≠ 0，别当成「没有冻结/没有贴顶分项」）"
        )

    lines += ["", "-- 新报告小节覆盖率（缺节如实写「缺」，不许当 0） --"]
    lines += section_coverage_lines(scan.current)

    lines += ["", "-- 同签名聚合（信息+@函数行N 归一；一簇几份/跨几台机/几次独立事件/最早最晚时间） --"]
    cluster_lines, signaturable, cluster_count = signature_cluster_lines(scan.current)
    lines += cluster_lines
    if cluster_count:
        lines.append(
            f"（当前口径有签名的 {signaturable} 份归并成 {cluster_count} 个签名簇"
            f"——贴顶最大的三项已在表首，按份数降序）"
        )

    # ---- 以下四节为 2026-11-04 inbox 波新增（补四处瞎区；上面的表逐字未改） ----
    lines += ["", "-- 面上问题 vs 单机反复（同机连发按 30 分钟窗口切事件） --"]
    lines += signature_face_lines(signature_clusters(scan.current))

    lines += ["", f"-- ver=?（app 版本取不到，不参与按版本的缺陷账；共 {len(scan.unknown_version)} 份） --"]
    lines += unknown_version_lines(scan.unknown_version)

    lines += ["", "-- 无签名桶（签名归并不进去的份：静默退出 + 无信息的退化报告） --"]
    lines += no_signature_bucket_lines(scan.current)

    lines += ["", "-- 按版本：本版还剩几个真缺陷（一句话结论） --"]
    lines += version_defect_lines(scan)

    lines += [bar, "分桶结束", ""]
    return "\n".join(lines)


# ----------------------------------------------------------------------
# 输入源
# ----------------------------------------------------------------------


def load_dir(directory: Path) -> list[Report]:
    reports = []
    for path in sorted(directory.rglob("*.txt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "eraTW 安卓移植版" not in text:
            continue
        reports.append(parse_report(text, str(path)))
    return reports


def _read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def _endpoint() -> "tuple[str, object, str]":
    """读 local.report.properties → (端点基址, get 函数, 已编码令牌)。令牌永不进返回值文本。"""
    props_path = Path(__file__).resolve().parents[1] / "local.report.properties"
    props = _read_properties(props_path)
    endpoint = props.get("endpoint", "")
    token = props.get("token", "")
    if not endpoint or not token:
        raise SystemExit(f"{props_path} 缺 endpoint/token（该文件已 gitignore）")
    base = endpoint.rstrip("/")
    if base.endswith("/report"):
        base = base[: -len("/report")]
    headers = {"X-Eratw-Token": token, "User-Agent": "eratw-triage/1"}
    encoded_token = urllib.parse.quote(token, safe="")

    def get(path: str) -> str:
        request = urllib.request.Request(base + path, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode("utf-8", "replace")

    return get, base, encoded_token


def _get_with_retry(get: object, path: str, what: str) -> str:
    """一发请求 3 次重试（口径同逐份拉取：一次网络抖动不许打断整轮判读）。"""
    last_error: Exception | None = None
    for attempt in range(FETCH_ATTEMPTS):
        try:
            return get(path)  # type: ignore[operator]
        except urllib.error.HTTPError as error:
            last_error = error
            # 4xx 是判读口径问题（旧版端点没有 /stats、令牌不符），重试没有意义，直接如实报。
            if 400 <= error.code < 500:
                raise SystemExit(f"{what} 失败：HTTP {error.code}（{error.reason}）") from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
        if attempt + 1 < FETCH_ATTEMPTS:
            time.sleep(FETCH_RETRY_BACKOFF_SECONDS * (attempt + 1))
    raise SystemExit(f"{what} 失败：{last_error}")


def fetch_stats() -> dict:
    """GET /stats：只出聚合表，一份正文都不拉（带去重版 worker 才有这个面）。"""
    get, _base, encoded_token = _endpoint()
    text = _get_with_retry(get, f"/stats?token={encoded_token}", "端点聚合读数")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise SystemExit(f"端点 /stats 回的不是 JSON：{error}") from error
    if not isinstance(data, dict):
        raise SystemExit("端点 /stats 回包不是对象")
    return data


def render_stats(stats: dict) -> str:
    """把端点 /stats 的回包渲成一张表：签名簇 × 版本 × 机型数 + 无签名桶 + 版本桶。"""
    bar = "=" * 72
    lines = [bar, "端点聚合读数（GET /stats：去重 + 按签名/版本/机型计数）", bar]
    lines += [
        f"上报 {stats.get('occurrences', 0)} 条 = 去重后 {stats.get('incidents', 0)} 起事故"
        f" + 重复上传 {stats.get('duplicate_uploads', 0)} 条（这一轮不必拉的正文）",
        f"签名簇 {stats.get('signatures_total', 0)} 个"
        + ("（已截断，只回前若干簇）" if stats.get("signatures_truncated") else ""),
        f"清单翻页{'已到上限、后面的没统计' if stats.get('listing_truncated') else '完整'}"
        f" · {stats.get('limit_note', '')}",
        f"覆盖范围：{stats.get('backfill_hint') or '未标注（旧版端点没有这个字段）'}",
        "",
        "-- 签名簇 × 版本 × 机型数（按上报条数降序） --",
    ]
    signatures = stats.get("signatures") or []
    if not signatures:
        lines.append("（这一桶空的：要么还没有带去重版端点的上报，要么全在无签名桶里）")
    for item in signatures:
        versions = "、".join(
            f"{v.get('version')}×{v.get('occurrences')}/{v.get('devices')}机"
            for v in (item.get("versions") or [])
        ) or "无版本读数"
        label = item.get("label") or {}
        text = label.get("message") or "（无消息）"
        where = f" @ {label['script']}" if label.get("script") else ""
        degraded_note = f" · 退化 {item.get('degraded_occurrences', 0)} 份" if item.get("degraded_occurrences") else ""
        lines.append(
            f"{item.get('occurrences', 0):5d} 条 · {item.get('incidents', 0):4d} 起 · "
            f"{item.get('devices', 0):3d} 台机{degraded_note} · 版本 {versions} · {text}{where}"
        )
    no_sig = stats.get("no_signature") or {}
    lines += [
        "",
        "-- 无签名桶（退化/最小 + 纯静默退出；不进上面的簇） --",
        f"{no_sig.get('occurrences', 0):5d} 条 · {no_sig.get('incidents', 0):4d} 起 · "
        f"{no_sig.get('devices', 0):3d} 台机 · 退化 {no_sig.get('degraded_occurrences', 0)} 份",
        f"（{no_sig.get('hint', '端点未给口径说明')}）",
        "",
        "-- 按版本 --",
    ]
    for item in stats.get("versions") or []:
        lines.append(
            f"{item.get('occurrences', 0):5d} 条 · {item.get('incidents', 0):4d} 起 · "
            f"{item.get('devices', 0):3d} 台机 · 签名簇 {item.get('signatures', 0)} 个 · "
            f"无签名 {item.get('no_signature_occurrences', 0)} 条 · ver={item.get('version')}"
        )
    lines += [bar, "聚合读数结束（逐份判读仍要 --fetch 拉正文；这张表不替代静默退出四桶）", ""]
    return "\n".join(lines)


def fetch_reports(keep: Path | None) -> list[Report]:
    get, _base, encoded_token = _endpoint()

    listing = None
    for attempt in range(FETCH_ATTEMPTS):
        try:
            listing = json.loads(get(f"/reports?token={encoded_token}"))  # type: ignore[operator]
            break
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt + 1 >= FETCH_ATTEMPTS:
                raise SystemExit(f"端点清单拉取失败：{error}") from error
            time.sleep(FETCH_RETRY_BACKOFF_SECONDS * (attempt + 1))
    keys = listing.get("reports", []) if isinstance(listing, dict) else []
    print(f"端点清单 {len(keys)} 份", file=sys.stderr)
    reports: list[Report] = []
    failed = 0
    reused = 0
    if keep is not None:
        keep.mkdir(parents=True, exist_ok=True)
    for index, key in enumerate(keys, start=1):
        flat = key.replace("eratw/", "").replace("/", "__")
        kept = (keep / flat) if keep is not None else None
        # `--keep` 目录同时是断点续拉缓存：只有带完整报告头的文件才复用，
        # 上次中断留下的半截文件会重新拉（写失败不会把一份好报告永久弄丢）。
        if kept is not None and kept.is_file:
            # is_file() 与 read_text() 之间文件仍可能消失（清理程序 / 并发删除）。
            # 读不到就当没缓存，走网络重拉；不能因为一个缓存槽位的消失打断整轮。
            cached = ""
            try:
                cached = kept.read_text(encoding="utf-8", errors="replace")
            except OSError:
                pass
            if "eraTW" in cached and "错误报告" in cached:
                reports.append(parse_report(cached, key))
                reused += 1
                if index % 50 == 0 or index == len(keys):
                    print(f"已拉取 {index}/{len(keys)}（复用 {reused}）", file=sys.stderr)
                continue
        text = None
        last_error: Exception | None = None
        for attempt in range(FETCH_ATTEMPTS):
            try:
                text = get(f"/report/{urllib.parse.quote(key, safe='')}?token={encoded_token}")
                break
            except urllib.error.HTTPError as error:
                last_error = error
                if error.code in (404, 410):
                    break
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = error
            if attempt + 1 < FETCH_ATTEMPTS:
                time.sleep(FETCH_RETRY_BACKOFF_SECONDS * (attempt + 1))
        if text is None:
            failed += 1
            print(f"拉取失败 {key}: {last_error}", file=sys.stderr)
            continue
        reports.append(parse_report(text, key))
        if kept is not None:
            try:
                kept.write_text(text, encoding="utf-8")
            except OSError as error:
                print(f"缓存写入失败 {key}: {error}（本轮照常分桶）", file=sys.stderr)
        if index % 50 == 0 or index == len(keys):
            print(f"已拉取 {index}/{len(keys)}（复用 {reused}）", file=sys.stderr)
    if reused:
        print(f"复用本地缓存 {reused} 份", file=sys.stderr)
    if failed:
        print(f"共 {failed} 份拉取失败（已跳过）", file=sys.stderr)
    return reports


# ----------------------------------------------------------------------
# selftest
# ----------------------------------------------------------------------


def _fake_report(
    kind: str,
    version: str,
    stamp: str,
    device: str,
    err_type: str = "",
    msg: str = "",
    trail: tuple[str, ...] = (),
    time: str = "2026-10-31 10:00:00",
    code: str = "7",
) -> str:
    lines = [
        "================================================================",
        "eraTW 安卓移植版 错误报告",
        "================================================================",
        f"报告类型：{kind}",
        f"报告时间：{time}",
        "把本文件完整发给开发者（QQ 群 / Issue 附件）就是一次有效报错。",
        "----------------------------------------------------------------",
        "[版本与设备]",
        f"app 版本：{version} (versionCode {code})",
        f"设备：{device}",
        "系统：Android 14（API 34）",
        "----------------------------------------------------------------",
        "[构建与产物标识]",
        f"构建标识：{stamp}",
        "产物指纹：deadbeef",
        "----------------------------------------------------------------",
        "[错误]",
        f"类型：{err_type}",
    ]
    if msg:
        lines.append(f"信息：{msg}")
    lines.append("----------------------------------------------------------------")
    if trail:
        lines.append("[补充现场]")
        lines.extend(trail)
        lines.append("----------------------------------------------------------------")
    lines.append("报告结束")
    return "\n".join(lines) + "\n"


def selftest() -> int:
    checks = 0

    def expect(condition: bool, label: str) -> None:
        nonlocal checks
        if not condition:
            raise AssertionError(f"selftest 失败：{label}")
        checks += 1

    corpus = [
        _fake_report(
            "应用崩溃", "0.2.32", "7f1afec", "HONOR PGT-AN00",
            "java.lang.OutOfMemoryError",
            "Failed to allocate a 56 byte allocation with 497992 free bytes and 486KB until OOM",
            time="2026-10-31 16:14:59",
        ),
        # 同一份报告被重复 POST：正文一致，只有端点时间戳不同
        _fake_report(
            "应用崩溃", "0.2.32", "7f1afec", "HONOR PGT-AN00",
            "java.lang.OutOfMemoryError",
            "Failed to allocate a 56 byte allocation with 497992 free bytes and 486KB until OOM",
            time="2026-10-31 16:14:59",
        ),
        _fake_report(
            "启动失败", "0.2.3", "10ca2cb", "Xiaomi 25113PN0EC",
            "com.eratw.port.core.code.StepLimitExceeded",
            "执行时间超过上限(60170ms，已执行 13697024 步)，疑似死循环 @ EVENTTRAIN#4719",
            time="2026-10-30 17:12:29",
        ),
        _fake_report(
            "上次异常退出", "0.2.31", "7591d00", "vivo V2199A", "",
            trail=(
                "t=756499ms used=431MiB max=512MiB native=119MiB turn 836ms",
                "t=766375ms used=394MiB max=512MiB native=118MiB trim=uiHidden freed=0MiB",
            ),
            time="2026-10-30 23:29:52",
        ),
        _fake_report(
            "上次异常退出", "0.2.32", "7f1afec", "realme RMX3800", "",
            trail=(
                "t=2184003ms used=463MiB max=512MiB native=138MiB turn 0ms",
                "t=2196720ms used=467MiB max=512MiB native=251MiB pause",
            ),
            time="2026-10-31 11:51:12",
        ),
        _fake_report(
            "上次异常退出", "0.2.32", "7f1afec", "samsung SM-G9860", "",
            trail=("t=20845ms used=60MiB max=576MiB native=18MiB pause",),
            time="2026-10-31 00:26:45",
        ),
        _fake_report(
            "启动失败", "0.2.32", "7f1afec", "HONOR ALI-AN00",
            "com.eratw.port.core.err.CodeEE",
            'XML_GET函数:""解析错误:缺少根元素（位置 9）',
            time="2026-10-31 01:00:00",
        ),
        # 端点探针：有报告头标记但没有报告类型，不能算进任何桶
        "eraTW 安卓移植版 错误报告\nprobe-line-1\n",
    ]
    scan = analyze([parse_report(text) for text in corpus])
    expect(len(scan.reports) == 7, "重复上传合并成一条")
    expect(scan.duplicates == 1, "重复计数")
    expect(len(scan.ignored) == 1, "端点探针被忽略")
    expect(len(scan.legacy) == 1, "0.2.3 单列")
    expect(len(scan.false_positive) == 1, "0.2.31 脏退出误报单列")
    expect(len(scan.current) == 4, "当前口径 = 0.2.31/0.2.32 其余全部")

    focus = [r for r in scan.current if "OutOfMemoryError" in r.err_type or "CodeEE" in r.err_type]
    expect(len(focus) == 2, "当前口径 OOM + CodeEE 各一条")
    expect(any("OutOfMemoryError" in r.err_type for r in focus), "OOM 被点名")
    expect(any("CodeEE" in r.err_type for r in focus), "CodeEE 被点名")

    heap = diagnose(parse_report(corpus[4]).trail)
    expect(heap.mode == "Java 堆贴顶", "467/512 判 Java 堆贴顶")
    expect(heap.peak_native == 251 and heap.peak_percent == 91, "峰值读数")

    native = diagnose([TrailSample(0, 210, 512, 304, "pause")])
    expect(native.mode == "native 驻留偏高", "堆不高 native 高")

    neither = diagnose([TrailSample(0, 60, 576, 18, "pause")])
    expect(neither.mode == "两者都不高（映射或信号）", "两者都不高")

    expect(diagnose([]).mode == "无轨迹", "空轨迹")

    boundary = diagnose([TrailSample(0, 435, 512, 200, "pause")])
    expect(boundary.peak_percent == 84 and boundary.mode == "native 驻留偏高", "85% 边界下方")
    boundary = diagnose([TrailSample(0, 436, 512, 200, "pause")])
    expect(boundary.peak_percent == 85 and boundary.mode == "Java 堆贴顶", "85% 边界")

    trim = diagnose([TrailSample(0, 500, 512, 20, "trim=uiHidden freed=0MiB"),
                     TrailSample(1, 500, 512, 20, "trim=runningCritical freed=0MiB")])
    expect(trim.trim_events == 2 and trim.trim_freed == 0, "trim 事件与收回量")

    # ---- 0.2.33 起的新列：sys= / lmk=（系统可用内存 / 本机低内存线） ----
    with_sys = parse_report(
        _fake_report(
            "上次异常退出", "0.2.33", "9a1b2c3", "vivo V2199A", "",
            trail=("t=1500ms used=301MiB max=512MiB native=88MiB turn 42ms sys=820MiB lmk=180MiB",),
        )
    )
    expect(len(with_sys.trail) == 1, "带新列的轨迹能解析")
    sample = with_sys.trail[0]
    expect(sample.phase == "turn 42ms", "sys/lmk 从阶段名里摘掉")
    expect(sample.sys_avail_mib == 820 and sample.lmk_threshold_mib == 180, "sys/lmk 读回")

    low = diagnose([TrailSample(0, 172, 512, 60, "pause",
                                sys_avail_mib=172, lmk_threshold_mib=180)])
    expect(low.mode == "系统内存不足（被 LMK 收掉）", "跌破低内存线判系统内存不足")
    expect("系统最低可用 172 MiB" in low.headline, "系统读数进 headline")
    expect("LMK" in low.mode, "系统桶点名 LMK")

    expect(diagnose([TrailSample(0, 60, 576, 18, "pause",
                                 sys_avail_mib=180, lmk_threshold_mib=180)]).mode
           == "两者都不高（映射或信号）", "恰好等于低内存线不算跌破")
    expect(diagnose([TrailSample(0, 60, 576, 18, "pause",
                                 sys_avail_mib=179, lmk_threshold_mib=180)]).mode
           == "系统内存不足（被 LMK 收掉）", "低一 MiB 就算跌破")
    expect(diagnose([TrailSample(0, 60, 576, 18, "pause", sys_avail_mib=172)]).mode
           == "两者都不高（映射或信号）", "缺 lmk 不往系统桶里推")
    expect(diagnose([TrailSample(0, 460, 512, 60, "pause",
                                 sys_avail_mib=100, lmk_threshold_mib=180)]).mode
           == "Java 堆贴顶", "Java 堆贴顶时系统压力不抢判")

    text = render(scan)
    expect("0.2.31 脏退出误报" in text, "输出带误报单列")
    expect("旧版本（不计入当前口径）" in text, "输出带旧版单列")
    expect("当前口径：OutOfMemoryError / CodeEE" in text, "输出带新增问题单列")
    expect("静默退出判读" in text, "输出带静默退出判读")
    expect("份带 sys= 列" in text, "输出带 sys= 覆盖率（别把缺列当成没压力）")

    # 解析健壮性：空串与半份报告不炸
    empty = parse_report("")
    expect(empty.kind == "" and empty.trail == [], "空报告可解析")

    # ---- EP1/EP2 波五处新面：合成最小形状 + 数值列断言 ------------------------------
    # 形状逐字对齐产品代码（前任注释出处核对过、无出入）：
    #   stallstack 轨迹行 = StallSentinel.kt:322-331 trailPhase（TRAIL_PHASE「stallstack」:157；
    #     判读键 ui-stalled/all-stalled/publish-stalled = Heartbeat.kt:251-254；vm 缺读走
    #     HeartbeatJudgment.NOT_MEASURED「未采到」= Heartbeat.kt:189，栈顶缺帧走
    #     StallJudgment.NOT_CAPTURED「未取到」:163）
    #   冻结小节头 = StallSentinel.kt:160 SCENE_HEADER；帧行「简单类名.方法:行号」= frameLine :246；
    #     抓到/没抓到两形见 sceneLines :307/:309。
    #   heapcensus 轨迹行 = GameHost.kt:3080 censusTrailLine（`heapcensus used=<n>MiB/<max>` + render）；
    #     内存节独立行 = memoryCensusReportLine → LiveSetCensus.kt:163 render() 行首「Java 堆结构账面」。
    #   errdup/errcap = ErrorReportCollector.kt:1387 pinPhase `$tag x$count $display`；
    #     display「位置｜消息」分隔符全角 ｜ = displayOf :1482。
    #   新 trim 分项 = GameHost.kt:3563-3568 NativeTrimPlan.readoutLine（` bitmap=->view=->native=->[ deep]`），
    #     挂在 MainActivity.kt:811 的 `trim=<档> freed=<n>MiB` 之后。
    #   轨迹行整体格式 = RunWatch.kt:678 formatLine `t=<n>ms used=<n>MiB max=<n>MiB native=<n>MiB <阶段>`
    #     ⇒ 阶段名直接拼在行尾的 native= 之后，所以「行首 native=119」与「分项 native=300->244」是两列。
    def nf(trail, scene=None, census=False):
        text = "\n".join(
            [
                "================================================================",
                "eraTW 安卓移植版 错误报告",
                "报告类型：上次异常退出",
                "app 版本：0.2.37 (versionCode 9)",
                "设备：Test Device",
                "----------------------------------------------------------------",
                "[补充现场]",
                *trail,
                "----------------------------------------------------------------",
            ]
        ) + "\n"
        if scene == "captured":
            text += "\n".join(
                [
                    "[主线程冻结现场（哨兵抓栈，最内帧在前）]",
                    "主线程距今=3200ms（阈值1500ms）判读=ui-stalled",
                    "脚本线程距今=未采到",
                    "主线程 main：",
                    "GameSurface.draw:412",
                    "脚本 eratw-vm：",
                    "VmEngine.run:88",
                    "----------------------------------------------------------------",
                ]
            ) + "\n"
        elif scene == "missing":
            text += "\n".join(
                [
                    "[主线程冻结现场（哨兵抓栈，最内帧在前）]",
                    "主线程距今=5000ms（阈值1500ms）判读=all-stalled",
                    "脚本线程距今=未采到",
                    "主线程 main：",
                    "未取到",
                    "脚本 eratw-vm：",
                    "未取到",
                    "----------------------------------------------------------------",
                ]
            ) + "\n"
        if census:
            text += "\n".join(
                [
                    "[内存]",
                    "Java 堆结构账面（估算口径，非 dominator tree） ｜装载：函数 1200 指令 550000",
                    "----------------------------------------------------------------",
                ]
            ) + "\n"
        return text + "报告结束\n"

    stall_lines = [
        "t=61000ms used=150MiB max=512MiB native=90MiB "
        "stallstack ui-stalled uiFrozen=1500ms vm=120ms frames=8 ui@GameSurface.draw:412 vm@VmEngine.run:88",
        "t=62000ms used=160MiB max=512MiB native=91MiB "
        "stallstack ui-stalled uiFrozen=3200ms vm=未采到 frames=24 ui@Handler.dispatchMessage:102",
        "t=63000ms used=170MiB max=512MiB native=92MiB "
        "stallstack all-stalled uiFrozen=5000ms vm=60ms frames=12 ui@GLSurface.queueEvent:1 vm@VmEngine.run:88",
    ]
    census_lines = [
        "t=71000ms used=431MiB max=512MiB native=119MiB "
        "heapcensus used=460MiB/512 Java 堆结构账面（估算口径） ｜装载：函数 1200",
        "t=72000ms used=435MiB max=512MiB native=121MiB "
        "heapcensus used=480MiB/512 Java 堆结构账面（估算口径） ｜装载：函数 1250",
        "t=73000ms used=440MiB max=512MiB native=118MiB "
        "heapcensus used=500MiB/512 Java 堆结构账面（估算口径） ｜装载：函数 1300",
    ]
    errx_lines = [
        "t=81000ms used=200MiB max=512MiB native=90MiB "
        "errdup x21 QOL_GATHERING_DRAWING 行69｜RAND 的参数必须为正数（实际 0）",
        "t=82000ms used=200MiB max=512MiB native=90MiB "
        "errdup x7 EVENTTRAIN#4719｜执行时间超过上限",
        "t=83000ms used=200MiB max=512MiB native=90MiB "
        "errdup x14 MainScene#120｜空指针异常",
        "t=84000ms used=200MiB max=512MiB native=90MiB errcap x3 FooBar#5｜本局额度已满",
    ]
    trim_lines = [
        "t=91000ms used=431MiB max=512MiB native=119MiB "
        "trim=uiHidden freed=0MiB bitmap=120->0MiB view=41->8MiB native=300->244MiB deep",
        "t=92000ms used=435MiB max=512MiB native=119MiB "
        "trim=runningCritical freed=10MiB bitmap=80->40MiB view=30->12MiB native=310->260MiB",
        "t=93000ms used=440MiB max=512MiB native=119MiB "
        "trim=background freed=20MiB bitmap=60->20MiB view=50->20MiB native=290->250MiB deep",
        # 旧 trim（无箭头分项）：0.2.36 之前的形状，绝不能被当成新面样本
        "t=94000ms used=441MiB max=512MiB native=119MiB trim=uiHidden freed=0MiB",
    ]

    S = parse_report(nf(stall_lines[:2], scene="captured"))
    S2 = parse_report(nf(stall_lines[2:], scene="missing"))
    H = parse_report(nf(census_lines, census=True))
    E = parse_report(nf(errx_lines))
    T = parse_report(nf(trim_lines))
    H2 = parse_report(nf(census_lines))  # 只有 heapcensus 轨迹行、没有独立内存行
    faces = [S, S2, H, E, T]

    # 前缀归桶：老 trim 落空、其余四个新前缀各归各位（缺 ≠ 0 的守门）
    expect(trail_prefix_of(T.trail[3].phase) is None, "老 trim（无箭头分项）不计入新面")
    expect(trail_prefix_of(S.trail[0].phase) == "stallstack", "stallstack 前缀归位")
    expect(trail_prefix_of(H.trail[0].phase) == "heapcensus", "heapcensus 前缀归位")
    expect(trail_prefix_of(E.trail[0].phase) == "errdup", "errdup 前缀归位")
    expect(trail_prefix_of(E.trail[3].phase) == "errcap", "errcap 前缀归位")
    expect(trail_prefix_of(T.trail[0].phase) == "trim", "带箭头的新 trim 归位")

    # stallstack 的 vm 列：数字取到、未采到时该列缺（不拍平成 0）
    stall_m = STALLSTACK_RE.match(S.trail[0].phase)
    expect(stall_m is not None and stall_m.group(3) == "120", "stallstack vm=120ms 单列取到")
    stall_m2 = STALLSTACK_RE.match(S.trail[1].phase)
    expect(stall_m2 is not None and stall_m2.group(3) is None, "stallstack vm=未采到时列缺不填 0")

    bucket_lines, bucket_total = new_trail_bucket_lines(faces)
    bucket_text = "\n".join(bucket_lines)
    # 每桶钉死数值列（min/中位/max），不是只断言「不抛异常」
    expect(
        "stallstack：3 行 · uiFrozen min1500/中位3200/max5000 · frames min8/中位12/max24"
        in bucket_text,
        "stallstack 桶读数",
    )
    expect(
        "heapcensus：3 行 · used min460/中位480/max500 · max min512/中位512/max512 "
        "· 水位% min89/中位93/max97" in bucket_text,
        "heapcensus 桶读数",
    )
    expect("errdup：3 行 · x计数 min7/中位14/max21" in bucket_text, "errdup 桶读数")
    expect("errcap：1 行 · x计数 min3/中位3/max3" in bucket_text, "errcap 桶读数")
    expect(
        "trim：3 行 · freed min0/中位10/max20 · bitmap前 min60/中位80/max120 "
        "· bitmap后 min0/中位20/max40 · view前 min30/中位41/max50 · view后 min8/中位12/max20 "
        "· native前 min290/中位300/max310 · native后 min244/中位250/max260 · 深档 2/3 行"
        in bucket_text,
        "新 trim 桶读数（三列箭头 + 深档计数）",
    )
    expect(bucket_total == 13, "新前缀轨迹行总数（老 trim 不入账）")

    # 两列不许拍平：heapcensus 行首 RunWatch used= 与分项 used= 分开取
    expect(H.trail[0].used_mib == 431, "heapcensus 行首 RunWatch used=431MiB")
    expect(
        HEAPCENSUS_RE.match(H.trail[0].phase).group(1) == "460",
        "heapcensus 分项 used=460MiB（与行首两列）",
    )
    expect(
        max(s.used_mib for s in H.trail) == 440 and "used min460" in bucket_text,
        "行首 used(max 440) 与分项 used(min 460) 分列不混",
    )
    # 两列不许拍平：新 trim 行首 native= 与箭头 native= 分开取
    expect(T.trail[0].native_mib == 119, "新 trim 行首 RunWatch native=119MiB")
    trim_native_arrow = NATIVE_ARROW_RE.search(T.trail[0].phase)
    expect(
        trim_native_arrow is not None and trim_native_arrow.groups() == ("300", "244"),
        "新 trim 箭头 native=300->244MiB 单取（不与行首 119 混）",
    )

    # 小节覆盖率（total=5：S/S2 带冻结节，H 带独立内存行）
    cov_text = "\n".join(section_coverage_lines(faces))
    expect(
        "[主线程冻结现场] 小节：2/5（40%）；其中抓到栈的 1/5（20%）" in cov_text,
        "冻结小节覆盖率（抓到栈只算 S，S2 没抓到）",
    )
    expect(
        "内存节 Java 堆分项（`Java 堆结构账面…`）：1/5（20%）" in cov_text,
        "内存节 Java 堆分项覆盖率（只有 H 有独立行）",
    )

    # 小节 / 分项 flags：缺节记 False 不当 0；轨迹里嵌的「Java 堆结构账面」不算独立内存行
    expect(S.has_stall_scene and S.stall_scene_captured, "S 有冻结节且抓到栈")
    expect(S2.has_stall_scene and not S2.stall_scene_captured, "S2 有冻结节但没抓到栈")
    expect(H.has_heap_census_line, "H 有独立内存分项行")
    expect(not E.has_heap_census_line, "E 没有内存分项行")
    expect(not H2.has_heap_census_line, "只带 heapcensus 轨迹行不算独立内存行（两码事）")

    # ==========================================================================
    # 2026-11-04 inbox 波：四件事各有夹具，夹具正文全部取自真实收件箱（raw-500）
    # ==========================================================================
    expect(FINGERPRINT_FIXTURES.is_file(),
           f"对拍夹具在位（缺了它端点与脚本两侧口径就没人守）：{FINGERPRINT_FIXTURES}")
    fixtures = json.loads(FINGERPRINT_FIXTURES.read_text(encoding="utf-8"))
    by_name = {case["name"]: case for case in fixtures["cases"]}

    # ---- 事一：内容指纹与端点逐字同口径（fp / 身份位 / 四个聚合维度）----
    for case in fixtures["cases"]:
        want = case["expect"]
        fp, identity = content_fingerprint(case["body"])
        expect(fp == want["fp"], f"夹具 {case['name']}：sha256 与端点侧同值")
        expect(identity == want["identity"], f"夹具 {case['name']}：身份位同值（决定时间进不进哈希）")
        lines = split_lines(case["body"])
        error = error_section_lines(lines)
        message = line_field(error, MESSAGE_LINE_PREFIX)
        script = line_field(error, SCRIPT_LINE_PREFIX)
        expect(report_signature_code(message, script) == want["sig_code"], f"夹具 {case['name']}：签名码同值")
        expect(version_slug_of(version_from_lines(lines)) == want["version_slug"],
               f"夹具 {case['name']}：版本位同值（取不到=q，正是 ver=? 的那一支）")
        expect(device_code_of(line_field(lines, "设备：")) == want["device_code"], f"夹具 {case['name']}：机型码同值")
        expect(kind_code_of(line_field(lines, "报告类型：")) == want["kind_code"], f"夹具 {case['name']}：类型码同值")
        expect((DEGRADED_MARK in case["body"]) == want["degraded"], f"夹具 {case['name']}：退化位同值")
    for pair in fixtures["pairs"]:
        a, b = by_name[pair["a"]]["expect"], by_name[pair["b"]]["expect"]
        expect((a["fp"] == b["fp"]) is pair["expect_same_fp"], f"夹具 pairs：{pair['why']}")

    # ---- 事二/事三：退化报告与 ver=? 走本地判读（真实正文，不是理想化数据）----
    def _swap(body: str, prefix: str, value: str) -> str:
        """只换正文里第一处该前缀的那一行（用来造「同一件事换个时间/换台机」的真实变体）。"""
        hit = False
        out = []
        for line in body.split("\n"):
            if not hit and line.startswith(prefix):
                out.append(prefix + value)
                hit = True
            else:
                out.append(line)
        return "\n".join(out)

    minimal_body = by_name["minimal-degraded"]["body"]  # 2026-11-03 内存见底那份，330 B
    minimal_no_msg = by_name["minimal-degraded-no-message"]["body"]
    shifted_no_msg = by_name["minimal-no-message-time-shifted"]["body"]
    probe_body = by_name["probe"]["body"]
    step_body = by_name["step-limit-legacy"]["body"]

    m1 = parse_report(minimal_body)
    expect(m1.degraded and m1.version == "" and m1.device == "", "真实退化报告：无版本行、无设备行")
    expect(m1.err_msg.startswith("Failed to allocate"),
           "真实退化报告的「信息」读得出（没有 [错误] 节名行时退回整份正文取数）")
    expect(m1.fp == by_name["minimal-degraded"]["expect"]["fp"], "退化报告的指纹走同一条归一规则")

    m2 = parse_report(minimal_no_msg)
    m3 = parse_report(shifted_no_msg)
    expect(m2.identity is False and m3.identity is False, "两份无身份退化报告（连信息都没写出来）")
    expect(m2.fp != m3.fp, "无身份 + 不同报告时间 ⇒ 本地也不并（宁可留双份）")
    scanDeg = analyze([parse_report(minimal_body), m2, m3, parse_report(probe_body)])
    expect(len(scanDeg.unknown_version) == 3, "三份最小退化报告都进 ver=? 桶（本轮真实 3 份的形状）")
    expect(len(scanDeg.ignored) == 1, "探针无报告类型 ⇒ 仍按老口径忽略（连 ver=? 都不进）")
    expect(len(scanDeg.current) == 3, "ver=? 的三份退化报告仍计入当前口径，只是不许派版本")

    # 退化且无信息 ⇒ 不参与签名（本轮那 3 份就是这么被误并成两个假簇的）
    expect(_report_signature_key(m2) is None, "退化且无信息的报告不参与签名")
    deg_with_script = minimal_no_msg.replace("报告结束\n", "脚本位置：CHAT_ACTION 行34\n报告结束\n")
    dws = parse_report(deg_with_script)
    expect(dws.script == "CHAT_ACTION 行34", "退化报告带上脚本位置时读得出来")
    expect(_report_signature_key(dws) is None, "退化报告只有脚本位置没有信息 ⇒ 仍不参与签名")
    nond = deg_with_script.replace("本文件是最小退化报告", "（非退化：完整报告已生成）")
    expect(_report_signature_key(parse_report(nond)) is not None,
           "非退化报告只有脚本位置也照样归簇（新判据只咬退化那一形，没把老口径放宽）")
    expect(_report_signature_key(m1) is not None, "退化但有信息的报告仍参与签名（信息就是它的身份）")

    # ---- 事三：按版本的一句话结论 + 无签名桶 ----
    # 那份真实 StepLimitExceeded 正文带的是 0.2.3（旧版桶），换成本轮在跑的 0.2.39 才看得见「本版结论」那一行。
    step_modern = parse_report(_swap(step_body, "app 版本：", "0.2.39 (versionCode 15)"))
    silent_body = by_name["silent-exit-trail"]["body"]
    scanV = analyze([step_modern, parse_report(minimal_body), m2, parse_report(silent_body)])
    version_text = "\n".join(version_defect_lines(scanV))
    expect("ver=0.2.39" in version_text and "本版还剩 1 类真缺陷" in version_text,
           "有版本的行给出「还剩几类真缺陷」（一份签名 + 一份静默退出 ⇒ 只算 1 类）")
    expect("ver=?" in version_text and "取不到版本" in version_text,
           "ver=? 那行不下结论（一类别派到某个版本头上）")
    bucket_text = "\n".join(no_signature_bucket_lines(scanV.current))
    expect("ver=?" in bucket_text and "退化 1 份" in bucket_text,
           "无签名桶按版本如实报数并标出退化份（退化份不再消失，也不再冒充签名簇）")
    expect("带轨迹 1 份" in bucket_text,
           "静默退出（有轨迹、无异常信息）留在无签名桶 —— 那是它的正常形状，不是缺陷数")
    unknown_text = "\n".join(unknown_version_lines(scanV.unknown_version))
    expect("进 ver=? 桶" in unknown_text and "共 2 份" in unknown_text, "ver=? 桶给出份数与形状")

    # ---- 事三：同机连发 vs 跨机同签名（时间取真实收件箱：vivo V2528A 在 QOL_GATHERING_DRAWING 行69 上连发）----
    burst_times = (
        "2026-11-02 03:36:49", "2026-11-02 03:38:36", "2026-11-02 03:39:26",
        "2026-11-02 03:40:11", "2026-11-02 12:45:13",
    )
    same_machine = [parse_report(_swap(step_body, "报告时间：", when)) for when in burst_times]
    clusters = signature_clusters(same_machine)
    expect(len(clusters) == 1 and clusters[0].count == 5, "同机同签名五份归一簇（时间不同不影响签名）")
    expect(clusters[0].episodes == 2,
           "03:36~03:40 四次落在 30 分钟窗口内 = 一次事件，12:45 另起一次 ⇒ 2 次事件")
    expect(clusters[0].repeats == 3, "同机连发 3 份（玩家在反复试同一招，不是面上扩散三份）")
    expect(clusters[0].machines == 1 and clusters[0].face == "单机反复（非面上）", "单机簇判为「反复试」，不冒充面上问题")
    expect(clusters[0].versions == ["0.2.3"], "簇的版本列如实带出")

    cross = same_machine + [
        parse_report(_swap(step_body, "设备：", "Xiaomi 25113PN0EC")),
        parse_report(_swap(_swap(step_body, "设备：", "Xiaomi 25113PN0EC"), "报告时间：", "2026-11-02 09:00:00")),
    ]
    crossClusters = signature_clusters(cross)
    expect(crossClusters[0].machines == 2 and crossClusters[0].face == "面上问题（跨机）",
           "跨机同签名才判面上问题")
    face_text = "\n".join(signature_face_lines(crossClusters))
    expect("面上问题（≥2 台机同签名）：1 簇" in face_text, "面上/单机的分账写在输出里")
    singleFace = "\n".join(signature_face_lines(clusters))
    expect("单机反复（只有 1 台机）：1 簇" in singleFace, "单机的簇不会被写进面上那本账")

    # ---- 事二：端点聚合表的判读侧渲染（回包字段与 worker handleStats 逐字同构）----
    stats_sample = {
        "occurrences": 617, "incidents": 562, "duplicate_uploads": 55,
        "signatures_total": 2, "signatures_returned": 2,
        "signatures_truncated": False, "listing_truncated": False,
        "backfill_hint": "历史正文不在 occ 索引里：跑 POST /reindex 回填",
        "limit_note": "list 最多翻 20 页（每页 1000 键），签名簇最多列 200 个",
        "signatures": [
            {
                "sig": "a7750c54", "occurrences": 130, "incidents": 128, "devices": 1,
                "signatures": 1, "degraded_occurrences": 0,
                "first_upload_ms": 1793590000000, "last_upload_ms": 1793790000000,
                "versions": [{"version": "0.2.34", "occurrences": 130, "incidents": 128,
                              "devices": 1, "signatures": 1, "degraded_occurrences": 0}],
                "label": {"message": "RAND 的参数必须为正数（实际 N）", "script": "QOL_GATHERING_DRAWING 行69"},
            },
            {
                "sig": "9bcc9efd", "occurrences": 4, "incidents": 4, "devices": 4,
                "signatures": 1, "degraded_occurrences": 1,
                "first_upload_ms": 1793700000000, "last_upload_ms": 1793790000000,
                "versions": [{"version": "0.2.39", "occurrences": 4, "incidents": 4,
                              "devices": 4, "signatures": 1, "degraded_occurrences": 1}],
                "label": {"message": "No native method ModelSet found", "script": "CHAT_ACTION 行34"},
            },
        ],
        "no_signature": {"occurrences": 190, "incidents": 430, "devices": 60, "signatures": 0,
                         "degraded_occurrences": 3, "first_upload_ms": 1793100000000,
                         "last_upload_ms": 1793790000000},
        "versions": [
            {"version": "0.2.34", "occurrences": 150, "incidents": 148, "devices": 20,
             "signatures": 3, "degraded_occurrences": 0, "no_signature_occurrences": 20},
            {"version": "?", "occurrences": 5, "incidents": 5, "devices": 0,
             "signatures": 0, "degraded_occurrences": 3, "no_signature_occurrences": 5},
        ],
        "devices": [{"device_code": "7c0e8085", "occurrences": 130, "incidents": 128, "devices": 1,
                     "signatures": 1, "degraded_occurrences": 0}],
    }
    stats_text = render_stats(stats_sample)
    expect("上报 617 条 = 去重后 562 起事故 + 重复上传 55 条" in stats_text, "聚合表先给「白拉了多少份」")
    expect("RAND 的参数必须为正数（实际 N） @ QOL_GATHERING_DRAWING 行69" in stats_text,
           "签名簇行带归一后的信息 + 行号（行号不抹）")
    expect("130 条 ·  128 起 ·   1 台机" in stats_text, "签名簇 × 机型数一行读得出")
    expect("ver=?" in stats_text.splitlines()[-3] or "ver=?" in stats_text, "版本表里 ver=? 单列")
    expect("退化 3 份" in stats_text.split("无签名桶")[1], "无签名（退化/最小）单独一桶且标退化份数")
    expect("历史正文不在 occ 索引里" in stats_text, "聚合表把「只统计入库之后」的口径写在脸上")
    # 旧版端点缺字段时不许炸（缺 = 如实空，不拿 0 冒充读数）
    legacy_stats = render_stats({"occurrences": 3})
    expect("上报 3 条" in legacy_stats and "签名簇 0 个" in legacy_stats, "旧端点回包缺字段仍出得来表，不硬编 0")

    # ---- 去重：本地目录模式与端点同一个数（正文只差时间 ⇒ 一条；只差机型 ⇒ 两条）----
    scanD = analyze([parse_report(step_body),
                     parse_report(by_name["step-limit-time-shifted"]["body"]),
                     parse_report(step_body)])
    expect(len(scanD.reports) == 1 and scanD.duplicates == 2,
           "本地去重走内容指纹：同一内容的三次上传算一起（与端点 incidents 同口径）")
    scanD2 = analyze([parse_report(step_body), parse_report(_swap(step_body, "设备：", "Xiaomi 25113PN0EC"))])
    expect(len(scanD2.reports) == 2, "机型不同 = 两起事故（设备行进指纹，不被并掉）")

    print(f"selftest ok ({checks} checks)")
    return 0


# ----------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="上报收件箱分桶（构建戳 × 机型 × 异常类型）")
    parser.add_argument("--dir", type=Path, help="扫描本地报告目录（递归 .txt）")
    parser.add_argument("--fetch", action="store_true", help="从端点拉取（用 local.report.properties）")
    parser.add_argument("--keep", type=Path, help="--fetch 时把原文落盘到这个目录")
    parser.add_argument("--stats", action="store_true",
                        help="只读端点 GET /stats 出聚合表（签名簇 × 版本 × 机型数），一份正文都不拉")
    parser.add_argument("--stats-in", type=Path, metavar="JSON",
                        help="把已经存好的 /stats 回包渲成表（离线复核 / selftest 用，不联网）")
    parser.add_argument("--out", type=Path, help="输出文件（UTF-8）；缺省 stdout")
    parser.add_argument("--selftest", action="store_true", help="用合成报告自检分桶与判读口径")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if args.stats or args.stats_in:
        if args.dir or args.fetch:
            parser.error("--stats / --stats-in 不与 --dir / --fetch 混用（聚合表自己就是一轮判读）")
        if args.stats_in:
            if not args.stats_in.is_file():
                raise SystemExit(f"文件不存在：{args.stats_in}")
            try:
                stats = json.loads(args.stats_in.read_text(encoding="utf-8"))
            except json.JSONDecodeError as error:
                raise SystemExit(f"{args.stats_in} 不是合法 JSON：{error}") from error
        else:
            stats = fetch_stats()
        output = render_stats(stats)
        if args.out:
            args.out.write_text(output, encoding="utf-8")
            print(f"已写出 {args.out}（{len(output)} 字符）", file=sys.stderr)
        else:
            if hasattr(sys.stdout, "reconfigure"):
                sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            print(output)
        return 0

    if args.dir and args.fetch:
        parser.error("--dir 与 --fetch 只能二选一")
    if not args.dir and not args.fetch:
        parser.error("需要 --dir 或 --fetch 或 --stats（或 --selftest）")

    if args.fetch:
        reports = fetch_reports(args.keep)
    else:
        if not args.dir.is_dir():
            raise SystemExit(f"目录不存在：{args.dir}")
        reports = load_dir(args.dir)

    output = render(analyze(reports))
    if args.out:
        args.out.write_text(output, encoding="utf-8")
        print(f"已写出 {args.out}（{len(output)} 字符）", file=sys.stderr)
    else:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
