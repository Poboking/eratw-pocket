#!/usr/bin/env python
"""engine-profiles 生成器回归闸。

用临时**合成**的 reference 树验证抽取/分类/增量、YAML/Kotlin 双产物与严格校验、
`--check` 首差与只读性，不依赖真实 reference、网络或真 erbcc。
生成器的手写输入表 `specs/engine-profiles.input.yaml` 走 PyYAML（生成器缺它即硬失败），
所以本闸也间接要求 PyYAML 在场。
真源集成由 gen_engine_profiles.py --check 单独执行。

用法（在 eratw-android 下）：python devtools/test_engine_profiles.py
退出码：0 = 全绿；1 = 有断言失败。
"""
from __future__ import annotations

import contextlib
import copy
import io
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
import gen_engine_profiles as g

TOOL = os.path.join(HERE, "gen_engine_profiles.py")

FAILS: list[str] = []
CHECKS = 0

# 合成数据集：每引擎的函数/命令（v24 最小；cs/em 加 MAP_X/PLAY_Z；snake 再加 EVAL/SQL_Q）。
# 元组 = (子目录, 函数文件, 命令文件, 枚举文件, 函数名, 命令名)。
FIXTURE = {
    "v24": ("Emuera1824+v24/src1824/Emuera",
            "GameData/Function/Creator.cs", "GameProc/Function/FunctionIdentifier.cs",
            "GameProc/Function/BuiltInFunctionCode.cs",
            ["A", "B", "SHARED"], ["CMD_A", "CMD_SHARED"]),
    "cs_reference": ("emuera-cs",
                     "MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs",
                     "MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs",
                     "MinorShift.Emuera.Runtime.Script.Statements/FunctionCode.cs",
                     ["A", "B", "SHARED", "MAP_X"], ["CMD_A", "CMD_SHARED", "PLAY_Z"]),
    "em_ee": ("emuera-em/Emuera",
              "Runtime/Script/Statements/Function/Creator.cs",
              "Runtime/Script/Statements/FunctionIdentifier.cs",
              "Runtime/Script/Statements/BuiltInFunctionCode.cs",
              ["A", "B", "SHARED", "MAP_X"], ["CMD_A", "CMD_SHARED", "PLAY_Z"]),
    "snake": ("emuera-skia-4.981/Emuera",
              "Runtime/Script/Statements/Function/Creator.cs",
              "Runtime/Script/Statements/FunctionIdentifier.cs",
              "Runtime/Script/Statements/BuiltInFunctionCode.cs",
              ["A", "B", "SHARED", "MAP_X", "EVAL"], ["CMD_A", "CMD_SHARED", "PLAY_Z", "SQL_Q"]),
}


def check(label: str, ok: bool, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if ok:
        print(f"[ ok ] {label}")
    else:
        FAILS.append(f"{label} :: {detail}".rstrip(" :"))
        print(f"[FAIL] {label}  {detail}".rstrip())


def write(path: str, text: str, newline: str = "\n") -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline=newline) as f:
        f.write(text)


def make_fixture(root: str) -> None:
    for _name, (sub, fun_rel, cmd_rel, enum_rel, funcs, cmds) in FIXTURE.items():
        base = os.path.join(root, "reference", sub)
        # 函数文件：真实形状 —— static FunctionMethodCreator() 里唯一的 methodList 字典初始化器，
        # 条目逗号分隔（不是分号语句），抽取器逐条消费并校验 RHS 形状。
        entries = ",\n".join(f'            ["{n}"] = new {n.title().replace("_", "")}Method()' for n in funcs)
        write(os.path.join(base, fun_rel.replace("/", os.sep)),
              "class FunctionMethodCreator {\n"
              "    static FunctionMethodCreator() {\n"
              "        methodList = new Dictionary<string, FunctionMethod> {\n"
              f"{entries}\n"
              "        };\n"
              "    }\n"
              "}\n")
        # 命令文件：static FunctionIdentifier() 构造器 + code.ToString() 恒等助手 + METHOD 回填。
        cmd_lines = "".join(f"        addFunction(FunctionCode.{n}, new X());\n" for n in cmds)
        write(os.path.join(base, cmd_rel.replace("/", os.sep)),
              "class FunctionIdentifier {\n"
              "    private static void addFunction(FunctionCode code, object inst) { string key = code.ToString(); }\n"
              "    static FunctionIdentifier() {\n"
              f"{cmd_lines}"
              "        foreach (var pair in methodList) { string key = pair.Key;\n"
              "            funcDic.Add(key, new FunctionIdentifier(key, pair.Value, methodInstruction)); }\n"
              "    }\n"
              "}\n")
        # 枚举文件：命令名 + 两个从不注册的成员（SET/__NULL__），与四树漂移闸同口径。
        members = ",\n".join(f"        {n}" for n in cmds)
        write(os.path.join(base, enum_rel.replace("/", os.sep)),
              f"enum FunctionCode {{\n{members},\n        SET,\n        __NULL__\n}}\n")


def run(args: list[str]) -> subprocess.CompletedProcess:
    flags = ["-B", "-S"] if sys.flags.no_site else ["-B"]
    return subprocess.run([sys.executable, *flags, TOOL, *args], capture_output=True, text=True,
                          encoding="utf-8", env={**os.environ, "PYTHONIOENCODING": "utf-8"})


def snapshot(root: str) -> dict:
    return {str(p): (p.stat().st_mtime_ns, p.read_bytes() if p.is_file() else None)
            for p in Path(root).rglob("*")}


def rejects(doc: dict) -> bool:
    for emit in (g.yaml_dump, g.kotlin_dump):
        try:
            emit(doc)
        except (KeyError, TypeError, ValueError):
            continue
        return False
    return True


def kotlin_sets(text: str) -> dict[str, list[str]]:
    blocks = re.findall(r"    val ([A-Z_][A-Z0-9_]*): Set<String> = setOf\(\n(.*?)    \)\n", text, re.S)
    return {name: re.findall(r'^        "([A-Z_][A-Z0-9_]*)",$', body, re.M) for name, body in blocks}


def check_structure(doc: dict) -> None:
    exp = {"v24": (3, 2), "cs_reference": (4, 3), "em_ee": (4, 3), "snake": (5, 4)}
    got = {k: (len(v.get("functions", [])), len(v.get("commands", []))) for k, v in doc["profiles"].items()}
    check("counts", all(got[k] == exp[k] for k in exp), f"got={got}")
    for engine, (_sub, _fun, _cmd, _enum, funcs, cmds) in FIXTURE.items():
        check(f"complete profile names {engine}",
              doc["profiles"][engine] == {"functions": sorted(funcs), "commands": sorted(cmds)})
    d = doc["deltas"]
    check("em_add functions", d["em_ee_vs_v24"]["functions_added"] == ["MAP_X"])
    check("em_add commands", d["em_ee_vs_v24"]["commands_added"] == ["PLAY_Z"])
    check("snake_only functions", d["snake_only"]["functions"] == ["EVAL"])
    check("snake_only commands", d["snake_only"]["commands"] == ["SQL_Q"])
    check("group em.map", "MAP_X" in doc["feature_groups"].get("em.map", []))
    check("group snake.sql", doc["feature_groups"].get("snake.sql") == ["SQL_Q"])
    check("group snake.dynamic", doc["feature_groups"].get("snake.dynamic") == ["EVAL"])
    pr = doc.get("probe", {})
    rows = pr.get("readings", {})
    check("probe shape", len(rows) == 3 and all(len(v) == len(pr.get("inputs", [])) for v in rows.values()))
    check("probe isnumeric invariant",
          all(rows.get(e, [])[3:8] == [1, 0, 0, 0, 0] for e in ("v24", "em_ee", "snake")))
    check("probe string-compare split",
          rows.get("v24", [])[0:3] == [0, 1, 0] and rows.get("em_ee", [])[0:3] == [1, 0, 1]
          and rows.get("snake", [])[0:3] == [1, 0, 1])
    check("probe getnum split",
          rows.get("v24", [])[8:10] == [0, 0] and rows.get("em_ee", [])[8:10] == [-1, -1]
          and rows.get("snake", [])[8:10] == [-1, -1])
    check("identity targets/corpora carried from input yaml",
          list(doc["targets"]) == ["snake_4981", "em_ee_1824_v22", "v24_1824"]
          and [k for k, v in doc["targets"].items() if v.get("default")] == ["snake_4981"]
          and list(doc["corpora"]) == ["V4_981", "V4_980"]
          and doc["corpora"]["V4_980"]["marker_file"] is None
          and doc["corpora"]["V4_980"]["retired"] is True
          and doc["dialect"]["getnum_nickname_mastername"]["keywords"]
          == {"routed": ["NAME", "CALLNAME", "RELATION"], "gated": ["NICKNAME", "MASTERNAME"]})


def check_kotlin(doc: dict, text: str, sets: dict) -> None:
    profiles = {"SNAKE": ("snake", "SNAKE_4981"), "EM_EE": ("em_ee", "EM_EE_1824_V22"), "V24": ("v24", "V24_1824")}
    parsed = kotlin_sets(text)
    expected_keys = {f"{prefix}_{kind.upper()}" for prefix in profiles for kind in ("functions", "commands")}
    expected_keys.add("PORT_EXTENSIONS")
    check("exactly seven Kotlin sets (six surfaces + extensions)", set(parsed) == expected_keys)
    check("Kotlin PORT_EXTENSIONS matches doc", parsed.get("PORT_EXTENSIONS") == doc["port_extensions"])
    for prefix, (engine, family) in profiles.items():
        for kind in ("functions", "commands"):
            name = f"{prefix}_{kind.upper()}"
            check(f"Kotlin complete sorted unique names {name}", parsed.get(name) == doc["profiles"][engine][kind])
            match = re.search(rf"fun {kind}Of\(family: EngineFamily\): Set<String> = when \(family\) {{(.*?)\n    }}", text, re.S)
            check(f"Kotlin {kind}Of {family}", match is not None and
                  f"EngineFamily.{family} -> {name}" in match[1] and match[1].count(" -> ") == 3)
    dialects = {
        "SNAKE_4981": ("FIXED", "NAME_CALLNAME_ONLY"),
        "EM_EE_1824_V22": ("FIXED", "NAME_CALLNAME_ONLY"),
        "V24_1824": ("LEGACY_BOTH_LT", "WITH_NICKNAME_MASTERNAME"),
    }
    check("Kotlin DIALECT map shape", "val DIALECT: Map<EngineFamily, EngineDialect> = mapOf(" in text
          and text.count(" to EngineDialect(") == 3)
    for family, (compare, nickname) in dialects.items():
        check(f"Kotlin dialect {family}",
              f"EngineFamily.{family} to EngineDialect(\n"
              f"            stringCompare = StringCompareDialect.{compare},\n"
              f"            nicknameKeywords = NicknameKeywordPolicy.{nickname}," in text)
    # csv_encoding 不在本波（契约 CONTRACT-ENGCPL §3.1 E5 已裁）：方言仍是三 facet。
    check("Kotlin keyword lists (RELATION routed/gated)",
          '    val RELATION_ROUTED_KEYWORDS: List<String> = listOf(\n'
          '        "NAME",\n        "CALLNAME",\n        "RELATION",\n    )\n' in text
          and '    val RELATION_GATED_KEYWORDS: List<String> = listOf(\n'
          '        "NICKNAME",\n        "MASTERNAME",\n    )\n' in text)
    check("Kotlin TargetSpec declaration and default id",
          "data class TargetSpec(\n    val profileId: String,\n    val engine: String,\n"
          "    val version: String,\n    val family: EngineFamily,\n"
          "    val corpusVersionId: String,\n    val fullStatementCoverage: Boolean = false,\n)" in text
          and text.count("        TargetSpec(\n") == 3
          and '    const val DEFAULT_TARGET_PROFILE_ID: String = "snake_4981"' in text)
    for profile, engine, version, family, corpus, covers in (
            ("snake_4981", "snake", "4.981", "SNAKE_4981", "V4_981", "true"),
            ("em_ee_1824_v22", "em_ee", "1824+v22+EMv18+EEv57", "EM_EE_1824_V22", "V4_981", "false"),
            ("v24_1824", "v24", "1824+v24", "V24_1824", "V4_981", "false")):
        check(f"Kotlin target {profile}",
              f'        TargetSpec(\n            profileId = "{profile}",\n'
              f'            engine = "{engine}",\n            version = "{version}",\n'
              f'            family = EngineFamily.{family},\n'
              f'            corpusVersionId = "{corpus}",\n'
              f'            fullStatementCoverage = {covers},\n        ),' in text)
    check("Kotlin CorpusSpec declaration and map",
          "data class CorpusSpec(\n    val id: String,\n    val rootDir: String,\n"
          "    val markerFile: String?,\n    val retired: Boolean,\n)" in text
          and '    val CORPORA: Map<String, CorpusSpec> = mapOf(\n' in text
          # 2026-11-14 语料换代 261017→261111：V4_981 指向 261111，261017 转退役留档。
          and '            rootDir = "eraTW4.981-261111-EAGLOV3",\n'
              '            markerFile = "ERB/DLC/AI对话补丁/对话调用.erb",\n'
              '            retired = false,\n' in text
          and '            rootDir = "eraTW4.981-261017-EAGLOV3",\n'
              '            markerFile = "ERB/DLC/AI对话补丁/对话调用.erb",\n'
              '            retired = true,\n' in text
          and '            rootDir = "eratw-画蛇添足版eratw4.980",\n'
              '            markerFile = null,\n            retired = true,\n' in text)
    check("Kotlin has one GENERATED header and no block delimiters",
          text.startswith("// GENERATED ") and text.count("GENERATED") == 1
          and "/*" not in text and "*/" not in text
          and "package com.eratw.port.core.compat\n" in text and "internal object EngineProfileData {" in text)
    changed = copy.deepcopy(doc)
    changed["profiles"]["snake"]["functions"] = ["REPLACED_FUNCTION"]
    changed["profiles"]["v24"]["commands"] = ["REPLACED_COMMAND"]
    changed["dialect"]["string_compare_ge_le"]["values"]["snake"] = "bug"
    changed["dialect"]["getnum_nickname_mastername"]["values"]["snake"] = True
    emitted = g.kotlin_dump(changed)
    check("Kotlin consumes passed names, not global registries",
          kotlin_sets(emitted)["SNAKE_FUNCTIONS"] == ["REPLACED_FUNCTION"]
          and kotlin_sets(emitted)["V24_COMMANDS"] == ["REPLACED_COMMAND"])
    check("Kotlin consumes passed dialect, not global DIALECT",
          "EngineFamily.SNAKE_4981 to EngineDialect(\n"
          "            stringCompare = StringCompareDialect.LEGACY_BOTH_LT,\n"
          "            nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME," in emitted)
    changed = copy.deepcopy(doc)
    changed["meta"]["generated_at"] = "1900-01-01"
    changed["dialect"].pop("isnumeric_v24_fix")
    check("Kotlin has no date or ISNUMERIC facet", g.kotlin_dump(changed) == text)
    with patch.object(g, "PORT_EXTENSIONS", frozenset({"PORT_Z", "PORT_A"})):
        port_doc = g.build_doc(sets)
    check("nonempty synthetic port extensions sorted and serialized",
          port_doc["port_extensions"] == ["PORT_A", "PORT_Z"]
          and "port_extensions:\n  - PORT_A\n  - PORT_Z\n" in g.yaml_dump(port_doc))
    def strip_port(text: str) -> str:
        return re.sub(r"    val PORT_EXTENSIONS: Set<String> = setOf\(\n.*?    \)\n", "", text, count=1, flags=re.S)
    port_kotlin = g.kotlin_dump(port_doc)
    check("port extensions do not leak into engine profiles",
          strip_port(port_kotlin) == strip_port(text)
          and kotlin_sets(port_kotlin)["PORT_EXTENSIONS"] == ["PORT_A", "PORT_Z"])
    for engine in FIXTURE:
        for kind in ("functions", "commands"):
            for bad in (["lowercase"], ["B", "A"], ["A", "A"], ["A\""], [1], {"A"}):
                changed = copy.deepcopy(doc)
                changed["profiles"][engine][kind] = bad
                check(f"reject {engine}.{kind} {bad!r}", rejects(changed))
        for facet, bad_values in (("string_compare_ge_le", ("unknown", True, None, 1)),
                                  ("getnum_nickname_mastername", ("false", "unknown", 0, 1, None))):
            for bad in bad_values:
                changed = copy.deepcopy(doc)
                changed["dialect"][facet]["values"][engine] = bad
                check(f"reject dialect {engine}.{facet} {bad!r}", rejects(changed))
        for kind in ("functions", "commands"):
            changed = copy.deepcopy(doc)
            del changed["profiles"][engine][kind]
            check(f"reject missing {engine}.{kind}", rejects(changed))
    for bad in (["lowercase"], ["PORT_Z", "PORT_A"], ["PORT_A", "PORT_A"]):
        changed = copy.deepcopy(doc)
        changed["port_extensions"] = bad
        check(f"reject port_extensions {bad!r}", rejects(changed))
    for key in ("targets", "corpora"):
        changed = copy.deepcopy(doc)
        del changed[key]
        check(f"reject missing {key}", rejects(changed))
    changed = copy.deepcopy(doc)
    changed["targets"]["v24_1824"]["default"] = True
    check("reject two defaults", rejects(changed))
    changed = copy.deepcopy(doc)
    changed["targets"]["snake_4981"]["corpus"] = "V4_999"
    check("reject dangling corpus reference", rejects(changed))
    changed = copy.deepcopy(doc)
    changed["targets"]["snake_4981"]["full_statement_coverage"] = "yes"
    check("reject non-bool full_statement_coverage", rejects(changed))


def check_cli(tmp: str, ref: str, out: Path, kotlin: Path, doc: dict) -> None:
    args = ["--reference-root", ref, "--out", str(out)]
    dual = [*args, "--emit-kotlin", str(kotlin)]
    r = run([*dual, "--quiet"])
    check("dual generate exit0", r.returncode == 0, r.stdout + r.stderr)
    expected = {out: g.yaml_dump(doc), kotlin: g.kotlin_dump(doc)}
    for path, text in expected.items():
        check(f"UTF8 LF no BOM {path.name}", path.read_bytes() == text.encode("utf-8")
              and b"\r" not in path.read_bytes() and not path.read_bytes().startswith(b"\xef\xbb\xbf"))
    check_kotlin(doc, expected[kotlin], g.extract_all(Path(ref)))
    before = snapshot(tmp)
    r = run([*dual, "--check", "--quiet"])
    check("dual check up-to-date exit0 and no writes", r.returncode == 0 and before == snapshot(tmp), r.stderr)
    r = run([*dual, "--quiet"])
    check("dual generation deterministic", r.returncode == 0 and
          all(path.read_bytes() == text.encode("utf-8") for path, text in expected.items()), r.stderr)
    with patch.object(g, "build_doc", wraps=g.build_doc) as build, \
            patch.object(g, "yaml_dump", wraps=g.yaml_dump) as yaml_emit, \
            patch.object(g, "kotlin_dump", wraps=g.kotlin_dump) as kotlin_emit:
        result = g.main([*dual, "--check", "--quiet"])
    check("dual shares exactly one build_doc object", result == 0 and build.call_count == 1
          and yaml_emit.call_count == 1 and kotlin_emit.call_count == 1
          and yaml_emit.call_args.args[0] is kotlin_emit.call_args.args[0])

    def restore() -> None:
        for path, text in expected.items():
            path.write_text(text, encoding="utf-8", newline="\n")

    def tamper(label: str, path: Path, text: str | None, diagnostic: str = "first diff at line") -> None:
        restore()
        if text is None:
            path.unlink()
        else:
            path.write_text(text, encoding="utf-8", newline="\n")
        before = snapshot(tmp)
        r = run([*dual, "--check", "--quiet"])
        check(label, r.returncode == 1 and str(path) in r.stderr and diagnostic in r.stderr
              and before == snapshot(tmp), r.stdout + r.stderr)

    for path, text in expected.items():
        tamper(f"missing {path.name} fails without writes", path, None, "MISSING:")
        tamper(f"empty {path.name} reports EOF", path, "", "<EOF>")
        for newline in ("\r\n", "\r"):
            restore()
            path.write_bytes(text.replace("\n", newline).encode("utf-8"))
            before = snapshot(tmp)
            r = run([*dual, "--check", "--quiet"])
            check(f"check {path.name} {newline!r} exit0 without rewrite",
                  r.returncode == 0 and before == snapshot(tmp), r.stderr)
        tamper(f"append {path.name} reports EOF", path, text + "EXTRA\n", "<EOF>")
        tamper(f"truncate {path.name} reports EOF", path, "".join(text.splitlines(keepends=True)[:-2]), "<EOF>")
        tamper(f"missing final newline {path.name}", path, text[:-1])
        tamper(f"BOM {path.name} is not ignored", path, "\ufeff" + text, "first diff at line 1 ")

    yaml_text, kotlin_text = expected[out], expected[kotlin]
    tamper("YAML changed name same count", out, yaml_text.replace("  - A\n", "  - Z\n", 1))
    tamper("YAML changed dialect", out, yaml_text.replace("    v24: bug\n", "    v24: fixed\n", 1))
    restore()
    old_date = re.sub(r"  generated_at:.*", "  generated_at: '1900-01-01'", yaml_text)
    out.write_text(old_date, encoding="utf-8", newline="\n")
    before = snapshot(tmp)
    r = run([*dual, "--check", "--quiet"])
    check("YAML date ignored without writes", r.returncode == 0 and before == snapshot(tmp), r.stderr)
    changed = old_date.replace("  - A\n", "  - Z\n", 1)
    line = next(i for i, value in enumerate(changed.splitlines(), 1) if value == "      - Z")
    tamper("YAML date does not mask true first diff", out, changed, f"first diff at line {line} ")
    changed = "\n".join(value for value in changed.split("\n") if not value.startswith("  generated_at:"))
    tamper("YAML filtered dates preserve physical line numbers", out, changed,
           f"existing line {line - 1}, generated line {line}")

    mutations = {
        "changed name same count": kotlin_text.replace('        "A",', '        "Z",', 1),
        "removed name": kotlin_text.replace('        "A",\n', "", 1),
        "added name": kotlin_text.replace('        "A",\n', '        "A",\n        "EXTRA",\n', 1),
        "removed set": re.sub(r"    val SNAKE_FUNCTIONS: Set<String> = setOf\(\n.*?    \)\n\n", "", kotlin_text, count=1, flags=re.S),
        "changed string dialect": kotlin_text.replace("StringCompareDialect.FIXED", "StringCompareDialect.LEGACY_BOTH_LT", 1),
        "changed nickname dialect": kotlin_text.replace("NicknameKeywordPolicy.NAME_CALLNAME_ONLY", "NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME", 1),
        "inserted generated_at": kotlin_text + "generated_at: '1900-01-01'\n",
        "changed header": kotlin_text.replace("// GENERATED", "// generated", 1),
        "trailing whitespace": kotlin_text.replace("internal object EngineProfileData {", "internal object EngineProfileData { ", 1),
        "duplicate name": kotlin_text.replace('        "A",', '        "A",\n        "A",', 1),
        "unsorted names": kotlin_text.replace('        "A",\n        "B",', '        "B",\n        "A",', 1),
    }
    for label, text in mutations.items():
        check(f"Kotlin injection applied: {label}", text != kotlin_text)
        tamper(f"Kotlin {label} fails", kotlin, text)

    restore()
    kotlin.write_text("STALE\n", encoding="utf-8")
    before = snapshot(tmp)
    r = run([*args, "--check", "--quiet"])
    check("YAML-only ignores unrelated stale Kotlin", r.returncode == 0 and before == snapshot(tmp), r.stderr)
    out.unlink()
    kotlin.unlink()
    before = snapshot(tmp)
    r = run([*dual, "--check", "--quiet"])
    check("dual reports both missing products", r.returncode == 1 and str(out) in r.stderr
          and str(kotlin) in r.stderr and before == snapshot(tmp), r.stderr)
    absent = Path(tmp) / "absent" / "Data.kt"
    r = run([*args, "--emit-kotlin", str(absent), "--check", "--quiet"])
    check("check does not create missing parent", r.returncode == 1 and not absent.parent.exists(), r.stderr)
    restore()
    for checking in ([], ["--check"]):
        for target in (out, out.parent / "alias" / ".." / out.name):
            before = snapshot(tmp)
            r = run([*args, "--emit-kotlin", str(target), *checking, "--quiet"])
            check(f"reject same resolved path {target} {checking}", r.returncode != 0
                  and "must target different files" in r.stderr and before == snapshot(tmp), r.stderr)
    hardlink = Path(tmp) / "linked.kt"
    os.link(out, hardlink)
    before = snapshot(tmp)
    r = run([*args, "--emit-kotlin", str(hardlink), "--quiet"])
    check("reject hardlinked output paths", r.returncode != 0 and "must target different files" in r.stderr
          and before == snapshot(tmp), r.stderr)

    sub, fun_rel, _cmd_rel, _enum_rel, _funcs, _cmds = FIXTURE["v24"]
    source = Path(ref) / "reference" / sub / fun_rel
    original = source.read_text(encoding="utf-8")
    source.write_text(original.replace('["A"]', '["Z"]'), encoding="utf-8")
    before = snapshot(tmp)
    r = run([*dual, "--check", "--quiet"])
    check("same-count source replacement makes both products stale", r.returncode == 1
          and str(out) in r.stderr and str(kotlin) in r.stderr and before == snapshot(tmp), r.stderr)
    source.write_text(original.replace('["A"]', '["a"]'), encoding="utf-8")
    before = snapshot(tmp)
    r = run([*dual, "--quiet"])
    check("invalid source names fail before any output write", r.returncode != 0
          and "unrecognized methodList entry" in r.stderr and before == snapshot(tmp), r.stderr)
    source.write_text(original, encoding="utf-8")
    changed = copy.deepcopy(doc)
    changed["dialect"]["getnum_nickname_mastername"]["values"]["v24"] = "false"
    before = snapshot(tmp)
    message = ""
    with patch.object(g, "build_doc", return_value=changed):
        try:
            g.main([*dual, "--quiet"])
        except SystemExit as exc:
            message = str(exc)
    check("invalid dialect fails before any output write", "getnum_nickname_mastername" in message
          and before == snapshot(tmp), message)
    r = run(["--reference-root", os.path.join(tmp, "nope"), "--out", str(out), "--check"])
    check("missing reference exit1", r.returncode != 0 and "Reference source missing" in r.stderr, r.stderr)
    stderr = io.StringIO()
    kotlin.write_text("value\ngenerated_at: 'old'\n", encoding="utf-8", newline="\n")
    with contextlib.redirect_stderr(stderr):
        result = g.check_output(kotlin, "value\ngenerated_at: 'new'\n")
    check("Kotlin existing date-like line is compared", result is False
          and "first diff at line 2 " in stderr.getvalue(), stderr.getvalue())


def _fn_src(entries: str) -> str:
    return ("class FunctionMethodCreator {\n"
            "    static FunctionMethodCreator() {\n"
            "        methodList = new Dictionary<string, FunctionMethod> {\n"
            f"{entries}\n"
            "        };\n"
            "    }\n"
            "}\n")


def _cmd_src(body: str) -> str:
    return ("class FunctionIdentifier {\n"
            "    private static void addFunction(FunctionCode code, object inst) { string key = code.ToString(); }\n"
            "    static FunctionIdentifier() {\n"
            f"{body}\n"
            "        foreach (var pair in methodList) { string key = pair.Key;\n"
            "            funcDic.Add(key, new FunctionIdentifier(key, pair.Value, methodInstruction)); }\n"
            "    }\n"
            "}\n")


def _exits(fn, *args) -> str:
    try:
        fn(*args)
    except SystemExit as exc:
        return str(exc)
    return ""


def check_extraction_cases() -> None:
    # 五个 Roslyn 审计合成判例（regex_synthetic_cases）+ 结构负例：旧正则在这些上全盲或误报。
    check("extract comment false-positive excluded",
          g.extract_functions(_fn_src('["REAL"] = new RealMethod(), // methodList["FAKE"] = new FakeMethod()'), "t")
          == {"REAL"})
    check("extract spacing/newline tolerant",
          g.extract_functions(_fn_src('[ "REAL" ] = new\nRealMethod ()'), "t") == {"REAL"})
    check("extract command multiline tolerant",
          g.extract_commands(_cmd_src("addFunction(\n FunctionCode.REAL, new R());"), "t")[0] == {"REAL"})
    check("extract alias registration keeps both names",
          g.extract_functions(_fn_src('["A"] = new RealMethod(), ["B"] = methodList["A"]'), "t") == {"A", "B"})
    check("extract #if false disabled excluded",
          g.extract_functions(_fn_src('["REAL"] = new RealMethod(),\n#if false\n["FAKE"] = new FakeMethod(),\n#endif'), "t")
          == {"REAL"})
    check("extract #if true / #else branches",
          g.extract_functions(_fn_src('#if true\n["YES"] = new YesMethod(),\n#else\n["NO"] = new NoMethod(),\n#endif'), "t")
          == {"YES"})
    check("extract conditional commands recorded",
          g.extract_commands(_cmd_src("if (JSONConfig.Data.UseScopedVariableInstruction) {\n"
                                      "    addFunction(FunctionCode.VARI, new V());\n"
                                      "    addFunction(FunctionCode.VARS, new W());\n}"), "t")
          == ({"VARI", "VARS"}, {"VARI": "JSONConfig.Data.UseScopedVariableInstruction",
                                 "VARS": "JSONConfig.Data.UseScopedVariableInstruction"}))
    check("extract rejects unknown RHS",
          "unknown RHS" in _exits(g.extract_functions, _fn_src('["X"] = SomeFactory()'), "t"))
    check("extract rejects duplicate function",
          "duplicate function" in _exits(g.extract_functions,
                                         _fn_src('["A"] = new AMethod(), ["A"] = new BMethod()'), "t"))
    check("extract rejects dangling alias",
          "unregistered" in _exits(g.extract_functions, _fn_src('["B"] = methodList["A"]'), "t"))
    check("extract rejects multiple initializers",
          "exactly one" in _exits(g.extract_functions,
                                  _fn_src('["A"] = new AMethod()') + "class Q { void M() { methodList = new Dictionary<string, int> { }; } }", "t"))
    check("extract rejects command inside loop",
          "inside a loop" in _exits(g.extract_commands,
                                    _cmd_src("foreach (var x in y) { addFunction(FunctionCode.BAD, new B()); }"), "t"))
    check("extract rejects command inside else",
          "inside else" in _exits(g.extract_commands,
                                  _cmd_src("if (X) { } else { addFunction(FunctionCode.BAD, new B()); }"), "t"))
    check("extract rejects duplicate command",
          "duplicate command" in _exits(g.extract_commands,
                                        _cmd_src("addFunction(FunctionCode.A, new X()); addFunction(FunctionCode.A, new Y());"), "t"))
    check("extract enum members with values",
          g.extract_enum_codes("enum FunctionCode { __NULL__ = 0x0000, SET, PRINT = 3, /*c*/ FOO }", "t")
          == {"__NULL__", "SET", "PRINT", "FOO"})
    # 枚举漂移闸：注册命令必须是枚举子集，且枚举减注册恰为 {SET,__NULL__}。
    with tempfile.TemporaryDirectory(prefix="engine_enum_") as tmp:
        ref = os.path.join(tmp, "root")
        make_fixture(ref)
        sub, _fun, _cmd, enum_rel, _funcs, _cmds = FIXTURE["v24"]
        enum_path = Path(ref) / "reference" / sub / enum_rel
        enum_path.write_text(enum_path.read_text(encoding="utf-8").replace("        SET,", "        SET,\n        GHOST,"),
                             encoding="utf-8")
        check("extract_all rejects enum drift (unregistered extra member)",
              "enum drift" in _exits(g.extract_all, Path(ref)))


def main() -> int:
    FAILS.clear()
    global CHECKS
    CHECKS = 0
    with tempfile.TemporaryDirectory(prefix="engine_profiles_") as tmp:
        ref = os.path.join(tmp, "root")
        out = Path(tmp) / "engine-profiles.yaml"
        kotlin = Path(tmp) / "EngineProfileData.kt"
        make_fixture(ref)
        r = run(["--reference-root", ref, "--out", str(out), "--quiet"])
        check("generate exit0", r.returncode == 0, r.stderr[-300:])
        check("generate wrote file (YAML-only)", out.exists() and not kotlin.exists())
        doc = g.build_doc(g.extract_all(Path(ref)))
        check("YAML text exactly reproducible from build_doc", out.read_text(encoding="utf-8") == g.yaml_dump(doc))
        check_structure(doc)
        r = run(["--reference-root", ref, "--out", str(out), "--check", "--quiet"])
        check("check up-to-date exit0 (YAML-only)", r.returncode == 0, r.stdout + r.stderr)
        check_cli(tmp, ref, out, kotlin, doc)
    check_extraction_cases()

    print(f"\n{CHECKS} checks / {len(FAILS)} failures / 0 skipped")
    if FAILS:
        for x in FAILS:
            print("  -", x)
        return 1
    print("ALL OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
