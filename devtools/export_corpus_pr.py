#!/usr/bin/env python3
"""语料 bug 台账 -> 上游 PR bundle 导出器（lane corpus-pr，2026-11-10）。

用法（在 eratw-android 下）::

    python devtools/export_corpus_pr.py            # 生成 out/corpus-pr/<id>/
    python devtools/export_corpus_pr.py --check    # 只校验已生成物与源一致（CI 用）
    python devtools/export_corpus_pr.py --out DIR  # 换输出根

输入（只读，两个都在 git 里）::

    devtools/corpus-patches/index.yaml   # 补丁资产索引（窄 schema，见 specs/corpus-bugs.md §3）
    specs/corpus-bugs.md                 # 缺陷台账 §1 表格

输出（每 id 一目录；确定性、无网络、无时间戳、无绝对路径）::

    out/corpus-pr/<id>/README.md         # 病灶 / 证据 / 修法 / 风险 / 上游路径
    out/corpus-pr/<id>/<id>.patch        # index.yaml 指向的 unified diff 原字节
    out/corpus-pr/<id>/manifest.json     # bad/fixed/patch sha256 + 语料版本 + 台账行 sha

三向一致性硬规则（specs/corpus-bugs.md §5）：
- 台账「已修（补丁 <id>）」行 <=> index.yaml 条目 <=> 可导出 bundle，缺一即红；
- 台账其它状态行跳过并告警（不生成 bundle，也不判红）；
- index.yaml 有条目但台账不是「已修（补丁 …）」=> 红（补丁已落地、台账没认账）。

退出码：0 = 全部一致（可能有告警）；2 = 任一红；1 = 用法/IO 错误。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_INDEX = os.path.join(HERE, "corpus-patches", "index.yaml")
DEFAULT_SPECS = os.path.join(REPO, "specs", "corpus-bugs.md")
DEFAULT_OUT = os.path.join(REPO, "out", "corpus-pr")

SCHEMA_VERSION = 1
BUNDLE_FILES = ("README.md", "manifest.json")

REQUIRED_INDEX_FIELDS = (
    "id",
    "target",
    "patch",
    "bad-sha256",
    "fixed-sha256",
    "corpus",
    "status",
    "summary",
    "evidence",
)
OPTIONAL_INDEX_FIELDS = ("root", "skip-sha256")

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ROW_RE = re.compile(r"^\|\s*(CB-\d{3})\s*\|")
FIXED_RE = re.compile(r"^\**\s*已修（补丁\s*(CB-\d{3})")
CELL_SPLIT_RE = re.compile(r"(?<!\\)\|")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_text(text: str) -> str:
    return sha256_bytes(text.encode("utf-8"))


def sha256_file(path: str) -> str:
    with open(path, "rb") as fh:
        return sha256_bytes(fh.read())


def split_skip(value: str) -> list:
    return [item.strip() for item in (value or "").split(",") if item.strip()]


def parse_index(path: str):
    """窄 schema 手写解析 —— 与 buildSrc CorpusPatches 同口径：不认识的行判红，宁红不猜。

    返回 (entries, problems)。entry 是 dict（含私有键 '_line' 记起始行号）。
    """
    entries = []
    problems = []
    cur = None
    start_line = 0
    with open(path, "r", encoding="utf-8", newline="") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip("\r\n")
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s.startswith("-"):
                if cur is not None:
                    entries.append(cur)
                cur = {"_line": lineno}
                start_line = lineno
                s = s[1:].strip()
                if not s:
                    continue
            if ":" not in s:
                problems.append("%s:%d: 无法解析的行（缺冒号）：%r" % (path, lineno, line))
                continue
            key, value = s.split(":", 1)
            key, value = key.strip(), value.strip()
            if cur is None:
                if not value and not line[:1].isspace():
                    continue  # YAML 文档级键（本索引只有顶层 `patches:`），不是条目字段
                problems.append("%s:%d: 条目之外的键 %r" % (path, lineno, key))
                continue
            if key in cur:
                problems.append("%s:%d: 同一补丁条目内键 %r 重复" % (path, lineno, key))
                continue
            cur[key] = value
    if cur is not None:
        entries.append(cur)

    known = set(REQUIRED_INDEX_FIELDS) | set(OPTIONAL_INDEX_FIELDS) | {"_line"}
    seen = {}
    for entry in entries:
        where = "%s:%d" % (path, entry.get("_line", 0))
        pid = entry.get("id", "")
        if not pid:
            problems.append("%s: 补丁条目缺 id" % where)
            continue
        if pid in seen:
            problems.append("%s: 补丁 id %s 重复（另一处 %s）" % (where, pid, seen[pid]))
        seen[pid] = where
        for field in REQUIRED_INDEX_FIELDS:
            if not entry.get(field):
                problems.append("%s: %s 缺必填字段 %s" % (where, pid, field))
        for field in entry:
            if field not in known:
                problems.append("%s: %s 含未知字段 %r（窄 schema 不猜）" % (where, pid, field))
        for field in ("bad-sha256", "fixed-sha256"):
            value = entry.get(field, "")
            if value and not SHA256_RE.match(value):
                problems.append("%s: %s 的 %s 不是小写 64 位 hex：%r" % (where, pid, field, value))
        for value in split_skip(entry.get("skip-sha256", "")):
            if not SHA256_RE.match(value):
                problems.append("%s: %s 的 skip-sha256 项不是小写 64 位 hex：%r" % (where, pid, value))
        if entry.get("root", "corpus") not in ("corpus", "game"):
            problems.append("%s: %s 的 root 只能是 corpus/game：%r" % (where, pid, entry.get("root")))
        asset = os.path.join(os.path.dirname(path), entry.get("patch", ""))
        if entry.get("patch") and not os.path.isfile(asset):
            problems.append("%s: %s 的补丁资产缺失：%s" % (where, pid, asset))
    return entries, problems


def parse_ledger(path: str):
    """解析 specs/corpus-bugs.md §1 表格行（`| CB-xxx | … |`）。返回 (rows, problems)。"""
    rows = []
    problems = []
    with open(path, "r", encoding="utf-8", newline="") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip("\r\n")
            match = ROW_RE.match(line)
            if not match:
                continue
            body = line.strip()
            if body.startswith("|"):
                body = body[1:]
            if body.endswith("|"):
                body = body[:-1]
            cells = [cell.strip() for cell in CELL_SPLIT_RE.split(body)]
            if len(cells) < 6:
                problems.append("%s:%d: %s 台账行列数不足（%d < 6）"
                                % (path, lineno, match.group(1), len(cells)))
                continue
            rows.append({
                "id": cells[0],
                "cells": cells,
                "line": lineno,
                "raw": line,
                "sha256": sha256_text(line + "\n"),
            })
    return rows, problems


def disposal_of(row) -> str:
    return row["cells"][5] if len(row["cells"]) > 5 else ""


def fixed_patch_id(row):
    """台账行若为「已修（补丁 CB-xxx …）」返回该 id，否则 None。"""
    match = FIXED_RE.match(disposal_of(row))
    return match.group(1) if match else None


def _clip(text: str, limit: int = 80) -> str:
    text = " ".join(str(text or "").split())
    return text if len(text) <= limit else text[:limit - 1] + "…"


def collect_consistency(entries: list, rows: list, specs_path: str):
    """三向一致性：台账「已修（补丁）」行 <=> index 条目 <=> 可导出 bundle。

    返回 (problems, warnings, exportable)；exportable = [(entry, row)]，按 id 升序。
    """
    problems = []
    warnings = []
    by_id = {}
    for entry in entries:
        if entry.get("id"):
            by_id[entry["id"]] = entry

    row_by_id = {}
    fixed_rows = {}
    for row in rows:
        rid = row["id"]
        row_by_id[rid] = row
        pid = fixed_patch_id(row)
        if pid:
            fixed_rows[rid] = row
            if pid != rid:
                problems.append("%s:%d: 台账 %s 的处置指向补丁 %s（id 不一致）"
                                % (specs_path, row["line"], rid, pid))
        elif rid in by_id:
            problems.append(
                "%s:%d: index.yaml 有 %s 的补丁条目，但台账处置不是「已修（补丁 …）」：%s"
                % (specs_path, row["line"], rid, _clip(disposal_of(row), 60)))

    for rid, row in fixed_rows.items():
        if rid not in by_id:
            problems.append("%s:%d: 台账 %s 标了「已修（补丁）」，但 index.yaml 没有该条目"
                            % (specs_path, row["line"], rid))

    for pid in sorted(by_id):
        if pid in fixed_rows:
            continue
        if pid not in row_by_id:
            problems.append("index.yaml: %s 有补丁条目，但台账 §1 表格里没有这一行" % pid)

    exportable = [(by_id[rid], fixed_rows[rid]) for rid in sorted(fixed_rows) if rid in by_id]

    for row in rows:
        if row["id"] in fixed_rows:
            continue
        warnings.append("%s:%d: 跳过 %s（处置不是「已修（补丁 …）」）：%s"
                        % (specs_path, row["line"], row["id"], _clip(disposal_of(row), 60)))

    return problems, warnings, exportable


def commit_subject(entry: dict) -> str:
    summary = entry.get("summary", "")
    for sep in ("；", "。", ";", "."):
        idx = summary.find(sep)
        if idx >= 0:
            summary = summary[:idx]
            break
    return "corpus(%s): %s" % (entry.get("id", ""), _clip(summary, 100))


def render_readme(entry: dict, row: dict, patch_sha: str) -> str:
    pid = entry.get("id", "")
    root = entry.get("root", "corpus")
    root_note = "<游戏根>/ERB" if root == "corpus" else "<游戏根>"
    skip = split_skip(entry.get("skip-sha256", ""))
    lines = []
    lines.append("# %s —— 语料缺陷上游补丁包" % pid)
    lines.append("")
    lines.append("- 语料版本：`%s`" % entry.get("corpus", ""))
    lines.append("- 目标文件：`%s`" % entry.get("target", ""))
    lines.append("- 目标根：`%s`（= %s）" % (root, root_note))
    lines.append("- 病灶 sha256：`%s`" % entry.get("bad-sha256", ""))
    lines.append("- 修后 sha256：`%s`" % entry.get("fixed-sha256", ""))
    if skip:
        # skip 有两类语义（病灶已不存在 / 非本链出包对象、须保持编译基准字节），
        # 一律写中性判词，具体边界回指 specs/corpus-bugs.md §3，避免把后者误读成「已修」。
        lines.append("- 登记为不进入本补丁链的版本（skip-sha256，语义见 specs/corpus-bugs.md §3）：%s"
                     % ", ".join("`%s`" % s for s in skip))
    lines.append("- 补丁文件：`%s.patch`（sha256 `%s`）" % (pid, patch_sha))
    lines.append("- 台账：`specs/corpus-bugs.md:%d`（行 sha256 `%s`）" % (row["line"], row["sha256"]))
    lines.append("- 台账状态：%s" % _clip(entry.get("status", ""), 160))
    lines.append("")
    lines.append("## 病灶")
    lines.append("")
    lines.append(entry.get("summary", ""))
    lines.append("")
    lines.append("## 证据")
    lines.append("")
    lines.append(entry.get("evidence", ""))
    lines.append("")
    lines.append("## 修法")
    lines.append("")
    lines.append("见 `%s.patch`：unified diff，套用到「目标文件」后该文件 sha256 必须等于「修后 sha256」。" % pid)
    lines.append("套用失败（上下文不匹配）即判红，不许静默跳过 —— 防上游语料换版漂移。")
    lines.append("")
    lines.append("## 风险")
    lines.append("")
    lines.append("- 只改语料文本，不触碰引擎；未套补丁时按原样直编，行为与原版一致。")
    lines.append("- 目标文件在其它语料版本上的 sha256 若既非病灶也非修后（且不在 skip-sha256），判红。")
    lines.append("- 本 bundle 不含可执行内容；提交上游前必须人工复核 diff 上下文与目标行。")
    lines.append("")
    lines.append("## 上游路径")
    lines.append("")
    lines.append("- 建议分支：`eratw-corpus-fix/%s`" % pid)
    lines.append("- 建议 commit subject：`%s`" % commit_subject(entry))
    lines.append("- 上游仓库 / 维护者渠道：提交前由主人确认（本工具离线，不代填 URL）。")
    lines.append("- 维护者问询点与提交流程：见 `specs/corpus-bugs.md` §5.4。")
    lines.append("")
    lines.append("## 复现校验")
    lines.append("")
    lines.append("```")
    lines.append("python devtools/export_corpus_pr.py --check")
    lines.append("python devtools/test_export_corpus_pr.py")
    lines.append("```")
    return "\n".join(lines) + "\n"


def render_manifest(entry: dict, row: dict, patch_sha: str, file_names: list) -> str:
    pid = entry.get("id", "")
    obj = {
        "schema": SCHEMA_VERSION,
        "id": pid,
        "corpus": entry.get("corpus", ""),
        "root": entry.get("root", "corpus"),
        "target": entry.get("target", ""),
        "patch": {"file": "%s.patch" % pid, "sha256": patch_sha},
        "bad_sha256": entry.get("bad-sha256", ""),
        "fixed_sha256": entry.get("fixed-sha256", ""),
        "skip_sha256": split_skip(entry.get("skip-sha256", "")),
        "ledger": {
            "row_line": row["line"],
            "row_sha256": row["sha256"],
            "status": entry.get("status", ""),
            "disposal": disposal_of(row),
        },
        "upstream": {
            "branch": "eratw-corpus-fix/%s" % pid,
            "commit_subject": commit_subject(entry),
        },
        "files": sorted(file_names),
    }
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def build_bundle(entry: dict, row: dict, index_path: str) -> dict:
    """返回 bundle 的 {相对文件名: bytes}；不含磁盘 IO 副作用（除读补丁资产）。"""
    pid = entry.get("id", "")
    if not re.match(r"^CB-\d{3}$", pid):
        raise ValueError("非法补丁 id：%r（手工越界或无 id 一律拒绝落盘）" % pid)
    patch_path = os.path.join(os.path.dirname(index_path), entry.get("patch", ""))
    with open(patch_path, "rb") as fh:
        patch_bytes = fh.read()
    patch_sha = sha256_bytes(patch_bytes)
    file_names = ["README.md", "manifest.json", "%s.patch" % pid]
    files = {
        "%s.patch" % pid: patch_bytes,
        "manifest.json": render_manifest(entry, row, patch_sha, file_names).encode("utf-8"),
        "README.md": render_readme(entry, row, patch_sha).encode("utf-8"),
    }
    return files


def write_bundle(out_root: str, pid: str, files: dict):
    if not re.match(r"^CB-\d{3}$", pid):
        raise ValueError("非法补丁 id：%r" % pid)
    target = os.path.join(out_root, pid)
    os.makedirs(target, exist_ok=True)
    for name in sorted(os.listdir(target)):
        if name not in files:
            os.remove(os.path.join(target, name))
    written = []
    for name in sorted(files):
        path = os.path.join(target, name)
        with open(path, "wb") as fh:
            fh.write(files[name])
        written.append(path)
    return target, written


def diff_bundle(out_root: str, pid: str, files: dict) -> list:
    target = os.path.join(out_root, pid)
    if not os.path.isdir(target):
        return ["缺少 bundle 目录：%s" % target]
    problems = []
    for name in sorted(files):
        path = os.path.join(target, name)
        if not os.path.isfile(path):
            problems.append("%s: 缺少文件 %s" % (pid, name))
            continue
        with open(path, "rb") as fh:
            on_disk = fh.read()
        if on_disk != files[name]:
            problems.append("%s: %s 与重新生成的字节不一致" % (pid, name))
    for name in sorted(set(os.listdir(target)) - set(files)):
        problems.append("%s: 多出文件 %s（重新生成会清掉）" % (pid, name))
    return problems


def patch_chain_problems(entries: list) -> list:
    """同一 (root, target) 的多条补丁必须按 index 顺序串成 sha 链。

    后一条 bad-sha256 必须等于前一条 fixed-sha256 —— 否则 buildSrc 顺序套用第二条
    必然判红（前一条已把文件改走）。不等即判红，并点名两个 id（宁红不猜）。
    """
    problems = []
    last = {}
    for entry in entries:
        pid = entry.get("id", "")
        if not pid:
            continue
        key = (entry.get("root", "corpus"), entry.get("target", ""))
        prev = last.get(key)
        if prev is not None:
            prev_id, prev_fixed = prev
            bad = entry.get("bad-sha256", "")
            if bad and prev_fixed and bad != prev_fixed:
                problems.append(
                    "%s: 补丁链断 —— %s 的 bad-sha256 %s 不等于前一条 %s 的 fixed-sha256 %s（同目标 %s）"
                    % (pid, pid, bad, prev_id, prev_fixed, key[1]))
        last[key] = (pid, entry.get("fixed-sha256", ""))
    return problems


def run(index_path: str, specs_path: str, out_root: str, check_only: bool = False):
    """一致性检查 + 导出。返回 (exit_code, problems, warnings, exported_ids)。"""
    problems = []
    warnings = []
    try:
        entries, index_problems = parse_index(index_path)
    except OSError as exc:
        return 1, ["读取 index.yaml 失败：%s" % exc], [], []
    except UnicodeDecodeError as exc:
        return 1, ["index.yaml 不是合法 UTF-8：%s" % exc], [], []
    problems.extend(index_problems)
    problems.extend(patch_chain_problems(entries))
    try:
        rows, row_problems = parse_ledger(specs_path)
    except OSError as exc:
        return 1, ["读取 specs/corpus-bugs.md 失败：%s" % exc], [], []
    except UnicodeDecodeError as exc:
        return 1, ["specs/corpus-bugs.md 不是合法 UTF-8：%s" % exc], [], []
    problems.extend(row_problems)

    more_problems, more_warnings, exportable = collect_consistency(entries, rows, specs_path)
    problems.extend(more_problems)
    warnings.extend(more_warnings)

    bundles = {}
    for entry, row in exportable:
        pid = entry["id"]
        try:
            bundles[pid] = build_bundle(entry, row, index_path)
        except (OSError, ValueError) as exc:
            problems.append("%s: 生成 bundle 失败：%s" % (pid, exc))

    if check_only:
        if not os.path.isdir(out_root):
            return (2 if problems else 0), problems, warnings, sorted(bundles)
        for pid in sorted(bundles):
            problems.extend(diff_bundle(out_root, pid, bundles[pid]))
        if os.path.isdir(out_root):
            for name in sorted(os.listdir(out_root)):
                path = os.path.join(out_root, name)
                if name not in bundles and os.path.isdir(path):
                    problems.append("输出根多出未登记的 bundle 目录：%s" % name)
    elif problems:
        return 2, problems, warnings, []
    else:
        os.makedirs(out_root, exist_ok=True)
        for pid in sorted(bundles):
            write_bundle(out_root, pid, bundles[pid])

    return (2 if problems else 0), problems, warnings, sorted(bundles)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="语料 bug 台账 -> 上游 PR bundle 导出器")
    parser.add_argument("--index", default=DEFAULT_INDEX, help="补丁索引（默认 devtools/corpus-patches/index.yaml）")
    parser.add_argument("--specs", default=DEFAULT_SPECS, help="缺陷台账（默认 specs/corpus-bugs.md）")
    parser.add_argument("--out", default=DEFAULT_OUT, help="输出根（默认 out/corpus-pr）")
    parser.add_argument("--check", action="store_true", help="只校验已生成物与源一致，不写盘")
    parser.add_argument("--quiet", action="store_true", help="不打印告警行")
    args = parser.parse_args(argv)

    code, problems, warnings, exported = run(args.index, args.specs, args.out, check_only=args.check)
    if not args.quiet:
        for warning in warnings:
            print("[warn] %s" % warning, file=sys.stderr)
    for problem in problems:
        print("[RED ] %s" % problem, file=sys.stderr)
    if code == 0:
        verb = "校验通过" if args.check else "导出"
        print("%s %d 个 bundle：%s" % (verb, len(exported), args.out))
        if exported:
            print("ids = %s" % ", ".join(exported))
    elif code == 2:
        print("三向一致性判红：%d 条问题（宁红不猜，不写盘）" % len(problems), file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
