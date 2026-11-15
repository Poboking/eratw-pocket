#!/usr/bin/env python3
"""第三方引擎兼容端到端回归闸（B7-E2E / CONTRACT-ENGCOMPAT §8.6 lane B7-E2E）。

用法（在 eratw-android 下）::

    python devtools/test_engine_compat_e2e.py               # CLI 级端到端（真 erbcc，离线）
    ERATW_E2E_GRADLE=1 python devtools/test_engine_compat_e2e.py   # 追加 gradle 装配级四步

与 `test_engine_compat.py`（B6-PACK，纯函数 + dry-run token 断言）的分工：那边钉 python
出包面与形状契约，这边钉**真实编译链**——用 erbcc installDist 的 CLI 直接编
`devtools/fixtures/engine-compat/` 第三方迷你夹具，把三档/四档编译对拍、`--engine-report`
读数、印章字节与报告渲染的期望值**实测钉死**。不发网络、不改夹具与语料；产物只写
`build/engc-b7e2e/pye2e/`（gitignore 面）。

前置：
- erbcc installDist（缺时脚本自动跑 `./gradlew :tools:erbcc:installDist`，需要 JAVA_HOME）；
- `ERATW_E2E_GRADLE=1` 段还需要 Android SDK（ANDROID_HOME，`:app:stageGameAssets` 配置期要 AGP）。

判据（契约 §8.6 必做 1-4）：
1. 四路编译对拍：无 flag / snake_4981 / v24_1824 / em_ee_1824_v22（与 compileCorpus 同口径：
   corpus=ERB 目录 + --mode strict + --require-fold）——
   无 flag/snake/em_ee 三产物 sha256 逐字节相同；v24 strict 拒落盘（退出 1）但报告先写全。
2. §8.5 留验定性：夹具 `ERB/expr_snake_only.erb` 在**表达式位**（IF 条件）调 snake-only 内建
   `SQL_CONNECT`。实测 `unknownFunctions` 在 v24 与 em_ee 两档**都命中**（名字 + 物理 1 基
   行号 11 + file 空串），snake_4981 零命中 ⇒ 该字段不是「编译期不可达」的永空面；
   B6 夹具恒空的真实原因是夹具里没有表达式位被裁内建，不是扫描位发不了火
   （发火链锚见 `tools/erbcc/src/test/.../EngineProfileExprUnknownFunctionReportTest` KDoc）。
3. 印章与报告装配：夹具 engine.config → v24_1824 印章四字段；真实 v24 报告 JSON 过
   `engine_compat_report.validate_report` 形状闸并渲染成 md（缺口名字/行号上屏）。
   gradle 段（开关后）再钉 `-PeratwEngineProfile` 真的进了 erbcc args（--info Command 行）、
   报告固定落点 `tools/erbcc/build/engine-report.json`、印章字节落 staging + 输入指纹
   四态（写/UP-TO-DATE/内容变更重跑/撤属性清残留）。
4. 反向证明（v24 档三变体，全部只进临时目录）：只把夹具两条 snake-only 行首命令换成
   语义等价的通用 PRINTL ⇒ 拒绝消失、SQL_CONNECT 命中仍在；只把表达式位 SQL_CONNECT
   换成三档通用内建 MAX ⇒ 拒绝仍在、unknownFunctions 清空；两者都换 ⇒ 退出 0、两数组
   全空——即上面的红/读数由夹具里那几个名字产生，不是硬编出来的通过。

跨路面（曾阻塞、已由组长接线）：`:app:stageGameAssets` 带第三方游戏根时，eraTW 专属语料补丁
CB-005（`root: game`、目标 `CSV/_Rename.csv`）在 `buildSrc/.../CorpusPatches.kt` 的
`planGameRootPatches` 对「目标缺席」一律判红。现由显式声明 `-PeratwForeignCorpus=1`
（pack `--foreign-corpus`，须配显式 `--game-root`）让整表在编译面与 staging 面同时不适用；
不带该声明仍按 CB-005 判红 —— 本文件的 gradle 装配段两侧都钉（见 `test_gradle_stage_stamp_lifecycle`）。

退出码：0 = 无断言失败（可含显式 SKIP 行）；1 = 有断言失败。
头条读数只把**真正跑过**的用例计入 passed，跳过的单列（装配段要 `ERATW_E2E_GRADLE=1` 才跑）。
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)
sys.dont_write_bytecode = True

import engine_compat  # noqa: E402
import engine_compat_report  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "engine-compat")
EVIDENCE = os.path.join(APP_ROOT, "build", "engc-b7e2e", "pye2e")
INSTALL_LIB = os.path.join(APP_ROOT, "tools", "erbcc", "build", "install", "erbcc", "lib")
GRADLE = os.path.join(APP_ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")

# ---------------------------------------------------------------- 夹具完整性钉

# 既有 5 件「一字不动」闸（B7-E2E 文件所有权）+ 本路新增表达式位探针件。
# **钉的是 LF 字节**：这批件的 checkout 由 .gitattributes 的 `text eol=lf` 钉死，
# 否则 core.autocrlf=true 的机器会把它们检出成 CRLF、本闸当场红（2026-11-12 主树实测踩到）。
FIXTURE_SHA = {
    os.path.join("ERB", "print_safe.erb"):
        "3253f1175f4aa612de7a3d651d2d295f5b4c1c5de02f08a926684dd54a147dc5",
    os.path.join("ERB", "snake_only.erb"):
        "2b5806cb63eb866169bc6d9cdd57ecb9fe2c28400005c037660e17d1708a49a9",
    "emuera.config":
        "ff634e0cbdde4fa28793e088e1a8c0e745a0d1e488875271cb938eb062db76b5",
    "engine.config.fixture.json":
        "f5c3b125fe2542c6f09517ef6acdaeef6ecabce987bbf25b2b040ee9e874ef8f",
    os.path.join("CSV", "GameBase.csv"):
        "43f8a94c121254eec4fe04d6c74b07bda4fba7c2712796d2368e5ed249aaf9f0",
    os.path.join("CSV", "Item.csv"):
        "9289572da038b058b944a7b12328fe7f616a83c30fe23667243a372698202b53",
    # 本路新增：表达式位被裁内建探针（unknownFunctions 数据面）。SQL_CONNECT 的 IF
    # 必须落在**物理第 11 行**——报告行号口径钉的就是这个位置。
    os.path.join("ERB", "expr_snake_only.erb"):
        "67d4926bb5872746aaec396555a97d3279dd5bca7e5ea4de349e885726b6dc51",
}

# 编译矩阵产物钉（corpus=<夹具>/ERB + --mode strict + --require-fold，与 compileCorpus 同参）。
# 2026-11-13 本 worktree 实测：无 flag / snake_4981 / em_ee_1824_v22 三路三件逐字节相同
# （em_ee 档被裁名保持节点、不改字节，见 §8.5 定性）；v24 档 strict 拒落盘、无产物。
ARTIFACT_SHA = {
    "program.erbc": "5c4fbf6c85c78c217374cebcbad689879bd2444836556f546e6f2d3dd43fd2fa",
    "program.meta": "47ce7742c81a5e75bd7fc7533b524c94767dbe57a4a302b07f68e69d030f5df2",
    "program.stmt.idx": "45befa2fdeb5f3c364b308b7eab53e4f64a4193c3a73583aa6650b6c7e8e7db9",
}

# 报告行号口径钉（物理 1 基行；snake_only.erb 的两条行首命令、expr 探针的 IF）。
EXPECTED_V24_COMMANDS = [
    {"name": "SETBGIMAGE", "file": "snake_only.erb", "line": 7},
    {"name": "PLAYBGM", "file": "snake_only.erb", "line": 8},
]
EXPECTED_EXPR_HITS = [
    {"name": "SQL_CONNECT", "file": "", "line": 11},
]


# ---------------------------------------------------------------- 工具

def _sha256(path: str) -> str:
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def _java_exe() -> str:
    jdk = os.environ.get("JAVA_HOME", "").strip()
    cand = os.path.join(jdk, "bin", "java.exe" if os.name == "nt" else "java") if jdk else ""
    if cand and os.path.isfile(cand):
        return cand
    from shutil import which
    found = which("java")
    if found:
        return found
    raise RuntimeError("找不到 java：请设 JAVA_HOME 或把 java 放进 PATH")


_cli_ready_cache: list = []


def _ensure_cli() -> None:
    """erbcc installDist 必须可跑；缺时自动装一次（离线，只吃本机 gradle 缓存）。"""
    if _cli_ready_cache:
        return
    jars = [f for f in os.listdir(INSTALL_LIB) if f.endswith(".jar")] if os.path.isdir(INSTALL_LIB) else []
    if not any(f.startswith("erbcc-") for f in jars):
        if not os.path.isfile(GRADLE):
            raise RuntimeError(f"缺 erbcc installDist 且找不到 {GRADLE}")
        proc = subprocess.run(
            [GRADLE, "--configure-on-demand", ":tools:erbcc:installDist"],
            cwd=APP_ROOT, capture_output=True, timeout=1800,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                "erbcc installDist 失败：" + proc.stdout.decode("utf-8", "replace")[-800:])
    _cli_ready_cache.append(True)


def _run_erbcc(args: list, log_name: str) -> int:
    """跑一次 erbcc CLI，stdout+stderr 原样存进取证目录，返回退出码。"""
    _ensure_cli()
    cp = os.path.join(INSTALL_LIB, "*") if os.name == "nt" else os.path.join(INSTALL_LIB, "*")
    cmd = [_java_exe(), "-Dfile.encoding=UTF-8", "-cp", cp,
           "com.eratw.port.erbcc.MainKt"] + args
    proc = subprocess.run(cmd, cwd=APP_ROOT, capture_output=True, timeout=1800)
    os.makedirs(EVIDENCE, exist_ok=True)
    with open(os.path.join(EVIDENCE, log_name), "wb") as fh:
        fh.write(b"$ " + " ".join(args).encode("utf-8") + b"\n")
        fh.write(proc.stdout or b"")
        fh.write(b"\n--stderr--\n")
        fh.write(proc.stderr or b"")
    return proc.returncode


def _compile_variant(tag: str, corpus: str = None, out: str = None,
                     profile: str = None, report: str = None) -> int:
    corpus = corpus or os.path.join(FIXTURE, "ERB")
    out = out or os.path.join(EVIDENCE, "out-" + tag)
    args = ["compile", "--corpus", corpus, "--out", out,
            "--mode", "strict", "--require-fold"]
    if profile:
        args += ["--engine-profile", profile, "--engine-report", report or (out + ".report.json")]
    return _run_erbcc(args, "cli-" + tag + ".log")


def _artifact_shas(out_dir: str) -> dict:
    erbc = os.path.join(out_dir, "erbc.v1")
    return {name: _sha256(os.path.join(erbc, name)) for name in sorted(ARTIFACT_SHA)}


def _load_report(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _rows(report: dict, key: str) -> list:
    return [{"name": r["name"], "file": r["file"], "line": r["line"]}
            for r in report[key]]


# ---------------------------------------------------------------- 1) 夹具完整性

def test_fixture_files_intact():
    """既有 5 件一字不动 + 表达式探针件按行号口径钉死（改动即红，保护对拍前提）。"""
    for rel, sha in FIXTURE_SHA.items():
        path = os.path.join(FIXTURE, rel)
        assert os.path.isfile(path), f"夹具缺文件：{rel}"
        assert _sha256(path) == sha, f"夹具内容漂移（本闸按此内容钉行号/产物）：{rel}"
    with open(os.path.join(FIXTURE, "ERB", "expr_snake_only.erb"),
              encoding="utf-8", newline="") as fh:
        lines = fh.read().replace("\r\n", "\n").split("\n")
    assert lines[10].lstrip().startswith("IF SQL_CONNECT("), \
        "SQL_CONNECT 的 IF 必须停在物理第 11 行（报告 line 口径钉）"


# ---------------------------------------------------------------- 2) 四路编译对拍

def test_compile_matrix_four_variants():
    """无 flag / snake / v24 / em_ee 全链路读数钉（§8.6 必做 1）。"""
    res = {}
    for tag, profile in (("noflag", None), ("snake", "snake_4981"),
                         ("v24", "v24_1824"), ("emee", "em_ee_1824_v22")):
        report = os.path.join(EVIDENCE, f"matrix-{tag}.report.json")
        code = _compile_variant("matrix-" + tag, profile=profile, report=report)
        res[tag] = (code, report if profile else None)
    assert res["noflag"][0] == 0, "无 flag 必须退出 0"
    assert res["snake"][0] == 0, "snake_4981 必须退出 0"
    assert res["emee"][0] == 0, "em_ee：表达式位被裁名不拒编，必须退出 0（D11：报告是取证面）"
    assert res["v24"][0] == 1, "v24 编 snake-only 行首命令必须走既有拒绝路径退出 1"
    # 产物：三路成功者三件逐字节相同且等于钉；v24 不落盘
    for tag in ("noflag", "snake", "emee"):
        out = os.path.join(EVIDENCE, "out-matrix-" + tag)
        assert _artifact_shas(out) == ARTIFACT_SHA, f"{tag} 档产物与钉不符"
    assert not os.path.isdir(os.path.join(EVIDENCE, "out-matrix-v24", "erbc.v1")), \
        "v24 strict 失败不许落产物"


def test_reports_shape_and_rows():
    """三份真实报告 JSON：§8.0.3 形状闸 + 逐条行号 + counts 与数组自洽（必做 1+2）。"""
    for tag, profile_id, engine, version, cmds, funcs in (
        ("snake", "snake_4981", "snake", "4.981", [], []),
        ("v24", "v24_1824", "v24", "1824+v24", EXPECTED_V24_COMMANDS, EXPECTED_EXPR_HITS),
        ("emee", "em_ee_1824_v22", "em_ee", "1824+v22+EMv18+EEv57", [], EXPECTED_EXPR_HITS),
    ):
        path = os.path.join(EVIDENCE, f"matrix-{tag}.report.json")
        report = _load_report(path)
        problems = engine_compat_report.validate_report(report)
        assert problems == [], f"{tag} 报告形状不过 §8.0.3 闸：{problems}"
        assert report["profile"] == profile_id and report["engine"] == engine \
            and report["version"] == version, f"{tag} 报告档位三串不符"
        assert _rows(report, "unknownCommands") == cmds, f"{tag} unknownCommands 不符"
        assert _rows(report, "unknownFunctions") == funcs, f"{tag} unknownFunctions 不符"
        counts = report["counts"]
        assert counts["unknownCommands"] == len(report["unknownCommands"]) == len(cmds)
        assert counts["unknownFunctions"] == len(report["unknownFunctions"]) == len(funcs)
        assert counts["files"] == 3 and counts["compiled"] == 3, f"{tag} 文件/切片计数漂移"
    # v24 的 parseFailures=3（两条被拒行首 + 该文件的连带计数）只钉方向不钉魔法数？
    # ——钉死：strict 拒绝路径的行数读数本身就是兼容层产线的一部分。
    assert _load_report(os.path.join(EVIDENCE, "matrix-v24.report.json"))["counts"][
        "parseFailures"] == 3, "v24 拒编的解析失败计数漂移（报告面回归）"


# ---------------------------------------------------------------- 3) §8.5 留验定性

def test_unknown_functions_reachable():
    """§8.5 留验的正面回答：unknownFunctions 在编译期**可达**且按物理行号发火。

    判据 = 表达式位（非行首）被裁内建在 v24 与 em_ee 两档各命中 1 条
    （name=SQL_CONNECT、line=11、file=""，CorpusCompile 接线口径），snake_4981 零命中。
    「恒空即摘字段」的分支不成立 ⇒ 字段保留（裁决归组长，本闸只给实测）。
    """
    v24 = _load_report(os.path.join(EVIDENCE, "matrix-v24.report.json"))
    em = _load_report(os.path.join(EVIDENCE, "matrix-emee.report.json"))
    snake = _load_report(os.path.join(EVIDENCE, "matrix-snake.report.json"))
    assert _rows(v24, "unknownFunctions") == EXPECTED_EXPR_HITS, "v24 表达式位命中丢失"
    assert _rows(em, "unknownFunctions") == EXPECTED_EXPR_HITS, "em_ee 表达式位命中丢失"
    assert _rows(snake, "unknownFunctions") == [], "snake 档 SQL_CONNECT 可见却报了命中（假阳性）"
    # 相位定性（em_ee 档最干净：没有行首命令面噪音）：表达式位被裁名**不产生解析失败、
    # 不拒编**，只进报告 ⇒ 命中来自 ArgSignatureCheck 的联结后扫描位，不是解析期。
    assert em["counts"]["parseFailures"] == 0, "表达式位被裁名不该新增解析失败"
    assert em["counts"]["unknownFunctions"] == 1 and em["counts"]["compiled"] == 3


def test_report_render_real_artifact():
    """真实 v24 报告 JSON → engine_compat_report 渲染 md（渲染失败=交付要写清的缺陷面）。"""
    report = _load_report(os.path.join(EVIDENCE, "matrix-v24.report.json"))
    md_path = engine_compat_report.render_file(report, os.path.join(EVIDENCE, "render-v24.md"))
    with open(md_path, encoding="utf-8") as fh:
        md = fh.read()
    for needle in ("v24_1824", "1824+v24", "SETBGIMAGE", "PLAYBGM", "SQL_CONNECT",
                   "未检查（二期）"):
        assert needle in md, f"渲染 md 缺 {needle!r}：\n{md[:800]}"


def test_stamp_from_fixture_config():
    """夹具配置 → v24_1824 印章四字段字节（B 路配置面与本路编译档串逐字对齐）。"""
    cfg = engine_compat.load_engine_config(
        os.path.join(FIXTURE, "engine.config.fixture.json"))
    assert cfg == {"profile": "v24_1824", "engine": "v24", "version": "1824+v24"}, cfg
    tmp = tempfile.mkdtemp(prefix="e2e-stamp-")
    try:
        path = engine_compat.write_stamp_file(cfg, os.path.join(tmp, "engine-profile.json"))
        doc = _load_report(path)
        assert list(doc.keys()) == ["schema", "profile", "engine", "version"], doc
        report = _load_report(os.path.join(EVIDENCE, "matrix-v24.report.json"))
        # 印章三串与报告三串逐字同源（TARGETS 单源，8.0.1/8.0.4 的端到端一致性）
        for key in ("profile", "engine", "version"):
            assert doc[key] == report[key], f"印章/报告 {key} 不同源：{doc[key]!r} vs {report[key]!r}"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 4) 反向证明

def _mutant_corpus(tmp: str, name: str, head_generic: bool, expr_generic: bool) -> str:
    """复制夹具到临时根，按需把行首 snake-only 命令 / 表达式位 SQL_CONNECT 换成通用形态。"""
    root = os.path.join(tmp, name)
    shutil.copytree(FIXTURE, root)
    if head_generic:
        snake = os.path.join(root, "ERB", "snake_only.erb")
        with open(snake, encoding="utf-8", newline="") as fh:
            lines = fh.read().split("\n")
        out = []
        for line in lines:
            stripped = line.lstrip("\r")
            if stripped.startswith(("SETBGIMAGE", "PLAYBGM")):
                out.append("PRINTL mutated generic " + stripped.split()[0].lower())
            else:
                out.append(line)
        assert sum(1 for m in out if m.startswith("PRINTL mutated generic")) == 2, \
            "行首变异锚失效（反向证明不成立）"
        with open(snake, "w", encoding="utf-8", newline="") as fh:
            fh.write("\n".join(out))
    if expr_generic:
        expr = os.path.join(root, "ERB", "expr_snake_only.erb")
        with open(expr, encoding="utf-8", newline="") as fh:
            text = fh.read()
        assert 'SQL_CONNECT("127.0.0.1", 3141)' in text, "表达式变异锚失效"
        with open(expr, "w", encoding="utf-8", newline="") as fh:
            fh.write(text.replace('SQL_CONNECT("127.0.0.1", 3141)', "MAX(1, 2)"))
    return root


def test_reverse_proof_mutated_fixture_passes_v24():
    """反向证明（三变体，v24 档）：拒绝与命中逐一由夹具里那几个名字产生，硬编通过即红。

    - head_generic（只换行首命令）⇒ 退出 0、parseFailures 0、两数组只剩 SQL_CONNECT 命中
      —— 顺带钉「表达式位被裁名在 v24 也不拒编、不进解析失败面」；
    - expr_generic（只换表达式位）⇒ 退出 1、unknownCommands 照旧 2 条、unknownFunctions 清空
      —— unknownFunctions 半边不是恒空装饰，删掉触发源它就没了；
    - 全换 ⇒ 退出 0、两数组全空。
    """
    tmp = tempfile.mkdtemp(prefix="e2e-mutant-")
    try:
        cases = (
            ("head", dict(head_generic=True, expr_generic=False),
             0, [], EXPECTED_EXPR_HITS),
            ("expr", dict(head_generic=False, expr_generic=True),
             1, EXPECTED_V24_COMMANDS, []),
            ("both", dict(head_generic=True, expr_generic=True),
             0, [], []),
        )
        for tag, mut, want_code, want_cmds, want_funcs in cases:
            root = _mutant_corpus(tmp, "corpus-" + tag, **mut)
            report = os.path.join(tmp, tag + ".report.json")
            code = _compile_variant("mutant-" + tag, corpus=os.path.join(root, "ERB"),
                                    out=os.path.join(tmp, "out-" + tag),
                                    profile="v24_1824", report=report)
            assert code == want_code, f"[{tag}] v24 退出码期望 {want_code} 实得 {code}"
            rep = _load_report(report)
            assert _rows(rep, "unknownCommands") == want_cmds, f"[{tag}] unknownCommands 不符"
            assert _rows(rep, "unknownFunctions") == want_funcs, f"[{tag}] unknownFunctions 不符"
            if want_code == 0:
                assert rep["counts"]["parseFailures"] == 0, f"[{tag}] 不该有解析失败"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 5) gradle 装配级（开关）

def _gradle(args: list, log_name: str, env_extra: dict = None):
    env = dict(os.environ)
    env.pop("ERATW_GAMEDIR", None)   # erbc-only staging 形态：不让外部 4.980 根混进来
    env.pop("ERATW_CORPUS", None)
    env.update(env_extra or {})
    proc = subprocess.run([GRADLE, "--configure-on-demand"] + args,
                          cwd=APP_ROOT, capture_output=True, timeout=3600, env=env)
    os.makedirs(EVIDENCE, exist_ok=True)
    with open(os.path.join(EVIDENCE, log_name), "wb") as fh:
        fh.write(b"$ " + " ".join(args).encode("utf-8") + b"\n")
        fh.write(proc.stdout or b"")
        fh.write(b"\n--stderr--\n")
        fh.write(proc.stderr or b"")
    text = (proc.stdout or b"").decode("utf-8", "replace") \
        + (proc.stderr or b"").decode("utf-8", "replace")
    return proc.returncode, text


def test_gradle_compilecorpus_profile_passthrough():
    """gradle 段①：-PeratwEngineProfile 真进了 erbcc 命令行（--info 的 Command 行取证），
    报告按契约落固定点 tools/erbcc/build/engine-report.json；未知档位透传成 erbcc 退出 2。"""
    if not os.environ.get("ERATW_E2E_GRADLE"):
        print("SKIP test_gradle_compilecorpus_profile_passthrough（未设 ERATW_E2E_GRADLE=1）")
        _SKIPPED.add("test_gradle_compilecorpus_profile_passthrough")
        return
    out = os.path.join(EVIDENCE, "gradle-erbc-em")
    code, text = _gradle([
        ":tools:erbcc:compileCorpus",
        f"-Pcorpus={os.path.join(FIXTURE, 'ERB')}",
        f"-Pout={out}",
        "-PeratwEngineProfile=em_ee_1824_v22",
        "--info",
    ], "gradle-compile-em.log")
    assert code == 0, f"compileCorpus(em_ee) 退出 {code}：{text[-600:]}"
    assert "--engine-profile em_ee_1824_v22" in text and "--engine-report" in text, \
        "erbcc 命令行里没看到透传的引擎参数（--info 取证缺失）"
    fixed_report = os.path.join(APP_ROOT, "tools", "erbcc", "build", "engine-report.json")
    assert os.path.isfile(fixed_report), "报告没落到契约固定点 tools/erbcc/build/engine-report.json"
    rep = _load_report(fixed_report)
    assert rep["profile"] == "em_ee_1824_v22" and _rows(rep, "unknownFunctions") == EXPECTED_EXPR_HITS
    assert _artifact_shas(out) == ARTIFACT_SHA, "gradle compileCorpus 产物与 CLI 钉不符"
    # 非法档位 = erbcc 退出码 2（支持表报错），gradle 任务失败 —— 透传成立的最硬证据
    code_bad, text_bad = _gradle([
        ":tools:erbcc:compileCorpus",
        f"-Pcorpus={os.path.join(FIXTURE, 'ERB')}",
        f"-Pout={os.path.join(EVIDENCE, 'gradle-erbc-bogus')}",
        "-PeratwEngineProfile=bogus_id",
    ], "gradle-compile-bogus.log")
    assert code_bad != 0 and "bogus_id" in text_bad \
        and "non-zero exit value 2" in text_bad, \
        f"bogus 档位必须让 erbcc 以 2 退出：rc={code_bad}"


def test_gradle_stage_stamp_lifecycle():
    """gradle 段②：印章字节四态（写/UP-TO-DATE/内容变更重跑/撤属性清残留），erbc-only 形态。

    带第三方游戏根的完整 staging 形态当前被 CB-005 游戏根补丁闸挡住（见模块 docstring），
    本用例先按 erbc-only 形态钉 StageGameAssetsTask 的印章接线，再单独探一次游戏根形态：
    被挡 ⇒ KNOWN-BLOCKED 留痕（组长接线后自动升级为全绿复验）。
    """
    if not os.environ.get("ERATW_E2E_GRADLE"):
        print("SKIP test_gradle_stage_stamp_lifecycle（未设 ERATW_E2E_GRADLE=1）")
        _SKIPPED.add("test_gradle_stage_stamp_lifecycle")
        return
    if not (os.environ.get("ANDROID_HOME") or os.path.isfile(
            os.path.join(APP_ROOT, "local.properties"))):
        print("SKIP test_gradle_stage_stamp_lifecycle（无 ANDROID_HOME，AGP 配置期会拒）")
        _SKIPPED.add("test_gradle_stage_stamp_lifecycle")
        return
    erbc_dir = os.path.join(EVIDENCE, "gradle-erbc-em", "erbc.v1")
    assert os.path.isdir(erbc_dir), "先跑 compileCorpus 用例（依赖其产物）"
    stamp = os.path.join(EVIDENCE, "engine-profile.stamp.json")
    cfg = engine_compat.validate_config(
        {"schema": 1, "engine": "em_ee", "version": "1824+v22+EMv18+EEv57"})
    engine_compat.write_stamp_file(cfg, stamp)
    # 跨 APK 边界的落位判据：staging 根整体就是 APK 的 assets/，所以印章必须落在 staging 根的
    # **顶层**。写进 staging/assets/ 会编成 APK 条目 assets/assets/engine-profile.json，
    # 而运行期 EngineProfileStamp 按 core-vm EngineStamp.ASSET_NAME="engine-profile.json"
    # 打开 = assets/engine-profile.json ⇒ 印章读不到、配置模式的包静默退化成无印章全量包
    # （2026-11-12 组长端到端实测抓到，pack 的 config-mode 回读判红即此；别把错路径钉成断言）。
    staged = os.path.join(APP_ROOT, "app", "build", "generated", "gameAssets",
                          "engine-profile.json")
    assert not os.path.exists(os.path.join(
        os.path.dirname(staged), "assets", "engine-profile.json")), "印章又落回多一层 assets/ 的旧口径"
    base = [":app:stageGameAssets",
            f"-PeratwErbcDir={erbc_dir}", "-PeratwAllowStaleErbc=1",
            f"-PeratwEngineStampFile={stamp}"]
    code, text = _gradle(base, "gradle-stage-s1.log")
    assert code == 0, f"s1 带印章 staging 失败：{text[-600:]}"
    with open(staged, "rb") as fh_a, open(stamp, "rb") as fh_b:
        assert fh_a.read() == fh_b.read(), "s1 staging 印章字节与源文件不符"
    code, text = _gradle(base, "gradle-stage-s2.log")
    assert code == 0 and "stageGameAssets UP-TO-DATE" in text, "s2 同输入必须 UP-TO-DATE"
    cfg_snake = engine_compat.validate_config(
        {"schema": 1, "engine": "snake", "version": "4.981"})
    engine_compat.write_stamp_file(cfg_snake, stamp)
    code, text = _gradle(base, "gradle-stage-s3.log")
    assert code == 0 and "stageGameAssets UP-TO-DATE" not in text, \
        "s3 印章内容变了必须重跑（@InputFile 没生效 = 假绿）"
    with open(staged, "rb") as fh_a, open(stamp, "rb") as fh_b:
        assert fh_a.read() == fh_b.read() and b"snake_4981" in open(staged, "rb").read(), \
            "s3 新印章字节没落 staging"
    code, text = _gradle([":app:stageGameAssets",
                          f"-PeratwErbcDir={erbc_dir}", "-PeratwAllowStaleErbc=1"],
                         "gradle-stage-s4.log")
    assert code == 0 and not os.path.exists(staged), "s4 撤属性必须清掉 staging 残留印章"
    # 游戏根形态两侧断言（组长接线 -PeratwForeignCorpus 后由「已知阻塞留痕」升级而来）：
    # ① 不带开关 ⇒ 必须仍被 CB-005「游戏根目标缺席」判红 —— eraTW 出包根的护栏没被削弱；
    # ② 带 -PeratwForeignCorpus=1 ⇒ 整表不适用、staging 成功且印章字节逐字落位。
    gameroot_args = [":app:stageGameAssets",
                     f"-PeratwErbcDir={erbc_dir}", "-PeratwAllowStaleErbc=1",
                     f"-PeratwGameRoot={FIXTURE}", f"-PeratwEngineStampFile={stamp}"]
    code, text = _gradle(gameroot_args, "gradle-stage-gameroot-strict.log")
    # 只匹 ASCII 的补丁 id：gradle 子进程在 Windows 上是 GBK 控制台输出，中文子串匹配会因
    # 解码口径不同而假红（2026-11-12 实测：红是对的，断言先炸）。
    assert code != 0 and "CB-005" in text, \
        f"① 缺省口径必须仍按 CB-005 判红（护栏被削弱？）：rc={code} {text[-500:]}"
    if os.path.exists(staged):
        os.remove(staged)
    code, text = _gradle([*gameroot_args, "-PeratwForeignCorpus=1"],
                         "gradle-stage-gameroot-foreign.log")
    assert code == 0, f"② foreign-corpus 下第三方游戏根 staging 仍失败：{text[-600:]}"
    assert "foreign-corpus" in text, "② 必须有「整表不适用（foreign-corpus）」留痕，静默放行不算过"
    with open(staged, "rb") as fh_a, open(stamp, "rb") as fh_b:
        assert fh_a.read() == fh_b.read(), "② 游戏根形态下印章字节没落 staging"


# ---------------------------------------------------------------- 入口

# 跳过的用例名单（gradle 装配段/SDK 缺席时）：跳过不等于通过，头条读数必须分开记账，
# 否则「9/9 passed」会把两条压根没跑的装配段用例也算成绿（2026-11-12 组长验收时订正）。
_SKIPPED = set()


def main() -> int:
    os.makedirs(EVIDENCE, exist_ok=True)
    tests = [v for k, v in sorted(globals().items())
             if k.startswith("test_") and callable(v)]
    failed = 0
    ran = 0
    for case in tests:
        try:
            case()
            ran += 1
            print(f"PASS {case.__name__}")
        except Exception as exc:  # 一条红不挡其余，读数全打出来
            failed += 1
            print(f"FAIL {case.__name__}: {exc}")
    gradle_on = bool(os.environ.get("ERATW_E2E_GRADLE"))
    passed = ran - len(_SKIPPED)
    detail = f"（真跑 {passed}、跳过 {len(_SKIPPED)}" \
        f"{'：' + '、'.join(sorted(_SKIPPED)) if _SKIPPED else ''}、失败 {failed}）"
    print(f"{passed}/{len(tests)} passed {detail}"
          + ("" if gradle_on or not _SKIPPED
             else " 装配段未计入：设 ERATW_E2E_GRADLE=1 才是全链读数"))
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
