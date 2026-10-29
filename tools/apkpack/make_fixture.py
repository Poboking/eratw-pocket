#!/usr/bin/env python
"""生成「随包链路」的最小伪游戏根（不依赖 328 MB 真产物，用来自证打包机制）。

用法（在 eratw-android 下）::

    python tools/apkpack/make_fixture.py                     # 默认写 app/build/pkgfixture/game
    python tools/apkpack/make_fixture.py --out D:/tmp/fixture --program-mb 3
    python tools/apkpack/make_fixture.py --print-only        # 只打将要写的清单

产出目录刻意覆盖随包链路会碰到的**每一类坑**，这样一次 `assembleDebug` 就能把机制钉死：

* ``erbc.v1/``            program.erbc（可压缩内容，便于对比 STORED/DEFLATE 两种口径）
                          + program.meta（第 3 行是真算出来的 ``program-sha256 "<hex>"``，
                            staging 的配对对账必须过）+ manifest.txt
* ``CSV/``                ``.csv`` / ``.als`` 混排 + ``_default.config``（下划线开头的**文件**：
                          aapt2 的 ``<dir>_*`` 只挡目录，这条要靠实包验证）
* ``resources/``          ``.webp`` / ``.png`` / ``.jpg`` / ``.xml`` / ``.txt`` / ``.html``、
                          含空格的目录（``AI arts``）、中日文目录名（``初期立绘``/``差し替え画像``）、
                          大写后缀（``UP.WEBP``）、**无后缀文件**、零字节文件、9 MiB 单条
* ``sound/``              ``.mp3`` / ``.ogg`` / ``.wav`` + ``游戏bgm/<地名>/bgm``（无后缀、零字节，
                          真语料里 18 个这种标记文件）
* ``emuera.config``       游戏根标志文件（GameStorage 的「像游戏根」判据）

退出码：0 = 写好了，1 = 参数/磁盘出错。
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent   # eratw-android
DEFAULT_OUT = REPO_ROOT / "app" / "build" / "pkgfixture" / "game"

EMUERA_CONFIG = """// fixture emuera.config（字段名照原版，值只为让配置解析有得读）
GAME_TITLE "eratw-pkg-fixture"
DRAW_WAIT 0
NO_COMMAND -1
CSV_ENCODING SHIFT_JIS
USE_KEYBOARD_MACROS -1
"""

MANIFEST = """# fixture compile manifest
file ERB/TEST.ERB 12
function 1
"""


def blob(seed: bytes, size: int) -> bytes:
    """确定性字节流：重复的半随机块（能压缩，所以 DEFLATE 口径量得出来）。"""
    block = bytearray()
    counter = int.from_bytes(seed, "big")
    while len(block) < size:
        counter = (counter * 6364136223846793005 + 1442695040888963407) % (1 << 64)
        # 每 64 字节重复一次：既非全零（能压但不能压成 0），又能让 deflate 显出差异
        chunk = counter.to_bytes(8, "big") * 8
        block += chunk
    return bytes(block[:size])


def text(marker: str) -> bytes:
    return (f"# fixture {marker}\n" + ("eratw pkg400 fixture line\n" * 24)).encode("utf-8")


def sha256_of(path: Path) -> str:
    """流式摘要（328 MB 精确尺寸模式用得上，不进堆）。"""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def build(out: Path, program_mb: float, program_bytes: int = 0, meta_bytes: int = 0) -> list[tuple[str, int]]:
    written: list[tuple[str, int]] = []

    def put(rel: str, data: bytes) -> None:
        p = out / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        written.append((rel, len(data)))

    def put_sized(rel: str, size: int, seed: bytes) -> None:
        """按**精确字节数**落一个文件（分块写，不进堆整份）。"""
        p = out / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        chunk = blob(seed, 1 << 20)
        with p.open("wb") as f:
            left = size
            while left > 0:
                n = min(len(chunk), left)
                f.write(chunk[:n])
                left -= n
        written.append((rel, size))

    if program_bytes:
        # 精确尺寸模式：只用来量「全 STORED 随包 APK 的体积读数」，
        # 内容重复度不代表真产物（DEFLATE 档的读数别用这个模式取）。
        erbc_size = program_bytes
        put_sized("erbc.v1/program.erbc", erbc_size, b"\x00\xff\x55\xaa")
        sha = sha256_of(out / "erbc.v1" / "program.erbc")
    else:
        erbc = blob(b"\x01\x02\x03\x04\x05\x06\x07\x08", int(program_mb * 1024 * 1024))
        put("erbc.v1/program.erbc", erbc)
        sha = hashlib.sha256(erbc).hexdigest()

    meta_head = (
        "format-version 1\n"
        'engine "erbcc fixture-0"\n'
        f'program-sha256 "{sha}"\n'
        "labels 0\nfiles 0\ndata-sections 0\nfunctions 0\n"
        "global-dims 0\nprivate-dims 0\ndim-config 0\nmacros 0\nconstants 0\nend\n"
    ).encode("utf-8")
    if meta_bytes and meta_bytes > len(meta_head):
        # 精确尺寸模式：按整行填充，再补空格到一个字节不差（侧车头三行不动，
        # staging 的对账只读 `program-sha256` 那一行）。
        filler = b'file "fixture" "pad"\n'
        pad = meta_bytes - len(meta_head) - 1
        n = pad // len(filler)
        meta = meta_head + filler * n + b" " * (pad - len(filler) * n) + b"\n"
        if len(meta) != meta_bytes:      # 兜底：截断/补齐到精确字节数
            meta = meta[:meta_bytes] if len(meta) > meta_bytes else meta + b" " * (meta_bytes - len(meta))
        put("erbc.v1/program.meta", meta)
    else:
        put("erbc.v1/program.meta", meta_head)
    put("erbc.v1/manifest.txt", MANIFEST.encode("utf-8"))

    put("emuera.config", EMUERA_CONFIG.encode("utf-8"))
    put("default.config", EMUERA_CONFIG.encode("utf-8"))

    put("CSV/Abl.csv", text("CSV/Abl.csv"))
    put("CSV/Abl.als", text("CSV/Abl.als"))
    put("CSV/Chara/chara001.csv", text("CSV/Chara/chara001.csv"))
    put("CSV/_default.config", text("CSV/_default.config（下划线开头的文件，不该被 aapt2 忽略）"))

    put("resources/title.webp", blob(b"\x11\x22\x33\x44\x55\x66\x77\x88", 96 * 1024))
    put("resources/AI arts/104_別顔.webp", blob(b"\x01\x00\xff\x0e", 48 * 1024))
    put("resources/初期立绘/_image_list.html", text("resources/初期立绘/_image_list.html"))
    put("resources/isaac/cg/_cg.xml", text("resources/isaac/cg/_cg.xml"))
    put("resources/isaac/cg/big.webp", blob(b"\x02\x00\xff\x0e", 9 * 1024 * 1024))
    put("resources/差し替え画像/01.png", blob(b"\x03\x00\xff\x0e", 32 * 1024))
    put("resources/As06MARK-IV/102_拼接.jpg", blob(b"\x04\x00\xff\x0e", 24 * 1024))
    put("resources/uppercase/UP.WEBP", blob(b"\x05\x00\xff\x0e", 16 * 1024))
    put("resources/list.txt", text("resources/list.txt"))
    put("resources/marks", text("resources/marks（无后缀文件：aapt2 noCompress 按后缀匹配，够不着）"))
    put("resources/empty.webp", b"")

    put("sound/bad-apple-audio.mp3", blob(b"\x06\x00\xff\x0e", 64 * 1024))
    put("sound/game.ogg", blob(b"\x07\x00\xff\x0e", 40 * 1024))
    put("sound/game.wav", blob(b"\x08\x00\xff\x0e", 40 * 1024))
    put("sound/游戏bgm/人间之里/bgm", b"")
    put("sound/游戏bgm/红魔馆/bgm", b"")
    return written


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="生成随包链路的最小伪游戏根")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--program-mb", type=float, default=3.0, help="program.erbc 体积（MiB）")
    ap.add_argument(
        "--program-bytes",
        type=int,
        default=0,
        help="program.erbc **精确字节数**（覆盖 --program-mb；量全 STORED 体积读数时用）",
    )
    ap.add_argument("--meta-bytes", type=int, default=0, help="program.meta 精确字节数（同上）")
    ap.add_argument("--print-only", action="store_true", help="只打清单，不落盘")
    args = ap.parse_args(argv)

    if args.print_only:
        for rel in KNOWN_NAMES:
            print(rel)
        return 0
    if args.program_mb <= 0 and args.program_bytes <= 0:
        print("--program-mb / --program-bytes 至少给一个正数", file=sys.stderr)
        return 1

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    written = build(out, args.program_mb, args.program_bytes, args.meta_bytes)
    total = sum(n for _, n in written)
    print(f"fixture 根  {out}")
    print(f"条目        {len(written)} 个文件，合计 {total:,} 字节（{total / 1024 / 1024:.1f} MiB）")
    for rel, size in written:
        print(f"  {rel:<44} {size:>10} 字节")
    return 0


KNOWN_NAMES = [
    "erbc.v1/program.erbc", "erbc.v1/program.meta", "erbc.v1/manifest.txt",
    "emuera.config", "default.config",
    "CSV/Abl.csv", "CSV/Abl.als", "CSV/Chara/chara001.csv", "CSV/_default.config",
    "resources/title.webp", "resources/AI arts/104_別顔.webp",
    "resources/初期立绘/_image_list.html", "resources/isaac/cg/_cg.xml",
    "resources/isaac/cg/big.webp", "resources/差し替え画像/01.png",
    "resources/As06MARK-IV/102_拼接.jpg", "resources/uppercase/UP.WEBP",
    "resources/list.txt", "resources/marks", "resources/empty.webp",
    "sound/bad-apple-audio.mp3", "sound/game.ogg", "sound/game.wav",
    "sound/游戏bgm/人间之里/bgm", "sound/游戏bgm/红魔馆/bgm",
]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
