#!/usr/bin/env python3
"""预置 qol_data.db 出包接线回归闸（跳过首次建图包）。

用法（在 eratw-android 下）::

    python devtools/test_seed_qol_db_pack.py
    python -m unittest devtools.test_seed_qol_db_pack

不依赖 Gradle / Android SDK / 网络 / 真 erbc / 真语料：`discover_seed_qol_db` 是纯函数
（路径与 erbc 产物都可注入），APK 侧由 `tools/apkpack/test_inspect_apk.py` 覆盖。

覆盖判据：
- 自动发现：`build/seeddb/qol_data.db` + `seed.json` 且 db/program 两个 sha 都对上 ⇒ 可用；
- 不可信即跳过：缺库 / 空库 / 缺 seed.json / 坏 JSON / 缺字段 / db 指纹不符 /
  找不到 program.erbc / 程序指纹不符（缺省只告警，`--require-seed-qol-db` 或显式
  `--seed-qol-db` 判红）；
- 不适用面：`--no-seed-qol-db` / `--skip-assets` / `--foreign-corpus` / 第三方引擎模式
  一律 skipped、不发声（否则日常小包会被无意义告警淹掉）；
- gradle 参数：可用 ⇒ `-PeratwSeedQolDb=<绝对路径>`；不可用 ⇒ 空列表（参数序列与引入前逐字一致）；
- package.report 自证：`seed-qol-db <sha256> <bytes>` 与生成物比对，缺行按 require 升级为判红；
- dry-run 端到端：真跑 `main(["--dry-run", ...])`，断言命令里出现/不出现预置库参数，
  以及 `--require-seed-qol-db` 在没产物时 exit 2。

退出码：0 = 全绿；1 = 有断言失败（unittest 默认）。
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)
sys.dont_write_bytecode = True

import pack_release as pr  # noqa: E402

SEED_DB = b"SQLite format 3\x00" + bytes(range(256)) * 40
PROGRAM = b"ERBC-PROGRAM-FIXTURE" + bytes(range(128))
META_KEYS = ("schema", "now_version", "corpus", "program-sha256", "meta-sha256",
             "db-sha256", "db-bytes", "tables", "graph-distance-rows", "generated-at",
             "deterministic", "compare-261017")


def _sha(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


class Fixture(unittest.TestCase):
    """每个用例一套临时 seeddb + 临时 erbc 产物。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.seeddb = self.root / "build" / "seeddb"
        self.seeddb.mkdir(parents=True)
        self.db = self.seeddb / pr.SEED_QOL_DB_BASENAME
        self.meta = self.seeddb / pr.SEED_QOL_DB_META_BASENAME
        self.erbc_dir = self.root / "erbc.v1"
        self.erbc_dir.mkdir()
        self.program = self.erbc_dir / "program.erbc"
        self.db.write_bytes(SEED_DB)
        self.program.write_bytes(PROGRAM)
        self.write_meta()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write_meta(self, **overrides) -> None:
        doc = {
            "schema": "eratw-qol-seeddb/1",
            "now_version": "20261111",
            "corpus": "eraTW4.981-261111-EAGLOV3",
            "program-sha256": _sha(PROGRAM),
            "meta-sha256": "0" * 64,
            "db-sha256": _sha(SEED_DB),
            "db-bytes": len(SEED_DB),
            "tables": 12,
            "graph-distance-rows": 36418,
            "generated-at": "2026-11-16T00:00:00Z",
            "deterministic": True,
            "compare-261017": {},
        }
        doc.update(overrides)
        self.meta.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")

    def discover(self, **kwargs) -> pr.SeedQolDbDiscovery:
        kwargs.setdefault("db_path", str(self.db))
        kwargs.setdefault("meta_path", str(self.meta))
        kwargs.setdefault("erbc_program", str(self.program))
        return pr.discover_seed_qol_db(**kwargs)


class DiscoveryTest(Fixture):
    def test_ok_auto(self) -> None:
        found = self.discover()
        self.assertEqual([], found.problems)
        self.assertEqual([], found.warnings)
        self.assertEqual("", found.skipped)
        self.assertIsNotNone(found.seed)
        seed = found.seed
        self.assertEqual(_sha(SEED_DB), seed.db_sha256)
        self.assertEqual(len(SEED_DB), seed.db_bytes)
        self.assertEqual(_sha(PROGRAM), seed.program_sha256)
        self.assertEqual("auto", seed.origin)
        self.assertEqual(os.path.abspath(self.db), seed.path)

    def test_missing_db_warns_then_requires(self) -> None:
        missing = self.seeddb / "nope.db"
        found = pr.discover_seed_qol_db(db_path=str(missing), meta_path=str(self.meta),
                                        erbc_program=str(self.program))
        self.assertIsNone(found.seed)
        self.assertEqual([], found.problems)
        self.assertTrue(found.warnings)
        self.assertIn("不存在", found.warnings[0])
        hard = pr.discover_seed_qol_db(db_path=str(missing), meta_path=str(self.meta),
                                       erbc_program=str(self.program), require=True)
        self.assertTrue(hard.problems)

    def test_empty_db(self) -> None:
        self.db.write_bytes(b"")
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("空文件", found.warnings[0])
        hard = self.discover(require=True)
        self.assertTrue(hard.problems)

    def test_db_sha_mismatch(self) -> None:
        self.write_meta(**{"db-sha256": "f" * 64})
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("指纹与 seed.json 不符", found.warnings[0])
        hard = self.discover(require=True)
        self.assertTrue(any("指纹与 seed.json 不符" in p for p in hard.problems))

    def test_db_bytes_mismatch(self) -> None:
        self.write_meta(**{"db-bytes": len(SEED_DB) + 1})
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertTrue(found.warnings)

    def test_program_sha_mismatch(self) -> None:
        self.write_meta(**{"program-sha256": "e" * 64})
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("erbc 产物不符", found.warnings[0])
        hard = self.discover(require=True)
        self.assertTrue(hard.problems)

    def test_missing_program_artifact(self) -> None:
        self.program.unlink()
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("找不到当前 erbc 产物", found.warnings[0])
        hard = self.discover(require=True)
        self.assertTrue(hard.problems)

    def test_missing_meta(self) -> None:
        self.meta.unlink()
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("缺 seed.json", found.warnings[0])

    def test_bad_meta_json(self) -> None:
        self.meta.write_text("{not json", encoding="utf-8")
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("读不出来", found.warnings[0])
        hard = self.discover(require=True)
        self.assertTrue(hard.problems)

    def test_meta_missing_fields(self) -> None:
        doc = {"schema": "eratw-qol-seeddb/1"}
        self.meta.write_text(json.dumps(doc), encoding="utf-8")
        found = self.discover()
        self.assertIsNone(found.seed)
        self.assertIn("缺 db-sha256", found.warnings[0])

    def test_skipped_contexts(self) -> None:
        for kwargs, needle in (
            ({"skip_assets": True}, "skip-assets"),
            ({"foreign_corpus": True}, "foreign-corpus"),
            ({"engine_config": True}, "engine-config"),
            ({"disabled": True}, "no-seed-qol-db"),
        ):
            found = self.discover(**kwargs)
            self.assertIsNone(found.seed)
            self.assertEqual([], found.problems)
            self.assertEqual([], found.warnings)
            self.assertIn(needle, found.skipped)

    def test_explicit_path_failure_is_problem(self) -> None:
        # 显式点名要的库拿不到 ⇒ 判红（不许静默退回「不带预置库」）
        found = self.discover(explicit=str(self.seeddb / "gone.db"), db_path="", meta_path="")
        self.assertIsNone(found.seed)
        self.assertTrue(any("不存在" in p for p in found.problems))

    def test_explicit_ok_origin_cli(self) -> None:
        found = self.discover(explicit=str(self.db), db_path="", meta_path="")
        self.assertIsNotNone(found.seed)
        self.assertEqual("cli", found.seed.origin)


class GradleArgsTest(unittest.TestCase):
    def test_none_yields_no_args(self) -> None:
        self.assertEqual([], pr.seed_qol_db_gradle_args(None))

    def test_seed_yields_one_arg(self) -> None:
        seed = pr.SeedQolDb(path="/x/build/seeddb/qol_data.db", db_sha256="a" * 64,
                            db_bytes=10, program_sha256="b" * 64, origin="cli")
        self.assertEqual(["-PeratwSeedQolDb=/x/build/seeddb/qol_data.db"],
                         pr.seed_qol_db_gradle_args(seed))


class ReportProblemsTest(unittest.TestCase):
    def seed(self) -> pr.SeedQolDb:
        return pr.SeedQolDb(path="/x/qol_data.db", db_sha256=_sha(SEED_DB),
                            db_bytes=len(SEED_DB), program_sha256=_sha(PROGRAM), origin="auto")

    def test_match(self) -> None:
        report = {pr.SEED_QOL_DB_REPORT_KEY: f"{_sha(SEED_DB)} {len(SEED_DB)}",
                  pr.SEED_QOL_DB_SOURCE_KEY: "/x/qol_data.db"}
        self.assertEqual([], pr.seed_qol_db_report_problems(self.seed(), report=report))

    def test_sha_mismatch(self) -> None:
        report = {pr.SEED_QOL_DB_REPORT_KEY: f"{'0' * 64} {len(SEED_DB)}"}
        self.assertTrue(pr.seed_qol_db_report_problems(self.seed(), report=report))

    def test_bytes_mismatch(self) -> None:
        report = {pr.SEED_QOL_DB_REPORT_KEY: f"{_sha(SEED_DB)} {len(SEED_DB) + 1}"}
        self.assertTrue(pr.seed_qol_db_report_problems(self.seed(), report=report))

    def test_missing_line_require(self) -> None:
        self.assertTrue(pr.seed_qol_db_report_problems(self.seed(), require=True, report={}))

    def test_missing_line_lenient(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual([], pr.seed_qol_db_report_problems(self.seed(), report={}))

    def test_none_seed_require_missing(self) -> None:
        self.assertTrue(pr.seed_qol_db_report_problems(None, require=True, report={}))

    def test_none_seed_require_present(self) -> None:
        report = {pr.SEED_QOL_DB_REPORT_KEY: f"{_sha(SEED_DB)} {len(SEED_DB)}"}
        self.assertEqual([], pr.seed_qol_db_report_problems(None, require=True, report=report))

    def test_malformed_line(self) -> None:
        self.assertTrue(pr.seed_qol_db_report_problems(self.seed(), report={
            pr.SEED_QOL_DB_REPORT_KEY: "not-a-digest"}))


class DryRunIntegrationTest(Fixture):
    """真跑 main(--dry-run)：只打印 gradle 命令，不构建、不需要令牌。"""

    ENV_KEYS = ("ERATW_REPORT_ENDPOINT", "ERATW_REPORT_TOKEN", "ERATW_UPDATE_MIRRORS",
                "ERATW_GRADLE_EXTRA_ARGS", "ERATW_ERBC_DIR", "ERATW_CORPUS",
                "ERATW_GAMEDIR")

    def setUp(self) -> None:
        super().setUp()
        self.game_root = self.root / "corpus-root"
        self.game_root.mkdir()
        self._saved_env = {k: os.environ.get(k) for k in self.ENV_KEYS}
        for key in self.ENV_KEYS:
            os.environ.pop(key, None)

    def tearDown(self) -> None:
        for key, value in self._saved_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        super().tearDown()

    def run_main(self, argv: list[str]) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = pr.main(argv)
        return code, out.getvalue()

    def test_dry_run_carries_seed_arg(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        with mock.patch.object(pr, "APP_ROOT", str(self.root)):
            code, out = self.run_main(["--dry-run", "--offline",
                                       "--game-root", str(self.game_root)])
        self.assertEqual(0, code, out)
        self.assertIn("-PeratwSeedQolDb=", out)
        self.assertIn("预置库：", out)

    def test_dry_run_without_seeddb_warns_only(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        (self.seeddb / pr.SEED_QOL_DB_BASENAME).unlink()
        with mock.patch.object(pr, "APP_ROOT", str(self.root)):
            code, out = self.run_main(["--dry-run", "--offline",
                                       "--game-root", str(self.game_root)])
        self.assertEqual(0, code, out)
        self.assertNotIn("-PeratwSeedQolDb=", out)
        self.assertIn("本次不带预置库", out)

    def test_dry_run_require_missing_seed_is_red(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        (self.seeddb / pr.SEED_QOL_DB_BASENAME).unlink()
        with mock.patch.object(pr, "APP_ROOT", str(self.root)):
            code, out = self.run_main(["--dry-run", "--offline", "--require-seed-qol-db",
                                       "--game-root", str(self.game_root)])
        self.assertEqual(2, code, out)

    def test_dry_run_no_seed_flag(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        with mock.patch.object(pr, "APP_ROOT", str(self.root)):
            code, out = self.run_main(["--dry-run", "--offline", "--no-seed-qol-db",
                                       "--game-root", str(self.game_root)])
        self.assertEqual(0, code, out)
        self.assertNotIn("-PeratwSeedQolDb=", out)
        self.assertIn("跳过", out)

    def test_mutually_exclusive_flags(self) -> None:
        code, _ = self.run_main(["--dry-run", "--no-seed-qol-db",
                                 "--require-seed-qol-db"])
        self.assertEqual(2, code)

    def test_dry_run_missing_seeddb_mentions_autogen(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        (self.seeddb / pr.SEED_QOL_DB_BASENAME).unlink()
        err = io.StringIO()
        with mock.patch.object(pr, "APP_ROOT", str(self.root)), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(err):
            code = pr.main(["--dry-run", "--offline", "--game-root", str(self.game_root)])
        self.assertEqual(0, code, err.getvalue())
        # dry-run 不跑引擎，但必须让调用方看见「真出包会先补跑生成器」。
        self.assertIn("自动跑", err.getvalue())

    def test_missing_seed_triggers_generator_and_release_form_is_red(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        (self.seeddb / pr.SEED_QOL_DB_BASENAME).unlink()
        err = io.StringIO()
        with mock.patch.object(pr, "APP_ROOT", str(self.root)), \
                mock.patch.object(pr, "run_seed_qol_db_generator", return_value=1) as gen, \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(err):
            code = pr.main(["--offline", "--no-update", "--no-archive",
                            "--game-root", str(self.game_root)])
        self.assertEqual(2, code, err.getvalue())
        self.assertEqual(1, gen.call_count)
        # 正式形态缺省强制：生成失败必须拒包，不许把建图成本退回给玩家。
        self.assertIn("拒绝出包", err.getvalue())

    def test_skip_assets_does_not_trigger_generator(self) -> None:
        from unittest import mock
        os.environ["ERATW_ERBC_DIR"] = str(self.erbc_dir)
        (self.seeddb / pr.SEED_QOL_DB_BASENAME).unlink()
        err = io.StringIO()
        with mock.patch.object(pr, "APP_ROOT", str(self.root)), \
                mock.patch.object(pr, "run_seed_qol_db_generator", return_value=1) as gen, \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(err):
            # dev 形态（--skip-assets）本来就不带预置库：不许为此跑引擎、也不许判红。
            code = pr.main(["--dry-run", "--offline", "--skip-assets",
                            "--game-root", str(self.game_root)])
        self.assertEqual(0, code, err.getvalue())
        self.assertEqual(0, gen.call_count)


class GeneratorWiringTest(unittest.TestCase):
    """预置库自动生成接线的纯函数口径（不跑引擎）。"""

    def test_skip_reason_matrix(self) -> None:
        reason = pr.seed_qol_db_generator_skip_reason
        base = dict(disabled=False, explicit="", skip_assets=False,
                    foreign_corpus=False, engine_config=False, verify_only=False)
        self.assertEqual("", reason(**base))
        self.assertEqual("--no-seed-qol-db", reason(**{**base, "disabled": True}))
        self.assertEqual("--skip-assets", reason(**{**base, "skip_assets": True}))
        self.assertEqual("--foreign-corpus", reason(**{**base, "foreign_corpus": True}))
        self.assertEqual("--verify-only", reason(**{**base, "verify_only": True}))
        self.assertIn("引擎配置模式", reason(**{**base, "engine_config": True}))
        self.assertIn("--seed-qol-db", reason(**{**base, "explicit": "/x.db"}))

    def test_generator_cmd_shape(self) -> None:
        cmd = pr.seed_qol_db_generator_cmd(game_root="G", erbc_dir="E", compare="C")
        self.assertEqual(sys.executable, cmd[0])
        self.assertTrue(cmd[1].replace("\\", "/").endswith("devtools/gen_qol_seeddb.py"))
        self.assertEqual(["--game-root", "--erbc-dir", "--compare"],
                         [cmd[2], cmd[4], cmd[6]])
        self.assertEqual(["G", "E", "C"], [cmd[3], cmd[5], cmd[7]])
        self.assertEqual(2, len(pr.seed_qol_db_generator_cmd()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
