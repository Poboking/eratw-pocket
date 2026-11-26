#!/usr/bin/env python3
"""erbc 新鲜度预检的双维度判据 —— 2026-11-26。

用法（在 eratw-android 下）::

    python devtools/test_erbc_preflight_dims.py
    # 装了 pytest 的机器也可：python -m pytest devtools/test_erbc_preflight_dims.py

为什么单独立一条：2026-11-26 复盘出的 0.2.48x 事故里，出包链**每一道闸都是绿的**，
唯一缺的就是「补丁资产变了但 erbc 没重编」这一维。判据拆成两维是那笔修复的核心，
拆了又被后人合成一句「不符」就没法干活（出包方分不清是编译器变了还是补丁变了），
故在此钉死：两个维度必须各给各的文案，且谁红只说谁。

不依赖 Gradle / Android SDK / 真 erbc：临时仓库造两棵 .kt 树 + 补丁目录 + 合成 manifest，
只测 pack_release.py 的 [preflight_erbc_digest]。指纹算法单源在 erbc_freshness_probe.py，
本用例直接调它算期望值（不另写一份哈希，免得测的是自己的复制品）。

退出码：0 = 全部通过，1 = 有断言失败。
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import erbc_freshness_probe as probe_mod  # noqa: E402
import pack_release as pr  # noqa: E402


def _make_repo(root: Path) -> None:
    """造最小仓库：两棵编译器 .kt 树（探针缺树直接判 ERROR）+ 补丁资产目录。"""
    for rel, name in (("core-vm/src/main/kotlin/com/eratw/port/core", "Vm.kt"),
                      ("tools/erbcc/src/main/kotlin/com/eratw/port/erbcc", "Main.kt")):
        p = root / rel / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("// fixture\n", encoding="utf-8")
    patches = root / probe_mod.PATCHES_REL
    patches.mkdir(parents=True, exist_ok=True)
    (patches / "index.yaml").write_text("patches:\n", encoding="utf-8")
    (patches / "CB-001.patch").write_text("diff --git a/x b/x\n", encoding="utf-8")


def _manifest(root: Path, erbc_dir: Path, *, compiler: str | None = None,
              patches: str | None = None) -> None:
    """写合成 manifest：compiler-digest 必填，corpus-patches-digest 可缺（= 旧产物）。"""
    cur_c, _ = probe_mod.compiler_digest(root)
    cur_p, _ = probe_mod.corpus_patches_digest(root)
    lines = [
        "erbcc-version 0.2.0",
        f"compiler-digest {compiler or cur_c}",
        "compiler-sources 2",
    ]
    if patches != "-":
        lines.append(f"corpus-patches-digest {patches or cur_p}")
    erbc_dir.mkdir(parents=True, exist_ok=True)
    (erbc_dir / "manifest.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _preflight(root: Path, erbc_dir: Path) -> list[str]:
    """跑一次预检；APP_ROOT 与 ERATW_ERBC_DIR 都指到临时仓库（不碰真仓产物）。"""
    old_root, old_env = pr.APP_ROOT, os.environ.get("ERATW_ERBC_DIR")
    pr.APP_ROOT = root
    os.environ["ERATW_ERBC_DIR"] = str(erbc_dir)
    try:
        return pr.preflight_erbc_digest()
    finally:
        pr.APP_ROOT = old_root
        if old_env is None:
            os.environ.pop("ERATW_ERBC_DIR", None)
        else:
            os.environ["ERATW_ERBC_DIR"] = old_env


def case_both_match() -> None:
    with tempfile.TemporaryDirectory() as td:
        root, erbc = Path(td), Path(td) / "erbc"
        _make_repo(root)
        _manifest(root, erbc)
        problems = _preflight(root, erbc)
        assert problems == [], f"两个指纹都对上了却报问题：{problems}"


def case_patches_changed() -> None:
    with tempfile.TemporaryDirectory() as td:
        root, erbc = Path(td), Path(td) / "erbc"
        _make_repo(root)
        _manifest(root, erbc)
        # 出包后有人改了补丁（0.2.483 事故的真实形状）：编译器没动，补丁资产变了。
        (root / probe_mod.PATCHES_REL / "CB-999.patch").write_text("diff --git a/y b/y\n",
                                                                  encoding="utf-8")
        problems = _preflight(root, erbc)
        text = "\n".join(problems)
        assert len(problems) == 2, f"补丁维判红应给两条处置，实际：{problems}"
        assert "语料补丁资产" in text and "corpus-patches-digest" in text, text
        assert "编译器源码" not in text, f"编译器没变却报了编译器维：{text}"
        assert "compileCorpus" in text, text


def case_manifest_missing_patches_line() -> None:
    with tempfile.TemporaryDirectory() as td:
        root, erbc = Path(td), Path(td) / "erbc"
        _make_repo(root)
        _manifest(root, erbc, patches="-")  # 旧编译器产物：没有这行
        problems = _preflight(root, erbc)
        text = "\n".join(problems)
        assert "旧编译器产物" in text, f"缺行应提示「旧编译器产物」，实际：{text}"
        assert "编译器源码" not in text, f"编译器没变却报了编译器维：{text}"


def case_compiler_changed() -> None:
    with tempfile.TemporaryDirectory() as td:
        root, erbc = Path(td), Path(td) / "erbc"
        _make_repo(root)
        _manifest(root, erbc, compiler="0" * 64)
        problems = _preflight(root, erbc)
        text = "\n".join(problems)
        assert "编译器源码" in text, f"编译器维没报：{text}"
        assert "语料补丁资产" not in text, f"补丁没变却报了补丁维：{text}"


def case_missing_manifest() -> None:
    with tempfile.TemporaryDirectory() as td:
        root, erbc = Path(td), Path(td) / "erbc"
        _make_repo(root)
        erbc.mkdir(parents=True, exist_ok=True)
        problems = _preflight(root, erbc)
        assert problems and "读不出" in problems[0], f"缺清单应 fail-closed，实际：{problems}"


def main() -> int:
    cases = [
        ("两个指纹都对上 -> 放行", case_both_match),
        ("只改补丁 -> 只报补丁维", case_patches_changed),
        ("旧产物缺行 -> 报补丁维", case_manifest_missing_patches_line),
        ("编译器变了 -> 只报编译器维", case_compiler_changed),
        ("清单缺失 -> fail-closed", case_missing_manifest),
    ]
    failed = 0
    for name, fn in cases:
        try:
            fn()
            print(f"PASS  {name}")
        except AssertionError as exc:
            failed += 1
            print(f"FAIL  {name}\n      {exc}")
    print(f"\n{len(cases) - failed}/{len(cases)} 通过")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
