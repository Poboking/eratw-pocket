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


if __name__ == "__main__":
    sys.exit(main())
