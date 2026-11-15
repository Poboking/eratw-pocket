#!/usr/bin/env python3
"""RT2-B③/RT2-ERBM 内置 mod 资产出包体检定向回归。

用法（在 eratw-android 下）::

    python devtools/test_rt2_manifest_assets.py

不依赖 Gradle / Android SDK / 网络 / 真 APK：全部用临时 zip 当 APK，仓库里的静态资产与
声明真源用真实文件（保证体检链四段同源）。不出全量包。

判据：
- APK 内 assets/mods/rt2 的点 manifest 与仓库静态资产逐字节一致、@meta 包名与文件名
  一致、包名集合与声明真源一致 ⇒ 绿；
- .erbm 容器（RT2-ERBM）：ERTM1 头 / 尾 32B=SHA-256(body) / version=1 / 档位位 ∈ {0,1} /
  文件名=packageId / 每容器恰配一 manifest（孤儿容器判红）/ manifest 钉了
  artifact-sha256 则与 .erbm 实际摘要相等 ⇒ 绿；
- 缺档 / 旧字节 / 坏档（坏 magic、摘要不符）/ 包名分叉 / 孤儿容器 / 多出来历不明的档 /
  文件名不符 ⇒ 红；
- main 的 kind_problems 必须收这两张问题表（--verify-only 与 --skip-assets 都跑得到）。
"""

from __future__ import annotations

import hashlib
import os
import shutil
import struct
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import pack_release as pr  # noqa: E402

ASSET_NAME = "eratw.family.manifest"
MEMORY_MANIFEST_NAME = "eratw.memory.manifest"
# M10-1：Living World Demo 四载体资产（eratw.lw.*，与声明真源逐字节一致的对账面）。
LW_CHARACTER_MANIFEST_NAME = "eratw.lw.character.manifest"
LW_RELATIONSHIP_MANIFEST_NAME = "eratw.lw.relationship.manifest"
LW_MEMORY_MANIFEST_NAME = "eratw.lw.memory.manifest"
LW_EVENT_MANIFEST_NAME = "eratw.lw.event.manifest"
DEMO_MANIFEST_NAME = "eratw.demo.manifest"
DEMO_ERBM_NAME = "eratw.demo.erbm"

# M10-1 四载体的（资产名, 期望 @meta 包名）对。
LW_MANIFEST_NAMES = (
    (LW_CHARACTER_MANIFEST_NAME, "eratw.lw.character"),
    (LW_RELATIONSHIP_MANIFEST_NAME, "eratw.lw.relationship"),
    (LW_MEMORY_MANIFEST_NAME, "eratw.lw.memory"),
    (LW_EVENT_MANIFEST_NAME, "eratw.lw.event"),
)

# demo manifest 的 @meta 包名行（hex("eratw.demo")）与替身包名（hex("mod.sample")）。
DEMO_PKG_HEX = b"S65726174772e64656d6f"
ROGUE_PKG_HEX = b"S6d6f642e73616d706c65"


def real_asset() -> bytes:
    with open(os.path.join(pr.BUILTIN_ASSET_DIR, ASSET_NAME), "rb") as fh:
        return fh.read()


def real_memory_manifest() -> bytes:
    with open(os.path.join(pr.BUILTIN_ASSET_DIR, MEMORY_MANIFEST_NAME), "rb") as fh:
        return fh.read()


def real_lw_manifest(name: str) -> bytes:
    with open(os.path.join(pr.BUILTIN_ASSET_DIR, name), "rb") as fh:
        return fh.read()


def real_demo_manifest() -> bytes:
    with open(os.path.join(pr.BUILTIN_ASSET_DIR, DEMO_MANIFEST_NAME), "rb") as fh:
        return fh.read()


def real_demo_erbm() -> bytes:
    with open(os.path.join(pr.BUILTIN_ASSET_DIR, DEMO_ERBM_NAME), "rb") as fh:
        return fh.read()


def check(assets: dict) -> list:
    tmp = tempfile.mkdtemp(prefix="rt2-pack-")
    saved = pr.APK_PATH
    apk = os.path.join(tmp, "app-release.apk")
    try:
        with zipfile.ZipFile(apk, "w") as zf:
            for name, data in assets.items():
                zf.writestr(name, data)
        pr.APK_PATH = apk
        pr._APK_ENTRY_CACHE.clear()
        pr._APK_NAMELIST_CACHE.clear()
        # RT2-ERBM：两张问题表一起收（与 main 的 kind_problems 接线同口径）。
        return pr.rt2_manifest_problems() + pr.rt2_erbm_problems()
    finally:
        pr.APK_PATH = saved
        pr._APK_ENTRY_CACHE.clear()
        pr._APK_NAMELIST_CACHE.clear()
        shutil.rmtree(tmp, ignore_errors=True)


def good_assets() -> dict:
    """全绿形状：family + memory + eratw.lw 四载体 manifest + demo 两件套（manifest 钉 .erbm 实际摘要）。"""
    assets = {
        pr.RT2_BUILTIN_ASSET_PREFIX + ASSET_NAME: real_asset(),
        pr.RT2_BUILTIN_ASSET_PREFIX + MEMORY_MANIFEST_NAME: real_memory_manifest(),
        pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_MANIFEST_NAME: real_demo_manifest(),
        pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_ERBM_NAME: real_demo_erbm(),
    }
    for name, _package_id in LW_MANIFEST_NAMES:
        assets[pr.RT2_BUILTIN_ASSET_PREFIX + name] = real_lw_manifest(name)
    return assets


def test_real_asset_passes() -> None:
    problems = check(good_assets())
    assert not problems, f"真资产不该判红：{problems}"


def test_missing_manifest_fails() -> None:
    problems = check({"assets/erbc.v1/package.report": b"game-root -\n"})
    assert any("装 0 个 mod" in p for p in problems), f"缺档必须判红：{problems}"


def test_stale_bytes_fails() -> None:
    # 合法形状但 @meta 换成别的包名：既要报「与仓库字节不符」，也要报文件名/集合不符。
    stale = real_asset().replace(
        b"S65726174772e66616d696c79", b"S6d6f642e73616d706c65"
    )
    assert stale != real_asset(), "替换没生效，反向证明不成立"
    problems = check({pr.RT2_BUILTIN_ASSET_PREFIX + ASSET_NAME: stale})
    assert any("字节不符" in p for p in problems), f"旧字节必须判红：{problems}"
    assert any("文件名" in p or "不一致" in p for p in problems), f"包名分叉必须判红：{problems}"


def test_bad_magic_fails() -> None:
    broken = real_asset().replace(b"RT2MANIFEST/3", b"RT2MANIFEST/9", 1)
    problems = check({pr.RT2_BUILTIN_ASSET_PREFIX + ASSET_NAME: broken})
    assert any("坏档" in p for p in problems), f"坏档必须判红：{problems}"


def test_extra_unknown_manifest_fails() -> None:
    assets = good_assets()
    assets[pr.RT2_BUILTIN_ASSET_PREFIX + "rogue.manifest"] = real_asset()
    problems = check(assets)
    assert any("来源不明" in p or "不一致" in p for p in problems), f"多档必须判红：{problems}"


def test_filename_mismatch_fails() -> None:
    problems = check({pr.RT2_BUILTIN_ASSET_PREFIX + "other.manifest": real_asset()})
    assert any("文件名" in p for p in problems), f"文件名不符必须判红：{problems}"


def test_parse_negatives() -> None:
    assert pr.parse_rt2_manifest_package_id(real_asset())[0] == "eratw.family"
    assert pr.parse_rt2_manifest_package_id(real_memory_manifest())[0] == "eratw.memory"
    # M10-1：四载体 @meta 包名与文件名同源（真资产逐只解包名）。
    for name, package_id in LW_MANIFEST_NAMES:
        assert pr.parse_rt2_manifest_package_id(real_lw_manifest(name))[0] == package_id, name
    for payload, keyword in (
        (b"nope", "首行"),
        (b"RT2MANIFEST/3\n@meta\nSzz\n/\n", "@meta 包名"),
        (b"RT2MANIFEST/3\n@meta\n", "@meta 包名行"),
        (b"\xff\xfe", "UTF-8"),
    ):
        pkg, err = pr.parse_rt2_manifest_package_id(payload)
        assert pkg is None and err and keyword in err, f"{payload!r} 应报 {keyword}，实际 {(pkg, err)}"


# ------------------------------------------------------------------
# RT2-ERBM：.erbm 容器体检（负例本体即反向证明：改前红、真资产复绿）
# ------------------------------------------------------------------


def test_erbm_bad_magic_fails() -> None:
    broken = real_demo_erbm().replace(b"ERTM1", b"ERTM9", 1)
    assert broken != real_demo_erbm(), "替换没生效，反向证明不成立"
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_ERBM_NAME: broken})
    assert any("坏档" in p and "magic" in p for p in problems), f"坏 magic 必须判红：{problems}"


def test_erbm_digest_mismatch_fails() -> None:
    # 破坏正文中段字节（magic 保留）：尾部 SHA-256 复算必须当场不符。
    broken = bytearray(real_demo_erbm())
    broken[40] = (broken[40] + 1) % 256
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_ERBM_NAME: bytes(broken)})
    assert any("摘要" in p for p in problems), f"摘要不符必须判红：{problems}"


def test_erbm_pkg_fork_fails() -> None:
    # manifest 的 @meta 包名换成别的包（合法形状、合法摘要钉值不动）：
    # 容器按期望文件名找到 manifest 但包名对不上 ⇒ 容器/manifest 包名分叉判红
    # （装配侧按 packageId 配对，这个错位到真机就是「容器找不到 manifest」整包拒）。
    forked = real_demo_manifest().replace(DEMO_PKG_HEX, ROGUE_PKG_HEX, 1)
    assert forked != real_demo_manifest(), "替换没生效，反向证明不成立"
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_MANIFEST_NAME: forked})
    assert any("包名分叉" in p and "容器 eratw.demo" in p for p in problems), \
        f"包名分叉必须判红：{problems}"
    assert any("文件名" in p for p in problems), f"manifest 文件名分叉必须判红：{problems}"


def test_erbm_orphan_fails() -> None:
    # 孤儿容器 = 有 .erbm 无同包名 manifest：装配侧 fail-closed 整包拒，出包期先挡。
    assets = good_assets()
    del assets[pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_MANIFEST_NAME]
    problems = check(assets)
    assert any("孤儿容器" in p for p in problems), f"孤儿容器必须判红：{problems}"
    assert any("缺内置 RT2 manifest" in p for p in problems), f"缺 manifest 也要点名：{problems}"


def test_erbm_bad_version_or_flags_fails() -> None:
    # 改头部字段后必须重算尾摘要，否则摘要闸先红、盖住 version/flags 判据本身。
    bad_version = bytearray(real_demo_erbm())
    bad_version[5] = 9  # version u16 高字节 → 0x0900 = 2304 ≠ 1
    bad_version[-32:] = hashlib.sha256(bytes(bad_version[:-32])).digest()
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_ERBM_NAME: bytes(bad_version)})
    assert any("版本不符" in p for p in problems), f"坏版本必须判红：{problems}"
    bad_flags = bytearray(real_demo_erbm())
    bad_flags[7] = 0x02  # flags u16 → 0x0002，不在合法集 {0,1}
    bad_flags[-32:] = hashlib.sha256(bytes(bad_flags[:-32])).digest()
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_ERBM_NAME: bytes(bad_flags)})
    assert any("档位位" in p for p in problems), f"未知档位位必须判红：{problems}"
    # 头部字段进不了尾摘要 = 摘要没绑住头部，反向证明不成立。
    assert struct.unpack(">H", bytes(bad_version[5:7]))[0] != 1
    assert struct.unpack(">H", bytes(bad_flags[7:9]))[0] not in (0, 1)


def test_erbm_pinned_digest_mismatch_fails() -> None:
    # 容器本身自洽（头/摘要都合法），但 manifest 钉的 artifact-sha256 与实际摘要不等：
    # 模拟「.erbm 被单独替换、manifest 没跟着重生」的错位。
    actual = hashlib.sha256(real_demo_erbm()).hexdigest()
    other_sha = hashlib.sha256(b"not-the-real-container").hexdigest().encode("ascii")
    pinned_wrong = real_demo_manifest().replace(
        ("S" + actual.encode().hex()).encode(),
        ("S" + other_sha.hex().encode().hex()).encode(),
        1,
    )
    assert pinned_wrong != real_demo_manifest(), "替换没生效，反向证明不成立"
    problems = check(good_assets() | {pr.RT2_BUILTIN_ASSET_PREFIX + DEMO_MANIFEST_NAME: pinned_wrong})
    assert any("artifact-sha256" in p for p in problems), f"钉值不符必须判红：{problems}"


def test_parse_erbm_negatives() -> None:
    pkg, err = pr.parse_rt2_erbm(real_demo_erbm())
    assert pkg == "eratw.demo" and err is None, f"真容器必须解出 eratw.demo，实际 {(pkg, err)}"
    for payload, keyword in (
        (b"E", "长度不足"),
        (b"ERBM!" + b"\x00" * 40, "magic"),
        (real_demo_erbm().replace(b"ERTM1", b"XRTM1", 1), "magic"),
    ):
        pkg, err = pr.parse_rt2_erbm(payload)
        assert pkg is None and err and keyword in err, f"{payload!r:.20} 应报 {keyword}，实际 {(pkg, err)}"
    # manifest 的 artifact-sha256 解析：family 未钉（空）+ demo 钉 .erbm 实际摘要。
    pinned, err = pr.parse_rt2_manifest_artifact_sha256(real_asset())
    assert pinned == "" and err is None, f"family 未钉摘要，实际 {(pinned, err)}"
    pinned, err = pr.parse_rt2_manifest_artifact_sha256(real_demo_manifest())
    assert pinned and pinned == hashlib.sha256(real_demo_erbm()).hexdigest()
    assert err is None, f"demo 钉值必须等于 .erbm 摘要，实际 {(pinned, err)}"
    pinned, err = pr.parse_rt2_manifest_artifact_sha256(b"RT2MANIFEST/3\n@artifact-sha256\nSzz\n/\n")
    assert pinned is None and err and "解不出" in err, f"坏钉值必须报错，实际 {(pinned, err)}"
    pinned, err = pr.parse_rt2_manifest_artifact_sha256(b"RT2MANIFEST/3\n@artifact-sha256\n")
    assert pinned is None and err and "摘要行" in err, f"缺摘要行必须报错，实际 {(pinned, err)}"


def test_main_wires_check() -> None:
    with open(os.path.join(HERE, "pack_release.py"), encoding="utf-8") as fh:
        source = fh.read()
    assert "kind_problems += rt2_manifest_problems()" in source, "出包主链必须收这张问题表"
    assert "kind_problems += rt2_erbm_problems()" in source, "出包主链必须收 .erbm 体检表"


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
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
    sys.exit(main())
