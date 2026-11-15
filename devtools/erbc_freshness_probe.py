#!/usr/bin/env python3
"""erbc 新鲜度探针 —— 让「要不要重编 6 分钟的 erbc」由编译器源码内容决定。

背景（2026-11-14 出包剖析）
--------------------------------------------------------------------------
`devtools/pack_one` 旧判据是「`program.erbc` 的 mtime 必须新于三棵源码树里任何文件的
mtime，且本次没拉过代码」。这个判据过保守，每天 git 快进后即便只改了 BOARD.md / 文档，
也会把 6m23s 的 `:tools:erbcc:compileCorpus` 重跑一遍；更糟的是它扫了 buildSrc 第三棵树，
而真正决定 erbc 产物的 `compiler-digest` 只算两棵（见下），两边口径不一致。

erbc 是「编译器源码 + 语料」的纯函数，与 app 代码、文档、git 提交数都无关。
本脚本复刻 `ErbcFreshness.compilerDigestOf()` / `ErbcCompilerDigest.compute()` 的**同一口径**，
把当前两棵源码树的 `compiler-digest` 与 `erbc.v1/manifest.txt` 里登记的值对账：

  * 一致 ⇒ 编译器没变，`program.erbc` 可以复用（出包期 CMP-66 硬闸也只会得出同一结论）；
  * 不一致 ⇒ 必须重编，否则出包 6 分钟后一定被 `stageGameAssets` 判红、整包作废。

摘要口径（改动必须与上面两个 Kotlin 文件同步，否则会误判）
--------------------------------------------------------------------------
两棵源码树 `core-vm/src/main/kotlin`、`tools/erbcc/src/main/kotlin` 下递归的**全部 `.kt`**，
按相对**仓库根**的路径（`\\` → `/`）合流后全局字典序，逐文件把
`u32be(路径字节数) ‖ 路径 UTF-8 ‖ u32be(文件字节数) ‖ 文件字节` 喂 SHA-256。

用法
--------------------------------------------------------------------------
    python devtools/erbc_freshness_probe.py                 # JSON 读数
    python devtools/erbc_freshness_probe.py --short        # MATCH/MISMATCH/ERROR 一行（给 pack_one）
    python devtools/erbc_freshness_probe.py --check        # 不一致时退出码 2（给 CI / 日包预检）

退出码：0 = 计算完成（含 MISMATCH，由调用方决定动作）；2 = 无法判定或 --check 判为不一致。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import time
from pathlib import Path

SOURCE_ROOTS = ("core-vm/src/main/kotlin", "tools/erbcc/src/main/kotlin")
MANIFEST_REL = Path("tools/erbcc/build/erbc-out/erbc.v1/manifest.txt")


def repo_root_of(script: Path) -> Path:
    """脚本固定挂在 <repo>/devtools/ 下；仓库根 = 脚本目录的父目录。"""
    return script.resolve().parent.parent


def compiler_digest(repo_root: Path) -> tuple[str, int]:
    """与 ErbcCompilerDigest.compute / ErbcFreshness.compilerDigestOf 逐字同口径。"""
    repo_root = repo_root.resolve()
    pairs: list[tuple[str, Path]] = []
    for rel_root in SOURCE_ROOTS:
        base = repo_root / rel_root
        if not base.is_dir():
            raise FileNotFoundError(f"编译器源码树不存在：{base}（compiler-digest 没有判据）")
        for p in base.rglob("*.kt"):
            if p.is_file():
                pairs.append((p.relative_to(repo_root).as_posix(), p))
    if not pairs:
        raise RuntimeError(f"编译器源码树里没有 .kt 文件：{list(SOURCE_ROOTS)}")
    pairs.sort(key=lambda item: item[0])

    sha = hashlib.sha256()
    for rel, path in pairs:
        rel_bytes = rel.encode("utf-8")
        body = path.read_bytes()
        sha.update(struct.pack(">I", len(rel_bytes)))
        sha.update(rel_bytes)
        sha.update(struct.pack(">I", len(body)))
        sha.update(body)
    return sha.hexdigest(), len(pairs)


def manifest_digest(manifest: Path) -> tuple[str, int]:
    """读 manifest 的 compiler-digest / compiler-sources；缺行即拒，不猜。"""
    if not manifest.is_file():
        raise FileNotFoundError(f"找不到 erbc 清单：{manifest}")
    digest = ""
    sources = -1
    with manifest.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("source "):
                break  # 4324 条 source 行之后没有头字段，提前收工
            parts = line.split()
            if len(parts) == 2 and parts[0] == "compiler-digest":
                digest = parts[1].lower()
            elif len(parts) == 2 and parts[0] == "compiler-sources":
                try:
                    sources = int(parts[1])
                except ValueError:
                    pass
    if len(digest) != 64 or not all(c in "0123456789abcdef" for c in digest):
        raise ValueError(f"manifest 缺合法的 compiler-digest 行：{manifest}")
    return digest, sources


def probe(repo_root: Path, manifest: Path) -> dict:
    started = time.perf_counter()
    try:
        current, count = compiler_digest(repo_root)
        recorded, recorded_count = manifest_digest(manifest)
    except Exception as exc:  # 缺树/缺清单/读失败：一律 fail-closed，不静默放行
        return {
            "ok": False,
            "match": False,
            "error": str(exc),
            "repo_root": str(repo_root),
            "manifest": str(manifest),
            "elapsed_ms": round((time.perf_counter() - started) * 1000),
        }
    return {
        "ok": True,
        "match": current == recorded,
        "current": current,
        "manifest_digest": recorded,
        "sources": count,
        "manifest_sources": recorded_count,
        "repo_root": str(repo_root),
        "manifest": str(manifest),
        "elapsed_ms": round((time.perf_counter() - started) * 1000),
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="erbc compiler-digest 新鲜度探针")
    ap.add_argument("--repo-root", default="", help="仓库根（缺省取脚本上级目录）")
    ap.add_argument("--manifest", default="", help="erbc.v1/manifest.txt 路径")
    ap.add_argument("--short", action="store_true", help="只输出 MATCH/MISMATCH/ERROR 一行")
    ap.add_argument("--check", action="store_true", help="不一致或无法判定时退出码 2")
    args = ap.parse_args(argv)

    script = Path(__file__)
    root = Path(args.repo_root).resolve() if args.repo_root else repo_root_of(script)
    manifest = Path(args.manifest).resolve() if args.manifest else (root / MANIFEST_REL)
    r = probe(root, manifest)

    if args.short:
        if not r["ok"]:
            print(f"ERROR {r['error']}")
        elif r["match"]:
            print(f"MATCH {r['current']}")
            return 0
        else:
            print(f"MISMATCH {r['current']} {r['manifest_digest']}")
        return 2 if args.check else 0

    print(json.dumps(r, ensure_ascii=False, indent=2))
    if args.check and (not r["ok"] or not r["match"]):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
