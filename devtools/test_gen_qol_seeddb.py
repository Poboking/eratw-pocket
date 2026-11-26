#!/usr/bin/env python3
"""T11 定向单测：`devtools/gen_qol_seeddb.py` 的纯函数与校验逻辑。

用法（在 eratw-android 下）::

    python devtools/test_gen_qol_seeddb.py
    python -m unittest devtools/test_gen_qol_seeddb.py  # 若所在目录不在 sys.path，直接跑上一个

全部用临时 sqlite 库与临时文件，不跑 gradle、不碰真语料/真 erbc。
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import sqlite3
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import gen_qol_seeddb as seedgen  # noqa: E402

GRAPH_DDL = {
    "GRAPH_EDGE": "map_id INTEGER, from_node INTEGER, to_node INTEGER, can_move INTEGER, can_see INTEGER",
    "GRAPH_DISTANCE": "map_id INTEGER, source INTEGER, target INTEGER, distance INTEGER, previous INTEGER",
    "GRAPH_CROSS_MAP_EDGE": "from_map INTEGER, to_map INTEGER, from_node INTEGER, to_node INTEGER",
    "GRAPH_NODE_ATTR": "place_id INTEGER, attr TEXT",
}
PLAIN_TABLES = (
    "QOL_CRAFT_RECIPE",
    "QOL_DISH_DB",
    "QOL_FISH_DB",
    "QOL_ITEM_DB",
    "QOL_ITEM_META",
    "QOL_MUSHI_DB",
    "QOL_WOOD_DB",
)


def make_db(
    path: pathlib.Path,
    *,
    meta_version: str = "20261111",
    distance_rows: int = seedgen.EXPECTED_GRAPH_DISTANCE,
    with_all_tables: bool = True,
    page_size: int = 4096,
    skip_table: str | None = None,
) -> None:
    """造一份结构合格（或按开关缺表/改行数/改版本）的临时 qol_data.db。"""
    con = sqlite3.connect(str(path))
    try:
        con.execute(f"PRAGMA page_size = {page_size}")
        con.execute("CREATE TABLE _meta (key TEXT PRIMARY KEY, value TEXT)")
        con.execute("INSERT INTO _meta VALUES ('graph_schema_version', ?)", (meta_version,))
        con.execute("INSERT INTO _meta VALUES ('item_schema_version', ?)", (meta_version,))
        for name, ddl in GRAPH_DDL.items():
            if skip_table == name:
                continue
            con.execute(f"CREATE TABLE {name} ({ddl})")
        if skip_table != "GRAPH_DISTANCE":
            con.executemany(
                "INSERT INTO GRAPH_DISTANCE VALUES (?, ?, ?, ?, ?)",
                ((row, 0, 0, 1, 0) for row in range(distance_rows)),
            )
        for name in PLAIN_TABLES:
            if skip_table == name:
                continue
            con.execute(f"CREATE TABLE {name} (value INTEGER)")
        con.commit()
    finally:
        con.close()


class TempDirCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()


class ParseNowVersionTest(unittest.TestCase):
    def test_parses_dim_const_form(self) -> None:
        text = "#DIM CONST NOW_VERSION = 20261111\n#DIM OTHER = 1\n"
        self.assertEqual(seedgen.parse_now_version(text), "20261111")

    def test_parses_with_extra_spaces(self) -> None:
        self.assertEqual(seedgen.parse_now_version("#  DIM  CONST  NOW_VERSION   =  20261111"), "20261111")

    def test_missing_raises(self) -> None:
        with self.assertRaises(ValueError):
            seedgen.parse_now_version("#DIM CONST OTHER = 1")


class Sha256Test(unittest.TestCase):
    def test_matches_hashlib(self) -> None:
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            fh.write(b"eraTW seeddb\n")
            name = fh.name
        try:
            self.assertEqual(seedgen.sha256_file(pathlib.Path(name)), hashlib.sha256(b"eraTW seeddb\n").hexdigest())
        finally:
            os.unlink(name)

    def test_empty_file(self) -> None:
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            name = fh.name
        try:
            self.assertEqual(
                seedgen.sha256_file(pathlib.Path(name)), hashlib.sha256(b"").hexdigest()
            )
        finally:
            os.unlink(name)


class ValidateDbTest(TempDirCase):
    def test_clean_db_passes(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db)
        self.assertEqual(seedgen.validate_db(db, "20261111"), [])

    def test_missing_db_is_problem(self) -> None:
        problems = seedgen.validate_db(self.tmp / "nope.db", "20261111")
        self.assertTrue(problems and "产物不存在" in problems[0])

    def test_missing_table_reported(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db, skip_table="GRAPH_NODE_ATTR")
        problems = seedgen.validate_db(db, "20261111")
        self.assertTrue(any("缺表" in p and "GRAPH_NODE_ATTR" in p for p in problems), problems)

    def test_version_mismatch_reported(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db, meta_version="20261017")
        problems = seedgen.validate_db(db, "20261111")
        self.assertTrue(any("graph_schema_version" in p for p in problems), problems)

    def test_distance_rows_mismatch_reported(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db, distance_rows=10)
        problems = seedgen.validate_db(db, "20261111")
        self.assertTrue(any("GRAPH_DISTANCE 行数" in p for p in problems), problems)

    def test_page_size_mismatch_reported(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db, page_size=8192)
        problems = seedgen.validate_db(db, "20261111")
        self.assertTrue(any("page_size" in p for p in problems), problems)

    def test_sidecar_files_reported(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db)
        db.with_name(db.name + "-wal").write_bytes(b"")
        problems = seedgen.validate_db(db, "20261111")
        self.assertTrue(any("侧车" in p for p in problems), problems)


class CompareGraphTablesTest(TempDirCase):
    def test_identical_databases_match(self) -> None:
        first = self.tmp / "a.db"
        second = self.tmp / "b.db"
        make_db(first)
        make_db(second)
        result = seedgen.compare_graph_tables(first, second)
        self.assertTrue(result["match"], result)
        self.assertEqual(set(result["tables"]), set(seedgen.GRAPH_TABLES))
        for entry in result["tables"].values():
            self.assertEqual(entry["only-main"], 0)
            self.assertEqual(entry["only-old"], 0)

    def test_row_difference_detected(self) -> None:
        first = self.tmp / "a.db"
        second = self.tmp / "b.db"
        make_db(first)
        make_db(second)
        con = sqlite3.connect(str(second))
        try:
            con.execute("DELETE FROM GRAPH_DISTANCE WHERE rowid = 1")
            con.execute("INSERT INTO GRAPH_EDGE VALUES (9, 9, 9, 1, 1)")
            con.commit()
        finally:
            con.close()
        result = seedgen.compare_graph_tables(first, second)
        self.assertFalse(result["match"], result)
        self.assertEqual(result["tables"]["GRAPH_DISTANCE"]["only-main"], 1)
        self.assertEqual(result["tables"]["GRAPH_EDGE"]["only-old"], 1)

    def test_missing_compare_file_fails(self) -> None:
        first = self.tmp / "a.db"
        make_db(first)
        result = seedgen.compare_graph_tables(first, self.tmp / "missing.db")
        self.assertFalse(result["match"])
        self.assertIn("对拍库不存在", result["error"])


class SeedJsonTest(TempDirCase):
    def test_roundtrip(self) -> None:
        seed_path = self.tmp / "seed.json"
        seed = {
            "schema": seedgen.SCHEMA,
            "now_version": "20261111",
            "corpus": {"root": "D:/corpus", "commit": "deadbeef"},
            "program-sha256": "a" * 64,
            "meta-sha256": "b" * 64,
            "db-sha256": "c" * 64,
            "db-bytes": 1425408,
            "tables": list(seedgen.REQUIRED_TABLES),
            "graph-distance-rows": seedgen.EXPECTED_GRAPH_DISTANCE,
            "generated-at": "2026-11-16T00:00:00+00:00",
            "deterministic": True,
            "compare-261017": None,
        }
        seedgen.write_seed(seed_path, seed)
        loaded = seedgen.read_seed(seed_path)
        self.assertEqual(loaded, seed)

    def test_bad_json_returns_none(self) -> None:
        seed_path = self.tmp / "seed.json"
        seed_path.write_text("{not json", encoding="utf-8")
        self.assertIsNone(seedgen.read_seed(seed_path))

    def test_cache_matching(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db)
        seed = {
            "schema": seedgen.SCHEMA,
            "program-sha256": "a" * 64,
            "meta-sha256": "b" * 64,
            "db-sha256": seedgen.sha256_file(db),
            "db-bytes": db.stat().st_size,
        }
        self.assertTrue(seedgen.seed_matches_cache(seed, "a" * 64, "b" * 64, db))
        self.assertFalse(seedgen.seed_matches_cache(seed, "x" * 64, "b" * 64, db))
        self.assertFalse(seedgen.seed_matches_cache(dict(seed, schema=999), "a" * 64, "b" * 64, db))
        self.assertFalse(seedgen.seed_matches_cache(seed, "a" * 64, "b" * 64, self.tmp / "missing.db"))

    def test_cache_rejects_tampered_db(self) -> None:
        db = self.tmp / "qol_data.db"
        make_db(db)
        seed = {
            "schema": seedgen.SCHEMA,
            "program-sha256": "a" * 64,
            "meta-sha256": "b" * 64,
            "db-sha256": seedgen.sha256_file(db),
            "db-bytes": db.stat().st_size,
        }
        db.write_bytes(b"tampered")
        self.assertFalse(seedgen.seed_matches_cache(seed, "a" * 64, "b" * 64, db))


class GradleEnvTest(unittest.TestCase):
    def test_keeps_existing_values(self) -> None:
        merged = seedgen.gradle_env({"JAVA_HOME": "C:/custom-jdk", "GRADLE_USER_HOME": "C:/custom-gradle"})
        self.assertEqual(merged["JAVA_HOME"], "C:/custom-jdk")
        self.assertEqual(merged["GRADLE_USER_HOME"], "C:/custom-gradle")

    def test_fills_defaults_when_missing(self) -> None:
        if not pathlib.Path(seedgen.DEFAULT_JAVA_HOME).is_dir():
            self.skipTest("本机没有缺省 JDK 目录")
        merged = seedgen.gradle_env({})
        self.assertEqual(merged.get("JAVA_HOME"), seedgen.DEFAULT_JAVA_HOME)


if __name__ == "__main__":
    unittest.main(verbosity=2)
