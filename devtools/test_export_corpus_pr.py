#!/usr/bin/env python3
"""export_corpus_pr 定向回归 —— lane corpus-pr，2026-11-10。

用法（在 eratw-android 下）::

    python devtools/test_export_corpus_pr.py

不依赖 Gradle / Android SDK / 网络：全部在临时目录里合成小 index.yaml、台账与补丁资产，
只测 devtools/export_corpus_pr.py 的一致性与导出面。

覆盖的判据（specs/corpus-bugs.md §5 三向一致性硬规则）：
- 真仓一致性：台账里每个「已修（补丁 CB-xxx）」行 <=> index.yaml 条目 <=> 可导出 bundle，
  三面读数逐一对得上；
- bundle 自洽：manifest.patch.sha256 == 落盘 <id>.patch 的 sha256；manifest.id == 目录名；
  README 带 index.yaml 的 summary/evidence 原文；files 列与实际三个文件一致；
- 确定性：同一输入连跑两次，全部文件逐字节相同；
- 未知状态跳过并告警：待裁定 / 已修（出包侧）行不生成 bundle、但仍出现在 warnings；
- 判红：缺补丁资产 / 台账已修但 index 缺条目 / index 有条目但台账未认账 /
  缺必填字段 / sha 形不对 / 未知字段 / --check 漂移 / 输出根多出未登记目录；
- 落盘护栏：非 CB-ddd 的 id 一律 ValueError（防手工路径穿越）。

退出码：0 = 全部通过，1 = 有断言失败。
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import export_corpus_pr as ex  # noqa: E402

FAILS = []


def check(label: str, ok: bool, detail: str = "") -> None:
    if ok:
        print("[ ok ] " + label)
    else:
        FAILS.append(label + (("：" + detail) if detail else ""))
        print("[FAIL] " + label + (("  " + detail) if detail else ""))


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    with open(path, "rb") as fh:
        return sha256_bytes(fh.read())


def fake_sha(tag: str) -> str:
    """稳定可读的 64 位 hex（测试用，不是真 sha）。"""
    seed = hashlib.sha256(tag.encode("utf-8")).hexdigest()
    return seed


def write_text(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def write_bytes(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)


def tree_sha(root: str) -> dict:
    out = {}
    for base, _dirs, names in os.walk(root):
        for name in names:
            path = os.path.join(base, name)
            out[os.path.relpath(path, root).replace(os.sep, "/")] = sha256_file(path)
    return out


SPECS_HEADER = """# corpus-bugs —— 合成台账（测试用）

## 1. 在册缺陷

| id | 簇（端点签名/读数） | 语料落点 | 病灶 | 判定 | 处置 | 证据 |
|---|---|---|---|---|---|---|
"""

SPECS_ROWS = [
    "| CB-101 | 合成簇一 | `A.ERB:1` | 病灶一 | 内容缺陷 | **已修（补丁 CB-101）**：合成修法一 | 证据一 |",
    "| CB-102 | 合成簇二 | `B.ERB:2` | 病灶二 | 内容缺陷 | **已修（补丁 CB-102）**：合成修法二 | 证据二 |",
    "| CB-103 | 合成簇三 | `C.ERB:3` | 病灶三 | 待定 | **待裁定**：合成待定三 | 证据三 |",
    "| CB-104 | 合成簇四 | `D.ERB:4` | 病灶四 | 出包侧 | **已修（出包侧）**：合成出包四 | 证据四 |",
]

PATCH_BODY = "--- a/X.ERB\n+++ b/X.ERB\n@@ -1 +1 @@\n-old line\n+new line\n"


def index_entry(pid: str, target: str = None, patch: str = None,
                bad: str = None, fixed: str = None, root: str = None,
                extra: str = "", drop: str = "") -> str:
    target = target if target is not None else "%s.ERB" % pid
    patch = patch if patch is not None else "%s.patch" % pid
    bad = bad if bad is not None else fake_sha("bad-" + pid)
    fixed = fixed if fixed is not None else fake_sha("fixed-" + pid)
    lines = ["  - id: %s" % pid]
    if drop != "target":
        lines.append("    target: %s" % target)
    if root:
        lines.append("    root: %s" % root)
    if drop != "patch":
        lines.append("    patch: %s" % patch)
    if drop != "bad-sha256":
        lines.append("    bad-sha256: %s" % bad)
    if drop != "fixed-sha256":
        lines.append("    fixed-sha256: %s" % fixed)
    if drop != "corpus":
        lines.append("    corpus: SYNTH-1.0")
    if drop != "status":
        lines.append("    status: 已批修（合成）")
    if drop != "summary":
        lines.append("    summary: 合成病灶 %s；合成修法" % pid)
    if drop != "evidence":
        lines.append("    evidence: 合成证据 %s" % pid)
    if extra:
        lines.append("    " + extra)
    return "\n".join(lines) + "\n"


def make_fixture(root: str, index_text: str = None, specs_text: str = None,
                 patch_ids=("CB-101", "CB-102")):
    """造一个最小可导出仓：index.yaml + specs/corpus-bugs.md + <id>.patch 资产。"""
    patches_dir = os.path.join(root, "devtools", "corpus-patches")
    index_path = os.path.join(patches_dir, "index.yaml")
    specs_path = os.path.join(root, "specs", "corpus-bugs.md")
    if index_text is None:
        index_text = "# 合成索引\npatches:\n" + index_entry("CB-101") + index_entry("CB-102")
    write_text(index_path, index_text)
    if specs_text is None:
        specs_text = SPECS_HEADER + "\n".join(SPECS_ROWS) + "\n"
    write_text(specs_path, specs_text)
    for pid in patch_ids:
        write_bytes(os.path.join(patches_dir, "%s.patch" % pid), PATCH_BODY.encode("utf-8"))
    return index_path, specs_path


def test_real_repo_three_way():
    rows, row_problems = ex.parse_ledger(ex.DEFAULT_SPECS)
    entries, index_problems = ex.parse_index(ex.DEFAULT_INDEX)
    check("真仓：台账解析零问题", not row_problems, str(row_problems[:2]))
    check("真仓：index.yaml 解析零问题", not index_problems, str(index_problems[:2]))

    fixed = {}
    for row in rows:
        pid = ex.fixed_patch_id(row)
        if pid:
            fixed[row["id"]] = pid
    ids = {entry["id"] for entry in entries}
    check("真仓：每个「已修（补丁）」行都有 index 条目", set(fixed) <= ids,
          "缺 " + str(sorted(set(fixed) - ids)))
    check("真仓：每个 index 条目都在台账里且标了已修", ids <= set(fixed),
          "多 " + str(sorted(ids - set(fixed))))
    check("真仓：台账行 id 与补丁 id 一一对应",
          all(row_id == pid for row_id, pid in fixed.items()))

    out = tempfile.mkdtemp(prefix="corpuspr-real-")
    try:
        code, problems, _warnings, exported = ex.run(ex.DEFAULT_INDEX, ex.DEFAULT_SPECS, out)
        check("真仓：导出 exit=0", code == 0, str(problems[:2]))
        check("真仓：导出的 id 集合 == 已修集合", set(exported) == set(fixed), str(exported))

        self_consistent = True
        detail = ""
        for pid in exported:
            bundle_dir = os.path.join(out, pid)
            names = sorted(os.listdir(bundle_dir))
            if names != sorted(["%s.patch" % pid, "README.md", "manifest.json"]):
                self_consistent, detail = False, "%s 文件集 %s" % (pid, names)
                break
            with open(os.path.join(bundle_dir, "manifest.json"), encoding="utf-8") as fh:
                manifest = json.load(fh)
            if manifest["id"] != pid:
                self_consistent, detail = False, "%s manifest.id" % pid
                break
            patch_sha = sha256_file(os.path.join(bundle_dir, "%s.patch" % pid))
            if manifest["patch"]["sha256"] != patch_sha:
                self_consistent, detail = False, "%s patch.sha256" % pid
                break
            if manifest["files"] != sorted(["%s.patch" % pid, "README.md", "manifest.json"]):
                self_consistent, detail = False, "%s files 列" % pid
                break
            entry = next(e for e in entries if e["id"] == pid)
            with open(os.path.join(bundle_dir, "README.md"), encoding="utf-8") as fh:
                readme = fh.read()
            if entry["summary"] not in readme or entry["evidence"] not in readme:
                self_consistent, detail = False, "%s README 未带 summary/evidence 原文" % pid
                break
        check("真仓：bundle 自洽（文件集 / id / patch sha / README 原文）", self_consistent, detail)

        code2, problems2, _w2, _e2 = ex.run(ex.DEFAULT_INDEX, ex.DEFAULT_SPECS, out, check_only=True)
        check("真仓：--check 复跑 exit=0", code2 == 0, str(problems2[:2]))
    finally:
        shutil.rmtree(out, ignore_errors=True)


def test_fixture_export_and_warnings():
    root = tempfile.mkdtemp(prefix="corpuspr-fx-")
    try:
        index_path, specs_path = make_fixture(root)
        out = os.path.join(root, "out")
        code, problems, warnings, exported = ex.run(index_path, specs_path, out)
        check("合成：exit=0", code == 0, str(problems[:2]))
        check("合成：只导出「已修（补丁）」两条", exported == ["CB-101", "CB-102"], str(exported))
        joined = "\n".join(warnings)
        check("合成：待裁定行告警且不导出",
              "CB-103" in joined and not os.path.isdir(os.path.join(out, "CB-103")))
        check("合成：已修（出包侧）行告警且不导出",
              "CB-104" in joined and not os.path.isdir(os.path.join(out, "CB-104")))
        with open(os.path.join(out, "CB-101", "manifest.json"), encoding="utf-8") as fh:
            manifest = json.load(fh)
        check("合成：manifest 记台账行号与行 sha",
              manifest["ledger"]["row_line"] > 0 and len(manifest["ledger"]["row_sha256"]) == 64)
        check("合成：manifest 记 upstream 分支与 commit subject",
              manifest["upstream"]["branch"] == "eratw-corpus-fix/CB-101"
              and manifest["upstream"]["commit_subject"].startswith("corpus(CB-101): "))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def run_fixture(index_text=None, specs_text=None, patch_ids=("CB-101", "CB-102")):
    root = tempfile.mkdtemp(prefix="corpuspr-fx-")
    index_path, specs_path = make_fixture(root, index_text=index_text,
                                          specs_text=specs_text, patch_ids=patch_ids)
    out = os.path.join(root, "out")
    code, problems, warnings, exported = ex.run(index_path, specs_path, out)
    return root, out, code, problems, warnings, exported


def test_deterministic():
    root, out_a, code_a, problems_a, _w, exported = run_fixture()
    out_b = os.path.join(root, "out-b")
    try:
        code_b, problems_b, _w2, _e = ex.run(
            os.path.join(root, "devtools", "corpus-patches", "index.yaml"),
            os.path.join(root, "specs", "corpus-bugs.md"), out_b)
        check("确定性：两次导出都 exit=0", code_a == 0 and code_b == 0,
              str(problems_a[:1]) + str(problems_b[:1]))
        check("确定性：两次导出逐字节相同", tree_sha(out_a) == tree_sha(out_b))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_check_mode_detects_drift():
    root, out, code, problems, _w, exported = run_fixture()
    try:
        check("--check：干净 tree exit=0", code == 0, str(problems[:1]))
        index_path = os.path.join(root, "devtools", "corpus-patches", "index.yaml")
        specs_path = os.path.join(root, "specs", "corpus-bugs.md")
        code_ok, problems_ok, _w2, _e = ex.run(index_path, specs_path, out, check_only=True)
        check("--check：干净 tree 复跑 exit=0", code_ok == 0, str(problems_ok[:1]))
        with open(os.path.join(out, "CB-101", "README.md"), "a", encoding="utf-8") as fh:
            fh.write("drift\n")
        code_bad, problems_bad, _w3, _e2 = ex.run(index_path, specs_path, out, check_only=True)
        check("--check：README 漂移判红", code_bad == 2 and any("README" in p for p in problems_bad),
              str(problems_bad[:2]))
        os.makedirs(os.path.join(out, "CB-999"), exist_ok=True)
        code_extra, problems_extra, _w4, _e3 = ex.run(index_path, specs_path, out, check_only=True)
        check("--check：输出根多出未登记目录判红",
              code_extra == 2 and any("CB-999" in p for p in problems_extra), str(problems_extra[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_missing_patch_asset_red():
    root, _out, code, problems, _w, exported = run_fixture(patch_ids=("CB-101",))
    try:
        check("缺补丁资产：判红 exit=2", code == 2, str(code))
        check("缺补丁资产：问题点名 CB-102", any("CB-102" in p for p in problems), str(problems[:2]))
        check("缺补丁资产：一个 bundle 都不落盘", exported == [], str(exported))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_ledger_fixed_but_index_missing_red():
    specs = SPECS_HEADER + "\n".join(SPECS_ROWS + [
        "| CB-105 | 合成簇五 | `E.ERB:5` | 病灶五 | 内容缺陷 | **已修（补丁 CB-105）**：合成修法五 | 证据五 |",
    ]) + "\n"
    root, _out, code, problems, _w, _e = run_fixture(specs_text=specs)
    try:
        check("台账已修但 index 缺条目：判红 exit=2", code == 2, str(code))
        check("台账已修但 index 缺条目：问题点名 CB-105",
              any("CB-105" in p for p in problems), str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_index_entry_but_ledger_not_fixed_red():
    index_text = "# 合成索引\npatches:\n" + index_entry("CB-101") + index_entry("CB-102") + index_entry("CB-103")
    root, _out, code, problems, _w, _e = run_fixture(index_text=index_text)
    try:
        check("index 有条目但台账未认账：判红 exit=2", code == 2, str(code))
        check("index 有条目但台账未认账：问题点名 CB-103",
              any("CB-103" in p for p in problems), str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_missing_required_field_red():
    index_text = "# 合成索引\npatches:\n" + index_entry("CB-101", drop="fixed-sha256") + index_entry("CB-102")
    root, _o, code, problems, _w, _e = run_fixture(index_text=index_text)
    try:
        check("缺必填字段 fixed-sha256：判红 exit=2", code == 2, str(code))
        check("缺必填字段：问题点名 fixed-sha256", any("fixed-sha256" in p for p in problems), str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_bad_sha_shape_red():
    index_text = "# 合成索引\npatches:\n" + index_entry("CB-101", fixed="not-a-sha") + index_entry("CB-102")
    root, _o, code, problems, _w, _e = run_fixture(index_text=index_text)
    try:
        check("sha 形不对：判红 exit=2", code == 2, str(code))
        check("sha 形不对：问题点名 fixed-sha256", any("fixed-sha256" in p for p in problems), str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_unknown_field_red():
    index_text = "# 合成索引\npatches:\n" + index_entry("CB-101", extra="weird: 1") + index_entry("CB-102")
    root, _o, code, problems, _w, _e = run_fixture(index_text=index_text)
    try:
        check("未知字段：判红 exit=2（窄 schema 不猜）", code == 2, str(code))
        check("未知字段：问题点名 weird", any("weird" in p for p in problems), str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_path_traversal_refused():
    root, _o, _c, _p, _w, _e = run_fixture()
    try:
        index_path = os.path.join(root, "devtools", "corpus-patches", "index.yaml")
        rows, _ = ex.parse_ledger(os.path.join(root, "specs", "corpus-bugs.md"))
        bad_entry = {"id": "../evil", "patch": "CB-101.patch"}
        raised = False
        try:
            ex.build_bundle(bad_entry, rows[0], index_path)
        except ValueError:
            raised = True
        check("落盘护栏：非 CB-ddd 的 id 被 ValueError 拒绝", raised)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_patch_chain_two_hops():
    sha_a = fake_sha("hop-a")
    sha_b = fake_sha("hop-b")
    sha_c = fake_sha("hop-c")
    ok_index = ("# 合成索引\npatches:\n"
                + index_entry("CB-101", target="Same.ERB", bad=sha_a, fixed=sha_b)
                + index_entry("CB-102", target="Same.ERB", bad=sha_b, fixed=sha_c))
    root, _o, code, problems, _w, exported = run_fixture(index_text=ok_index)
    try:
        check("补丁链：同 target 两跳 sha 相接 -> 绿",
              code == 0 and exported == ["CB-101", "CB-102"], str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)

    broken_index = ("# 合成索引\npatches:\n"
                    + index_entry("CB-101", target="Same.ERB", bad=sha_a, fixed=sha_b)
                    + index_entry("CB-102", target="Same.ERB", bad=fake_sha("wrong-bad"), fixed=sha_c))
    root2, _o2, code2, problems2, _w2, _e2 = run_fixture(index_text=broken_index)
    try:
        hit = [p for p in problems2 if "CB-101" in p and "CB-102" in p]
        check("补丁链：断链判红并点名两个 id", code2 == 2 and bool(hit), str(problems2[:2]))
    finally:
        shutil.rmtree(root2, ignore_errors=True)


def test_check_mode_empty_out():
    root = tempfile.mkdtemp(prefix="corpuspr-empty-")
    try:
        index_path, specs_path = make_fixture(root)
        missing_out = os.path.join(root, "out-missing")
        code, problems, _w, _e = ex.run(index_path, specs_path, missing_out, check_only=True)
        check("--check：out/ 不存在 -> exit 0（无产物 != 漂移）",
              code == 0 and not problems, str(problems[:2]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    for fn in (test_real_repo_three_way, test_fixture_export_and_warnings,
               test_deterministic, test_check_mode_detects_drift,
               test_missing_patch_asset_red, test_ledger_fixed_but_index_missing_red,
               test_index_entry_but_ledger_not_fixed_red, test_missing_required_field_red,
               test_bad_sha_shape_red, test_unknown_field_red, test_path_traversal_refused,
               test_patch_chain_two_hops, test_check_mode_empty_out):
        fn()
    if FAILS:
        print("\n%d 条断言失败：" % len(FAILS))
        for item in FAILS:
            print("  -", item)
        return 1
    print("\nexport_corpus_pr 定向回归全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
