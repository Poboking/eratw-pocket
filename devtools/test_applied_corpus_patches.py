#!/usr/bin/env python3
"""applied_corpus_patches 独立列定向回归 —— D8 / eng2-d8b，2026-11-08。

用法（在 eratw-android 下）::

    python devtools/test_applied_corpus_patches.py
    # 装了 pytest 的机器也可：python -m pytest devtools/test_applied_corpus_patches.py

不依赖 Gradle / Android SDK / 网络 / 真 APK：全部用合成的 manifest.txt 留痕文本与
package.report 字典（dry-run 形状），只测 pack_release.py 的生成面与回读核对面。
不出 743 MiB 全量包、不触发 --verify-only 的完整出包。

严格档（eng2-d8b 任务 B）：产物 package.report 必须**自己带** applied_corpus_patches 列
（buildSrc 侧任务 A 写），脚本不再代生成；产物缺列即回读核对判红 → kind_problems → exit 3。
APPLIED_CORPUS_PATCHES_STRICT 常量默认 True，仅接线完成前的过渡期才置 False。

判据（D8 §3 + eng2-d8b 缺口闭环）：
- 套了 CB-001 的包：产物自带正确列（id + 病灶/修后 sha + 含空格 target），回读核对绿；
- 没套补丁的包：产物自带空形状列（"-"）而不是缺列，回读核对绿；
- 值不符（sha 脱节 / 少报 / 多报）或产物缺列 → 回读核对红。
另覆盖：坏留痕 sha 形状 → 红（宁红不猜）、format/parse 往返一致、
产物自带列时不被脚本生成值覆盖（值不符才抓得住）、生成面对空输入不崩。
端到端（第 7 例，造临时 APK 走 report_package_kind）：① 自带正确列绿 ② 缺列红 ③ 值不符红 ——
② 是 D8 遗留的关键缺口（之前「先补列再核对」使缺列判红在端到端路径不可达），本路钉死。

退出码：0 = 全部通过，1 = 有断言失败。
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import pack_release as pr  # noqa: E402

# 与 devtools/corpus-patches/index.yaml 的 CB-001 行一致（只读核对，本用例不改该资产）。
CB001_ID = "CB-001"
CB001_TARGET = "口上・メッセージ関連/個人口上/051 Nitori [にとり]/Nitori/M_KOJO_K51_everyday_commmands.ERB"
CB001_BAD = "3482ab97bbdea05f7c33d6b4b69de43abfaea2576ce9bbef20e859ac81c586a8"
CB001_FIXED = "5a3a8c7b62d58860d900843c722925e222c1aaf952d10a2d31f1c9d99240db71"


def manifest_with_cb001() -> str:
    """合成一段 assets/erbc.v1/manifest.txt：一条普通行 + 一条 CB-001 留痕行。"""
    return (
        "manifest-format 2\n"
        "source A.ERB 8 " + "0" * 64 + "\n"
        f"{pr.CORPUS_PATCH_MARKER}{CB001_ID} {CB001_TARGET} {CB001_BAD} {CB001_FIXED}\n"
    )


def manifest_without_patch() -> str:
    """没套补丁的 manifest：只有普通清单行，零 corpus-patch 留痕。"""
    return "manifest-format 2\nsource A.ERB 8 " + "0" * 64 + "\n"


def assert_problems(label: str, problems: list, want_red: bool) -> None:
    red = bool(problems)
    assert red == want_red, (
        f"{label}：期望{'红' if want_red else '绿'}，实际 "
        f"{'problems=' + str(problems) if red else '绿'}"
    )


def test_cb001_column_present_and_correct() -> None:
    """产物自带正确列（buildSrc 接线后形状），回读核对绿；生成面形状仍逐项验证。

    严格档改造：原用例让脚本对「缺列 rep」代生成列（过渡形态），现改为产物自带列、
    脚本只保留不覆盖 —— 生成面的值/往返/留痕来源断言逐条保留。
    """
    manifest = manifest_with_cb001()
    # 产物 package.report 自己带列：值由生成面从 manifest 真实留痕行算出，
    # 与 buildSrc 侧 formatAppliedCorpusPatches 逐字同源（见 buildSrc 定向单测）。
    rep = {"game-root": "/fake/game", "erbc-freshness": "verified",
           pr.APPLIED_CORPUS_PATCHES_KEY: pr.applied_corpus_patches_column(manifest)}
    view = pr.apply_corpus_patches_column(rep, manifest)

    assert pr.APPLIED_CORPUS_PATCHES_KEY in view, "产物自带列必须保留"
    col = view[pr.APPLIED_CORPUS_PATCHES_KEY]
    assert CB001_ID in col, "列值须逐项读出补丁 id"
    assert CB001_BAD in col and CB001_FIXED in col, "列值须读出病灶/修后 sha"
    assert CB001_TARGET in col, "列值须含目标路径（留痕来源定位）"

    # 列值可逆解析成逐项记录，且 source 指向原留痕行（留痕来源）。
    recs = pr.parse_applied_corpus_patches_value(col)
    assert len(recs) == 1 and recs[0]["id"] == CB001_ID
    assert recs[0]["bad_sha256"] == CB001_BAD
    assert recs[0]["fixed_sha256"] == CB001_FIXED
    assert recs[0]["source"].startswith(pr.CORPUS_PATCH_MARKER)

    # format/parse 往返：生成面一步式与「解析留痕 → 格式化」同值。
    assert pr.applied_corpus_patches_column(manifest) == col
    assert pr.format_applied_corpus_patches(pr.parse_corpus_patch_markers(manifest)) == col

    assert_problems("产物自带正确列：回读核对",
                    pr.applied_corpus_patches_problems(view, manifest), want_red=False)


def test_no_patch_empty_shape_not_missing() -> None:
    """没套补丁的包产物自带空形状列（不是缺列）、回读核对绿。

    严格档改造：原用例让脚本对缺列 rep 代生成空形状，现改为产物自带空形状列。
    """
    manifest = manifest_without_patch()
    # 产物侧 buildSrc 写空形状列（无补丁也必须有列），脚本不代生成。
    rep = {"game-root": "/fake/game", "erbc-freshness": "verified",
           pr.APPLIED_CORPUS_PATCHES_KEY: pr.APPLIED_CORPUS_PATCHES_EMPTY}
    view = pr.apply_corpus_patches_column(rep, manifest)

    assert pr.APPLIED_CORPUS_PATCHES_KEY in view, "没套补丁也必须**有**该列（空形状，不是缺列）"
    assert view[pr.APPLIED_CORPUS_PATCHES_KEY] == pr.APPLIED_CORPUS_PATCHES_EMPTY
    assert pr.parse_applied_corpus_patches_value(view[pr.APPLIED_CORPUS_PATCHES_KEY]) == []
    assert pr.applied_corpus_patches_column(manifest) == pr.APPLIED_CORPUS_PATCHES_EMPTY

    assert_problems("产物自带空形状列：回读核对",
                    pr.applied_corpus_patches_problems(view, manifest), want_red=False)


def test_value_mismatch_fails_readback() -> None:
    """值不符时回读核对失败（红）—— 含 sha 脱节 / 少报 / 多报三形。

    三形都自带列，严格档下 apply 只保留、不代生成，故本用例不受开关影响（原样绿）。
    """
    manifest = manifest_with_cb001()
    # 产物 package.report 自带一个「值不符」的列（模拟 buildSrc 写歪 / 旧包）：
    # apply 必须保留产物值而不覆盖，核对才有得抓。
    bad_sha_col = f"{CB001_ID} {CB001_TARGET} {'f' * 64} {CB001_FIXED}"
    rep = {pr.APPLIED_CORPUS_PATCHES_KEY: bad_sha_col}
    view = pr.apply_corpus_patches_column(rep, manifest)
    assert view[pr.APPLIED_CORPUS_PATCHES_KEY] == bad_sha_col, "产物自带列不得被脚本生成值覆盖"
    assert_problems("病灶 sha 脱节：回读核对必须红",
                    pr.applied_corpus_patches_problems(view, manifest), want_red=True)

    # 少报：套了补丁却写空形状。
    lean = pr.apply_corpus_patches_column(
        {pr.APPLIED_CORPUS_PATCHES_KEY: pr.APPLIED_CORPUS_PATCHES_EMPTY}, manifest)
    assert_problems("少报（套了补丁写空形状）：回读核对必须红",
                    pr.applied_corpus_patches_problems(lean, manifest), want_red=True)

    # 多报：没套补丁的包却声明了 CB-001。
    extra = pr.apply_corpus_patches_column(
        {pr.APPLIED_CORPUS_PATCHES_KEY: pr.applied_corpus_patches_column(manifest)},
        manifest_without_patch())
    assert_problems("多报（没套补丁却声明 CB-001）：回读核对必须红",
                    pr.applied_corpus_patches_problems(extra, manifest_without_patch()), want_red=True)


def test_missing_column_fails_readback() -> None:
    """缺列 → 回读核对红（内部形态：直接造缺列视图调核对面）。

    本例是「内部形态」对照；端到端路径的缺列判红由第 7 例 ② 钉死（D8 缺口）。
    """
    manifest = manifest_with_cb001()
    rep = {"game-root": "/fake/game"}  # 直接造一个缺列的视图
    assert pr.APPLIED_CORPUS_PATCHES_KEY not in rep
    problems = pr.applied_corpus_patches_problems(rep, manifest)
    assert_problems("缺列：回读核对必须红", problems, want_red=True)
    assert any("缺" in p and pr.APPLIED_CORPUS_PATCHES_KEY in p for p in problems)


def test_bad_marker_shape_fails_readback() -> None:
    """坏留痕 sha 形状 → 回读核对红（宁红不猜）。

    严格档改造：坏形状留痕在 buildSrc 产物侧本就会构建失败（formatAppliedCorpusPatches
    抛 GradleException），脚本侧核对仍判红；不再让脚本对缺列 rep 代生成空形状占位。
    """
    bad_manifest = (
        "manifest-format 2\n"
        f"{pr.CORPUS_PATCH_MARKER}{CB001_ID} {CB001_TARGET} NOTSHA {CB001_FIXED}\n"
    )
    rep = {pr.APPLIED_CORPUS_PATCHES_KEY: pr.APPLIED_CORPUS_PATCHES_EMPTY}
    view = pr.apply_corpus_patches_column(rep, bad_manifest)
    assert pr.APPLIED_CORPUS_PATCHES_KEY in view, "产物自带列必须保留"
    assert_problems("坏留痕 sha 形状：回读核对必须红（宁红不猜）",
                    pr.applied_corpus_patches_problems(view, bad_manifest), want_red=True)


def test_column_generation_never_crashes_on_empty_input() -> None:
    """生成面纯函数对空输入产出空形状；严格档下 apply 对缺列空视图不代生成。

    严格档改造：原用例断言「apply 对空输入给空形状占位、核对绿」（过渡形态），
    现改为「缺列不代生成、回读核对判红」；生成面两个纯函数断言不变。
    """
    assert pr.applied_corpus_patches_column("") == pr.APPLIED_CORPUS_PATCHES_EMPTY
    assert pr.applied_corpus_patches_column(None) == pr.APPLIED_CORPUS_PATCHES_EMPTY
    view = pr.apply_corpus_patches_column({}, None)
    assert pr.APPLIED_CORPUS_PATCHES_KEY not in view, "严格档：产物缺列不代生成"
    assert_problems("严格档空视图缺列：回读核对必须红",
                    pr.applied_corpus_patches_problems(view, None), want_red=True)


def test_report_package_kind_wires_column_readback() -> None:
    """端到端：造临时 APK 走 report_package_kind，钉死 D8 缺口闭环三档。

    ① 产物自带正确列 ⇒ 绿；② 产物缺列 ⇒ 判红（关键缺口，之前「先补列再核对」
    使缺列判红在端到端路径不可达）；③ 产物列值与 manifest 真实留痕不符 ⇒ 判红。
    回读核对结果进 kind_problems（main 里 kind_problems 非空即 exit 3）。
    不构建、不出全量包、不触发完整 --verify-only。
    """
    tmp = tempfile.mkdtemp(prefix="d8b-apk-")
    saved_apk = pr.APK_PATH
    try:
        game_root = os.path.join(tmp, "game")
        os.makedirs(os.path.join(game_root, "ERB"))  # 空 ERB：erd_bundling 期望 0 条，不抢话
        manifest = manifest_with_cb001()

        def run(extra_report: str) -> list:
            report = (
                f"game-root {game_root}\n"
                "erbc-source verified\n"
                "erbc-freshness verified\n"
                "staged-entries 0\n"
                "staged-bytes 0\n"
                "entries-ERB 0\n" + extra_report
            )
            apk = os.path.join(tmp, "app-release.apk")
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("assets/erbc.v1/package.report", report)
                zf.writestr("assets/erbc.v1/manifest.txt", manifest)
            pr.APK_PATH = apk
            pr._APK_ENTRY_CACHE.clear()  # 换 APK 后丢缓存，避免读到上一份包
            pr._APK_NAMELIST_CACHE.clear()
            return pr.report_package_kind(skip_assets=False, allow_stale_erbc=True)

        # ① 产物自带正确列（buildSrc 接线后形状）→ 与真实留痕对齐 → 无补丁列问题（绿）。
        good = (f"{pr.APPLIED_CORPUS_PATCHES_KEY} "
                f"{pr.applied_corpus_patches_column(manifest)}\n")
        problems = run(good)
        assert not any(pr.APPLIED_CORPUS_PATCHES_KEY in p for p in problems), (
            f"① 产物自带正确列时 report_package_kind 不该报补丁列问题，实际 {problems}")

        # ② 产物缺列（撤掉 A 的写列 / buildSrc 未接线）→ 严格闸判红（关键缺口）。
        #    这形在 D8 的端到端路径不可达：当时 apply 先补列，核对面永远看不到缺列。
        problems = run("")
        assert any(pr.APPLIED_CORPUS_PATCHES_KEY in p and "缺" in p for p in problems), (
            f"② 产物缺列时 report_package_kind 必须报缺列问题，实际 {problems}")

        # ③ 产物自带错列 → 值不符判红 → 问题进 kind_problems（→ main exit 3）。
        wrong = (f"{pr.APPLIED_CORPUS_PATCHES_KEY} {CB001_ID} {CB001_TARGET} "
                 f"{'f' * 64} {CB001_FIXED}\n")
        problems = run(wrong)
        assert any(pr.APPLIED_CORPUS_PATCHES_KEY in p for p in problems), (
            f"③ 值不符时 report_package_kind 必须报补丁列问题，实际 {problems}")
    finally:
        pr.APK_PATH = saved_apk
        pr._APK_ENTRY_CACHE.clear()
        pr._APK_NAMELIST_CACHE.clear()
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    tests = (
        test_cb001_column_present_and_correct,
        test_no_patch_empty_shape_not_missing,
        test_value_mismatch_fails_readback,
        test_missing_column_fails_readback,
        test_bad_marker_shape_fails_readback,
        test_column_generation_never_crashes_on_empty_input,
        test_report_package_kind_wires_column_readback,
    )
    failed = 0
    for fn in tests:
        try:
            fn()
            print(f"[ ok ] {fn.__name__}")
        except Exception as exc:  # 断言失败，或反向证明撤掉列后暴露的 KeyError 等都记一条红
            failed += 1
            print(f"[FAIL] {fn.__name__}: {type(exc).__name__}: {exc}")
    total = len(tests)
    if failed:
        print(f"\n{failed}/{total} 条用例失败")
        return 1
    print(f"\napplied_corpus_patches 独立列回归全部通过（{total}/{total}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
