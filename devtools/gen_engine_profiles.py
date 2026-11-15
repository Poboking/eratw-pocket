#!/usr/bin/env python3
"""生成/审计 specs/engine-profiles.yaml —— 四引擎（v24 / em_ee / snake / cs_reference）的
函数与命令全集、增量、功能门组、bug-dialect 表。

本脚本是**唯一**生成入口，目的是让兼容层配置项划分可复现、可审计：
- 输入表是手写权威 `specs/engine-profiles.input.yaml`（引擎身份 / 档位 / 语料身份 / 方言），
  **读取**用 PyYAML（缺了硬失败，不静默回落）；**写出**仍是本脚本自带的手工发射器，
  逐字节可复现 —— 换 yaml.dump 会让产物整体改写（契约 CONTRACT-ENGCPL §5）；
- 直接读共享根 reference 下的四棵源码树，机械抽取注册表，零手工转录；
- 输出确定性（列表排序、固定顺序），`--check` 可判定落盘文件是否过期；
- 写出确定性（自带 YAML 发射器）；生成完若 PyYAML 在则额外做一次解析回读校验。

用法（在 eratw-android/ 下）：
    python devtools/gen_engine_profiles.py              # 生成/更新
    python devtools/gen_engine_profiles.py --check      # 审计：过期非零退出
    python devtools/gen_engine_profiles.py --reference-root <path>

reference 根解析顺序：--reference-root > 环境变量 ERATW_ROOT
（公开仓不内置任何个人机器路径）。
只读 reference，默认写入 <repo>/specs/engine-profiles.yaml；--emit-kotlin <path> 同轮生成/审计 Kotlin。
input yaml 永远按本脚本所在仓库定位（它是仓库数据，不是共享根数据）。
"""
from __future__ import annotations

import argparse
import datetime
import io
import os
import platform
import re
import sys
from itertools import zip_longest
from pathlib import Path

OUT_REL = Path("specs") / "engine-profiles.yaml"
INPUT_REL = Path("specs") / "engine-profiles.input.yaml"
INPUT_SCHEMA = "eratw.engine-profiles-input/v1"

# 端口扩展名（四棵 reference 树都没有、但本引擎实注册的名字）。这是**端口侧事实**，
# 不是从 C# 抽取的：2026-11-10 由活 JVM 注册表（320 名）减四树函数并集（351 名）实算，
# 跨面守卫（差集 ∩ 四树命令并集 = ∅）通过；取证见 build/b0-port-audit/surface-diff.json
# （基线 37181f200）。EngineProfileDataTest 用活注册表逐名复核本表，端口注册面一变即判红。
# SPRITEANIMEFRAME：端口 SpriteAnimeFrameMethod.kt 自称源自 4.981 fork 说明文档，但 snake
# reference 树里真名是 SPRITEANIMEADDFRAME、无 SPRITEANIMEFRAME —— 名实不符待 B3 审计裁定。
PORT_EXTENSIONS = frozenset({
    "CHARA_TO_ENTITY", "ENTITY_ADD_REL", "ENTITY_CREATE", "ENTITY_DEL_REL", "ENTITY_DESTROY",
    "ENTITY_EXISTS", "ENTITY_GET_INT", "ENTITY_GET_STATE", "ENTITY_GET_STR", "ENTITY_HAS_REL",
    "ENTITY_PROJECT_CHARA", "ENTITY_QUERY_COUNT", "ENTITY_QUERY_FILL", "ENTITY_QUERY_REL_COUNT",
    "ENTITY_QUERY_REL_FILL", "ENTITY_SET_INT", "ENTITY_SET_STATE", "ENTITY_SET_STR",
    "ENTITY_TO_CHARA", "ENTITY_UNPROJECT_CHARA", "HAS_SUPER", "MOD_IS_ACTIVE",
    "ROSTER_GENERATION", "SPRITEANIMEFRAME",
    # M4-5（`2bb3a5f78`）新注册的确定性派生内建：`EntityMethods.kt:557`，四棵 reference 树
    # grep 零命中（端口独有属实）。表漂移由 EngineProfileDataTest 的 I6 闸抓出（B7-REG 2026-11-12）。
    "ENTITY_RAND",
})

_STRING_COMPARE = {"bug": "LEGACY_BOTH_LT", "fixed": "FIXED"}
_NICKNAME_KEYWORDS = {False: "NAME_CALLNAME_ONLY", True: "WITH_NICKNAME_MASTERNAME"}
_RELATION_MASTERNAME = {False: "EXCLUDED", True: "INCLUDED"}

# 上面三张 map 是「方言值 → Kotlin 枚举名」，即生成器怎么写 Kotlin（枚举本身手写在
# compat/CompatFacets.kt），不是引擎事实；哪家引擎取哪个值在 input yaml 的 dialect 段。

# 产物里的**段序**（纯排列，不含任何语义）。事实一律在 input yaml；新增引擎若要落在非末尾
# 位置，只需在这两个元组里登记名字（_SOURCES_ORDER 决定 sources 段行序，_KOTLIN_ORDER 决定
# EngineProfileData 里三组常量与 DIALECT map 的行序）。抽取段与 registration 段的行序跟随
# input yaml 里 engines 段的书写顺序。
_SOURCES_ORDER = ("v24", "em_ee", "snake", "cs_reference")
_KOTLIN_ORDER = ("snake", "em_ee", "v24")


def _input_path() -> Path:
    return Path(__file__).resolve().parent.parent / INPUT_REL


def _validate_input(doc, path: Path) -> None:
    """input yaml 的窄校验：缺段/未知键/引用悬空/缺省档不唯一一律硬失败。"""

    def bad(msg: str) -> None:
        raise SystemExit(f"{path}: {msg}")

    if not isinstance(doc, dict):
        bad("顶层必须是映射")
    for key in ("schema", "engines", "targets", "corpora",
                "unregistered_enum_allowlist", "registration_anchors", "dialect"):
        if key not in doc:
            bad(f"缺段 {key}")
    if doc["schema"] != INPUT_SCHEMA:
        bad(f"schema 期望 {INPUT_SCHEMA}，实际 {doc['schema']!r}")
    engines = doc["engines"]
    if not isinstance(engines, dict) or not engines:
        bad("engines 必须是非空映射")
    for name, entry in engines.items():
        if not isinstance(entry, dict):
            bad(f"engines.{name} 必须是映射")
        for key in ("reference_dir", "registry", "meta"):
            if key not in entry:
                bad(f"engines.{name} 缺 {key}")
        reg = entry["registry"]
        if not isinstance(reg, dict) or set(reg) != {"functions", "commands", "enum"}:
            bad(f"engines.{name}.registry 必须恰含 functions/commands/enum")
        if "kotlin" in entry:
            want = {"const_prefix", "family"}
            if not isinstance(entry["kotlin"], dict) or set(entry["kotlin"]) != want:
                bad(f"engines.{name}.kotlin 必须恰含 {sorted(want)}")
    families = sorted(e["kotlin"]["family"] for e in engines.values() if "kotlin" in e)
    corpora = doc["corpora"]
    if not isinstance(corpora, dict) or not corpora:
        bad("corpora 必须是非空映射")
    for name, entry in corpora.items():
        if not isinstance(entry, dict):
            bad(f"corpora.{name} 必须是映射")
        if set(entry) != {"root_dir", "marker_file", "retired"}:
            bad(f"corpora.{name} 必须恰含 root_dir/marker_file/retired")
        if not isinstance(entry["root_dir"], str) or not entry["root_dir"]:
            bad(f"corpora.{name}.root_dir 必须是非空字符串")
        if entry["marker_file"] is not None and not isinstance(entry["marker_file"], str):
            bad(f"corpora.{name}.marker_file 必须是字符串或 null")
        if type(entry["retired"]) is not bool:
            bad(f"corpora.{name}.retired 必须是 true/false")
    targets = doc["targets"]
    if not isinstance(targets, dict) or not targets:
        bad("targets 必须是非空映射")
    defaults: list[str] = []
    allowed = {"engine", "stamp_version", "family", "corpus", "default",
               "full_statement_coverage"}
    for name, entry in targets.items():
        if not isinstance(entry, dict):
            bad(f"targets.{name} 必须是映射")
        extra = sorted(set(entry) - allowed)
        if extra:
            bad(f"targets.{name} 含未知键 {extra}")
        for key in ("engine", "stamp_version", "family", "corpus"):
            if not isinstance(entry.get(key), str) or not entry[key]:
                bad(f"targets.{name}.{key} 必须是非空字符串")
        if entry["engine"] not in engines:
            bad(f"targets.{name}.engine={entry['engine']!r} 不在 engines 段里")
        if entry["family"] not in families:
            bad(f"targets.{name}.family={entry['family']!r} 不在 engines.*.kotlin.family 里")
        if entry["corpus"] not in corpora:
            bad(f"targets.{name}.corpus={entry['corpus']!r} 不在 corpora 段里")
        for flag in ("default", "full_statement_coverage"):
            if type(entry.get(flag, False)) is not bool:
                bad(f"targets.{name}.{flag} 必须是 true/false")
        if entry.get("default", False):
            defaults.append(name)
    if len(defaults) != 1:
        bad(f"targets 里 default: true 必须恰一条，实际 {defaults}")
    allow = doc["unregistered_enum_allowlist"]
    if not isinstance(allow, list) or any(
            not isinstance(n, str) or re.fullmatch(r"[A-Z_][A-Z0-9_]*", n) is None for n in allow):
        bad("unregistered_enum_allowlist 必须是大写名字列表")
    dialect = doc["dialect"]
    if not isinstance(dialect, dict):
        bad("dialect 必须是映射")
    for facet in ("string_compare_ge_le", "getnum_nickname_mastername",
                  "relation_mastername", "isnumeric_v24_fix"):
        if facet not in dialect:
            bad(f"dialect 缺 {facet}")
    kw = dialect["getnum_nickname_mastername"].get("keywords")
    if not isinstance(kw, dict) or set(kw) != {"routed", "gated"}:
        bad("dialect.getnum_nickname_mastername.keywords 必须恰含 routed/gated")
    for side in ("routed", "gated"):
        names = kw[side]
        if not isinstance(names, list) or not names or any(
                not isinstance(n, str) or re.fullmatch(r"[A-Z_][A-Z0-9_]*", n) is None
                for n in names):
            bad(f"dialect.getnum_nickname_mastername.keywords.{side} 必须是大写名字列表")


def _load_input() -> dict:
    """读手写权威 input yaml。缺 PyYAML 硬失败（不静默回落成 python 常量表）。"""
    try:
        import yaml  # type: ignore
    except ImportError:  # pragma: no cover - 环境缺依赖时才有意义
        raise SystemExit(
            f"{INPUT_REL} 需要 PyYAML 才能读取；先 pip install pyyaml"
            "（生成器不再内置回落表）")
    path = _input_path()
    try:
        with io.open(path, encoding="utf-8") as f:
            doc = yaml.safe_load(f)
    except OSError as exc:
        raise SystemExit(f"读不到 {path}：{exc}")
    except yaml.YAMLError as exc:
        raise SystemExit(f"{path} 不是合法 YAML：{exc}")
    _validate_input(doc, path)
    return doc


INPUT = _load_input()
ENGINES = INPUT["engines"]

# 投影到 EngineProfileData 的引擎（有 kotlin 键的），(引擎, 常量前缀, EngineFamily 名)；
# 顺序 = _KOTLIN_ORDER。cs_reference 没有 kotlin 键 ⇒ 只进 yaml，不投影 Kotlin。
_KOTLIN_PROFILES = tuple(
    (name, ENGINES[name]["kotlin"]["const_prefix"], ENGINES[name]["kotlin"]["family"])
    for name in _KOTLIN_ORDER
)

# 每引擎：(子目录, 函数注册表文件, 命令注册表文件, FunctionCode 枚举文件)
ENGINE_FILES = {
    name: (entry["reference_dir"], entry["registry"]["functions"],
           entry["registry"]["commands"], entry["registry"]["enum"])
    for name, entry in ENGINES.items()
}

# 枚举里定义了但从不 addFunction 的成员（四树一致）：SET 是赋值伪指令、__NULL__ 是占位。
# 漂移即报错（强制重新审计），不是悄悄收进命令面。锚：各树 FunctionIdentifier 静态构造器。
EXPECTED_UNREGISTERED_ENUM = frozenset(INPUT["unregistered_enum_allowlist"])

# 语句面回填与枚举→ERB 恒等映射的 C# 锚（2026-11-10 Roslyn 审计 manual_mapping_and_dynamic_anchors）。
# method_fallback：static FunctionIdentifier() 末尾 foreach methodList，把每个式中函数补注册为
# METHOD 指令 ⇒ 语句面 = 显式命令 ∪ 函数。enum_to_erb：addFunction 助手 code.ToString()（大写枚举名
# 上 ToUpper 是 no-op）⇒ 枚举名即 ERB 关键字，恒等。
REGISTRATION_ANCHORS = INPUT["registration_anchors"]

# 版本/出处（与 sources 段同源；改动需人工维护）—— 逐字取自 input yaml 的 engines.*.meta
META_SOURCES = {name: ENGINES[name]["meta"] for name in _SOURCES_ORDER}

# 段序元组必须恰好覆盖「该出现在该段里的引擎」：漏登记会让新引擎静默不进产物。
if set(_SOURCES_ORDER) != set(ENGINE_FILES):
    raise SystemExit(
        f"_SOURCES_ORDER {sorted(_SOURCES_ORDER)} 与 input yaml 的 engines "
        f"{sorted(ENGINE_FILES)} 不是同一组；加引擎要同步登记段序")
if set(_KOTLIN_ORDER) != {name for name, entry in ENGINES.items() if "kotlin" in entry}:
    raise SystemExit(
        f"_KOTLIN_ORDER {sorted(_KOTLIN_ORDER)} 与带 kotlin 键的引擎 "
        f"{sorted(name for name, entry in ENGINES.items() if 'kotlin' in entry)} 不是同一组")


def resolve_reference_root(arg: str | None) -> Path:
    if arg:
        return Path(arg)
    env = os.environ.get("ERATW_ROOT")
    if env:
        return Path(env)
    raise SystemExit("Cannot resolve reference root: pass --reference-root or set ERATW_ROOT")


def read_text(p: Path) -> str:
    with io.open(p, encoding="utf-8-sig", errors="replace") as f:
        return f.read()


# ---------------- C# 词法预处理（只去注释与禁用文本，保留字符串字面量） ----------------
# 旧正则把注释里的注册也吃进来（v24 多收 CHKGLOBALDATA/CHKVARDATA/FIND_VARDATA，em/snake
# 再多 GROTATE；命令面把 OUTPUTLOG 误当显式命令），且「名字数 == new *Method() 数」自检
# 对注释/别名/共享构造器全盲。这里改成：先去注释与 #if 禁用段，再按注册形状结构化抽取，
# 未知形状一律报错。独立 Roslyn 解析（build/b0-registry-roslyn.json）已逐名核对四树结果。

def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释（保留换行以维持行号），字符串/字符字面量原样保留。"""
    out: list[str] = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"; out.append("  "); i += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; out.append("  "); i += 2; continue
            if c == "@" and nxt == '"':
                state = "vstr"; out.append('@"'); i += 2; continue
            if c == '"':
                state = "str"; out.append(c); i += 1; continue
            if c == "'":
                state = "char"; out.append(c); i += 1; continue
            out.append(c); i += 1
        elif state == "line":
            if c == "\n":
                state = "code"; out.append(c)
            else:
                out.append("\r" if c == "\r" else " ")
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                state = "code"; out.append("  "); i += 2; continue
            out.append(c if c in "\r\n" else " "); i += 1
        elif state == "str":
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                state = "code"
            i += 1
        elif state == "vstr":
            out.append(c)
            if c == '"':
                if nxt == '"':
                    out.append('"'); i += 2; continue
                state = "code"
            i += 1
        elif state == "char":
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == "'":
                state = "code"
            i += 1
    if state in ("block", "str", "vstr", "char"):
        raise SystemExit(f"Unterminated {state} literal/comment in registration source")
    return "".join(out)


def _eval_pp(expr: str) -> bool:
    """#if 常量表达式求值：true/false/!/&&/||/括号；标识符一律视为未定义（false）。
    四棵真实注册文件都没有预处理指令（Roslyn 审计 preprocessing=[]），此路只为防御与合成负例。"""
    tokens = re.findall(r"[A-Za-z_]\w*|&&|\|\||!|\(|\)", expr)
    pos = 0

    def peek() -> str | None:
        return tokens[pos] if pos < len(tokens) else None

    def parse_or() -> bool:
        nonlocal pos
        v = parse_and()
        while peek() == "||":
            pos += 1; v = parse_and() or v
        return v

    def parse_and() -> bool:
        nonlocal pos
        v = parse_unary()
        while peek() == "&&":
            pos += 1; v = parse_unary() and v
        return v

    def parse_unary() -> bool:
        nonlocal pos
        t = peek()
        if t == "!":
            pos += 1; return not parse_unary()
        if t == "(":
            pos += 1; v = parse_or()
            if peek() != ")":
                raise SystemExit(f"Bad #if expression: {expr!r}")
            pos += 1; return v
        if t == "true":
            pos += 1; return True
        if t == "false":
            pos += 1; return False
        if t is not None and re.fullmatch(r"[A-Za-z_]\w*", t):
            pos += 1; return False
        raise SystemExit(f"Bad #if expression: {expr!r}")

    if not tokens:
        raise SystemExit("Empty #if expression")
    result = parse_or()
    if pos != len(tokens):
        raise SystemExit(f"Bad #if expression: {expr!r}")
    return result


def strip_disabled_preprocessor(text: str) -> str:
    """按 #if/#elif/#else/#endif 去掉禁用段；所有预处理指令行（含 #region/#endregion/#pragma）
    本身一律置空（保留行号）。指令不是注册代码，不能混进条目切分。"""
    out: list[str] = []
    stack: list[tuple[bool, bool, bool]] = []  # (parent_active, any_branch_taken, current_active)
    active = True
    for line in text.split("\n"):
        m = re.match(r"\s*#\s*([A-Za-z_]\w*)\b(.*)", line)
        if not m:
            out.append(line if active else "")
            continue
        directive, expr = m.group(1), m.group(2).strip()
        if directive == "if":
            val = _eval_pp(expr) if active else False
            stack.append((active, val, val)); active = val
        elif directive == "elif":
            if not stack:
                raise SystemExit("#elif without #if")
            parent, taken, _ = stack[-1]
            val = parent and not taken and _eval_pp(expr)
            stack[-1] = (parent, taken or val, val); active = val
        elif directive == "else":
            if not stack:
                raise SystemExit("#else without #if")
            parent, taken, _ = stack[-1]
            val = parent and not taken
            stack[-1] = (parent, True, val); active = val
        elif directive == "endif":
            if not stack:
                raise SystemExit("#endif without #if")
            stack.pop(); active = stack[-1][2] if stack else True
        out.append("")
    if stack:
        raise SystemExit("Unterminated #if in registration source")
    return "\n".join(out)


def _clean(text: str) -> str:
    return strip_disabled_preprocessor(strip_comments(text))


def _skip_literal(text: str, i: int) -> int:
    """i 指向字符串/字符字面量起点，返回其后一位。"""
    n = len(text)
    if text.startswith('@"', i):
        i += 2
        while i < n:
            if text[i] == '"':
                if i + 1 < n and text[i + 1] == '"':
                    i += 2; continue
                return i + 1
            i += 1
        raise SystemExit("Unterminated verbatim string")
    quote = text[i]
    i += 1
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2; continue
        if c == quote:
            return i + 1
        i += 1
    raise SystemExit("Unterminated string/char literal")


def _match_delim(text: str, open_idx: int, opening: str, closing: str) -> int:
    """从 open_idx 处的 opening 配平到对应 closing（跳过字面量），返回 closing 下标。"""
    depth = 0
    i, n = open_idx, len(text)
    while i < n:
        c = text[i]
        if c in "\"'" or text.startswith('@"', i):
            i = _skip_literal(text, i); continue
        if c == opening:
            depth += 1
        elif c == closing:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise SystemExit(f"Unbalanced {opening}{closing} in registration source")


def _split_top_level(text: str, sep: str) -> list[str]:
    """按 depth-0 的 sep 切分（跳过字面量与括号嵌套）。"""
    parts: list[str] = []
    depth = 0
    start = i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'" or text.startswith('@"', i):
            i = _skip_literal(text, i); continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == sep and depth == 0:
            parts.append(text[start:i]); start = i + 1
        i += 1
    parts.append(text[start:])
    return parts


_FUNC_ENTRY_RE = re.compile(r'\[\s*"([A-Z_][A-Z0-9_]*)"\s*\]\s*=\s*(.+)', re.S)
_NEW_METHOD_RHS_RE = re.compile(r"new\s+[A-Za-z_]\w*Method\s*\(")
_ALIAS_RHS_RE = re.compile(r'methodList\s*\[\s*"([A-Z_][A-Z0-9_]*)"\s*\]')


def extract_functions(text: str, engine: str) -> set[str]:
    """从唯一的 methodList 字典初始化器抽取函数名；条目逐个消费，未知 RHS/重复名/悬空别名报错。"""
    text = _clean(text)
    starts = list(re.finditer(r"\bmethodList\s*=\s*new\s+Dictionary\b", text))
    if len(starts) != 1:
        raise SystemExit(f"[{engine}] expected exactly one methodList Dictionary initializer, found {len(starts)}")
    i, n = starts[0].end(), len(text)
    ang = par = 0
    while i < n:
        c = text[i]
        if c in "\"'" or text.startswith('@"', i):
            i = _skip_literal(text, i); continue
        if c == "<":
            ang += 1
        elif c == ">":
            ang -= 1
        elif c == "(":
            par += 1
        elif c == ")":
            par -= 1
        elif c == "{" and ang == 0 and par == 0:
            break
        i += 1
    else:
        raise SystemExit(f"[{engine}] no collection initializer after methodList Dictionary")
    body = text[i + 1:_match_delim(text, i, "{", "}")]
    names: dict[str, str | None] = {}
    for entry in _split_top_level(body, ","):
        e = entry.strip()
        if not e:
            continue
        m = _FUNC_ENTRY_RE.fullmatch(e)
        if not m:
            raise SystemExit(f"[{engine}] unrecognized methodList entry: {e[:80]!r}")
        name, rhs = m.group(1), m.group(2).strip()
        if name in names:
            raise SystemExit(f"[{engine}] duplicate function registration {name}")
        if _NEW_METHOD_RHS_RE.match(rhs):
            names[name] = None
        else:
            am = _ALIAS_RHS_RE.fullmatch(rhs)
            if not am:
                raise SystemExit(f"[{engine}] unknown RHS for function {name}: {rhs[:80]!r}")
            names[name] = am.group(1)
    for name, target in names.items():
        if target is not None and target not in names:
            raise SystemExit(f"[{engine}] alias {name} points at unregistered {target}")
    return set(names)


_CMD_REG_RE = re.compile(r"\b(addFunction|addPrintFunction|addPrintDataFunction)\s*\(\s*FunctionCode\.([A-Z_][A-Z0-9_]*)")
_CMD_SCAN_RE = re.compile(
    r"\b(?P<reg>(?:addFunction|addPrintFunction|addPrintDataFunction)\s*\(\s*FunctionCode\.(?P<name>[A-Z_][A-Z0-9_]*))"
    r"|\b(?P<if>if)\s*\("
    r"|\b(?P<loop>foreach|while|for|switch)\s*\("
    r"|\b(?P<else>else)\b"
    r"|(?P<open>\{)|(?P<close>\})"
)
_FALLBACK_RE = re.compile(
    r"funcDic\.Add\s*\(\s*key\s*,\s*new\s+FunctionIdentifier\s*\(\s*key\s*,\s*(?:pair|method)\.Value\s*,\s*methodInstruction\s*\)\s*\)"
)


def extract_commands(text: str, engine: str) -> tuple[set[str], dict[str, str]]:
    """从 static FunctionIdentifier() 构造器抽取显式命令（含条件注册）。
    返回 (possible 面集合, {条件命令名: 条件表达式})。注册落在 loop/else 块里视为未知形状报错。"""
    text = _clean(text)
    if "code.ToString()" not in text:
        raise SystemExit(f"[{engine}] enum->ERB identity helper (code.ToString()) not found")
    m = re.search(r"\bstatic\s+FunctionIdentifier\s*\(\s*\)\s*\{", text)
    if not m:
        raise SystemExit(f"[{engine}] no static FunctionIdentifier() constructor")
    open_idx = m.end() - 1
    body = text[open_idx + 1:_match_delim(text, open_idx, "{", "}")]
    if not _FALLBACK_RE.search(body):
        raise SystemExit(f"[{engine}] METHOD fallback (function->statement backfill) not found")
    names: set[str] = set()
    conditional: dict[str, str] = {}
    frames: list[tuple[str, str | None]] = []  # (kind, condition); kind ∈ if/loop/plain/else
    depth = 0
    pending_if: str | None = None  # 单语句 if（无花号）的条件，作用于下一条注册
    i, n = 0, len(body)
    while i < n:
        c = body[i]
        if c in "\"'" or body.startswith('@"', i):
            i = _skip_literal(body, i); continue
        sm = _CMD_SCAN_RE.match(body, i)
        if not sm:
            i += 1; continue
        if sm.group("open"):
            depth += 1; frames.append(("plain", None)); i = sm.end(); continue
        if sm.group("close"):
            if frames:
                frames.pop()
            depth -= 1; i = sm.end(); continue
        if sm.group("if") or sm.group("loop"):
            kind = "if" if sm.group("if") else "loop"
            paren = sm.end() - 1
            close = _match_delim(body, paren, "(", ")")
            cond = body[paren + 1:close].strip()
            k = close + 1
            while k < n and body[k].isspace():
                k += 1
            if k < n and body[k] == "{":
                depth += 1; frames.append((kind, cond if kind == "if" else None)); i = k + 1; continue
            if kind == "if":
                pending_if = cond; i = close + 1; continue
            raise SystemExit(f"[{engine}] {kind} without brace block in constructor")
        if sm.group("else"):
            k = sm.end()
            while k < n and body[k].isspace():
                k += 1
            if k < n and body[k] == "{":
                depth += 1; frames.append(("else", None)); i = k + 1; continue
            raise SystemExit(f"[{engine}] else without brace block in constructor")
        # registration
        name = sm.group("name")
        if any(kind == "loop" for kind, _ in frames):
            raise SystemExit(f"[{engine}] command {name} registered inside a loop (unknown shape)")
        if any(kind == "else" for kind, _ in frames):
            raise SystemExit(f"[{engine}] command {name} registered inside else (unknown shape)")
        conds = [cond for kind, cond in frames if kind == "if" and cond]
        if pending_if is not None:
            conds.append(pending_if); pending_if = None
        if name in names:
            raise SystemExit(f"[{engine}] duplicate command registration {name}")
        names.add(name)
        if conds:
            conditional[name] = " && ".join(conds)
        i = sm.end()
    return names, conditional


_ENUM_MEMBER_RE = re.compile(r"([A-Z_][A-Z0-9_]*)\s*(?:=\s*[^,]+)?", re.S)


def extract_enum_codes(text: str, engine: str) -> set[str]:
    text = _clean(text)
    m = re.search(r"\benum\s+FunctionCode\b", text)
    if not m:
        raise SystemExit(f"[{engine}] no FunctionCode enum")
    open_idx = text.index("{", m.end())
    body = text[open_idx + 1:_match_delim(text, open_idx, "{", "}")]
    members: set[str] = set()
    for part in _split_top_level(body, ","):
        p = part.strip()
        if not p:
            continue
        mm = _ENUM_MEMBER_RE.fullmatch(p)
        if not mm:
            raise SystemExit(f"[{engine}] unrecognized FunctionCode member: {p[:60]!r}")
        if mm.group(1) in members:
            raise SystemExit(f"[{engine}] duplicate FunctionCode member {mm.group(1)}")
        members.add(mm.group(1))
    return members


def extract_all(root: Path) -> dict[str, dict]:
    sets: dict[str, dict] = {}
    missing = []
    for name, (sub, fun_file, cmd_file, enum_file) in ENGINE_FILES.items():
        paths = [root / "reference" / sub / rel for rel in (fun_file, cmd_file, enum_file)]
        for p in paths:
            if not p.exists():
                missing.append(str(p))
        if not all(p.exists() for p in paths):
            continue
        funcs = extract_functions(read_text(paths[0]), name)
        cmds, conditional = extract_commands(read_text(paths[1]), name)
        enum = extract_enum_codes(read_text(paths[2]), name)
        if not cmds <= enum:
            raise SystemExit(f"[{name}] registered commands absent from FunctionCode enum: {sorted(cmds - enum)}")
        unregistered = enum - cmds
        if unregistered != EXPECTED_UNREGISTERED_ENUM:
            raise SystemExit(
                f"[{name}] FunctionCode enum drift: unregistered members {sorted(unregistered)} "
                f"!= expected {sorted(EXPECTED_UNREGISTERED_ENUM)}; re-audit before regenerating"
            )
        sets[name] = {
            "functions": funcs,
            "commands": cmds,
            "conditional_commands": conditional,
            "statements_count": len(cmds | funcs),
        }
    if missing:
        raise SystemExit("Reference source missing; cannot generate:\n  " + "\n  ".join(missing))
    return sets


# ---------------- 分类 ----------------
def cls_em(n: str) -> str:
    if n.startswith("MAP_"):
        return "em.map"
    if n.startswith("XML_"):
        return "em.xml"
    if n.startswith("DT_"):
        return "em.datatable"
    if n.startswith("HTML_"):
        return "em.html"
    if n.startswith("ENUM") or n in {
            "ISDEFINED", "EXISTVAR", "EXISTFILE", "EXISTFUNCTION", "EXISTMETH", "GETMETH",
            "GETMETHS", "GETVAR", "GETVARS", "SETVAR", "VARSETEX", "ARRAYMSORTEX", "ENUMFILES",
            "ERDNAME", "REGEXPMATCH"}:
        return "em.reflect"
    if n in {"PLAYSOUND", "STOPSOUND", "PLAYBGM", "STOPBGM", "SETSOUNDVOLUME", "SETBGMVOLUME", "EXISTSOUND"}:
        return "ee.sound"
    if n.startswith("GD") or n.startswith("GG") or n in {
            "GROTATE", "SPRITEDISPOSEALL", "SETBGIMAGE", "CLEARBGIMAGE", "REMOVEBGIMAGE",
            "BITMAP_CACHE_ENABLE", "GETDISPLAYLINE", "SETANIMETIMER"}:
        return "ee.graphics"
    if n in {"BINPUT", "BINPUTS", "ONEBINPUT", "ONEBINPUTS", "INPUTANY", "MOUSEB", "FLOWINPUT", "FLOWINPUTS"}:
        return "ee.input"
    if n.endswith("TEXTBOX"):
        return "ee.textbox"
    if n.startswith("TOOLTIP_"):
        return "ee.tooltip"
    if n in {"OUTPUTLOG", "SKIPLOG", "UPDATECHECK"}:
        return "ee.log_update"
    return "ee.misc"


def cls_snake(n: str) -> str:
    if n in {"DIMF", "FUNCTIONF", "LOCALF", "ARGF", "RESULTF", "SIN", "COS", "TAN", "ASIN",
             "ACOS", "ATAN", "FLOOR", "CEIL", "ROUND", "TOFLOAT", "TOSTRF", "GETVARF", "GETMETHF", "EVALF"}:
        return "snake.float"
    if n in {"EVAL", "EVALS", "CALLSTR", "JUMPSTR", "TRYCALLSTR", "TRYJUMPSTR", "TRYCCALLSTR", "TRYCJUMPSTR"}:
        return "snake.dynamic"
    if n.startswith("SQL_"):
        return "snake.sql"
    if n.startswith("G_POLYGON") or n in {
            "GETANIMETIMER", "SETANIMETIMER", "SPRITECREATEFROMFILE", "SETIMAGELAYER",
            "SETIMAGELAYERL", "CLEARIMAGELAYER", "CLEARIMAGELAYER_ALL", "SET_SKIA_QUALITY",
            "GET_SKIA_QUALITY", "SET_TEXT_DRAWING_MODE", "GET_TEXT_DRAWING_MODE",
            "STRICT_FONT_FALLBACK", "HTML_PRINTC", "HTML_PRINTLC", "TEXT_BGC_OFF", "TEXT_BGC_ON"}:
        return "snake.graphics"
    if n.startswith("BIT") and n != "BITMAP_CACHE_ENABLE":
        return "snake.bitops"
    if n in {"MATCHALL", "MATCHALLEX", "EXIST_IN_CSV", "EXIST_IN_ERD"} or n.startswith("GETCSVNOBY"):
        return "snake.match_reflect"
    if n in {"MAP_VALUES", "MAP_MERGE", "MAP_REMOVEIF", "MAP_FINDKEY", "MAP_TOSTRING",
             "MAP_FROMSTRING", "DT_CELL_GETF"}:
        return "snake.map_dt_ext"
    if n in {"SEQUENCEINPUT", "ENABLE_INPUT_MACRO", "DISABLE_INPUT_MACRO",
             "TINPUTNF", "TINPUTSNF", "TONEINPUTNF", "TONEINPUTSNF"}:
        return "snake.input_ext"
    return "snake.misc"


# Bug 方言轴：值/锚/证据逐字来自 input yaml 的 dialect 段（E3/E4/E5 的唯一出处）。
# 本表整段进生成物的 dialect 段；Kotlin 只取其中三个 bool/枚举 facet + csv_encoding。
DIALECT = INPUT["dialect"]

# 档位与语料身份（E1/E2/E7 的唯一出处）。
TARGETS = INPUT["targets"]
CORPORA = INPUT["corpora"]

PROBE = {
    "date": "2026-11-10",
    "method": "Minimal game from devtools/engine-probe/ (ERB/TEST.ERB with @SYSTEM_TITLE; one SAVETEXT(TOSTR(expr), N) per probe; CSV/Chara0.csv defines 番号/名前/呼び名/あだ名/主人の呼び方). Engines: v24 Emuera1824+v24.exe on netfx4.8; em_ee built Release-NAudio with .NET SDK 10.0.403; snake built Release with .NET SDK 8.0.425. Probe trees were throwaway copies; reference trees untouched.",
    "inputs": [
        "txt00 = TOSTR(\"a\" >= \"a\")",
        "txt01 = TOSTR(\"a\" >= \"b\")",
        "txt02 = TOSTR(\"a\" <= \"a\")",
        "txt03 = TOSTR(ISNUMERIC(\"123\"))",
        "txt04 = TOSTR(ISNUMERIC(\"1.2.3\"))",
        "txt05 = TOSTR(ISNUMERIC(\"0xZZ\"))",
        "txt06 = TOSTR(ISNUMERIC(\"+\"))",
        "txt07 = TOSTR(ISNUMERIC(\"\"))",
        "txt08 = TOSTR(GETNUM(NICKNAME, \"Ali\"))",
        "txt09 = TOSTR(GETNUM(MASTERNAME, \"Ali\"))",
        "txt10 = TOSTR(GETNUM(NAME, \"Alice\"))",
    ],
    "readings": {
        "v24": [0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0],
        "em_ee": [1, 0, 1, 1, 0, 0, 0, 0, -1, -1, 0],
        "snake": [1, 0, 1, 1, 0, 0, 0, 0, -1, -1, 0],
    },
    "note": "tx03..tx07 identical across all three engines: ISNUMERIC has no dialect divergence. cs_reference was not run (decompile reference); its NumericCheck is the same algorithm.",
}

V24_CHANGELOG = [
    {"v": "v24", "layer": "semantic", "observable": True, "summary": "ISNUMERIC errors on some strings; cannot judge numeric-convertibility", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:133"},
    {"v": "v23", "layer": "semantic", "observable": True, "summary": "GETNUM/GETNUMB cannot use NICKNAME/MASTERNAME", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:102,110; ConstantData.cs:876-877"},
    {"v": "v22", "layer": "semantic", "observable": True, "summary": "CALLNAME mishandled in some paths; GETNUMB was a no-op", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:110"},
    {"v": "v21", "layer": "shell", "observable": False, "summary": "Debug console threw exception at 100 lines", "anchor": "src1824/Emuera/Forms/DebugDialog.cs:398-399 (100-entry input history)"},
    {"v": "v20", "layer": "input", "observable": True, "summary": "INPUTS rejected a lone '@' (debug-code path)", "anchor": "src1824/Emuera/GameView/EmueraConsole.cs:847-848 (comment: lone @ must not be swallowed)"},
    {"v": "v19", "layer": "semantic", "observable": True, "summary": "ARRAYSORT 3rd+4th arg sum overflow leaked a handle", "anchor": "src1824/Emuera/GameProc/Process.ScriptProc.cs:658; src1824/Emuera/GameData/Variable/VariableEvaluator.cs:714"},
    {"v": "v18", "layer": "perf", "observable": False, "summary": "CUSTOMDRAWLINE was very slow; rewritten", "anchor": "src1824/Emuera/GameView/EmueraConsole.Print.cs:527,545"},
    {"v": "v17", "layer": "graphics", "observable": True, "summary": "GDRAWSPRITE bmp enlargement displayed unexpectedly (MS imaging quirk)", "anchor": "src1824/Emuera/GameData/Function/Creator.Method.cs:3656"},
    {"v": "v16", "layer": "semantic", "observable": True, "summary": "NOSAMES implementation was completely wrong", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:95"},
    {"v": "v16", "layer": "load_compile", "observable": True, "summary": "character CSV with ISASSI/assistant as key errored; now read-and-discard", "anchor": "src1824/Emuera/GameData/ConstantData.cs:1206-1207"},
    {"v": "v16", "layer": "semantic", "observable": True, "summary": "integer min value with '-' now emits warning", "anchor": "src1824/Emuera/GameData/Expression/OperatorMethod.cs:704-706"},
    {"v": "v15", "layer": "load_compile", "observable": True, "summary": "LOADTEXT allowed /r contamination", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:169"},
    {"v": "v14", "layer": "load_compile", "observable": True, "summary": "resource folder read .csvX garbage extensions as csv", "anchor": "src1824/Emuera/Content/AppContents.cs:84-88 (.csv extension exact compare)"},
    {"v": "v14", "layer": "shell", "observable": False, "summary": "CSV error line display threw exception"},
    {"v": "v13", "layer": "load_compile", "observable": True, "summary": "[SKIPSTART]/[SKIPEND] block containing { or } misfired line-join", "anchor": "src1824/Emuera/Sub/EraStreamReader.cs:86"},
    {"v": "v12", "layer": "semantic", "observable": True, "summary": "ADDSPCHAR wrong when SP-char compat setting enabled", "anchor": "src1824/Emuera/GameProc/Function/Instraction.Child.cs:961,976,992-993"},
    {"v": "v12", "layer": "memory", "observable": False, "summary": "source code not kept in memory; read from file on error", "anchor": "src1824/Emuera/GameProc/Process.cs:512-517 (getRawTextFormFilewithLine)"},
    {"v": "v11", "layer": "shell", "observable": False, "summary": "v10 tuning; UI response/display position"},
    {"v": "v10", "layer": "shell", "observable": False, "summary": "tooltip changes from 1823+v1 added to 1824", "anchor": "src1824/Emuera/GameView/EmueraConsole.cs:1302-1342"},
    {"v": "v10", "layer": "semantic", "observable": True, "summary": "VARSET failed under certain conditions", "anchor": "src1824/Emuera/GameProc/Function/Instraction.Child.cs:1180 (comment: take end before comparing)"},
    {"v": "v9", "layer": "semantic", "observable": True, "summary": "ARRAYMSORT extended from Int32 to full Int64 range", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:112"},
    {"v": "v9", "layer": "internal", "observable": False, "summary": "internal variable definition TYPO fixed"},
    {"v": "v8.1", "layer": "shell", "observable": False, "summary": "error log filename no longer full path"},
    {"v": "v8", "layer": "shell", "observable": False, "summary": "image data not cleared on restart; WINAPI-mode restart exception", "anchor": "src1824/Emuera/Content/AppContents.cs:144; src1824/Emuera/GameView/EmueraConsole.cs:1783"},
    {"v": "v7", "layer": "load_compile", "observable": True, "summary": "ERH file errors not caught/stopped correctly", "anchor": "src1824/Emuera/GameProc/Process.cs:164-168; src1824/Emuera/GameProc/HeaderFileLoader.cs:36"},
    {"v": "v6.2", "layer": "load_compile", "observable": True, "summary": "UNICODE pre-parse Restructure caused .NET exception", "anchor": "src1824/Emuera/GameData/Function/Creator.Method.cs:2495-2498 (runtime vs preparse branch)"},
    {"v": "v6.1", "layer": "semantic", "observable": True, "summary": "UNICODE passes 0x0A/0x0D; warns and returns empty string", "anchor": "src1824/Emuera/GameData/Function/Creator.Method.cs:2490-2499"},
    {"v": "v6", "layer": "semantic", "observable": True, "summary": "UNICODE rejects control-character numbers", "anchor": "src1824/Emuera/GameData/Function/Creator.Method.cs:2477,2489-2499"},
    {"v": "v5", "layer": "semantic", "observable": True, "summary": "STRJOIN argument handling was sloppy", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:139"},
    {"v": "v4", "layer": "semantic", "observable": True, "summary": "incomplete v1 fix; HTML_PRINT <button> value >32bit did not render as button", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:145"},
    {"v": "v3", "layer": "load_compile", "observable": True, "summary": "line-join misfired when FORM {numvar} at line head", "anchor": "src1824/Emuera/Sub/EraStreamReader.cs:125-130"},
    {"v": "v2", "layer": "semantic", "observable": True, "summary": "HTML_GETPRINTEDSTR returned wrong values", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:145"},
    {"v": "v1", "layer": "semantic", "observable": True, "summary": "STRJOIN implementation broken", "anchor": "src1824/Emuera/GameData/Function/Creator.cs:139"},
]


def build_doc(sets: dict[str, dict]) -> dict:
    v24f, v24c = sets["v24"]["functions"], sets["v24"]["commands"]
    emf, emc = sets["em_ee"]["functions"], sets["em_ee"]["commands"]
    snf, snc = sets["snake"]["functions"], sets["snake"]["commands"]
    csf, csc = sets["cs_reference"]["functions"], sets["cs_reference"]["commands"]

    em_add_fn = sorted(emf - v24f)
    em_add_cmd = sorted(emc - v24c)
    snake_only_fn = sorted(snf - (v24f | csf | emf))
    snake_only_cmd = sorted(snc - (v24c | csc | emc))

    groups: dict[str, list[str]] = {}
    for n in em_add_fn + em_add_cmd:
        groups.setdefault(cls_em(n), []).append(n)
    for n in snake_only_fn + snake_only_cmd:
        groups.setdefault(cls_snake(n), []).append(n)
    for k in list(groups):
        groups[k] = sorted(set(groups[k]))

    registration = {}
    for engine in ENGINE_FILES:
        s = sets[engine]
        cond = s["conditional_commands"]
        registration[engine] = {
            "commands_unconditional": len(s["commands"]) - len(cond),
            "conditional_commands": {name: cond[name] for name in sorted(cond)},
            "statements": {
                "derivation": "commands union functions; the METHOD fallback backfills every expression function as a statement",
                "count": s["statements_count"],
                "anchor": REGISTRATION_ANCHORS[engine]["method_fallback"],
            },
            "enum_unregistered": sorted(EXPECTED_UNREGISTERED_ENUM),
            "enum_to_erb": "identity via code.ToString() (ToUpper is a no-op on uppercase enum names)",
            "enum_to_erb_anchor": REGISTRATION_ANCHORS[engine]["enum_to_erb"],
        }

    return {
        "schema": "eratw.engine-profiles/v1",
        "meta": {
            "generated_by": "devtools/gen_engine_profiles.py",
            "generated_at": datetime.date.today().isoformat(),
            "purpose": "Machine-readable engine capability + bug-dialect profiles to drive the eratw-android compatibility layer (feature gates + dialect switches).",
            "extraction_method": "Comments and disabled #if text stripped first (string literals kept). Functions: the single methodList Dictionary collection initializer in each engine's FunctionMethodCreator static ctor; every top-level entry consumed, unknown RHS/duplicate/dangling-alias rejected. Commands: addFunction/addPrintFunction/addPrintDataFunction(FunctionCode.NAME) inside static FunctionIdentifier(), with enclosing if-conditions recorded (VARI/VARS gated on UseScopedVariableInstruction); registrations inside loops/else rejected. FunctionCode enum cross-checked: registered subset of enum, enum-minus-registered pinned to {SET,__NULL__}. Statement face = commands union functions (METHOD fallback). Independently verified name-for-name against a Roslyn syntax-tree parse (build/b0-registry-roslyn.json, 2026-11-10).",
            "counts": {
                "v24": {"functions": len(v24f), "commands": len(v24c)},
                "cs_reference": {"functions": len(csf), "commands": len(csc)},
                "em_ee": {"functions": len(emf), "commands": len(emc)},
                "snake": {"functions": len(snf), "commands": len(snc)},
            },
            "caveats": [
                "No pristine honke-1824 tree exists locally. v24 (bugfix-only fork, adds no functions) is the closest 1824-lineage proxy; pristine honke may have a smaller function set (STRJOIN/TOOLTIP_* are early private-edition additions).",
                "reference/emuera-cs is NOT honke-1824. It is an EvilMask EM+EE-lineage decompile (netcoreapp8.0): 271 functions, zero snake-only markers, has HTML_STRINGLEN/MAP/XML/DT and EvilMask references. specs/verify-notes.md '1.824 official source' label is inaccurate.",
                "Version labels do not imply bug-fix inclusion: snake is labelled 1824+v24 but its GetKeywordDictionary is EM+EE-derived and lacks the v23 NICKNAME fix.",
                "Command counts are the explicit possible face (registered via addFunction family, including conditional VARI/VARS). PRINTDATA IS registered (addPrintDataFunction) in all four trees. Only SET (assignment pseudo-statement) and __NULL__ (placeholder) are enum members never registered; see registration.*.enum_unregistered.",
                "Four v24 changelog entries stay intentionally unanchored (shell/internal, not observable): v14 CSV error-line display, v11 shell tuning, v9 internal TYPO, v8.1 error-log filename.",
            ],
            "verified_deltas": {
                "em_ee_vs_v24": {"functions_added": len(em_add_fn), "commands_added": len(em_add_cmd)},
                "snake_only_vs_v24_em_cs": {"functions": len(snake_only_fn), "commands": len(snake_only_cmd)},
                "em_ee_minus_snake_functions": sorted(emf - snf),
                "v24_minus_snake_functions": sorted(v24f - snf),
            },
        },
        "sources": META_SOURCES,
        "profiles": {
            "honke_1824": {"status": "unresolved_local_absent", "note": "Pristine original Emuera 1.824 not present locally; do not assume v24 == honke."},
            "v24": {"functions": sorted(v24f), "commands": sorted(v24c)},
            "em_ee": {"functions": sorted(emf), "commands": sorted(emc)},
            "snake": {"functions": sorted(snf), "commands": sorted(snc)},
            "cs_reference": {"functions": sorted(csf), "commands": sorted(csc)},
        },
        "deltas": {
            "em_ee_vs_v24": {"functions_added": em_add_fn, "functions_removed": sorted(v24f - emf), "commands_added": em_add_cmd, "commands_removed": sorted(v24c - emc)},
            "snake_vs_em_ee": {"functions_added": sorted(snf - emf), "functions_removed": sorted(emf - snf), "commands_added": sorted(snc - emc), "commands_removed": sorted(emc - snc)},
            "snake_only": {"functions": snake_only_fn, "commands": snake_only_cmd},
            "cs_reference_vs_em_ee": {"functions_added": sorted(csf - emf), "functions_removed": sorted(emf - csf), "commands_added": sorted(csc - emc), "commands_removed": sorted(emc - csc)},
        },
        "feature_groups": groups,
        "port_extensions": sorted(PORT_EXTENSIONS),
        "registration": registration,
        "dialect": DIALECT,
        "probe": PROBE,
        "v24_changelog": V24_CHANGELOG,
        "runtime_axes": {
            "v24": "netfx4.8", "cs_reference": "netcoreapp8.0", "em_ee": "net10.0-windows", "snake": "net8.0-windows",
            "note": "String comparison, sort ordering and float subpixel may differ across .NET runtimes; not yet diffed (TODO(verify)).",
        },
        "gate_design": {
            "axes": {
                "feature_profile": "Enable exactly the function/command set of the target profile; absent features must follow the same error path as that engine. EXISTFUNCTION/EXISTVAR are not builtin reflection (they consult user labels only, emuera-em Creator.Method.cs:7229-7272) and are themselves absent in v24.",
                "bug_dialect": "Per-behavior switches independent of profile; see dialect.* and v24_changelog (layer/observable).",
                "runtime": "Substrate semantics (netfx vs net8/net10); see runtime_axes.",
            },
            "precedents_em_ee_config": [
                {"code": "CompatiErrorLine", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:99", "effect": "execute unparsable lines"},
                {"code": "CompatiCALLNAME", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:100", "effect": "use NAME when CALLNAME empty"},
                {"code": "CompatiRAND", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:102", "effect": "RAND behaves like eramaker"},
                {"code": "CompatiDRAWLINE", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:103", "effect": "DRAWLINE always new line"},
                {"code": "CompatiFunctionNoignoreCase", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:104", "effect": "case-sensitive function/attribute names"},
                {"code": "CompatiLinefeedAs1739", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:106", "effect": "pre-1739 wrapping"},
                {"code": "SystemAllowFullSpace", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:105", "effect": "full-width space as whitespace"},
                {"code": "AllowLongInputByMouse", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:108", "effect": "ONEINPUT mouse long input"},
                {"code": "ForbidUpdateCheck", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:122", "effect": "gate EE_UPDATECHECK"},
                {"code": "ValidExtension", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:140", "effect": "allowed LOADTEXT/SAVETEXT extensions"},
                {"code": "SystemSaveInUTF8", "anchor": "reference/emuera-em/Emuera/Runtime/Config/ConfigData.cs:269", "effect": "save in UTF-8"},
            ],
        },
        "todo_verify": [
            "Byte-diff .NET runtime semantics (string.Compare Ordinal, List/Array sort, float subpixel) across netfx4.8 / net8 / net10.",
            "Obtain pristine honke 1.824 source to confirm the true baseline function set (v24 assumes no new functions).",
            "SPRITEANIMEFRAME: port SpriteAnimeFrameMethod.kt claims 4.981-fork provenance but the snake reference tree registers SPRITEANIMEADDFRAME and has no SPRITEANIMEFRAME; resolve which binary the fork doc describes (B3 port-name audit).",
        ],
        # 身份段放在**末尾**：它们是本波新增的两段投影，追加在文件尾才能保证相对基点
        # 「只增不改」（契约 CONTRACT-ENGCPL §2 G2）。事实本身在 input yaml。
        "targets": TARGETS,
        "corpora": CORPORA,
    }


# ---------------- 确定性 YAML 发射（stdlib-only） ----------------
_SAFE_BARE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*$')
_SAFE_KEY = re.compile(r'^[A-Za-z0-9_.\-]+$')
_RESERVED = {"true", "false", "null", "yes", "no", "on", "off", "y", "n", "~"}


def _scalar(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    s = str(v)
    if _SAFE_BARE.match(s) and s.lower() not in _RESERVED:
        return s
    return "'" + s.replace("'", "''") + "'"


def _key(k: str) -> str:
    return k if _SAFE_KEY.match(k) else "'" + k.replace("'", "''") + "'"


def _emit(obj, indent: int) -> list[str]:
    pad = "  " * indent
    out: list[str] = []
    for k, v in obj.items():
        key = _key(str(k))
        if isinstance(v, dict):
            if not v:
                out.append(f"{pad}{key}: {{}}")
            else:
                out.append(f"{pad}{key}:")
                out.extend(_emit(v, indent + 1))
        elif isinstance(v, list):
            if not v:
                out.append(f"{pad}{key}: []")
            else:
                out.append(f"{pad}{key}:")
                out.extend(_emit_list(v, indent + 1))
        else:
            out.append(f"{pad}{key}: {_scalar(v)}")
    return out


def _emit_list(items, indent: int) -> list[str]:
    pad = "  " * indent
    out: list[str] = []
    for it in items:
        if isinstance(it, dict) and it:
            sub = _emit(it, indent + 1)
            first = sub[0][len("  " * (indent + 1)):]
            out.append(f"{pad}- {first}")
            out.extend(sub[1:])
        elif isinstance(it, list) and it:
            out.append(f"{pad}-")
            out.extend(_emit_list(it, indent + 1))
        else:
            out.append(f"{pad}- {_scalar(it)}")
    return out


def _validate_names(names, label: str) -> None:
    if not isinstance(names, list) or any(
            not isinstance(n, str) or re.fullmatch(r"[A-Z_][A-Z0-9_]*", n) is None for n in names):
        raise ValueError(f"{label}: expected a list of uppercase names")
    if names != sorted(set(names)):
        raise ValueError(f"{label}: names must be sorted and unique")


def _validate_identity(doc: dict) -> None:
    """targets / corpora 两段的形状闸（生成物与 input yaml 同一口径）。"""
    corpora = doc["corpora"]
    if not isinstance(corpora, dict) or not corpora:
        raise ValueError("corpora: expected a non-empty mapping")
    for name, entry in corpora.items():
        if not isinstance(entry, dict) or set(entry) != {"root_dir", "marker_file", "retired"}:
            raise ValueError(f"corpora.{name}: expected exactly root_dir/marker_file/retired")
        if not isinstance(entry["root_dir"], str) or not entry["root_dir"]:
            raise ValueError(f"corpora.{name}.root_dir: expected a non-empty string")
        if entry["marker_file"] is not None and not isinstance(entry["marker_file"], str):
            raise ValueError(f"corpora.{name}.marker_file: expected a string or null")
        if type(entry["retired"]) is not bool:
            raise ValueError(f"corpora.{name}.retired: expected true/false")
    families = {family for _engine, _prefix, family in _KOTLIN_PROFILES}
    targets = doc["targets"]
    if not isinstance(targets, dict) or not targets:
        raise ValueError("targets: expected a non-empty mapping")
    defaults: list[str] = []
    for name, entry in targets.items():
        if not isinstance(entry, dict):
            raise ValueError(f"targets.{name}: expected a mapping")
        extra = sorted(set(entry) - {"engine", "stamp_version", "family", "corpus", "default",
                                     "full_statement_coverage"})
        if extra:
            raise ValueError(f"targets.{name}: unknown keys {extra}")
        for key in ("engine", "stamp_version", "family", "corpus"):
            if not isinstance(entry.get(key), str) or not entry[key]:
                raise ValueError(f"targets.{name}.{key}: expected a non-empty string")
        if entry["engine"] not in ENGINE_FILES:
            raise ValueError(f"targets.{name}.engine: {entry['engine']!r} is not a known engine")
        if entry["family"] not in families:
            raise ValueError(f"targets.{name}.family: {entry['family']!r} is not a Kotlin family")
        if entry["corpus"] not in corpora:
            raise ValueError(f"targets.{name}.corpus: {entry['corpus']!r} absent from corpora")
        for flag in ("default", "full_statement_coverage"):
            if type(entry.get(flag, False)) is not bool:
                raise ValueError(f"targets.{name}.{flag}: expected true/false")
        if entry.get("default", False):
            defaults.append(name)
    if len(defaults) != 1:
        raise ValueError(f"targets: exactly one default: true expected, got {defaults}")


def _validate_keywords(doc: dict) -> None:
    keywords = doc["dialect"]["getnum_nickname_mastername"]["keywords"]
    if not isinstance(keywords, dict) or set(keywords) != {"routed", "gated"}:
        raise ValueError("dialect.getnum_nickname_mastername.keywords: expected routed/gated")
    for side in ("routed", "gated"):
        names = keywords[side]
        if not isinstance(names, list) or not names:
            raise ValueError(f"dialect.getnum_nickname_mastername.keywords.{side}: expected a non-empty list")
        for n in names:
            if not isinstance(n, str) or re.fullmatch(r"[A-Z_][A-Z0-9_]*", n) is None:
                raise ValueError(
                    f"dialect.getnum_nickname_mastername.keywords.{side}: not an uppercase name: {n!r}")
        if len(set(names)) != len(names):
            raise ValueError(f"dialect.getnum_nickname_mastername.keywords.{side}: duplicate name")


def validate_doc(doc: dict) -> None:
    for engine in ENGINE_FILES:
        for kind in ("functions", "commands"):
            _validate_names(doc["profiles"][engine][kind], f"profiles.{engine}.{kind}")
        compare = doc["dialect"]["string_compare_ge_le"]["values"][engine]
        nickname = doc["dialect"]["getnum_nickname_mastername"]["values"][engine]
        relation = doc["dialect"]["relation_mastername"]["values"][engine]
        if not isinstance(compare, str) or compare not in _STRING_COMPARE:
            raise ValueError(f"dialect.string_compare_ge_le.{engine}: unknown value {compare!r}")
        if type(nickname) is not bool:
            raise ValueError(f"dialect.getnum_nickname_mastername.{engine}: expected True/False, got {nickname!r}")
        if type(relation) is not bool:
            raise ValueError(f"dialect.relation_mastername.{engine}: expected True/False, got {relation!r}")
    _validate_names(doc["port_extensions"], "port_extensions")
    _validate_keywords(doc)
    _validate_identity(doc)


def yaml_dump(doc: dict) -> str:
    validate_doc(doc)
    return "\n".join(_emit(doc, 0)) + "\n"


def _kotlin_bool(value: bool) -> str:
    return "true" if value else "false"


def kotlin_dump(doc: dict) -> str:
    validate_doc(doc)
    lines = [
        "// GENERATED by devtools/gen_engine_profiles.py --emit-kotlin <path>; DO NOT EDIT.",
        "package com.eratw.port.core.compat",
        "",
        "internal object EngineProfileData {",
    ]
    for engine, prefix, _family in _KOTLIN_PROFILES:
        for kind in ("functions", "commands"):
            lines.append(f"    val {prefix}_{kind.upper()}: Set<String> = setOf(")
            lines.extend(f'        "{name}",' for name in doc["profiles"][engine][kind])
            lines.extend(["    )", ""])
    lines.append("    val PORT_EXTENSIONS: Set<String> = setOf(")
    lines.extend(f'        "{name}",' for name in doc["port_extensions"])
    lines.extend(["    )", ""])
    for kind in ("functions", "commands"):
        lines.append(f"    fun {kind}Of(family: EngineFamily): Set<String> = when (family) {{")
        for _engine, prefix, family in _KOTLIN_PROFILES:
            lines.append(f"        EngineFamily.{family} -> {prefix}_{kind.upper()}")
        lines.extend(["    }", ""])
    lines.append("    val DIALECT: Map<EngineFamily, EngineDialect> = mapOf(")
    for engine, _prefix, family in _KOTLIN_PROFILES:
        compare = _STRING_COMPARE[doc["dialect"]["string_compare_ge_le"]["values"][engine]]
        nickname = _NICKNAME_KEYWORDS[doc["dialect"]["getnum_nickname_mastername"]["values"][engine]]
        relation = _RELATION_MASTERNAME[doc["dialect"]["relation_mastername"]["values"][engine]]
        lines.extend([
            f"        EngineFamily.{family} to EngineDialect(",
            f"            stringCompare = StringCompareDialect.{compare},",
            f"            nicknameKeywords = NicknameKeywordPolicy.{nickname},",
            f"            relationMastername = RelationMasternamePolicy.{relation},",
            "        ),",
        ])
    lines.extend(["    )", ""])
    keywords = doc["dialect"]["getnum_nickname_mastername"]["keywords"]
    for const, side in (("RELATION_ROUTED_KEYWORDS", "routed"), ("RELATION_GATED_KEYWORDS", "gated")):
        lines.append(f"    val {const}: List<String> = listOf(")
        lines.extend(f'        "{name}",' for name in keywords[side])
        lines.extend(["    )", ""])
    lines.append("    val TARGETS: List<TargetSpec> = listOf(")
    for profile_id, entry in doc["targets"].items():
        lines.extend([
            "        TargetSpec(",
            f'            profileId = "{profile_id}",',
            f'            engine = "{entry["engine"]}",',
            f'            version = "{entry["stamp_version"]}",',
            f'            family = EngineFamily.{entry["family"]},',
            f'            corpusVersionId = "{entry["corpus"]}",',
            f'            fullStatementCoverage = {_kotlin_bool(entry.get("full_statement_coverage", False))},',
            "        ),",
        ])
    lines.extend(["    )", ""])
    default_id = [name for name, entry in doc["targets"].items() if entry.get("default", False)][0]
    lines.append(f'    const val DEFAULT_TARGET_PROFILE_ID = "{default_id}"')
    lines.extend(["", "    val CORPORA: Map<String, CorpusSpec> = mapOf("])
    for corpus_id, entry in doc["corpora"].items():
        marker = entry["marker_file"]
        lines.extend([
            f'        "{corpus_id}" to CorpusSpec(',
            f'            id = "{corpus_id}",',
            f'            rootDir = "{entry["root_dir"]}",',
            "            markerFile = " + ("null," if marker is None else f'"{marker}",'),
            f'            retired = {_kotlin_bool(entry["retired"])},',
            "        ),",
        ])
    lines.extend(["    )", ""])
    lines.append("}")
    # 档位/语料规格放在 object **外面**：都是 public 数据类，proc / 测试侧要用 typealias 或
    # 直接引用（公开 API 不得暴露 internal object 的成员）。
    lines.extend([
        "// 档位：印章可写的三串 + 解析结果。family 直接用同包的 EngineFamily；",
        "// corpusVersionId 是字符串（如 V4_981），由 proc 侧 CorpusVersion 收口 ——",
        "// 生成物不得 import core.proc，避免 compat 反向依赖 proc。",
        "data class TargetSpec(",
        "    val profileId: String,",
        "    val engine: String,",
        "    val version: String,",
        "    val family: EngineFamily,",
        "    val corpusVersionId: String,",
        "    val fullStatementCoverage: Boolean = false,",
        ")",
        "",
        "// 语料身份。markerFile 相对语料根，null = 无认根标记（只认目录名）。",
        "data class CorpusSpec(",
        "    val id: String,",
        "    val rootDir: String,",
        "    val markerFile: String?,",
        "    val retired: Boolean,",
        ")",
        "",
    ])
    return "\n".join(lines)


def _comparison_lines(text: str, ignore_date: bool) -> list[tuple[int, str]]:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return [(i, line) for i, line in enumerate(text.splitlines(keepends=True), 1)
            if not (ignore_date and line.strip().startswith("generated_at:"))]


def check_output(path: Path, text: str, *, ignore_date: bool = False, quiet: bool = False) -> bool:
    try:
        existing = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        print(f"MISSING: {path}", file=sys.stderr)
        return False
    except (OSError, UnicodeError) as exc:
        print(f"CANNOT READ: {path}: {exc}", file=sys.stderr)
        return False
    old_lines = _comparison_lines(existing, ignore_date)
    new_lines = _comparison_lines(text, ignore_date)
    for old, new in zip_longest(old_lines, new_lines):
        if old is not None and new is not None and old[1] == new[1]:
            continue
        old_number = old[0] if old else len(existing.splitlines()) + 1
        new_number = new[0] if new else len(text.splitlines()) + 1
        old_text = repr(old[1]) if old else "<EOF>"
        new_text = repr(new[1]) if new else "<EOF>"
        print(f"STALE: {path} differs from source registries; regenerate this output", file=sys.stderr)
        print(f"  first diff at line {old_number} (existing line {old_number}, generated line {new_number}):\n"
              f"    - {old_text[:160]}\n    + {new_text[:160]}", file=sys.stderr)
        return False
    if not quiet:
        print(f"OK: {path} is up to date")
    return True


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="生成/审计 specs/engine-profiles.yaml")
    ap.add_argument("--reference-root", default=None, help="共享根（含 reference/）；默认按主机名解析")
    ap.add_argument("--out", default=None, help="输出路径；默认 <repo>/specs/engine-profiles.yaml")
    ap.add_argument("--emit-kotlin", default=None, metavar="PATH", help="同轮生成/审计 Kotlin；未传时仅处理 YAML")
    ap.add_argument("--check", action="store_true", help="只审计：过期或缺失非零退出，不写文件")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)

    repo = Path(__file__).resolve().parent.parent
    out = Path(args.out) if args.out else repo / OUT_REL
    kotlin_out = Path(args.emit_kotlin) if args.emit_kotlin else None
    if kotlin_out is not None and (out.resolve() == kotlin_out.resolve() or
                                  (out.exists() and kotlin_out.exists() and out.samefile(kotlin_out))):
        ap.error("--out and --emit-kotlin must target different files")
    root = resolve_reference_root(args.reference_root)

    sets = extract_all(root)
    doc = build_doc(sets)
    try:
        outputs = [(out, yaml_dump(doc), True)]
        if kotlin_out is not None:
            outputs.append((kotlin_out, kotlin_dump(doc), False))
    except (KeyError, TypeError, ValueError) as exc:
        raise SystemExit(f"Invalid engine profile document: {exc}") from exc
    for path, text, _ignore_date in outputs:
        if any(s in text for s in ("/*", "*/")):
            raise SystemExit(f"Generated output {path} contains forbidden block-comment delimiter")

    if args.check:
        results = [check_output(path, text, ignore_date=ignore_date, quiet=args.quiet)
                   for path, text, ignore_date in outputs]
        return 0 if all(results) else 1

    # 可选：PyYAML 在则做一次解析校验
    try:
        import yaml  # type: ignore
        if yaml.safe_load(outputs[0][1]) != doc:
            raise SystemExit("Generated YAML did not round-trip to the engine profile document")
        validated = "PyYAML OK"
    except ImportError:
        validated = "PyYAML unavailable (skipping parse check)"

    for path, text, _ignore_date in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        if not args.quiet:
            print("WROTE", path)

    if not args.quiet:
        c = doc["meta"]["counts"]
        print("  counts:", {k: (v["functions"], v["commands"]) for k, v in c.items()})
        print("  em_ee_add:", c["em_ee"]["functions"] - c["v24"]["functions"], "fn /",
              c["em_ee"]["commands"] - c["v24"]["commands"], "cmd ;", validated)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
