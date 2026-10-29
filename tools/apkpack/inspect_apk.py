#!/usr/bin/env python
"""检查 APK 的「随包装机即玩」形态：条目齐不齐、是不是真 STORED、体积在不在上限内，
以及**清单 <-> 随包文件集**能不能对账（CMP-74）。

用法（在 eratw-android 下）::

    python tools/apkpack/inspect_apk.py                       # 默认读 debug 包
    python tools/apkpack/inspect_apk.py --apk path/to.apk
    python tools/apkpack/inspect_apk.py --require-bundled     # 装机即玩判据：六类缺一项就算失败
    python tools/apkpack/inspect_apk.py --allow-compressed    # -PeratwErbcCompress 打出来的包
    python tools/apkpack/inspect_apk.py --max-mb 420          # 覆盖体积上限

四类口径：

1. **六类条目存在**：``erbc.v1``（program.erbc 或 program.partNNN.erbc + program.parts）、
   ``CSV/``、``resources/``、``sound/``、``plugins/``、``emuera.config``；``--require-bundled`` 时缺任一类 = FAIL。
   ``plugins/`` 是运行期读的（``ERB/SYSTEM.ERB:96-97`` 每局 ``MAP_FROMXML …,LOADTEXT("plugins/tw_csv_chs.xml")``），
   缺它开局就在 ``@INIT_NG_OR_LOAD`` 抛「XML 解析错误（位置 0）」。
2. **STORED**：报告里的 ``no-compress-suffixes`` 声明了「打包期交给 aapt2 的不压缩后缀表」，
   表内后缀对应的条目必须是 STORED。aapt2 的 ``--no-compress`` **只有按后缀匹配一种口径**，
   所以无后缀文件（真语料里 18 个零字节 ``sound/游戏bgm/<地名>/bgm`` 标记）落进 DEFLATE 是
   打包机制的上限、不是 bug —— 这类只统计不判红，报告里单独列出来。
   媒体后缀（webp/png/jpg/ogg/mp3/wav）DEFLATE 一律 FAIL：那是真涨体积又白等 inflate。
3. **体积上限**：缺省取报告里的 ``apk-ceiling-bytes``（随包字节 + 代码/资源开销余量），
   报告缺席时退成 ``--max-mb``（缺省 420 MB，对应「程序条目 DEFLATE」那档）。
   两条实测地板（2026-10-21 语料字节数）：程序 STORED 的全随包 ≈ **717 MB**，
   程序 DEFLATE（-PeratwErbcCompress）≈ **424 MB**。写死 420 判「全 STORED」的包必红。
4. **清单 <-> 随包对账**（CMP-74）：``assets/erbc.v1/manifest.txt`` 的
   ``package-artifacts`` 表必须与 zip 里 ``erbc.v1/`` 的实际文件集**双向相等**（逐条字节数 +
   SHA-256；``manifest.txt`` 与 ``package.report`` 自身不在表里），分片时还要按 ``shard`` 行
   的顺序汇总，结果必须等于 ``container-artifact``（切分前的整容器）且与 ``program.parts``
   的行序一致；``package.report`` 的 ``manifest-sha256`` 必须等于清单字节的摘要。
   旧格式（无 ``manifest-format`` 行）只能退回 ``artifacts`` 表，``--require-bundled`` 下判 FAIL。

退出码：0 = 没有 FAIL（可以有 WARN），1 = 有 FAIL。
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

ENTRY_CAP = 2 * 1024 * 1024 * 1024 - 1          # ZIP32 单条目上限
PLAY_BASE_LIMIT = 200 * 1024 * 1024             # Google Play 基础 APK 上限
REPO_ROOT = Path(__file__).resolve().parent.parent.parent   # eratw-android
DEFAULT_APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

METHODS = {0: "STORED", 8: "DEFLATE", 12: "BZIP2", 14: "LZMA"}
STORE_ASSET = "assets/erbc.v1/"
REPORT_ENTRY = STORE_ASSET + "package.report"
MANIFEST_ENTRY = STORE_ASSET + "manifest.txt"
MANIFEST_FORMAT = 2          # 本核验器认识的清单格式（ErbcArtifacts.MANIFEST_FORMAT）

# 这些后缀落进 DEFLATE 一定是回退：图片或音频本来就已压缩，二次压缩只白涨体积和构建时间。
MUST_BE_STORED = {"webp", "png", "jpg", "jpeg", "gif", "ogg", "mp3", "wav", "erbc", "meta"}
# 装机即玩的六类条目（与 GameAssetInstaller.DATA_DIRS + 程序产物 + 配置标志文件同源）。
# plugins/ 是运行期读的：ERB/SYSTEM.ERB:96-97 每局开局都 LOADTEXT("plugins/tw_csv_chs.xml")
# 喂给 MAP_FROMXML，缺它开局就在 @INIT_NG_OR_LOAD 抛「XML 解析错误（位置 0）」。
BUNDLED_DIRS = ("CSV", "resources", "sound", "plugins")
# staging 落在 assets 根的配置件（与 app/build.gradle.kts 的 stagedConfigFiles 同源）。
BUNDLED_CONFIGS = ("emuera.config", "default.config", "fixed.config")

DEFAULT_MAX_MB = 420      # 报告缺席时的兜底上限（程序条目 DEFLATE 那一档）


def mib(n: int) -> str:
    return f"{n / 1024 / 1024:.1f} MiB"


def staged_scope(name: str) -> bool:
    """这条 zip 条目是不是 staging 搬进来的（= 随包口径的判定范围）。

    排除 ``package.report`` 自己（它是打包期读数，不在 staging 字节里，写它等于自指），
    也排除应用自带 assets（``fonts/`` 等：字体刻意留 DEFLATE）。
    """
    if not name.startswith("assets/"):
        return False
    if name == REPORT_ENTRY:
        return False
    rest = name[len("assets/"):]
    top = rest.split("/", 1)[0]
    if top in BUNDLED_DIRS or top == "erbc.v1":
        return True
    return "/" not in rest and rest in BUNDLED_CONFIGS


class Report:
    def __init__(self) -> None:
        self.fails: list[str] = []
        self.warns: list[str] = []
        self.lines: list[str] = []

    def out(self, text: str = "") -> None:
        self.lines.append(text)

    def fail(self, text: str) -> None:
        self.fails.append(text)
        self.lines.append(f"  FAIL  {text}")

    def warn(self, text: str) -> None:
        self.warns.append(text)
        self.lines.append(f"  WARN  {text}")

    def ok(self, text: str) -> None:
        self.lines.append(f"  OK    {text}")


def classify(name: str) -> str:
    if name.startswith("assets/erbc.v1/"):
        return "assets/erbc.v1"
    for d in BUNDLED_DIRS:
        if name.startswith(f"assets/{d}/"):
            return f"assets/{d}"
    if name == "assets/emuera.config":
        return "assets/配置"
    if name.startswith("assets/"):
        return "assets/其它"
    if name.startswith("classes") and name.endswith(".dex"):
        return "dex"
    if name.startswith("res/") or name == "resources.arsc":
        return "res"
    return "其它"


def suffix_of(name: str) -> str:
    """ZIP 条目名的后缀（小写，无点）；无后缀返回空串（aapt2 的 noCompress 够不着这种）。"""
    base = name.rsplit("/", 1)[-1]
    if "." not in base or base.startswith("."):
        return ""
    return base.rsplit(".", 1)[-1].lower()


def read_package_report(zf: zipfile.ZipFile) -> dict[str, str]:
    """``assets/erbc.v1/package.report`` 的「键 值」行；报告缺席返回空表（旧包 / 未随包）。"""
    if REPORT_ENTRY not in {i.filename for i in zf.infolist()}:
        return {}
    try:
        text = zf.read(REPORT_ENTRY).decode("utf-8", "replace")
    except Exception as exc:      # noqa: BLE001 - 报告读不出来只影响兜底上限，不该炸整个核验
        return {"_error": str(exc)}
    parsed: dict[str, str] = {}
    for raw in text.splitlines():
        raw = raw.strip()
        if not raw or " " not in raw:
            continue
        key, value = raw.split(" ", 1)
        parsed[key] = value
    return parsed


def entry_sha256(zf: zipfile.ZipFile, name: str) -> str:
    """zip 内单条目的 SHA-256（流式；378 MB 的 program.erbc 不整份进堆）。"""
    h = hashlib.sha256()
    with zf.open(name) as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


class Manifest:
    """``assets/erbc.v1/manifest.txt`` 的机器可读面（CMP-74 的对账单源）。

    ``fields`` 取每个键的**首个**值；三张表按行序保存：

    - ``artifact``：erbcc 构建面登记的产物文件（旧格式只有 program.erbc）；
    - ``package-artifact``：打包期追加的实际随包文件集（除 manifest.txt / package.report）；
    - ``shard``：容器切片的行序表（每片字节数 + SHA-256）。
    """

    def __init__(self) -> None:
        self.raw: bytes | None = None
        self.fields: dict[str, str] = {}
        self.artifacts: list[tuple[str, int, str]] = []
        self.package_artifacts: list[tuple[str, int, str]] = []
        self.shards: list[tuple[str, int, str]] = []
        self.container_artifact: tuple[str, int, str] | None = None
        self.format: int = 0          # 0 = 文件缺席；1 = 旧格式（无 manifest-format 行）

    @property
    def present(self) -> bool:
        return self.raw is not None


def read_manifest(zf: zipfile.ZipFile, names: set[str]) -> Manifest:
    m = Manifest()
    if MANIFEST_ENTRY not in names:
        return m
    try:
        m.raw = zf.read(MANIFEST_ENTRY)
    except Exception as exc:      # noqa: BLE001 - 读不出来等同清单缺席，由调用方判红/告警
        m.raw = None
        m.fields["_error"] = str(exc)
        return m
    for raw_line in m.raw.decode("utf-8", "replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split(" ")
        key = fields[0]
        if key in ("artifact", "package-artifact", "shard"):
            if len(fields) != 4:
                m.fields.setdefault("_bad-table-lines", "")
                m.fields["_bad-table-lines"] += line + "\n"
                continue
            try:
                size = int(fields[2])
            except ValueError:
                m.fields.setdefault("_bad-table-lines", "")
                m.fields["_bad-table-lines"] += line + "\n"
                continue
            row = (fields[1], size, fields[3].lower())
            if key == "artifact":
                m.artifacts.append(row)
            elif key == "package-artifact":
                m.package_artifacts.append(row)
            else:
                m.shards.append(row)
            continue
        if key == "container-artifact" and len(fields) == 4:
            try:
                m.container_artifact = (fields[1], int(fields[2]), fields[3].lower())
            except ValueError:
                m.fields.setdefault("_bad-table-lines", "")
                m.fields["_bad-table-lines"] += line + "\n"
            continue
        m.fields.setdefault(key, line[len(key):].strip())
    try:
        m.format = int(m.fields.get("manifest-format", "1"))
    except ValueError:
        m.format = -1
    return m


def manifest_physical(names: set[str]) -> set[str]:
    """随包清单应当覆盖的文件集：``erbc.v1/`` 下除清单自身与 package.report 以外的条目。"""
    own = {MANIFEST_ENTRY, REPORT_ENTRY}
    return {n for n in names if n.startswith(STORE_ASSET) and n not in own}


def full_entry(rel: str) -> str:
    """清单里的相对路径（``erbc.v1/...``）→ zip 条目名（``assets/erbc.v1/...``）。"""
    return rel if rel.startswith("assets/") else "assets/" + rel


def check(apk: Path, require_erbc: bool, allow_compressed: bool,
          require_bundled: bool, max_mb: int | None) -> Report:
    rep = Report()
    if not apk.is_file():
        rep.fail(f"APK 不存在：{apk}")
        return rep

    total = apk.stat().st_size
    rep.out(f"APK   {apk}")
    rep.out(f"体积  {total} 字节（{mib(total)}，{total / 1000 / 1000:.1f} MB）")

    with zipfile.ZipFile(apk) as zf:
        infos = zf.infolist()
        rep.out(f"entry {len(infos)} 条")
        names = {i.filename for i in infos}
        pkg = read_package_report(zf)

        groups: dict[str, list[zipfile.ZipInfo]] = {}
        for info in infos:
            groups.setdefault(classify(info.filename), []).append(info)

        rep.out("")
        rep.out("分组")
        order = ["dex", "assets/erbc.v1", *[f"assets/{d}" for d in BUNDLED_DIRS],
                 "assets/配置", "assets/其它", "res", "其它"]
        for key in order:
            items = groups.get(key)
            if not items:
                continue
            size = sum(i.file_size for i in items)
            packed = sum(i.compress_size for i in items)
            rep.out(f"  {key:<18} {len(items):>5} 条  {mib(size):>12}  存进包 {mib(packed)}")

        erbc_items = groups.get("assets/erbc.v1", [])
        rep.out("")
        rep.out("assets/erbc.v1 明细")
        if not erbc_items:
            rep.out("  （空）")
        for info in sorted(erbc_items, key=lambda i: i.filename):
            method = METHODS.get(info.compress_type, str(info.compress_type))
            rep.out(
                f"  {info.filename:<32} {info.file_size:>12} 字节  {method:<7} "
                f"压缩后 {info.compress_size:>12} 字节"
            )

        rep.out("")
        rep.out("检查")

        # ---------------- 1. 程序产物 ----------------
        has_single = STORE_ASSET + "program.erbc" in names
        parts = sorted(
            n for n in names
            if re.fullmatch(r"assets/erbc\.v1/program\.part\d{3}\.erbc", n)
        )
        if has_single and parts:
            rep.fail("同时存在单条 program.erbc 与分片：装载侧只能认一种，打包脚本有 bug")
        elif has_single:
            rep.ok("program.erbc 单条 asset")
        elif parts:
            rep.ok(f"program.erbc 分 {len(parts)} 片")
        else:
            msg = "没有程序产物（program.erbc / program.partNNN.erbc）"
            if require_erbc or require_bundled:
                rep.fail(msg)
            else:
                rep.warn(msg + "：当前包只能走 adb push（开发调试通道）")

        # ---------------- 2. 五类条目齐不齐 ----------------
        for d in BUNDLED_DIRS:
            items = groups.get(f"assets/{d}", [])
            detail = f"{len(items)} 条 / {mib(sum(i.file_size for i in items))}" if items else "0 条"
            if items:
                rep.ok(f"assets/{d}/ 随包：{detail}")
            else:
                msg = f"assets/{d}/ 一条都没有（首启解包不出这个目录）"
                if require_bundled:
                    rep.fail(msg)
                else:
                    rep.warn(msg)
        config = "assets/emuera.config"
        if config in names:
            rep.ok("assets/emuera.config 随包（GameStorage 的游戏根标志文件）")
        elif require_bundled:
            rep.fail(f"缺 {config}：首启只能靠内部根兜底，不是装机即玩形态")
        else:
            rep.warn(f"缺 {config}")
        if "assets/erbc.v1/program.meta" not in names:
            msg = "缺 program.meta（侧车，装载前必需）"
            if require_erbc or require_bundled:
                rep.fail(msg)
            else:
                rep.warn(msg)
        else:
            rep.ok("program.meta 在包里")

        # ---------------- 3. STORED 口径 ----------------
        declared = {s for s in pkg.get("no-compress-suffixes", "").split(",") if s}
        # 程序条目走 DEFLATE 是**显式变体**（-PeratwErbcCompress，或报告里 program-mode deflate），
        # 上面已经单独 WARN 过；这里再判成「媒体条目回退」就是重复报红。
        program_names = {
            i.filename for i in erbc_items
            if i.filename == STORE_ASSET + "program.erbc"
            or i.filename == STORE_ASSET + "program.meta"
            or re.fullmatch(r"assets/erbc\.v1/program\.part\d{3}\.erbc", i.filename)
        }
        program_deflate_ok = allow_compressed or pkg.get("program-mode") == "deflate"
        bad_media: list[zipfile.ZipInfo] = []
        bad_declared: list[zipfile.ZipInfo] = []
        deflate_other: list[zipfile.ZipInfo] = []
        stored_count = 0
        for info in infos:
            if not staged_scope(info.filename):
                continue          # 字体等自带 assets 不在随包口径里
            sfx = suffix_of(info.filename)
            if info.compress_type == 0:
                stored_count += 1
                continue
            if program_deflate_ok and info.filename in program_names:
                continue
            if sfx in MUST_BE_STORED:
                bad_media.append(info)
            elif sfx and declared and sfx in declared:
                # 打包期声明了「这个后缀交给 aapt2 不压缩」，实包却是 DEFLATE ⇒ noCompress 没生效。
                bad_declared.append(info)
            else:
                # 无后缀文件（真语料 18 个零字节 `bgm` 标记）与报告里没声明的后缀：
                # aapt2 的 noCompress 够不着，落 DEFLATE 是机制上限，只统计不判红。
                deflate_other.append(info)
        extless_deflate = [i for i in deflate_other if suffix_of(i.filename) == ""]
        reported_extless = int(pkg.get("extensionless-files", -1) if pkg else -1)
        if reported_extless >= 0 and len(extless_deflate) > reported_extless:
            rep.fail(
                f"无后缀且被 DEFLATE 的条目 {len(extless_deflate)} 条，比 package.report 声明的"
                f" {reported_extless} 条多 ⇒ 有该 STORED 的条目掉了后缀表"
            )

        program_entries = [i for i in erbc_items if i.filename in program_names]
        compressed_program = [i for i in program_entries if i.compress_type != 0]
        if compressed_program:
            names_text = ", ".join(sorted(i.filename.split("/")[-1] for i in compressed_program))
            if allow_compressed:
                rep.warn(f"程序产物是 DEFLATE（{names_text}）：首启要流式 inflate，--allow-compressed 已放行")
            elif pkg.get("program-mode") == "deflate":
                rep.warn(f"程序产物是 DEFLATE（{names_text}）：package.report 声明 program-mode deflate")
            else:
                rep.fail(f"程序产物是 DEFLATE（{names_text}）：noCompress 没生效或用了 -PeratwErbcCompress")

        if bad_media:
            waste = sum(i.file_size - i.compress_size for i in bad_media)
            rep.fail(
                f"{len(bad_media)} 条媒体条目是 DEFLATE（白涨构建时间、首启还要 inflate），"
                f"省 {mib(waste)}：{', '.join(i.filename for i in bad_media[:5])}"
                + ("…" if len(bad_media) > 5 else "")
            )
        if bad_declared:
            rep.fail(
                f"{len(bad_declared)} 条条目没做到 STORED，但打包期把它们列进了 noCompress 表："
                f"{', '.join(i.filename for i in bad_declared[:5])}"
                + ("…" if len(bad_declared) > 5 else "")
            )
        if not bad_media and not bad_declared and not compressed_program:
            rep.ok(f"随包条目 STORED：{stored_count} 条")
        if deflate_other:
            exts = sorted({suffix_of(i.filename) or "(无后缀)" for i in deflate_other})
            rep.warn(
                f"{len(deflate_other)} 条仍被 DEFLATE（后缀不在 aapt2 够得着的范围，"
                f"合计 {mib(sum(i.file_size for i in deflate_other))}，后缀 {', '.join(exts)}）"
            )

        # ---------------- 4. 单条目上限 ----------------
        oversize = [i for i in erbc_items if i.file_size > ENTRY_CAP]
        for info in oversize:
            rep.fail(f"{info.filename} = {info.file_size} 字节，超过 2 GiB - 1 单条目上限")
        if not oversize and erbc_items:
            rep.ok("所有 erbc 条目都在 2 GiB - 1 以内")

        # ---------------- 5. 分片清单对账 ----------------
        parts_entry = STORE_ASSET + "program.parts"
        declared_parts: list[tuple[str, int, str | None]] = []
        if parts_entry in names:
            text = zf.read(parts_entry).decode("utf-8", "replace")
            for raw in text.splitlines():
                raw = raw.strip()
                if not raw:
                    continue
                fields = raw.split("\t")
                # 两列（文件名 TAB 字节数）是设备侧解析器的现行契约；三列（+ SHA-256）
                # 是预留的加强格式 —— 本核验器两种都认，两列时只在清单侧要求 sha。
                if len(fields) not in (2, 3):
                    rep.fail(f"分片清单行格式不对：{raw!r}")
                    continue
                try:
                    size = int(fields[1])
                except ValueError:
                    rep.fail(f"分片清单字节数不是整数：{raw!r}")
                    continue
                declared_parts.append((fields[0], size, fields[2].lower() if len(fields) == 3 else None))
            for part_name, size, sha in declared_parts:
                full = STORE_ASSET + part_name
                if full not in names:
                    rep.fail(f"清单里列了 {part_name}，包里没有")
                elif zf.getinfo(full).file_size != size:
                    rep.fail(f"{part_name} 清单写 {size}，实际 {zf.getinfo(full).file_size}")
                elif sha is not None and entry_sha256(zf, full) != sha:
                    rep.fail(f"{part_name} 与清单里的 SHA-256 不符")
            if declared_parts:
                rep.ok(
                    f"分片清单对账：{len(declared_parts)} 片、合计 "
                    f"{sum(s for _, s, _ in declared_parts)} 字节"
                )
            missing_parts = set(parts) - {STORE_ASSET + n for n, _, _ in declared_parts}
            if missing_parts:
                rep.warn(f"有分片没写进清单：{sorted(p.split('/')[-1] for p in missing_parts)}")

        # ---------------- 6. 清单 <-> 随包对账（CMP-74） ----------------
        man = read_manifest(zf, names)
        rep.out("")
        rep.out("清单 <-> 随包对账（manifest.txt）")
        bad_lines = man.fields.get("_bad-table-lines")
        if bad_lines:
            rep.fail("manifest 里有无法解析的条目行：" + " / ".join(bad_lines.strip().splitlines()))
        if not man.present:
            msg = "缺 assets/erbc.v1/manifest.txt：清单 <-> 随包文件集无法对账（CMP-74）"
            if require_erbc or require_bundled:
                rep.fail(msg)
            else:
                rep.warn(msg)
        else:
            physical = manifest_physical(names)
            fmt = man.format
            if fmt < 0:
                rep.fail("manifest-format 不是整数")
            elif fmt > MANIFEST_FORMAT:
                rep.fail(
                    f"清单格式 {fmt} 比本核验器认识的 {MANIFEST_FORMAT} 新：先升级 tools/apkpack/inspect_apk.py"
                )
            elif fmt < MANIFEST_FORMAT:
                msg = (
                    f"清单是旧格式 v{fmt}（无 manifest-format {MANIFEST_FORMAT}）：artifactId 表只登记 "
                    "program.erbc，侧车/分片不可对账"
                )
                if require_bundled:
                    rep.fail(msg + "；正式包请用新打包脚本重出")
                else:
                    rep.warn(msg)

            declared_artifacts = man.fields.get("artifacts")
            if declared_artifacts is not None:
                try:
                    count = int(declared_artifacts)
                except ValueError:
                    count = -1
                if count != len(man.artifacts):
                    rep.fail(f"manifest 的 artifacts {declared_artifacts} 与 artifact 行数 {len(man.artifacts)} 不符")

            # 6.1 清单自身摘要（package.report 的 manifest-sha256）
            if man.raw is None:
                rep.warn("manifest 读不出来：清单自身摘要无法对账")
            else:
                actual_manifest_sha = hashlib.sha256(man.raw).hexdigest()
                declared_manifest_sha = pkg.get("manifest-sha256", "").strip().lower()
                if not declared_manifest_sha:
                    rep.warn("package.report 缺 manifest-sha256（旧打包脚本）：清单自身无法对账")
                elif declared_manifest_sha != actual_manifest_sha:
                    rep.fail(
                        "package.report 的 manifest-sha256 与清单字节不符："
                        f"报告 {declared_manifest_sha}，实际 {actual_manifest_sha}"
                    )
                else:
                    rep.ok(f"manifest-sha256 对账通过：{actual_manifest_sha[:16]}…")

            shard_count = int(man.fields.get("container-shards", "0") or 0)
            if shard_count != len(man.shards):
                rep.fail(
                    f"manifest 的 container-shards {shard_count} 与 shard 行数 {len(man.shards)} 不符"
                )

            if man.package_artifacts:
                # 6.2 表 <-> 实包**双向**相等 + 逐条字节数/SHA-256
                declared = {full_entry(rel): (size, sha) for rel, size, sha in man.package_artifacts}
                if len(declared) != len(man.package_artifacts):
                    rep.fail(
                        f"manifest package-artifact 有重复路径：{len(man.package_artifacts)} 行 / "
                        f"{len(declared)} 个不同路径"
                    )
                missing = sorted(set(declared) - physical)
                extra = sorted(physical - set(declared))
                if missing:
                    rep.fail(f"清单 package-artifact 登记了包里没有的文件：{missing[:5]}")
                if extra:
                    rep.fail(
                        f"包里 erbc.v1 有清单没登记的文件（清单与随包文件集不可对账）：{extra[:5]}"
                    )
                bad_rows = 0
                for full, (size, sha) in sorted(declared.items()):
                    if full not in names:
                        continue          # 已计入 missing
                    if zf.getinfo(full).file_size != size:
                        rep.fail(f"{full} 清单写 {size} 字节，实际 {zf.getinfo(full).file_size}")
                        bad_rows += 1
                    elif entry_sha256(zf, full) != sha:
                        rep.fail(f"{full} 与清单里的 SHA-256 不符")
                        bad_rows += 1
                if not missing and not extra and not bad_rows:
                    rep.ok(
                        f"清单 <-> 随包：{len(declared)} 个文件双向一致（逐条字节数 + SHA-256）"
                    )
            else:
                # 6.2b 旧打包脚本只把构建清单原样随包：只能拿 artifact 表勉强对。
                candidates = [
                    (full_entry(rel), size, sha) for rel, size, sha in man.artifacts
                    # 分片态下整容器不在包里（由 shard 行表达），跳过这一条。
                    if not (shard_count > 0 and rel.endswith("program.erbc"))
                ]
                if candidates:
                    bad = 0
                    for full, size, sha in candidates:
                        if full not in names:
                            rep.fail(f"清单 artifact 登记了包里没有的文件：{full}")
                            bad += 1
                        elif zf.getinfo(full).file_size != size or entry_sha256(zf, full) != sha:
                            rep.fail(f"{full} 与清单 artifact 行的字节数/SHA-256 不符")
                            bad += 1
                    if not bad:
                        rep.ok(f"清单 artifact 行对账：{len(candidates)} 个文件一致（旧格式，无 package 段）")
                else:
                    rep.warn("清单没有可对账的产物表（旧格式）：清单与随包文件集无法互相证明")

            # 6.3 分片：行序 + 逐片 sha + 汇总 = container-artifact
            if shard_count > 0:
                ordered = [(full_entry(rel), size, sha) for rel, size, sha in man.shards]
                if not declared_parts:
                    rep.fail("分片态缺 program.parts：设备侧无法按行序拼回整容器")
                else:
                    parts_order = [(STORE_ASSET + n, size) for n, size, _ in declared_parts]
                    shards_order = [(full, size) for full, size, _ in ordered]
                    if parts_order != shards_order:
                        rep.fail(
                            "manifest 的 shard 行序/SHA 与 program.parts 不一致："
                            f"清单 {[p.split('/')[-1] for p, _ in shards_order]} / "
                            f"parts {[p.split('/')[-1] for p, _ in parts_order]}"
                        )
                aggregate = hashlib.sha256()
                total = 0
                bad_shard = 0
                for full, size, sha in ordered:
                    if full not in names:
                        rep.fail(f"清单 shard 登记了包里没有的分片：{full}")
                        bad_shard += 1
                        continue
                    if zf.getinfo(full).file_size != size:
                        rep.fail(f"{full} 清单写 {size} 字节，实际 {zf.getinfo(full).file_size}")
                        bad_shard += 1
                        continue
                    one = hashlib.sha256()
                    with zf.open(full) as f:
                        while True:
                            chunk = f.read(1 << 20)
                            if not chunk:
                                break
                            one.update(chunk)
                            aggregate.update(chunk)
                            total += len(chunk)
                    if one.hexdigest() != sha:
                        rep.fail(f"{full} 与清单 shard 行的 SHA-256 不符")
                        bad_shard += 1
                if man.container_artifact is None:
                    rep.fail("分片态缺 container-artifact 行：无法证明分片能拼回整容器")
                else:
                    _, csize, csha = man.container_artifact
                    if total != csize or aggregate.hexdigest() != csha:
                        rep.fail(
                            "分片汇总与 container-artifact 不符："
                            f"汇总 {total} 字节/{aggregate.hexdigest()[:16]}…，"
                            f"清单 {csize} 字节/{csha[:16]}…"
                        )
                    elif not bad_shard:
                        rep.ok(
                            f"分片汇总 = container-artifact：{shard_count} 片 / {total} 字节 / "
                            f"{aggregate.hexdigest()[:16]}…"
                        )
            elif man.container_artifact is not None:
                rel, csize, csha = man.container_artifact
                full = full_entry(rel)
                if full not in names:
                    rep.fail(f"container-artifact 指向的整容器不在包里：{full}")
                elif zf.getinfo(full).file_size != csize or entry_sha256(zf, full) != csha:
                    rep.fail(f"{full} 与 container-artifact 的字节数/SHA-256 不符")
                else:
                    rep.ok("单条产物 = container-artifact（整容器摘要一致）")

        # ---------------- 7. 随包读数与体积上限 ----------------
        if pkg:
            rep.out("")
            rep.out("package.report（打包期自述）")
            for key in ("schema", "program-mode", "program-chunk-mb", "program-parts",
                        "manifest-sha256", "staged-entries", "staged-bytes",
                        "linked-files", "copied-files",
                        "extensionless-files", "align-slack-bytes", "apk-ceiling-bytes",
                        "game-root", "erbc-source"):
                if key in pkg:
                    rep.out(f"  {key:<20} {pkg[key]}")
            if "_error" in pkg:
                rep.warn(f"package.report 读不出来：{pkg['_error']}")
            staged_bytes = int(pkg.get("staged-bytes", 0) or 0)
            staged_entries = int(pkg.get("staged-entries", 0) or 0)
            # 唯一口径：staged_scope()（排除 package.report 自己，算上三个 config）。
            in_scope = [i for i in infos if staged_scope(i.filename)]
            scope_bytes = sum(i.file_size for i in in_scope)
            if staged_bytes:
                if scope_bytes != staged_bytes:
                    rep.fail(
                        f"package.report 说随包 {staged_bytes} 字节，实包 {scope_bytes} 字节"
                        f"（差 {scope_bytes - staged_bytes}，条目 {len(in_scope)} / 报告 {staged_entries}）"
                        "：staging 与打包之间掉过条目"
                    )
                else:
                    rep.ok(f"随包字节与 package.report 一致：{scope_bytes} 字节 / {len(in_scope)} 条")
                if staged_entries and staged_entries != len(in_scope):
                    rep.fail(f"随包条目数对不上：报告 {staged_entries}，实包 {len(in_scope)}")
            ceiling = int(pkg.get("apk-ceiling-bytes", 0) or 0)
        else:
            ceiling = 0
            rep.out("")
            rep.warn(
                "包里没有 assets/erbc.v1/package.report：这是未随包的旧口径构建"
                "（-PeratwSkipAssets 或缺游戏根/产物），只能靠 adb push 喂数据"
            )

        limit_mb = max_mb if max_mb else (ceiling / 1000 / 1000 if ceiling else DEFAULT_MAX_MB)
        rep.out("")
        if total > limit_mb * 1000 * 1000:
            rep.fail(
                f"APK {total / 1000 / 1000:.1f} MB 超过体积上限 {limit_mb:.1f} MB"
                + ("（package.report 的 apk-ceiling-bytes）" if ceiling and not max_mb else "（--max-mb）")
            )
        else:
            rep.ok(f"APK {total / 1000 / 1000:.1f} MB 在上限 {limit_mb:.1f} MB 内")
        if total > PLAY_BASE_LIMIT:
            rep.warn(
                f"APK {mib(total)} 超过 Google Play 基础包 200 MiB 上限：只能侧载，"
                "上架需要 Play Asset Delivery / 分包"
            )

    if rep.fails:
        rep.out(f"结论：{len(rep.fails)} 项失败，{len(rep.warns)} 项告警")
    elif rep.warns:
        rep.out(f"结论：通过（{len(rep.warns)} 项告警）")
    else:
        rep.out("结论：通过")
    return rep


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="检查 APK 的随包装机即玩形态（六类条目 / STORED / 体积上限）")
    ap.add_argument("--apk", type=Path, default=DEFAULT_APK)
    ap.add_argument("--require-erbc", action="store_true", help="没有 erbc assets 也算失败")
    ap.add_argument(
        "--require-bundled",
        action="store_true",
        help="装机即玩判据：erbc.v1 + CSV/resources/sound/plugins + emuera.config 六类齐、且必须都在",
    )
    ap.add_argument(
        "--allow-compressed",
        action="store_true",
        help="允许程序产物是 DEFLATE（-PeratwErbcCompress 打出来的包）",
    )
    ap.add_argument(
        "--max-mb",
        type=int,
        default=None,
        help="体积上限（MB，十进制）；缺省用 package.report 的 apk-ceiling-bytes，报告缺席时 420",
    )
    args = ap.parse_args(argv)

    rep = check(args.apk, args.require_erbc, args.allow_compressed,
                args.require_bundled, args.max_mb)
    print("\n".join(rep.lines))
    return 1 if rep.fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
