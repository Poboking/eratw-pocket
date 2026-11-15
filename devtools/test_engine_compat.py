#!/usr/bin/env python3
"""引擎兼容构建期通道回归闸（ENGC-6 / B6-PACK）。

用法（在 eratw-android 下）::

    python devtools/test_engine_compat.py

不依赖 Gradle / Android SDK / 网络 / 真 erbcc / 真语料：配置校验与印章走纯函数，
APK 用临时 zip 构造，基线对拍用 dry-run 参数级断言。erbcc 的 `--engine-report`
只按契约 §8.0.3 的形状假设喂样例 JSON（不跑真 erbcc，接口由 Lane A 落地）。

覆盖判据（契约 §8.2 / §8.0）：
- 配置校验：三个合法组合各过；`engine:"honke"` / `version:"4.982"` / 未知字段 /
  schema 不符 / 缺字段 / 坏 JSON 各红，报错附支持表；
- 印章四字段逐字等于同步表（schema/profile/engine/version）；
- TARGETS 单源对拍（§8.0.1 / CONTRACT-ENGCPL E1）：python 侧三元组 == 生成物 yaml 的
  targets 段（不再读 Kotlin 源码文本，禁止跨语言正则对拍）；
- pack 零干扰（I7）：无 `--engine-config` 时 run_build 的 gradle 参数序列与改动前
  基线逐 token 一致（基线逐字转录自改动前 pack_release.py:557-589 的参数构造）；
- `--verify-only` 印章回读比对（临时 zip 构造带/不带印章两例）；
- 报告渲染（§8.0.3 JSON → md：目标档、行号、「未检查（二期）」）；
- 反向证明①：删掉「无配置早退」⇒ 零干扰用例红；反向证明②：印章写死
  `snake_4981` ⇒ 配置模式回读用例红。均为源码变异后导入变异模块实测，非推理。

退出码：0 = 全绿；1 = 有断言失败。
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)
sys.dont_write_bytecode = True

import engine_compat  # noqa: E402
import engine_compat_report  # noqa: E402
import pack_release as pr  # noqa: E402

# 生成物（机器可读投影）：档位三元组的对拍对象。
GENERATED_SPEC_FILE = os.path.join(APP_ROOT, "specs", "engine-profiles.yaml")

THREE_VALID_CONFIGS = (
    {"schema": 1, "engine": "snake", "version": "4.981"},
    {"schema": 1, "engine": "em_ee", "version": "1824+v22+EMv18+EEv57"},
    {"schema": 1, "engine": "v24", "version": "1824+v24"},
)


# ---------------------------------------------------------------- 工具

def _load_mutant(rel_path: str, replacements: list, name: str):
    """把 devtools/<rel_path> 源文本做字符串变异后当独立模块导入（反向证明用）。

    变异前归一 CRLF：worktree 检出是 CRLF 行尾，锚点按 LF 写。
    """
    with open(os.path.join(HERE, rel_path), encoding="utf-8") as fh:
        src = fh.read().replace("\r\n", "\n")
    for old, new in replacements:
        assert old in src, f"变异锚点不存在（反向证明失效）：{old!r}"
        assert src.count(old) == 1, f"变异锚点不唯一（反向证明失效）：{old!r}"
        src = src.replace(old, new, 1)
    tmpdir = tempfile.mkdtemp(prefix="engcompat-mutant-")
    # 变异模块放在 <tmp>/devtools/ 下，使它的「祖父目录」= tmp 根 —— 与真实仓库结构同形，
    # 模块内按 <repo>/specs/... 定位的数据文件才能在临时目录里成对补齐。
    path = os.path.join(tmpdir, "devtools", rel_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(src)
    # 手写引擎身份表按模块自身路径解析；补一份同路径副本，免得「读不到 input yaml」
    # 把反向证明变成假红（变异只该影响被变异的那一行）。
    spec_src = os.path.join(APP_ROOT, engine_compat.INPUT_SPEC_REL)
    if os.path.isfile(spec_src):
        spec_dst = os.path.join(tmpdir, engine_compat.INPUT_SPEC_REL)
        os.makedirs(os.path.dirname(spec_dst), exist_ok=True)
        shutil.copyfile(spec_src, spec_dst)
    # CONTRACT-ENGCPL L3：pack_release 的语料根名改读**生成物** specs/engine-profiles.yaml
    # （corpora 段），同样按模块自身路径解析 —— 与 input 表成对补齐。缺它变异模块在 import
    # 期就停在「语料身份表不存在」，反向证明会变成与变异无关的假红。
    generated_src = os.path.join(APP_ROOT, "specs", "engine-profiles.yaml")
    if os.path.isfile(generated_src):
        generated_dst = os.path.join(tmpdir, "specs", "engine-profiles.yaml")
        os.makedirs(os.path.dirname(generated_dst), exist_ok=True)
        shutil.copyfile(generated_src, generated_dst)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    # 变异模块的 APP_ROOT 指向临时目录会让 git_head 退化成 "nogit"，与真模块的基线
    # 比较出现假阳性差异 —— 指回真仓，保证 token 差异只能来自变异本身。
    if hasattr(mod, "APP_ROOT"):
        mod.APP_ROOT = pr.APP_ROOT
    return mod


def _source_normalized(rel_path: str) -> str:
    with open(os.path.join(HERE, rel_path), encoding="utf-8") as fh:
        return fh.read().replace("\r\n", "\n")


def _baseline_tasks(module) -> list:
    """改动前 pack_release.py run_build 的 gradle 参数序列（逐字转录基线）。

    基线对拍证据：静态 token 逐字取自改动前 run_build:557-589 的构造顺序
    （--configure-on-demand 起、-PeratwReportToken 止），动态值（worker 数、
    BuildStamp）取调用模块自己的同源函数。无配置时实际输出必须与本表**完全相等**，
    多一个 token 都算零干扰破坏。
    """
    return [
        "--configure-on-demand",
        ":app:assembleRelease",
        "--parallel",
        "--build-cache",
        f"--max-workers={module.gradle_worker_count()}",
        f"-PeratwBuildStamp={module.git_head()}",
        "-PeratwErbcCompress",
        "-PeratwGameRoot=C:/gr",
        "-PeratwReportEndpoint=https://ep.example/report",
        "-PeratwReportToken=***",  # run_build 会把令牌掩码成 *** 再打印
    ]


def _capture_dry_run_tokens(module, engine_cfg=None):
    """dry-run 跑 module.run_build，抓回 gradle 参数 token 序列（去 launcher 前缀）。

    返回 (tokens, error)：error 非空 = 该模块的 run_build 崩了（变异后的红）。
    """
    saved = os.environ.pop("ERATW_GRADLE_EXTRA_ARGS", None)
    out, err_out = io.StringIO(), io.StringIO()
    error = None
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err_out):
            module.run_build("https://ep.example/report", "tok", False, True,
                             "C:/gr", "https://m.example/", erbc_stored=False,
                             engine_config=engine_cfg)
    except Exception as exc:  # noqa: BLE001 反向证明需要捕捉变异后的崩溃
        error = exc
    finally:
        if saved is not None:
            os.environ["ERATW_GRADLE_EXTRA_ARGS"] = saved
    tokens = None
    for line in out.getvalue().splitlines():
        if ":app:assembleRelease" not in line:
            continue
        parts = line.split()[1:]           # 去掉 "[pack]" 前缀
        tokens = parts[parts.index("--configure-on-demand"):]
        break
    return tokens, error


def _with_temp_apk(entries: dict, fn):
    """把 entries 写成临时 zip 当 APK，指给 pack_release 后执行 fn，完事还原。"""
    tmpdir = tempfile.mkdtemp(prefix="engcompat-apk-")
    apk = os.path.join(tmpdir, "app-release.apk")
    with zipfile.ZipFile(apk, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    saved = pr.APK_PATH
    try:
        pr.APK_PATH = apk
        pr._APK_ENTRY_CACHE.clear()
        pr._APK_NAMELIST_CACHE.clear()
        return fn()
    finally:
        pr.APK_PATH = saved
        pr._APK_ENTRY_CACHE.clear()
        pr._APK_NAMELIST_CACHE.clear()
        shutil.rmtree(tmpdir, ignore_errors=True)


# ---------------------------------------------------------------- 配置校验

def test_config_three_valid_combos():
    for raw in THREE_VALID_CONFIGS:
        cfg = engine_compat.validate_config(raw)
        want = next(t for t in engine_compat.TARGETS
                    if t["engine"] == raw["engine"] and t["version"] == raw["version"])
        assert cfg == {"profile": want["profile"], "engine": raw["engine"],
                       "version": raw["version"]}, f"{raw} 应解析到 {want['profile']}，实际 {cfg}"


def test_config_unknown_engine_red():
    try:
        engine_compat.validate_config({"schema": 1, "engine": "honke", "version": "4.981"})
    except engine_compat.EngineConfigError as exc:
        msg = str(exc)
        assert "honke" in msg, f"报错要点名违规值：{msg}"
        for t in engine_compat.TARGETS:
            assert t["profile"] in msg, f"报错必须附支持表（缺 {t['profile']}）：{msg}"
        return
    raise AssertionError('engine:"honke" 必须判红')


def test_config_unknown_version_red():
    try:
        engine_compat.validate_config({"schema": 1, "engine": "snake", "version": "4.982"})
    except engine_compat.EngineConfigError as exc:
        msg = str(exc)
        assert "4.982" in msg and "snake_4981" in msg, f"报错要点名违规值并附支持表：{msg}"
        return
    raise AssertionError('version:"4.982" 必须判红')


def test_config_unknown_field_red():
    try:
        engine_compat.validate_config({"schema": 1, "engine": "snake", "version": "4.981",
                                       "dialect": "legacy"})
    except engine_compat.EngineConfigError as exc:
        msg = str(exc)
        assert "dialect" in msg, f"报错要点名未知字段：{msg}"
        assert "snake_4981" in msg, f"报错必须附支持表：{msg}"
        return
    raise AssertionError("未知字段必须判红")


def test_config_schema_and_types_red():
    cases = (
        ({"schema": 2, "engine": "snake", "version": "4.981"}, "schema"),
        ({"schema": True, "engine": "snake", "version": "4.981"}, "schema"),
        ({"schema": "1", "engine": "snake", "version": "4.981"}, "schema"),
        ({"schema": 1, "engine": "snake"}, "缺字段"),
        ({"schema": 1, "version": "4.981"}, "缺字段"),
        ({"schema": 1, "engine": 1, "version": "4.981"}, "字符串"),
        ([1, 2], "JSON 对象"),
    )
    for raw, keyword in cases:
        try:
            engine_compat.validate_config(raw)
        except engine_compat.EngineConfigError as exc:
            assert keyword in str(exc), f"{raw!r} 应报「{keyword}」，实际：{exc}"
            assert "snake_4981" in str(exc), f"报错必须附支持表：{exc}"
        else:
            raise AssertionError(f"{raw!r} 必须判红")


def test_config_load_file_reds():
    tmp = tempfile.mkdtemp(prefix="engcompat-cfg-")
    try:
        bad_json = os.path.join(tmp, "bad.json")
        with open(bad_json, "w", encoding="utf-8") as fh:
            fh.write("{not json")
        missing_field = os.path.join(tmp, "missing.json")
        with open(missing_field, "w", encoding="utf-8") as fh:
            json.dump({"schema": 1, "engine": "snake"}, fh)
        for path, keyword in ((os.path.join(tmp, "nope.json"), "读不到"),
                              (bad_json, "JSON"), (missing_field, "缺字段")):
            try:
                engine_compat.load_engine_config(path)
            except engine_compat.EngineConfigError as exc:
                assert keyword in str(exc), f"{path} 应报「{keyword}」，实际：{exc}"
            else:
                raise AssertionError(f"{path} 必须判红")
        # 合法文件全链路：load → 印章字节 → 回读比对为空
        good = os.path.join(tmp, "good.json")
        with open(good, "w", encoding="utf-8") as fh:
            json.dump(THREE_VALID_CONFIGS[2], fh)
        cfg = engine_compat.load_engine_config(good)
        assert cfg["profile"] == "v24_1824"
        assert engine_compat.verify_stamp_text(
            engine_compat.stamp_bytes(cfg).decode("utf-8"), cfg) == []
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 印章与同步表

def test_stamp_document_four_fields():
    for t in engine_compat.TARGETS:
        cfg = {"profile": t["profile"], "engine": t["engine"], "version": t["version"]}
        doc = engine_compat.stamp_document(cfg)
        assert list(doc.keys()) == ["schema", "profile", "engine", "version"], \
            f"印章必须恰四字段且键序固定：{list(doc.keys())}"
        assert doc == {"schema": 1, "profile": t["profile"], "engine": t["engine"],
                       "version": t["version"]}
    # 写盘 → 读回逐字节一致（临时目录，不碰 build/）
    cfg = engine_compat.validate_config(THREE_VALID_CONFIGS[0])
    tmp = tempfile.mkdtemp(prefix="engcompat-stamp-")
    try:
        path = engine_compat.write_stamp_file(cfg, os.path.join(tmp, "engine-profile.json"))
        with open(path, "rb") as fh:
            assert fh.read() == engine_compat.stamp_bytes(cfg), "印章落盘字节与 stamp_bytes 不一致"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_targets_python_matches_generated_yaml():
    """CONTRACT-ENGCPL E1 / §8.0.1：python 侧三元组 == 生成物 yaml 的 targets 段。

    两侧都只读**数据**（python 读 input 表，本用例读生成物 yaml 的 targets 段），
    不再读对方源码文本做正则对拍（契约 §5 禁止项）。任一侧手抄/改错即红。
    """
    try:
        import yaml  # type: ignore
    except ImportError:
        raise AssertionError("读 specs/engine-profiles.yaml 需要 PyYAML：pip install pyyaml")
    with open(GENERATED_SPEC_FILE, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)
    section = doc.get("targets") if isinstance(doc, dict) else None
    assert isinstance(section, dict) and section, \
        f"生成物缺 targets 段：{GENERATED_SPEC_FILE}（先跑 devtools/gen_engine_profiles.py）"
    generated = [(name, entry["engine"], entry["stamp_version"]) for name, entry in section.items()]
    python_side = [(t["profile"], t["engine"], t["version"]) for t in engine_compat.TARGETS]
    assert len(generated) == 3, f"生成物 targets 应恰 3 条，实际 {len(generated)}：{generated}"
    assert generated == python_side, (
        f"档位三元组漂移（input 表 vs 生成物 yaml）：\n生成物 {generated}\npython {python_side}")


# ---------------------------------------------------------------- pack 零干扰与配置模式

def test_pack_baseline_zero_interference():
    tokens, err = _capture_dry_run_tokens(pr, None)
    assert err is None, f"无配置 dry-run 不该报错：{err!r}"
    expected = _baseline_tasks(pr)
    assert tokens == expected, (
        "无配置 gradle 参数与改动前基线不符（零干扰破坏）：\n"
        f"  期望 {expected}\n  实际 {tokens}")
    assert not any(t.startswith("-PeratwEngineProfile") for t in tokens)
    assert not any(t.startswith("-PeratwEngineStampFile") for t in tokens)


def test_pack_config_mode_gradle_args():
    cfg = engine_compat.validate_config(THREE_VALID_CONFIGS[2])
    tokens, err = _capture_dry_run_tokens(pr, cfg)
    assert err is None, f"配置模式 dry-run 不该报错：{err!r}"
    expected_inject = [f"-PeratwEngineProfile={cfg['profile']}",
                       f"-PeratwEngineStampFile={pr.engine_stamp_path()}"]
    assert tokens[-2:] == expected_inject, (
        f"配置模式必须在参数尾部注入两个 -PeratwEngine* 参数：\n  实际 {tokens}")
    assert tokens[:-2] == _baseline_tasks(pr), (
        "配置模式除新增两参数外，其余参数必须与基线逐字一致：\n"
        f"  期望 {_baseline_tasks(pr)}\n  实际 {tokens[:-2]}")


def test_pack_verify_only_stamp_problems():
    cfg = engine_compat.validate_config(THREE_VALID_CONFIGS[0])
    good = engine_compat.stamp_bytes(cfg)
    wrong_version = good.replace(b"4.981", b"4.982")
    wrong_profile = good.replace(b"snake_4981", b"v24_1824")
    assert wrong_version != good and wrong_profile != good, "印章变异没生效，反向比对不成立"

    cases = (
        # 缺印章：配置模式却没烙章 ⇒ 判红
        ({}, lambda problems: assert_true(
            len(problems) == 1 and "缺印章" in problems[0], f"缺印章必须判红：{problems}")),
        # 印章正确 ⇒ 空
        ({engine_compat.STAMP_ASSET_PATH: good},
         lambda problems: assert_true(problems == [], f"正确印章不该判红：{problems}")),
        # 版本被改 ⇒ 判红且点名 version
        ({engine_compat.STAMP_ASSET_PATH: wrong_version},
         lambda problems: assert_true(
             problems and "version" in problems[0], f"版本不符必须判红：{problems}")),
        # 档位被改 ⇒ 判红且点名 profile
        ({engine_compat.STAMP_ASSET_PATH: wrong_profile},
         lambda problems: assert_true(
             problems and "profile" in problems[0], f"档位不符必须判红：{problems}")),
        # 坏 JSON ⇒ 判红
        ({engine_compat.STAMP_ASSET_PATH: b"not json"},
         lambda problems: assert_true(
             problems and "JSON" in problems[0], f"坏印章必须判红：{problems}")),
    )
    for entries, verify in cases:
        _with_temp_apk(entries, lambda v=verify: v(pr.engine_stamp_problems(cfg)))


def assert_true(ok: bool, detail: str) -> None:
    assert ok, detail


# ---------------------------------------------------------------- 报告渲染

SAMPLE_REPORT = {
    "schema": 1,
    "profile": "v24_1824",
    "engine": "v24",
    "version": "1824+v24",
    "corpus": "C:/games/third-party",
    "counts": {"files": 2, "compiled": 1, "parseFailures": 1,
               "unknownCommands": 1, "unknownFunctions": 0},
    "unknownCommands": [{"name": "SETBGIMAGE", "file": "ERB/minus.ERB", "line": 120}],
    "unknownFunctions": [],
}


def test_report_render():
    md = engine_compat_report.render_report(SAMPLE_REPORT)
    assert "v24_1824" in md, "头部必须写目标档"
    assert "v24" in md and "1824+v24" in md, "头部必须写 engine/version"
    fingerprint = engine_compat_report.corpus_fingerprint(SAMPLE_REPORT["corpus"])
    assert fingerprint in md, "头部必须写语料指纹"
    assert "SETBGIMAGE" in md and "120" in md, "缺口明细必须带名字与行号"
    assert "未检查（二期）" in md, "二期未检查项必须显式写出"
    assert "GameBase.csv" in md and "VariableSize.csv" in md, "CSV 键必须点名"
    assert "资源引用" in md, "资源引用必须点名"
    # 落盘（临时目录）：utf-8 文件写得出且内容一致
    tmp = tempfile.mkdtemp(prefix="engcompat-rpt-")
    try:
        path = engine_compat_report.render_file(SAMPLE_REPORT, os.path.join(tmp, "report.md"))
        with open(path, encoding="utf-8") as fh:
            assert fh.read() == md, "落盘内容与 render_report 不一致"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_report_validate_reds():
    bad = (
        ({"schema": 2}, "schema"),
        ({"schema": 1}, "缺键"),
        (dict(SAMPLE_REPORT, counts={"files": 1}), "counts"),
        (dict(SAMPLE_REPORT, counts=dict(SAMPLE_REPORT["counts"], files="2")), "整数"),
        (dict(SAMPLE_REPORT, unknownCommands=[{"name": "X", "file": "f"}]), "缺键"),
        (dict(SAMPLE_REPORT, unknownFunctions={"a": 1}), "数组"),
    )
    for report, keyword in bad:
        problems = engine_compat_report.validate_report(report)
        assert problems, f"{keyword} 形状问题必须判红：{report!r}"
        assert any(keyword in p for p in problems), f"应报「{keyword}」，实际 {problems}"


def test_pack_engine_report_problems():
    cfg = engine_compat.validate_config(THREE_VALID_CONFIGS[2])
    # 候选路径全空 ⇒ 报告缺产物判红（D11）
    problems = pr.engine_report_problems(cfg, candidates=[], out_dir=tempfile.mkdtemp())
    assert problems and "报告" in problems[0], f"缺报告必须判红：{problems}"
    # 正常 JSON ⇒ 渲染出 md、无问题
    tmp = tempfile.mkdtemp(prefix="engcompat-rpt2-")
    try:
        report_json = os.path.join(tmp, "engine-report.json")
        with open(report_json, "w", encoding="utf-8") as fh:
            json.dump(SAMPLE_REPORT, fh)
        problems = pr.engine_report_problems(cfg, candidates=[report_json], out_dir=tmp)
        assert problems == [], f"合法报告不该判红：{problems}"
        with open(os.path.join(tmp, "report.md"), encoding="utf-8") as fh:
            assert "未检查（二期）" in fh.read(), "渲染出的 md 必须带二期未检查项"
        # 目标档与配置不符 ⇒ 判红
        mismatched = dict(SAMPLE_REPORT, profile="snake_4981")
        with open(report_json, "w", encoding="utf-8") as fh:
            json.dump(mismatched, fh)
        problems = pr.engine_report_problems(cfg, candidates=[report_json], out_dir=tmp)
        assert problems and "profile" in problems[0], f"目标档不符必须判红：{problems}"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 接线与反向证明

def test_main_wiring():
    """pack_release.py 主链必须真的收引擎配置分支（照 rt2 测试的源码级接线闸）。"""
    src = _source_normalized("pack_release.py")
    assert '"--engine-config"' in src, "argparse 必须注册 --engine-config"
    assert "engine_cfg = engine_compat.load_engine_config(args.engine_config)" in src, \
        "主链必须加载配置"
    assert ("era_problems = ([] if args.allow_retired_corpus or engine_cfg is not None"
            in src), "配置模式必须跳过 eraTW 语料根闸"
    assert "data_source_alignment(args.skip_assets, args.game_root)" in src, \
        "同源判据必须保留"
    assert src.count("engine_config=engine_cfg") >= 2, "dry-run 与真构建都要透传配置"
    assert "kind_problems += engine_stamp_problems(engine_cfg)" in src, \
        "kind_problems 必须收印章回读"
    assert "kind_problems += engine_report_problems(engine_cfg)" in src, \
        "kind_problems 必须收报告渲染"
    assert "engine_config_gate(args.engine_config, args.game_root, args.skip_assets)" in src, \
        "配置模式必须过显式 --game-root 与 --skip-assets 拒绝门槛"


def test_config_mode_rejects_skip_assets():
    """组长裁定（B6 验收期）：配置模式 + --skip-assets 前置硬拦。

    印章只能经 StageGameAssetsTask 落进包（D10 印章是唯一档位载体）；跳随包资产
    的包没有可烙章面，运行期恒回落缺省档 = 假兼容包，静默比报错危险。
    """
    problems = pr.engine_config_gate("engine.config.json", "some/game/root", True)
    assert any("--skip-assets" in p for p in problems), problems
    # 对照：同一配置同一游戏根、不带 skip-assets 时不得误伤。
    ok = pr.engine_config_gate("engine.config.json", "some/game/root", False)
    assert ok == [], ok


def test_reverse_proof_no_config_early_exit():
    """反向证明①：删掉「无配置早退」⇒ 零干扰断言变红（实测变异模块，非推理）。

    早退守卫本体在 engine_gradle_args（run_build 无条件调用它）；注释掉守卫后
    无配置路径会在 engine_gradle_args(None) 当场崩，零干扰基线断言即红。
    """
    mutant = _load_mutant(
        "pack_release.py",
        [("\n    if engine_cfg is None:\n        return []",
          "\n    # MUTANT: 无配置早退已删除")],
        "pack_release_mutant_no_early_exit")
    tokens, err = _capture_dry_run_tokens(mutant, None)
    broken = err is not None or tokens != _baseline_tasks(pr)
    assert broken, (
        "反向证明①不成立：删掉无配置早退后，零干扰基线断言没有变红"
        f"（tokens={tokens!r} err={err!r}）")
    # 对照：真模块同参数下干净（已由 test_pack_baseline_zero_interference 钉，这里再取一次）
    tokens_real, err_real = _capture_dry_run_tokens(pr, None)
    assert err_real is None and tokens_real == _baseline_tasks(pr)


def test_reverse_proof_stamp_pinned_snake():
    """反向证明②：印章写死 snake_4981 ⇒ 配置模式回读判红（实测变异模块）。"""
    mutant = _load_mutant(
        "engine_compat.py",
        [('"profile": config["profile"],', '"profile": "snake_4981",  # MUTANT')],
        "engine_compat_mutant_pinned")
    cfg = mutant.validate_config(THREE_VALID_CONFIGS[2])
    assert cfg["profile"] == "v24_1824"
    doc = mutant.stamp_document(cfg)
    assert doc["profile"] == "snake_4981", "变异没生效，反向证明不成立"
    # 用真模块的回读判式判变异印章：必须红
    problems = engine_compat.verify_stamp_text(
        json.dumps(doc, ensure_ascii=False), cfg)
    assert problems and "profile" in problems[0], (
        f"反向证明②不成立：印章写死 snake_4981 未被判红：{problems}")
    # 对照：真模块对同一配置出的是 v24 印章且回读为空
    real_doc = engine_compat.stamp_document(cfg)
    assert engine_compat.verify_stamp_text(
        json.dumps(real_doc, ensure_ascii=False), cfg) == []


# ---------------------------------------------------------------- 入口

def main() -> int:
    tests = [v for k, v in sorted(globals().items())
             if k.startswith("test_") and callable(v)]
    failed = 0
    for case in tests:
        try:
            case()
            print(f"PASS {case.__name__}")
        except Exception as exc:  # 一条红不挡其余，读数全打出来
            failed += 1
            print(f"FAIL {case.__name__}: {exc}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
