#!/usr/bin/env python3
"""erbcc `--engine-report` JSON → Markdown 兼容性报告渲染器（ENGC-6 / B6-PACK）。

输入 JSON 形状钉死在契约 CONTRACT-ENGCOMPAT §8.0.3（v1，Lane A 产出）::

    {"schema":1,"profile":"v24_1824","engine":"v24","version":"1824+v24",
     "corpus":"<语料根绝对路径>",
     "counts":{"files":N,"compiled":N,"parseFailures":N,
               "unknownCommands":N,"unknownFunctions":N},
     "unknownCommands":[{"name":"SETBGIMAGE","file":"<相对路径>","line":120}],
     "unknownFunctions":[…]}

产出：Markdown，写到 `build/engine-compat/report.md`（pack_release.py 配置模式调用）。
头部写**目标档**与**语料指纹**；CSV 键（`GameBase.csv` / `VariableSize.csv`）与
资源引用是二期范围，报告里显式写「未检查（二期）」（PLAN §1.7：第三方游戏根
缺 eraTW 专属的 CSV/资源检查，不能默认第三方包与 eraTW 同构）。

stdlib only。
"""
from __future__ import annotations

import hashlib
import json
import os

SCHEMA_VERSION = 1

# 报告 JSON 的必填键与 counts 子键（形状不符直接判红，不猜不兜）。
REPORT_KEYS = ("schema", "profile", "engine", "version", "corpus", "counts",
               "unknownCommands", "unknownFunctions")
COUNT_KEYS = ("files", "compiled", "parseFailures", "unknownCommands", "unknownFunctions")
ENTRY_KEYS = ("name", "file", "line")

# 二期未检查项：报告里必须点名（「兼容某游戏」要变成出包前可执行清单，缺什么写什么）。
SECOND_PHASE_ITEMS = (
    "CSV 键（`GameBase.csv` / `VariableSize.csv`）：**未检查（二期）**",
    "资源引用（图片 / 音频等随包资源）：**未检查（二期）**",
)


class EngineReportError(ValueError):
    """报告 JSON 形状不符或渲染输入非法。"""


def validate_report(report) -> list:
    """按契约 §8.0.3 校验报告形状，返回问题列表（空 = 合法）。"""
    if not isinstance(report, dict):
        return [f"报告必须是 JSON 对象，实际 {type(report).__name__}"]
    problems = []
    if report.get("schema") != SCHEMA_VERSION or isinstance(report.get("schema"), bool):
        problems.append(f"schema 不符：期望 {SCHEMA_VERSION}，实际 {report.get('schema')!r}")
    for key in REPORT_KEYS:
        if key not in report:
            problems.append(f"缺键 {key}")
    counts = report.get("counts")
    if not isinstance(counts, dict):
        problems.append("counts 必须是对象")
    else:
        for key in COUNT_KEYS:
            value = counts.get(key)
            if not isinstance(value, int) or isinstance(value, bool):
                problems.append(f"counts.{key} 必须是整数，实际 {value!r}")
    for key in ("unknownCommands", "unknownFunctions"):
        entries = report.get(key)
        if not isinstance(entries, list):
            problems.append(f"{key} 必须是数组")
            continue
        for i, entry in enumerate(entries):
            if not isinstance(entry, dict):
                problems.append(f"{key}[{i}] 必须是对象")
                continue
            for sub in ENTRY_KEYS:
                if sub not in entry:
                    problems.append(f"{key}[{i}] 缺键 {sub}")
    return problems


def corpus_fingerprint(corpus: str) -> str:
    """语料指纹：语料根绝对路径归一（abspath + normcase）后的 sha256。

    报告 JSON 的 corpus 只带路径不带内容哈希（§8.0.3 形状 v1），
    指纹由渲染侧从路径派生，用于把「这份报告对准哪个语料根」钉进头部。
    """
    normalized = os.path.normcase(os.path.abspath(corpus))
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _entries_section(title: str, entries: list) -> list:
    lines = [f"## {title}（{len(entries)} 条）", ""]
    if not entries:
        lines.append("（无）")
        return lines
    lines.append("| 名字 | 文件 | 行 |")
    lines.append("|---|---|---|")
    for entry in entries:
        lines.append(f"| `{entry['name']}` | {entry['file']} | {entry['line']} |")
    return lines


def render_report(report: dict) -> str:
    """合法报告 dict → Markdown 文本。形状问题请先用 [validate_report] 判。"""
    counts = report["counts"]
    lines = [
        "# 引擎兼容性报告（engine-compat report）",
        "",
        f"- 目标档：`{report['profile']}`（engine `{report['engine']}` · "
        f"version `{report['version']}`）",
        f"- 语料根：`{report['corpus']}`",
        f"- 语料指纹：`sha256:{corpus_fingerprint(report['corpus'])}`"
        "（语料根绝对路径归一后计算）",
        "",
        "## 计数",
        "",
        "| files | compiled | parseFailures | unknownCommands | unknownFunctions |",
        "|---|---|---|---|---|",
        f"| {counts['files']} | {counts['compiled']} | {counts['parseFailures']} | "
        f"{counts['unknownCommands']} | {counts['unknownFunctions']} |",
        "",
    ]
    lines += _entries_section("目标外命令 unknownCommands", report["unknownCommands"])
    lines.append("")
    lines += _entries_section("目标外函数 unknownFunctions", report["unknownFunctions"])
    lines.append("")
    lines.append("## 未检查项（二期）")
    lines.append("")
    lines.extend(f"- {item}" for item in SECOND_PHASE_ITEMS)
    return "\n".join(lines) + "\n"


def render_file(report: dict, out_path: str) -> str:
    """渲染并落盘，返回 abs path。"""
    out_path = os.path.abspath(out_path)
    parent = os.path.dirname(out_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(render_report(report))
    return out_path


def load_report(path: str) -> dict:
    """读报告 JSON；文件缺失 / 坏 JSON / 形状不符抛 [EngineReportError]。"""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            report = json.load(fh)
    except OSError as exc:
        raise EngineReportError(f"读不到引擎报告 {path}：{exc}") from exc
    except json.JSONDecodeError as exc:
        raise EngineReportError(f"引擎报告 {path} 不是合法 JSON：{exc}") from exc
    problems = validate_report(report)
    if problems:
        raise EngineReportError(
            f"引擎报告 {path} 形状不符（契约 §8.0.3）：\n  - " + "\n  - ".join(problems))
    return report
