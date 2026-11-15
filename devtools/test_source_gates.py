#!/usr/bin/env python
"""数据源闸回归（lane erdpkg，2026-11-04）—— `emit_update.py` 三道新闸 + `pack_release.py` 的随包 ERD 体检。

用法（在 eratw-android 下）::

    python devtools/test_source_gates.py

不依赖 Gradle / Android SDK / 网络：全部在临时目录里合成小根与小 manifest，覆盖形状：

- erd_gate：包与根逐条相等（绿）/ 缺表（红）/ 多余表（红）/ 零表根只提示不判红；
- erbc_pack_gate：不带 erbc 放行（绿）/ 缺 manifest（红）/ 受限档（红）/ 源被改、源缺失、
  语料新增未编（红）/ GOLDEN_ 采集副本不误报（绿）/ 含空格相对路径解析（绿）/
  --allow-stale-erbc 把新鲜度问题降级放行；
- data_source_gate：4.981 双根自洽（绿）/ 退役根（红）/ 放行位（绿）/ 语料与根混装（红）
  —— 判据来自 pack_release，本处用合成根替换其共享根解析；
- pack_release.erd_bundling_problems：entries-ERB 与磁盘一致（绿）/ 数字不符（红）/ 缺行（红）。

退出码：0 = 全部通过，1 = 有断言失败。
"""

from __future__ import annotations

import hashlib
import os
import shutil
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import emit_update as eu  # noqa: E402
import pack_release as pr  # noqa: E402

FAILS = []


def check(label: str, problems: list, want_red: bool) -> None:
    red = bool(problems)
    if red != want_red:
        FAILS.append(f"{label}：期望{'红' if want_red else '绿'}，实际 {'红 ' + str(problems[:2]) if red else '绿'}")
        print(f"[FAIL] {label}")
    else:
        print(f"[ ok ] {label}")


def sha256_of(path: str) -> str:
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def write_manifest(root: str, entries: list, build_mode: str = "full") -> None:
    """entries = [(rel, path)]；按 ErbcArtifacts 的行式写 manifest（source/artifact）。"""
    lines = ["manifest-format 2\n", "erbcc 0.1.0-SNAPSHOT\n",
             f"build-mode {build_mode}\n", f"corpus-root {os.path.join(root, 'ERB')}\n"]
    for rel, path in entries:
        lines.append(f"source {rel} {os.path.getsize(path)} {sha256_of(path)}\n")
    prog = os.path.join(root, "erbc.v1", "program.erbc")
    lines.append(f"artifact erbc.v1/program.erbc {os.path.getsize(prog)} {sha256_of(prog)}\n")
    with open(os.path.join(root, "erbc.v1", "manifest.txt"), "w", encoding="utf-8") as fh:
        fh.writelines(lines)


def make_root(with_erds: bool) -> str:
    root = tempfile.mkdtemp(prefix="erdpkg-")
    os.makedirs(os.path.join(root, "ERB", "sub"))
    os.makedirs(os.path.join(root, "erbc.v1"))
    open(os.path.join(root, "ERB", "A.ERB"), "wb").write(b"#DIM A\n")
    open(os.path.join(root, "ERB", "sub", "B.ERH"), "wb").write(b";b\n")
    open(os.path.join(root, "erbc.v1", "program.erbc"), "wb").write(b"prog")
    if with_erds:
        os.makedirs(os.path.join(root, "ERB", "魔改内容"))
        open(os.path.join(root, "ERB", "魔改内容", "X@2.ERD"), "wb").write(b"erd")
        open(os.path.join(root, "ERB", "sub", "Y.erd"), "wb").write(b"erd2")  # 小写后缀同样算表
    return root


def idx_of(root: str) -> dict:
    """按 stage_assets_zip 的口径造索引（只放本测试需要的段：ERD 与 erbc.v1）。"""
    out = {}
    for dirpath, dirnames, filenames in os.walk(os.path.join(root, "ERB")):
        for f in filenames:
            if f.upper().endswith(".ERD"):
                arc = os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, "/")
                out[arc] = {"size": 0, "sha256": "0" * 64}
    for name in ("program.erbc", "manifest.txt"):
        p = os.path.join(root, "erbc.v1", name)
        if os.path.isfile(p):
            out[f"erbc.v1/{name}"] = {"size": os.path.getsize(p), "sha256": sha256_of(p)}
    return out


def test_erd_gate() -> None:
    root = make_root(with_erds=True)
    try:
        check("erd_gate：包与根逐条相等", eu.erd_gate(root, idx_of(root)), want_red=False)
        idx_missing = {k: v for k, v in idx_of(root).items() if "X@2" not in k}
        check("erd_gate：包里缺一张根里的表", eu.erd_gate(root, idx_missing), want_red=True)
        dirty = idx_of(root)
        dirty["ERB/GONE.ERD"] = {"size": 0, "sha256": "0" * 64}
        check("erd_gate：包里有根里已不存在的表", eu.erd_gate(root, dirty), want_red=True)
        bare = make_root(with_erds=False)
        try:
            check("erd_gate：零表根（包与根都无表）只提示不判红", eu.erd_gate(bare, {}), want_red=False)
        finally:
            shutil.rmtree(bare, ignore_errors=True)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_erbc_pack_gate() -> None:
    root = make_root(with_erds=True)
    try:
        entries = [("A.ERB", os.path.join(root, "ERB", "A.ERB")),
                   ("sub/B.ERH", os.path.join(root, "ERB", "sub", "B.ERH"))]
        check("erbc_pack_gate：数据包不带 erbc（合法形状）",
              eu.erbc_pack_gate(root, "", {}, "", False), want_red=False)
        check("erbc_pack_gate：带 erbc 却缺 manifest.txt",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx_of(root), "", False), want_red=True)
        check("erbc_pack_gate：缺 manifest 属形状判据，--allow-stale-erbc 也挡不住",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx_of(root), "", True), want_red=True)
        write_manifest(root, entries)
        idx = idx_of(root)  # 索引必须按**写盘后的产物目录**算（manifest.txt 此刻才存在）
        check("erbc_pack_gate：全对齐为绿",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=False)
        # 受限档
        write_manifest(root, entries, build_mode="limited")
        check("erbc_pack_gate：受限档产物不得随包",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=True)
        write_manifest(root, entries)
        # 语料新增（GOLDEN_ 副本排除）
        open(os.path.join(root, "ERB", "C.ERB"), "wb").write(b"new\n")
        open(os.path.join(root, "ERB", "GOLDEN_D.ERB"), "wb").write(b"golden\n")
        check("erbc_pack_gate：新增源未编进产物（红）且 GOLDEN_ 不误报",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=True)
        entries.append(("C.ERB", os.path.join(root, "ERB", "C.ERB")))
        write_manifest(root, entries)
        check("erbc_pack_gate：登记 C.ERB 后转绿（GOLDEN_ 仍不挡）",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=False)
        # 源被改 / 源没了
        open(os.path.join(root, "ERB", "C.ERB"), "wb").write(b"changed!!\n")
        check("erbc_pack_gate：源文件已改动但产物未重编",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=True)
        check("erbc_pack_gate：--allow-stale-erbc 降级放行",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", True), want_red=False)
        os.remove(os.path.join(root, "ERB", "C.ERB"))
        open(os.path.join(root, "ERB", "C.ERB"), "wb").write(b"new\n")
        os.remove(os.path.join(root, "ERB", "A.ERB"))
        check("erbc_pack_gate：清单登记的源文件已不存在",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, "", False), want_red=True)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    # 与整包对拍（新小根，manifest 全绿）
    root = make_root(with_erds=False)
    try:
        entries = [("A.ERB", os.path.join(root, "ERB", "A.ERB")),
                   ("sub/B.ERH", os.path.join(root, "ERB", "sub", "B.ERH"))]
        write_manifest(root, entries)
        idx = idx_of(root)
        prog_bytes = open(os.path.join(root, "erbc.v1", "program.erbc"), "rb").read()
        apk_same = os.path.join(root, "apk-same.apk")
        with zipfile.ZipFile(apk_same, "w") as zf:
            zf.writestr("assets/erbc.v1/program.erbc", prog_bytes)
        check("erbc_pack_gate：数据包 erbc 与整包同一次编译（绿）",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, apk_same, False), want_red=False)
        apk_diff = os.path.join(root, "apk-diff.apk")
        with zipfile.ZipFile(apk_diff, "w") as zf:
            zf.writestr("assets/erbc.v1/program.erbc", b"other-build")
        check("erbc_pack_gate：数据包 erbc 与整包不是同一次编译（红）",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, apk_diff, False), want_red=True)
        check("erbc_pack_gate：APK 对拍不符可被 --allow-stale-erbc 放行（内测语义）",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx, apk_diff, True), want_red=False)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    # 含空格相对路径（真语料里 `DLC/DLC FUNCTION.ERB` 就是这个形状）
    root = make_root(with_erds=False)
    try:
        os.remove(os.path.join(root, "ERB", "A.ERB"))
        open(os.path.join(root, "ERB", "DLC FUNCTION.ERB"), "wb").write(b"x y z\n")
        entries = [("DLC FUNCTION.ERB", os.path.join(root, "ERB", "DLC FUNCTION.ERB")),
                   ("sub/B.ERH", os.path.join(root, "ERB", "sub", "B.ERH"))]
        write_manifest(root, entries)
        check("erbc_pack_gate：含空格的相对路径解析正确（绿）",
              eu.erbc_pack_gate(root, os.path.join(root, "erbc.v1"), idx_of(root), "", False),
              want_red=False)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_data_source_gate() -> None:
    fake = tempfile.mkdtemp(prefix="erdpkg-roots-")
    try:
        new_root = os.path.join(fake, pr.CURRENT_CORPUS_DIR)
        old_root = os.path.join(fake, pr.RETIRED_CORPUS_DIR)
        os.makedirs(os.path.join(new_root, "ERB"))
        os.makedirs(os.path.join(old_root, "ERB"))
        saved_root_fn = pr.eratw_root
        saved_env = {k: os.environ.get(k) for k in ("ERATW_CORPUS", "ERATW_GAMEDIR")}
        pr.eratw_root = lambda: fake  # 合成共享根替换（判据逻辑本体不动）
        try:
            os.environ.pop("ERATW_CORPUS", None)
            os.environ["ERATW_GAMEDIR"] = new_root
            check("data_source_gate：现行根自洽（绿）", eu.data_source_gate(new_root, False), want_red=False)
            os.environ["ERATW_GAMEDIR"] = old_root
            check("data_source_gate：退役根（红）", eu.data_source_gate(old_root, False), want_red=True)
            check("data_source_gate：--allow-retired-corpus 放行", eu.data_source_gate(old_root, True), want_red=False)
            os.environ["ERATW_CORPUS"] = os.path.join(new_root, "ERB")
            check("data_source_gate：CORPUS 新根而 GAMEDIR 旧根（混装，红）",
                  eu.data_source_gate(old_root, True), want_red=True)
        finally:
            pr.eratw_root = saved_root_fn
            for k, v in saved_env.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
    finally:
        shutil.rmtree(fake, ignore_errors=True)


def test_pack_release_erd() -> None:
    root = make_root(with_erds=True)
    try:
        rep = {"game-root": root, "entries-ERB": "2"}
        check("erd_bundling_problems：entries-ERB 与磁盘一致（绿）", pr.erd_bundling_problems(rep), want_red=False)
        rep["entries-ERB"] = "1"
        check("erd_bundling_problems：数字与磁盘不符（红）", pr.erd_bundling_problems(rep), want_red=True)
        rep.pop("entries-ERB")
        check("erd_bundling_problems：缺 entries-ERB 行（红）", pr.erd_bundling_problems(rep), want_red=True)
        rep2 = {"game-root": make_root(with_erds=False)}
        check("erd_bundling_problems：不随资源（game-root 为 -）不抢话",
              pr.erd_bundling_problems({"game-root": "-"}), want_red=False)
        shutil.rmtree(rep2["game-root"], ignore_errors=True)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    for fn in (test_erd_gate, test_erbc_pack_gate, test_data_source_gate, test_pack_release_erd):
        fn()
    if FAILS:
        print(f"\n{len(FAILS)} 条断言失败：")
        for f in FAILS:
            print("  -", f)
        return 1
    print("\n数据源闸回归全部通过")
    return 0


# ---------------------------------------------------------------------------
# lane C-RES2 追加段开始（M4-3 业务词 grep 门禁，CONTRACT-RT2-C-RES2 §3-c）。
#
# 追加纪律：本段对既有内容**零字节改动** —— 上方 check / sha256_of / write_manifest /
# make_root / idx_of / 各 test_ 函数与 main 原样保留，结尾的 __main__ 守卫也原样保留；
# 经下方同名 main 包装（先原样转发既有编排，再追加业务词门禁回归）把新 gate 接进
# 同一退出码口径（FAILS 聚合 + 0/1 退出码）。
#
# 门禁语义（era-arch-v61u.md §19.3 引擎零业务耦合 / §23.2 F1 硬约束；词表源 §16/§19/§23.2）：
# 扫 core-vm/src/main 与 mod-api/src/main 的 .kt/.java，主源集**代码行**里不得出现
# 家族系统业务词。mod-family（业务本体）、app（装配点名 FamilyMod / eratw.family 合法）、
# tools/modgen*（按声明出码）不在扫描目录里，属结构性排除；一切测试源集与 build 产物
# 由 EXCLUDED 路径部件再拦一遍（双保险，防将来有人把测试目录并进扫描面）。
#
# 匹配口径：先剥注释再匹配 —— 块注释（含 KDoc）整段剥除、行注释剥尾。理由：业务零耦合的
# 铁律正文与边界讨论（EntityContracts 的禁词声明、SaveService 的「禁用 school.mod」判据
# 引用等）合法出现在注释里，门禁只拦业务词进**代码行**（标识符、字符串字面量、schema 名）。
# 剥注释只会漏报不会误报（字符串字面量里出现 // 的行会被截尾，属可接受漏报，已在此声明）。
#
# 基线（2026-11-12 全树逐词 grep，--color=never，剥注释后逐行核对）：
# - 词表里除白名单条目外**全部零命中**；
# - 既有合法命中全部显式登记在 BUSINESS_WORD_WHITELIST（词 × 路径/行标记 + 理由）；
# - 通用词 child / parent 被剔除不入表（AST 父子指针与 XPath 轴在编译器语境是通用词汇，
#   合法命中 163+ 处，白名单登记无意义；家族语义由 daughter/mother/sister/子嗣/母女 等
#   专名词承担）。
# ---------------------------------------------------------------------------

BUSINESS_WORD_SCAN_DIRS = ("core-vm/src/main", "mod-api/src/main")

# 路径含这些部件的一律跳过（在扫描目录内的测试源集与产物目录；大小写按仓内实形）。
BUSINESS_WORD_EXCLUDED_PATH_PARTS = (
    "src/test", "src/androidTest", "src/commonTest", "src/jvmTest", "/build/",
)

# (词, 匹配模式)。Latin 词一律带 \b 词界：word = 全词匹配；prefix = 词首匹配（拦
# daughterCount / FamilyTree 这类驼峰泄漏）。prefix 的自有命中走白名单精确豁免。
BUSINESS_WORD_LATIN = (
    ("kinship", "word"), ("succession", "word"), ("lineage", "word"), ("bloodline", "word"),
    ("genealogy", "word"), ("daughter", "prefix"), ("mother", "prefix"), ("father", "prefix"),
    ("sister", "prefix"), ("brother", "prefix"), ("son", "prefix"), ("heir", "prefix"),
    ("ancestor", "prefix"), ("sibling", "prefix"), ("descendant", "prefix"), ("offspring", "prefix"),
    ("adopt", "prefix"), ("family", "prefix"), ("clan", "prefix"), ("school", "prefix"),
)

# 中文/日文家族业务词（子串匹配；多字词无词界问题，单字「娘/母/父」之类碰撞面大不入表）。
BUSINESS_WORD_CJK = (
    "家族", "子嗣", "母女", "姐妹", "姉妹", "血亲", "血縁", "继承", "相続", "伦理", "教育",
    "女儿", "母亲", "父亲", "兄妹", "兄弟", "血缘", "养育", "抚养", "收养", "领养", "家系",
    "世袭", "亲戚", "親族", "子女", "子供", "養子", "義母", "婚姻", "婚約", "配偶",
)

# 白名单：(词, 路径包含子串或 None=任意文件, 行包含子串或 None=整行豁免, 理由)。
# 全部来自 2026-11-12 全树基线的既有合法命中，逐条带理由（契约 §1：不许空词表交差，
# 有命中的词要么剔除要么登记白名单）。
BUSINESS_WORD_WHITELIST = (
    ("ancestor", "XmlPath.kt", None, "XPath 轴名（XML 规范词汇），非家族业务"),
    ("sibling", "XmlPath.kt", None, "XPath 轴名（XML 规范词汇），非家族业务"),
    ("sibling", "ErbcStream.kt", "sibling(", "erbc 字节窗伴生切片函数，非家族业务"),
    ("descendant", "XmlPath.kt", None, "XPath 轴名（XML 规范词汇），非家族业务"),
    ("descendant", None, "descendantsByName", "XML DOM 子孙节点查询方法名，非家族业务"),
    ("adopt", None, "adoptSlice", "引擎自有 API：编译期函数切片收养（FunctionSlice），非家族收养语义"),
    ("adopt", None, "adoptFunction", "引擎自有 API：函数切片收养入口，非家族收养语义"),
    ("adopt", None, "adoptHandoffState", "引擎自有 API：进程状态交接收养，非家族收养语义"),
    ("family", "FontSidecar.kt", None, "font family（字体家族，排版域），非家族系统业务"),
    ("family", "EngineProfileData.kt", None, "引擎兼容档族参数/查询（engine compat family），非家族系统业务"),
    ("family", "EngineSurface.kt", None, "引擎兼容档族（engine compat family），非家族系统业务"),
    ("family", "CompatCommandAudit.kt", None, "引擎兼容档族（engine compat family），非家族系统业务"),
    ("family", "EngineProfiles.kt", None, "引擎兼容档族（engine compat family），非家族系统业务"),
    ("family", "EngineStamp.kt", None, "引擎兼容档族（engine compat family），非家族系统业务"),
    ("family", "GameSession.kt", "CompatCommandAudit", "引擎兼容档审计调用点，非家族系统业务"),
    ("family", "GameSession.kt", "CompatProfileMismatchException", "引擎兼容档失配异常，非家族系统业务"),
    ("family", None, "tableFamily", "GDI+ 字体族链字段（排版域），非家族系统业务"),
    ("家族", "FontSidecar.kt", None, "字体家族文案（font family），非家族系统业务"),
)

# 需要文件级正则支持；本脚本此前未 import re，这里补（不动上方既有 import 区）。
import re as _re_for_gate  # noqa: E402

_BLOCK_COMMENT_FOR_GATE = _re_for_gate.compile(r"/\*.*?\*/", _re_for_gate.DOTALL)
_LINE_COMMENT_FOR_GATE = _re_for_gate.compile(r"//.*")


def _strip_comments_for_word_gate(text: str) -> str:
    """剥块注释（含 KDoc）与行注释；只服务业务词门禁（不解释字符串字面量，见段首声明）。"""
    text = _BLOCK_COMMENT_FOR_GATE.sub(" ", text)
    text = _LINE_COMMENT_FOR_GATE.sub("", text)
    return text


def _business_word_patterns() -> list:
    out = []
    for word, mode in BUSINESS_WORD_LATIN:
        if mode == "word":
            out.append((word, _re_for_gate.compile(r"\b" + _re_for_gate.escape(word) + r"\b", _re_for_gate.IGNORECASE)))
        else:
            out.append((word, _re_for_gate.compile(r"\b" + _re_for_gate.escape(word) + r"\w*", _re_for_gate.IGNORECASE)))
    for word in BUSINESS_WORD_CJK:
        out.append((word, _re_for_gate.compile(_re_for_gate.escape(word))))
    return out


_BUSINESS_WORD_PATTERNS = _business_word_patterns()


def business_word_gate(repo_root: str) -> list:
    """扫 core-vm 与 mod-api 主源集的代码行，返回业务词命中问题列表（空 = 绿）。

    每条问题形如 `相对路径:行号: 业务词「x」: 行内容截断`；白名单条目命中不进列表。
    """
    problems = []
    for scan_dir in BUSINESS_WORD_SCAN_DIRS:
        base = os.path.join(repo_root, scan_dir.replace("/", os.sep))
        if not os.path.isdir(base):
            problems.append(f"扫描目录缺失: {scan_dir}")
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            rel_dir = os.path.relpath(dirpath, repo_root).replace(os.sep, "/")
            if any(part in rel_dir + "/" for part in BUSINESS_WORD_EXCLUDED_PATH_PARTS):
                dirnames[:] = []
                continue
            for name in filenames:
                if not name.endswith((".kt", ".java")):
                    continue
                path = os.path.join(dirpath, name)
                rel_path = os.path.relpath(path, repo_root).replace(os.sep, "/")
                if any(part in "/" + rel_path for part in BUSINESS_WORD_EXCLUDED_PATH_PARTS):
                    continue
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as fh:
                        text = fh.read()
                except OSError as exc:
                    problems.append(f"{rel_path}: 读取失败 {exc}")
                    continue
                code = _strip_comments_for_word_gate(text)
                for lineno, line in enumerate(code.splitlines(), 1):
                    for word, pattern in _BUSINESS_WORD_PATTERNS:
                        if not pattern.search(line):
                            continue
                        exempt = any(
                            w == word
                            and (p is None or p in rel_path)
                            and (l is None or l in line)
                            for (w, p, l, _r) in BUSINESS_WORD_WHITELIST
                        )
                        if exempt:
                            continue
                        problems.append(f"{rel_path}:{lineno}: 业务词「{word}」: {line.strip()[:100]}")
    return problems


def test_business_word_gate() -> None:
    """业务词门禁的独立回归（契约 §3-c）：合成小根绿/红两例 + 真树零命中。

    合成根按扫描目录形状摆（core-vm/src/main + mod-api/src/main），覆盖：
    无业务词绿 / 注释命中不判 / 标识符命中红 / 字符串字面量命中红 / 白名单豁免绿 /
    mod-family 与测试源集排除绿；最后对**真实源树**跑一遍（本脚本常态化运行的门禁本体）。
    """
    root = tempfile.mkdtemp(prefix="bizword-")
    try:
        main_src = os.path.join(root, "core-vm", "src", "main", "kotlin", "com", "example")
        os.makedirs(main_src)
        os.makedirs(os.path.join(root, "mod-api", "src", "main", "kotlin"))
        # 绿例：无业务词；业务词只出现在 KDoc 与行注释（注释剥除口径）。
        with open(os.path.join(main_src, "Clean.kt"), "w", encoding="utf-8") as fh:
            fh.write(
                "package com.example\n"
                "\n"
                "/** KDoc 里提 子嗣 与 daughter 不算命中（注释剥除） */\n"
                "// 行注释 daughter 也不算\n"
                "class Clean {\n"
                "    val level = 1\n"
                "}\n"
            )
        check("business_word_gate：合成绿例（无业务词 + 注释命中不判）",
              business_word_gate(root), want_red=False)
        # 红例 1：标识符带家族词（驼峰泄漏形态）。
        with open(os.path.join(main_src, "Dirty.kt"), "w", encoding="utf-8") as fh:
            fh.write("package com.example\n\nclass Dirty {\n    val daughterCount = 1\n}\n")
        check("business_word_gate：合成红例（标识符 daughterCount 判红）",
              business_word_gate(root), want_red=True)
        os.remove(os.path.join(main_src, "Dirty.kt"))
        # 红例 2：字符串字面量带中文家族词。
        with open(os.path.join(main_src, "DirtyCjk.kt"), "w", encoding="utf-8") as fh:
            fh.write('package com.example\n\nclass DirtyCjk {\n    val tag = "母女"\n}\n')
        check("business_word_gate：合成红例（字符串字面量「母女」判红）",
              business_word_gate(root), want_red=True)
        os.remove(os.path.join(main_src, "DirtyCjk.kt"))
        # 白名单豁免：引擎自有 adopt* API 命中不判红。
        with open(os.path.join(main_src, "SliceHost.kt"), "w", encoding="utf-8") as fh:
            fh.write("package com.example\n\nclass SliceHost {\n    fun adoptSlice(x: Int) = x\n}\n")
        check("business_word_gate：白名单豁免（adoptSlice 引擎自有 API）",
              business_word_gate(root), want_red=False)
        os.remove(os.path.join(main_src, "SliceHost.kt"))
        # 排除面：mod-family（业务本体）与测试源集的业务词合法。
        family_src = os.path.join(root, "mod-family", "src", "main", "kotlin")
        os.makedirs(family_src)
        with open(os.path.join(family_src, "FamilyBiz.kt"), "w", encoding="utf-8") as fh:
            fh.write('class FamilyBiz {\n    val daughterCount = 1\n    val tag = "子嗣"\n}\n')
        test_src = os.path.join(root, "core-vm", "src", "test", "kotlin")
        os.makedirs(test_src)
        with open(os.path.join(test_src, "BizTest.kt"), "w", encoding="utf-8") as fh:
            fh.write("class BizTest {\n    val daughterCount = 1\n}\n")
        check("business_word_gate：mod-family 与测试源集排除（业务词合法）",
              business_word_gate(root), want_red=False)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    # 真树零命中：词表 × 范围 × 白名单的现行口径必须全绿。
    check("business_word_gate：真实源树（core-vm/src/main + mod-api/src/main）零业务词命中",
          business_word_gate(os.path.dirname(HERE)), want_red=False)


_MAIN_FROM_ERDPKG = main


def main() -> int:
    """既有 main 的追加包装：先原样跑 erdpkg 四套回归，再追加业务词门禁回归。

    既有 main 函数体零改动（经 _MAIN_FROM_ERDPKG 原样转发，其 FAILS 聚合与打印口径不变）；
    本包装只把自己的回归接进同一退出码口径。函数名按模块级名字解析的次序覆盖原 main
    （结尾既有的 __main__ 守卫因此调用到本包装），这是不改既有函数前提下的注册方式。
    """
    rc = _MAIN_FROM_ERDPKG()
    if rc != 0:
        return rc
    test_business_word_gate()
    if FAILS:
        print(f"\n{len(FAILS)} 条断言失败（含业务词门禁段）：")
        for f in FAILS:
            print("  -", f)
        return 1
    print("\n业务词 grep 门禁回归全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
