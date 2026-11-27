#!/usr/bin/env python3
"""BOARD 看板归档与体量闸（唯一入口）。

BOARD.md 只留「当前在办 / 常驻裁定 / 现行闸门口径」；历史回执由本工具落
`<internal-workdir>/BOARD-ARCHIVE-<日期>.md`，提交索引由 git 自动生成
`<internal-workdir>/BOARD-HISTORY-INDEX.md`。三档用法：

    python devtools/board_archive.py snapshot            # 把当前 BOARD 原文快照进归档目录
    python devtools/board_archive.py snapshot --rev <r>  # 快照指定提交的 BOARD（裁切前基线）
    python devtools/board_archive.py index               # 从 git log 重生成历史索引
    python devtools/board_archive.py check               # 体量闸：超过 60 KiB 判红

`check` 的阈值不是编码规范，而是阅读工具的上限：BOARD 超过 64 KiB 时
Read 工具会整份拒绝，跨 agent 的唯一通信面就断了。留 4 KiB 余量给当日追加。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import date
from pathlib import Path

# 脚本位于 <repo>/devtools/，BOARD 在 <repo>/BOARD.md。
REPO_ROOT = Path(__file__).resolve().parents[1]
BOARD = REPO_ROOT / "BOARD.md"
# 公开分仓：归档目录是内部工作面，不随公开仓发布 ⇒ 落**同级暂存目录**，
# 不写进仓内以免脏工作树。
OUT_DIR = REPO_ROOT.parent / ".board-archive"
SIZE_LIMIT = 60 * 1024


def _read_board(rev: str | None = None) -> str:
    """读工作树 BOARD；给了 rev 就读该提交里的 BOARD（裁切前基线快照）。"""
    if rev:
        try:
            return subprocess.run(
                ["git", "show", f"{rev}:BOARD.md"],
                cwd=REPO_ROOT,
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            ).stdout
        except (OSError, subprocess.CalledProcessError) as exc:
            raise SystemExit(f"读不到 {rev}:BOARD.md：{exc}") from exc
    if not BOARD.is_file():
        raise SystemExit(f"找不到 BOARD：{BOARD}")
    return BOARD.read_text(encoding="utf-8")


def snapshot(rev: str | None = None) -> int:
    """把 BOARD 原文快照到 `<internal-workdir>/BOARD-ARCHIVE-<YYYYMMDD>.md`。"""
    text = _read_board(rev)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"BOARD-ARCHIVE-{date.today():%Y%m%d}.md"
    header = (
        f"# BOARD 归档快照 {date.today():%Y-%m-%d}\n\n"
        "> 本文件由 `devtools/board_archive.py snapshot` 自动生成，"
        f"内容 = {rev + ' 的 BOARD.md 原文' if rev else '当时 BOARD.md 的原文'}快照，**只读不回改**。\n"
        "> 现行看板见仓根 `BOARD.md`；历史提交索引见 `BOARD-HISTORY-INDEX.md`。\n\n"
        "---\n\n"
    )
    out.write_text(header + text, encoding="utf-8")
    print(f"snapshot: {out.relative_to(REPO_ROOT)} ({len(text.encode('utf-8'))} B)")
    return 0


def index() -> int:
    """从 git log 生成 BOARD 提交索引（按月分组）。"""
    try:
        raw = subprocess.run(
            [
                "git",
                "log",
                "--follow",
                "--date=short",
                "--format=%h%x09%ad%x09%s",
                "--",
                "BOARD.md",
            ],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"git log 失败：{exc}") from exc

    lines = [line for line in raw.splitlines() if line.strip()]
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "BOARD-HISTORY-INDEX.md"

    body = [
        "# BOARD 历史索引（自动生成）",
        "",
        "> 本文件由 `devtools/board_archive.py index` 从 `git log --follow -- BOARD.md`",
        "> 自动生成，**不要手改**。逐条回执原文见 `BOARD-ARCHIVE-<日期>.md` 与合并提交。",
        "",
    ]
    current_month = ""
    for line in lines:
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        commit, day, subject = parts
        month = day[:7]
        if month != current_month:
            body.append("")
            body.append(f"## {month}")
            body.append("")
            current_month = month
        body.append(f"- `{commit}` {day} — {subject}")
    body.append("")
    out.write_text("\n".join(body), encoding="utf-8")
    print(f"index: {out.relative_to(REPO_ROOT)} ({len(lines)} commits)")
    return 0


def check() -> int:
    """体量闸：BOARD 超过 60 KiB 判红（Read 工具 64 KiB 上限的预算）。"""
    size = BOARD.stat().st_size if BOARD.is_file() else 0
    if size <= 0:
        raise SystemExit(f"找不到或为空：{BOARD}")
    if size > SIZE_LIMIT:
        print(
            f"BOARD 体量超限：{size} B > {SIZE_LIMIT} B；"
            "把已收口回执快照进归档目录后再裁 BOARD",
            file=sys.stderr,
        )
        return 1
    print(f"BOARD 体量 OK：{size} B <= {SIZE_LIMIT} B")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="BOARD 归档 / 历史索引 / 体量闸")
    parser.add_argument("action", choices=("snapshot", "index", "check"))
    parser.add_argument("--rev", help="snapshot 专用：快照该 git 提交里的 BOARD.md")
    args = parser.parse_args()
    if args.action == "snapshot":
        return snapshot(args.rev)
    return {"index": index, "check": check}[args.action]()


if __name__ == "__main__":
    raise SystemExit(main())
