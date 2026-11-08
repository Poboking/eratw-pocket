#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""app/src/main 的 Kotlin 源码里 CJK 内联字符串字面量扫描（T-4 骨架）。

背景：玩家面文案正在分波收进三语表（ShellFailureCopy / PortraitShellCopy / ModImportCopy /
UpdateCopy / SettingsStrings）。本脚本量的是**还剩多少没收**、以及收完后当闸（--gate）。

四层豁免，各有各的语义，不许混：

W_TABLE      三语文案表本体（中英日三档全是「字面量」是它的常态）。
W_FORENSICS  崩溃取证面：崩溃线程上只剩中文这一条活路（ErrorReportCollector 全部、
             RunWatch 的口径记录），翻成三语反而毁掉取证对照。
W_ANCHOR     语料锚点：拿去**匹配上游 ERB 原话**的常量（HudLineParser），搬进三语表
             匹配就失效 —— 与 UpdateNotifyCopyTest 的 SPACE_PROBLEM_HINT 同一法理。
W_LEGACY     台账第④域存量（本波未收口、挂账在案）：UiPrefs 28 条、UpdateManager
             原话面 27 条、两个 FileProvider 20 条。存量清零一个就删一个；**全部清零后
             本段删除，--gate 即为常开闸**。

行级豁免（W_INLINE，对任何文件生效）：
  - 剥完注释后的 `Log.` 行（logcat 取证面，玩家看不见；上屏槽位另有测试闸兜着）；
  - `demoLines` 标记的行（开发退化面，组长已裁）；
  - 字面量含品牌字 `eraThe World`；
  - `AndroidManifest` 之类的非 Kotlin 文件不扫（--paths 指什么扫什么）。

语料侧 core 模块（core-vm 等）本就不在默认 --paths 里：语料读数原样是它的职责，
不是「内联文案」。要扩面用 --paths app/src/main render render-canvas（模块目录名）。

用法：
  python devtools/cjk_inline_scan.py             # 报告：按文件统计剩余 CJK 字面量
  python devtools/cjk_inline_scan.py --gate      # 非白名单 CJK 字面量非零退出（存量清零后挂 CI）
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# CJK 表意文字 + 假名 + 全角标点（全角标点单独不构成违例的前提是它在一个
# 豁免键里；这里先粗判「含 CJK 成分」，细口径交给豁免层）。
CJK = re.compile(
    "[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3000-\u303f\uff01-\uff60]"
)

STRING_LIT = re.compile(r'"((?:\\.|[^"\\])*)"')
LINE_COMMENT = re.compile(r'(^|[^:])//.*$')
BRAND = "eraThe World"

# ---------------------------------------------------------------- 白名单

# 三语文案表本体：中英日三档字面量是它的常态（W_TABLE）。
W_TABLE = {
    "ShellFailureCopy.kt",
    "PortraitShellCopy.kt",
    "ModImportCopy.kt",
    "UpdateCopy.kt",
    "SettingsStrings.kt",
}

# 崩溃取证面：崩溃线程上只剩中文这一条活路（W_FORENSICS）。翻成三语反而毁掉取证对照：
# ErrorReportCollector 全部取证串、RunWatch 的口径记录、CrashForensics 的崩溃现场与
# 启动失败屏兜底、StrictModeGuard 的侦测判读 message、RunDiagnostics/StallSentinel/
# Heartbeat/LoadTrace 的取证节标题与判读 —— 这些串的本职是落进取证报告与 logcat 的
# 原话，不是玩家面；玩家面另有上屏槽位测试闸兜着。
W_FORENSICS = {
    "ErrorReportCollector.kt",
    "RunWatch.kt",
    "CrashForensics.kt",
    "StrictModeGuard.kt",
    "RunDiagnostics.kt",
    "StallSentinel.kt",
    "Heartbeat.kt",
    "LoadTrace.kt",
}

# 语料锚点：拿去匹配上游 ERB 原话，不是玩家面（W_ANCHOR）；搬进三语表匹配就失效 ——
# 与 UpdateNotifyCopyTest 钉的 SPACE_PROBLEM_HINT 同一法理。PortraitHud 的体力/气力/法力
# 等单字是 HUD 行首的语料匹配锚（portrait 包的语料面）。
W_ANCHOR = {
    "HudLineParser.kt",
    "PortraitHud.kt",
}

# 开发者调试通道（vmdbg / DevTools 的 CLI 输出）：玩家看不见（W_DEVTOOLS）。
# 要收口也是独立波次，不与玩家面文案同账。
W_DEVTOOLS = {
    "DevState.kt",
    "DevToolsHost.kt",
    "DevProbe.kt",
}

# 台账第④域存量（本波未收口，挂账在案；清零一个删一个）（W_LEGACY）。
# 对账基准：2026-11-04 扫描读数（见各文件的 count 注记）。
W_LEGACY = {
    "UiPrefs.kt",           # 第④域：设置项名与摘要 28 条
    "UpdateManager.kt",     # 第④域：上游判读原话面 27 条
    "ReportFileProvider.kt",  # 第④域：两个 FileProvider 合计 20 条
    "UpdateFileProvider.kt",
}

# 行级豁免标记（W_INLINE）：剥完注释后的代码行含这些词才放行。
# - Log.：logcat 取证面（玩家看不见；上屏槽位另有测试闸兜住同一行发 Toast 的一手）。
# - demoLines：开发退化面（组长已裁）。
# - throw InterruptedException(：只进 logcat 的异常消息（UpdateNotifyCopyTest 钉「只许这一条」）。
# - 两个对拍锚常量：FAILURE_PREFIX（HeapFailureCopyTest 钉它与 ZH 表 failurePrefix 同文）、
#   SPACE_PROBLEM_HINT（上游判读锚，同 W_ANCHOR 法理）。
INLINE_MARKERS = ("Log.", "demoLines", "throw InterruptedException(", "FAILURE_PREFIX =", "SPACE_PROBLEM_HINT")

DEFAULT_PATHS = ("app/src/main",)


def strip_comments(lines: list[str]) -> list[str]:
    """按行剥 // 与 /* .. */（含嵌套 —— Kotlin 的块注释可以嵌套，见仓铁律）。

    三引号串不剥：跨行报错模板里若含 CJK，本扫描视它为字面量照报（骨架口径）。
    """
    out: list[str] = []
    depth = 0
    for line in lines:
        rest: list[str] = []
        i = 0
        n = len(line)
        while i < n:
            if depth > 0:
                hit = line.find("*/", i)
                if hit < 0:
                    i = n
                else:
                    depth -= 1
                    i = hit + 2
                continue
            two = line[i:i + 2]
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "//":
                break
            rest.append(line[i])
            i += 1
        out.append("".join(rest))
    return out


def scan_file(path: Path) -> list[dict]:
    """返回该文件的非白名单 CJK 字面量命中（含行号与原文样例）。"""
    code_lines = strip_comments(path.read_text(encoding="utf-8").splitlines())
    hits: list[dict] = []
    for no, line in enumerate(code_lines, start=1):
        if not CJK.search(line):
            continue
        if any(marker in line for marker in INLINE_MARKERS):
            continue
        for match in STRING_LIT.finditer(line):
            text = match.group(1)
            if BRAND in text:
                continue
            if CJK.search(text):
                hits.append({"line": no, "sample": text[:60]})
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--paths",
        nargs="*",
        default=list(DEFAULT_PATHS),
        help="扫描根（相对仓根的模块目录），默认 app/src/main",
    )
    parser.add_argument("--gate", action="store_true", help="非白名单 CJK 字面量非零退出（存量清零后的闸模式）")
    parser.add_argument("--json", action="store_true", help="输出 JSON（给 CI 摘读数用）")
    args = parser.parse_args()

    roots = [REPO / p for p in args.paths]
    for root in roots:
        if not root.is_dir():
            print(f"扫描根不存在：{root}", file=sys.stderr)
            return 2

    files = sorted(p for root in roots for p in root.rglob("*.kt"))
    report: dict[str, list[dict]] = {}
    for path in files:
        if (
            path.name in W_TABLE
            or path.name in W_FORENSICS
            or path.name in W_ANCHOR
            or path.name in W_DEVTOOLS
            or path.name in W_LEGACY
        ):
            continue
        hits = scan_file(path)
        if hits:
            report[str(path.relative_to(REPO)).replace("\\", "/")] = hits

    total = sum(len(v) for v in report.values())
    if args.json:
        print(json.dumps({"total": total, "files": report}, ensure_ascii=False, indent=2))
    else:
        print(f"扫描 {len(files)} 个 Kotlin 文件：非白名单 CJK 字面量 {total} 条，分布如下\n")
        for name, hits in report.items():
            print(f"{name}: {len(hits)} 条")
            for hit in hits[:5]:
                print(f"  L{hit['line']}: {hit['sample']}")
            if len(hits) > 5:
                print(f"  … 还有 {len(hits) - 5} 条")
        print(
            "\n白名单段落："
            "\n  W_TABLE（三语文案表本体）: " + ", ".join(sorted(W_TABLE))
            + "\n  W_FORENSICS（崩溃取证面）: " + ", ".join(sorted(W_FORENSICS))
            + "\n  W_ANCHOR（语料锚点）: " + ", ".join(sorted(W_ANCHOR))
            + "\n  W_DEVTOOLS（开发者调试通道）: " + ", ".join(sorted(W_DEVTOOLS))
            + "\n  W_LEGACY（台账第④域存量，收口一个删一个）: " + ", ".join(sorted(W_LEGACY))
        )

    if args.gate and total > 0:
        print(f"\n--gate：{total} 条非白名单 CJK 字面量未清零", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
