#!/usr/bin/env python3
"""T11：出包期现场生成 261111 口径的 qol_data.db（真引擎 + 确定性与结构校验）。

用法（在 eratw-android 下）::

    python devtools/gen_qol_seeddb.py
    python devtools/gen_qol_seeddb.py --compare "<work-root>/eratw-安卓移植计划/eraTW4.981-261017-EAGLOV3/plugins/qol_data.db"

做什么：
1. 从 project 根跑 `gradlew.bat --no-daemon --offline --console=plain --configure-on-demand
   :tools:vmdbg:bootChainProfile`，env 追加 ERATW_ERBC_DIR / ERATW_GAMEDIR /
   ERATW_BCP_WORK=<work>/run1 / ERATW_BCP_FRESH=1 / ERATW_BCP_STOP_AFTER=1。
   探针停在 TITLE 首个等待点（TITLE.ERB:28 CALL QOL_DB_INIT 之后、标题 INPUT 处），
   因此产物 `<work>/run1/sandbox/sql/plugins/qol_data.db` 已含完整建图链。
2. 再跑一次（run2 独立 work 目录）验证确定性：两次 db 的 sha256 必须相等。
3. 校验：12 张表齐全 / `_meta.graph_schema_version` == 语料 `ERB/DIM.ERH` 的 NOW_VERSION /
   GRAPH_DISTANCE=36418 / integrity_check=ok / journal_mode=delete / page_size=4096 /
   无 -wal/-shm 侧车。
4. 可选 `--compare <261017 qol_data.db>`：只读 URI ATTACH，四张图双向 EXCEPT 判空。
5. 落 `build/seeddb/{qol_data.db,seed.json}`；seed.json 与 program/meta/db 摘要全部对得上时
   默认直接 exit 0（`--force` 重跑）。

退出码：0 成功（含缓存命中）；1 生成/环境失败；2 两次 sha 不一致（已删产物）；
3 结构校验失败（已删产物）。

只依赖 python3 标准库；语料与 261017 库只读（只以 mode=ro URI 打开），不写入共享根。
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import os
import pathlib
import re
import shutil
import sqlite3
import subprocess
import sys
import time

SCHEMA = 1
REQUIRED_TABLES = (
    "GRAPH_CROSS_MAP_EDGE",
    "GRAPH_DISTANCE",
    "GRAPH_EDGE",
    "GRAPH_NODE_ATTR",
    "QOL_CRAFT_RECIPE",
    "QOL_DISH_DB",
    "QOL_FISH_DB",
    "QOL_ITEM_DB",
    "QOL_ITEM_META",
    "QOL_MUSHI_DB",
    "QOL_WOOD_DB",
    "_meta",
)
GRAPH_TABLES = ("GRAPH_EDGE", "GRAPH_DISTANCE", "GRAPH_CROSS_MAP_EDGE", "GRAPH_NODE_ATTR")
EXPECTED_GRAPH_DISTANCE = 36418
EXPECTED_JOURNAL_MODE = "delete"
EXPECTED_PAGE_SIZE = 4096
NOW_VERSION_RE = re.compile(r"#\s*DIM\s+CONST\s+NOW_VERSION\s*=\s*(\d+)", re.IGNORECASE)

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_JAVA_HOME = r"<work-root>\jdk17"
DEFAULT_GRADLE_USER_HOME = r"<work-root>\gradle-home"
GRADLE_TASK = ":tools:vmdbg:bootChainProfile"


# ---------------------------------------------------------------------------
# 纯函数：版本 / 摘要 / sqlite 事实读取
# ---------------------------------------------------------------------------


def parse_now_version(dim_erh_text: str) -> str:
    """从 DIM.ERH 文本里取 `#DIM CONST NOW_VERSION = 20261111` 的值。"""
    match = NOW_VERSION_RE.search(dim_erh_text)
    if match is None:
        raise ValueError("DIM.ERH 里找不到 `#DIM CONST NOW_VERSION = <数字>`")
    return match.group(1)


def sha256_file(path: pathlib.Path) -> str:
    """文件 sha256（分块读，不整文件进内存）。"""
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _readonly_uri(path: pathlib.Path) -> str:
    return path.resolve().as_uri() + "?mode=ro"


def inspect_db(db_path: pathlib.Path) -> dict:
    """读一份 qol_data.db 的可校验事实（只读打开）。"""
    con = sqlite3.connect(_readonly_uri(db_path), uri=True)
    try:
        tables = [
            row[0]
            for row in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        ]
        meta = dict(con.execute("SELECT key, value FROM _meta").fetchall()) if "_meta" in tables else {}
        distance_rows = None
        if "GRAPH_DISTANCE" in tables:
            distance_rows = con.execute("SELECT COUNT(*) FROM GRAPH_DISTANCE").fetchone()[0]
        integrity = con.execute("PRAGMA integrity_check").fetchone()[0]
        journal_mode = con.execute("PRAGMA journal_mode").fetchone()[0]
        page_size = con.execute("PRAGMA page_size").fetchone()[0]
        return {
            "tables": tables,
            "meta": meta,
            "graph-distance-rows": distance_rows,
            "integrity-check": integrity,
            "journal-mode": journal_mode,
            "page-size": page_size,
        }
    finally:
        con.close()


def validate_db(
    db_path: pathlib.Path,
    now_version: str,
    expected_tables: tuple = REQUIRED_TABLES,
    expected_graph_distance: int = EXPECTED_GRAPH_DISTANCE,
) -> list:
    """结构校验，返回问题列表（空 = 通过）。只读，不修改 db。"""
    problems = []
    if not db_path.is_file():
        return [f"产物不存在: {db_path}"]
    for sidecar in (db_path.with_name(db_path.name + "-wal"), db_path.with_name(db_path.name + "-shm")):
        if sidecar.exists():
            problems.append(f"存在 sqlite 侧车文件: {sidecar.name}（应已落盘收尾）")
    facts = inspect_db(db_path)
    actual = set(facts["tables"])
    missing = [name for name in expected_tables if name not in actual]
    if missing:
        problems.append("缺表: " + ", ".join(missing))
    graph_version = facts["meta"].get("graph_schema_version")
    if graph_version != now_version:
        problems.append(
            f"_meta.graph_schema_version={graph_version!r}，应等于语料 NOW_VERSION={now_version!r}"
        )
    if facts["graph-distance-rows"] != expected_graph_distance:
        problems.append(
            f"GRAPH_DISTANCE 行数={facts['graph-distance-rows']}，应为 {expected_graph_distance}"
        )
    if facts["integrity-check"] != "ok":
        problems.append(f"integrity_check={facts['integrity-check']!r}，应为 ok")
    if facts["journal-mode"] != EXPECTED_JOURNAL_MODE:
        problems.append(f"journal_mode={facts['journal-mode']!r}，应为 {EXPECTED_JOURNAL_MODE}")
    if facts["page-size"] != EXPECTED_PAGE_SIZE:
        problems.append(f"page_size={facts['page-size']}，应为 {EXPECTED_PAGE_SIZE}")
    return problems


def compare_graph_tables(db_path: pathlib.Path, compare_path: pathlib.Path) -> dict:
    """与旧库只读 ATTACH，四张图分别双向 EXCEPT 判空。"""
    result = {
        "compare-path": str(compare_path),
        "tables": {},
        "match": True,
    }
    if not compare_path.is_file():
        result["match"] = False
        result["error"] = f"对拍库不存在: {compare_path}"
        return result
    con = sqlite3.connect(_readonly_uri(db_path), uri=True)
    try:
        con.execute("ATTACH DATABASE ? AS old", (_readonly_uri(compare_path),))
        for table in GRAPH_TABLES:
            main_cols = [row[1] for row in con.execute(f"PRAGMA main.table_info({table})")]
            old_cols = [row[1] for row in con.execute(f"PRAGMA old.table_info({table})")]
            entry = {
                "rows": con.execute(f"SELECT COUNT(*) FROM main.{table}").fetchone()[0],
                "old-rows": con.execute(f"SELECT COUNT(*) FROM old.{table}").fetchone()[0],
            }
            if main_cols != old_cols:
                entry["match"] = False
                entry["error"] = f"列集合不同: main={main_cols} old={old_cols}"
                result["tables"][table] = entry
                result["match"] = False
                continue
            columns = ", ".join(f'"{name}"' for name in main_cols)
            only_main = con.execute(
                f"SELECT COUNT(*) FROM (SELECT {columns} FROM main.{table} "
                f"EXCEPT SELECT {columns} FROM old.{table})"
            ).fetchone()[0]
            only_old = con.execute(
                f"SELECT COUNT(*) FROM (SELECT {columns} FROM old.{table} "
                f"EXCEPT SELECT {columns} FROM main.{table})"
            ).fetchone()[0]
            entry["only-main"] = only_main
            entry["only-old"] = only_old
            entry["match"] = only_main == 0 and only_old == 0
            if not entry["match"]:
                result["match"] = False
            result["tables"][table] = entry
        return result
    finally:
        con.close()


# ---------------------------------------------------------------------------
# seed.json 读写与缓存判定
# ---------------------------------------------------------------------------


def build_seed(
    project_root: pathlib.Path,
    game_root: pathlib.Path,
    erbc_dir: pathlib.Path,
    db_path: pathlib.Path,
    now_version: str,
    deterministic,
    compare: dict | None,
) -> dict:
    """组装 seed.json（字段名按 T11 契约）。"""
    facts = inspect_db(db_path)
    return {
        "schema": SCHEMA,
        "now_version": now_version,
        "corpus": {
            "root": str(game_root),
            "commit": _git_head(project_root),
        },
        "program-sha256": sha256_file(erbc_dir / "program.erbc"),
        "meta-sha256": sha256_file(erbc_dir / "program.meta"),
        "db-sha256": sha256_file(db_path),
        "db-bytes": db_path.stat().st_size,
        "tables": facts["tables"],
        "graph-distance-rows": facts["graph-distance-rows"],
        "generated-at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "deterministic": deterministic,
        "compare-261017": compare,
    }


def write_seed(seed_path: pathlib.Path, seed: dict) -> None:
    """原子写 seed.json（UTF-8、缩进 2、末尾换行）。"""
    seed_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = seed_path.with_name(seed_path.name + ".tmp")
    text = _json_dumps(seed)
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, seed_path)


def _json_dumps(seed: dict) -> str:
    import json

    return json.dumps(seed, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def read_seed(seed_path: pathlib.Path) -> dict | None:
    """读 seed.json；缺失/坏 JSON 返回 None。"""
    import json

    if not seed_path.is_file():
        return None
    try:
        return json.loads(seed_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def seed_matches_cache(
    seed: dict | None,
    program_sha: str,
    meta_sha: str,
    db_path: pathlib.Path,
) -> bool:
    """seed 记录与当前 program/meta/db 全部对得上 ⇒ 可复用。"""
    if not seed:
        return False
    if seed.get("schema") != SCHEMA:
        return False
    if seed.get("program-sha256") != program_sha or seed.get("meta-sha256") != meta_sha:
        return False
    if not db_path.is_file():
        return False
    if seed.get("db-bytes") != db_path.stat().st_size:
        return False
    return seed.get("db-sha256") == sha256_file(db_path)


def _git_head(project_root: pathlib.Path) -> str | None:
    """取当前 HEAD（不依赖 git 也可用：失败返回 None 并写进 seed）。"""
    try:
        out = subprocess.run(
            ["git", "-C", str(project_root), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    head = out.stdout.strip()
    return head or None


# ---------------------------------------------------------------------------
# 生成：跑真探针
# ---------------------------------------------------------------------------


def gradle_env(env: dict | None = None) -> dict:
    """在现有环境上补 JAVA_HOME / GRADLE_USER_HOME 缺省值（已存在则不覆盖）。"""
    merged = dict(os.environ if env is None else env)
    if not merged.get("JAVA_HOME") and pathlib.Path(DEFAULT_JAVA_HOME).is_dir():
        merged["JAVA_HOME"] = DEFAULT_JAVA_HOME
    if not merged.get("GRADLE_USER_HOME") and pathlib.Path(DEFAULT_GRADLE_USER_HOME).is_dir():
        merged["GRADLE_USER_HOME"] = DEFAULT_GRADLE_USER_HOME
    return merged


def run_generation(
    project_root: pathlib.Path,
    erbc_dir: pathlib.Path,
    game_root: pathlib.Path,
    work_run_dir: pathlib.Path,
    log_path: pathlib.Path | None = None,
    env: dict | None = None,
) -> tuple:
    """跑一次 bootChainProfile；返回 (exit_code, db_path, elapsed_seconds, log_text)。"""
    merged = gradle_env(env)
    merged["ERATW_ERBC_DIR"] = str(erbc_dir)
    merged["ERATW_GAMEDIR"] = str(game_root)
    merged["ERATW_BCP_WORK"] = str(work_run_dir)
    merged["ERATW_BCP_FRESH"] = "1"
    merged["ERATW_BCP_STOP_AFTER"] = "1"
    command = [
        os.environ.get("COMSPEC", "cmd.exe"),
        "/c",
        str(project_root / "gradlew.bat"),
        "--no-daemon",
        "--offline",
        "--console=plain",
        "--configure-on-demand",
        GRADLE_TASK,
    ]
    started = time.monotonic()
    proc = subprocess.run(
        command,
        cwd=str(project_root),
        env=merged,
        capture_output=True,
        text=True,
        errors="replace",
    )
    elapsed = time.monotonic() - started
    log_text = (proc.stdout or "") + (proc.stderr or "")
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(log_text, encoding="utf-8", errors="replace")
    db_path = work_run_dir / "sandbox" / "sql" / "plugins" / "qol_data.db"
    return proc.returncode, db_path, elapsed, log_text


def _tail(text: str, lines: int = 40) -> str:
    return "\n".join(text.splitlines()[-lines:])


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="现场生成 261111 口径 qol_data.db（真引擎 + 确定性 + 结构校验）"
    )
    parser.add_argument(
        "--erbc-dir",
        default=os.environ.get("ERATW_ERBC_DIR") or str(PROJECT_ROOT / "tools/erbcc/build/erbc-out/erbc.v1"),
        help="erbc.v1 目录（缺省 env ERATW_ERBC_DIR 或 <project>/tools/erbcc/build/erbc-out/erbc.v1）",
    )
    parser.add_argument(
        "--game-root",
        default=os.environ.get("ERATW_GAMEDIR"),
        help="语料根（缺省 env ERATW_GAMEDIR）",
    )
    parser.add_argument(
        "--work", default=str(PROJECT_ROOT / "build/seeddb/work"), help="探针 work 根（缺省 <project>/build/seeddb/work）"
    )
    parser.add_argument(
        "--out-dir", default=str(PROJECT_ROOT / "build/seeddb"), help="产物目录（缺省 <project>/build/seeddb）"
    )
    parser.add_argument("--compare", default=None, help="可选：261017 口径 qol_data.db，只读对拍四张图")
    parser.add_argument("--no-determinism", action="store_true", help="跳过第二次生成（deterministic 记 null）")
    parser.add_argument("--force", action="store_true", help="忽略 seed.json 缓存强制重跑")
    return parser.parse_args(argv)


def _clean_outputs(out_dir: pathlib.Path) -> None:
    for name in ("qol_data.db", "qol_data.db-wal", "qol_data.db-shm", "seed.json", "seed.json.tmp"):
        path = out_dir / name
        if path.exists():
            path.unlink()


def main(argv=None) -> int:
    args = parse_args(argv)
    project_root = PROJECT_ROOT
    erbc_dir = pathlib.Path(args.erbc_dir).resolve()
    if not args.game_root:
        print("错误: 缺 --game-root（或 env ERATW_GAMEDIR）", file=sys.stderr)
        return 1
    game_root = pathlib.Path(args.game_root).resolve()
    work_dir = pathlib.Path(args.work).resolve()
    out_dir = pathlib.Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    seed_path = out_dir / "seed.json"
    out_db = out_dir / "qol_data.db"

    program_erbc = erbc_dir / "program.erbc"
    program_meta = erbc_dir / "program.meta"
    dim_erh = game_root / "ERB" / "DIM.ERH"
    for required, label in ((program_erbc, "program.erbc"), (program_meta, "program.meta"), (dim_erh, "ERB/DIM.ERH")):
        if not required.is_file():
            print(f"错误: 缺 {label}: {required}", file=sys.stderr)
            return 1

    now_version = parse_now_version(dim_erh.read_text(encoding="utf-8", errors="replace"))

    program_sha = sha256_file(program_erbc)
    meta_sha = sha256_file(program_meta)

    if not args.force:
        cached = read_seed(seed_path)
        if seed_matches_cache(cached, program_sha, meta_sha, out_db):
            print(f"缓存命中: {out_db} 与 seed.json 摘要一致，跳过生成（--force 可重跑）")
            if args.compare and not (cached or {}).get("compare-261017"):
                compare = compare_graph_tables(out_db, pathlib.Path(args.compare).resolve())
                cached["compare-261017"] = compare
                write_seed(seed_path, cached)
                print(f"补做对拍: match={compare['match']}")
            print(f"seed.json: {seed_path}")
            return 0

    compare_path = pathlib.Path(args.compare).resolve() if args.compare else None
    runs = 1 if args.no_determinism else 2
    print(f"生成 {runs} 次（ERATW_BCP_STOP_AFTER=1，work={work_dir}）")
    db_shas = []
    db_paths = []
    for index in range(1, runs + 1):
        run_work = work_dir / f"run{index}"
        if run_work.exists():
            shutil.rmtree(run_work)
        log_path = run_work / "gradle.log"
        code, db_path, elapsed, log_text = run_generation(
            project_root, erbc_dir, game_root, run_work, log_path=log_path
        )
        print(f"run{index}: exit={code} 耗时={elapsed:.1f}s db={db_path}")
        if code != 0:
            _clean_outputs(out_dir)
            print(f"错误: run{index} gradle 退出码 {code}\n{_tail(log_text)}", file=sys.stderr)
            return 1
        if not db_path.is_file():
            _clean_outputs(out_dir)
            print(f"错误: run{index} 未产出 db: {db_path}\n{_tail(log_text)}", file=sys.stderr)
            return 1
        db_shas.append(sha256_file(db_path))
        db_paths.append(db_path)
        print(f"run{index}: sha256={db_shas[-1]} bytes={db_path.stat().st_size}")

    deterministic = None
    if len(db_shas) == 2:
        if db_shas[0] != db_shas[1]:
            _clean_outputs(out_dir)
            print(
                f"错误: 两次生成 sha256 不一致（run1={db_shas[0]} run2={db_shas[1]}），已删产物",
                file=sys.stderr,
            )
            return 2
        deterministic = True
        print("确定性: 两次 sha256 相同")

    problems = validate_db(db_paths[0], now_version)
    if problems:
        _clean_outputs(out_dir)
        print("错误: 结构校验不通过，已删产物:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 3

    shutil.copyfile(db_paths[0], out_db)
    if sha256_file(out_db) != db_shas[0]:
        _clean_outputs(out_dir)
        print("错误: 拷到产物目录后 sha256 变了", file=sys.stderr)
        return 1

    compare = None
    if compare_path is not None:
        compare = compare_graph_tables(out_db, compare_path)
        print(
            "对拍 261017: match={} tables={}".format(
                compare["match"], ",".join(compare["tables"].keys()) if compare["tables"] else "-"
            )
        )

    seed = build_seed(project_root, game_root, erbc_dir, out_db, now_version, deterministic, compare)
    write_seed(seed_path, seed)
    print(
        "产物: {} ({}) sha256={}".format(out_db, seed["db-bytes"], seed["db-sha256"])
    )
    print(f"seed.json: {seed_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
