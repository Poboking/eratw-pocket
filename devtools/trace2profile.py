"""把真机 `am profile` 采到的 ART method-trace 转成 `baseline-prof.txt`（T2-63）。

## 为什么不用 profgen

`am profile start` 在本机（vivo V2199A / Android 14 user build）产出的文件 magic 是
`SLOW`（`0x574f4c53`），**不是** ART Profile 的 `pro\\0`，`com.android.tools.profgen`
的 `ArtProfileKt.ArtProfile(File)` 直接抛异常（2026-10-27 实测，vivo V2199A）。所以 `profgen`
那条「`.prof` → `baseline-prof.txt`」的反向转换用不上。

好消息是这个格式本身信息量更足：每条 record 是一条**四元组**

    类名 / 方法名 / 签名 / 源文件名

例如 `android.os.ParcelFileDescriptor` `close` `()V` `ParcelFileDescriptor.java`。

## ⚠️ 计数没有频次意义（2026-10-27 实测定案，别按占比读）

两种模式（`am profile start` 带/不带 `--sampling`）产出的都是「**方法表 + 事件流**」，
频次编码在二进制事件流里且未逆向 ⇒ 本脚本抽出来的是「**执行过的方法全集**」。
方法表每方法的 record 条数**不恒定**（第一轮 882 methods = 1764 records = 2×、
第二轮 1021 = 2042 = 2×，第三轮 1128 = 1128 = **1×**——机制未逆向，比值不作依据）：

- **不是**真实调用次数；
- **不是**时间占比（采样模式的样本数在事件流里，本脚本读不到）。

因此 `--top` 的排序是任意的，只起「上限」作用——**必须 ≥ 方法总数**（缺省 3000，
实测最大 1128，安全）；设小了会按任意顺序丢方法。`--stats` 的计数仅供对账。

## 输出

写成 AGP 认的 `baseline-prof.txt`：

    HSPLcom/eratw/port/core/sem/StatementExecutor;->execute(ILcom/eratw/port/core/sem/ExecContext;)V

`HSP` = hotspot + startup + post-startup 三档全打，让 dex2oat 在安装时（API 31+）
和首启后台编译（profileinstaller，API 24~30）都按 speed 编译这些方法。
`--only-prefix` 用来把规则限定在自己的代码上（缺省 `com.eratw.port`）；
传空串则连 AndroidX / Kotlin / ART 侧的热点一起收。

## 用法

    python trace2profile.py <trace 文件> -o <输出 txt> [--top 3000] [--only-prefix com.eratw.port]

`--stats` 会额外把 top 50 打到 stdout，便于回执里贴读数。
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path


def extract_strings(data: bytes) -> list[str]:
    """按出现顺序切出所有可打印 ASCII 串（长度 >= 1）。

    不解析 record 的二进制头部：不同 ART 版本的头长度和字段都在变，而四元组本身
    在两种模式下都稳定。用「源文件名」当右锚、签名当左锚，比读二进制偏移更耐版本。
    """
    out: list[str] = []
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if 0x20 <= b < 0x7F:
            j = i
            while j < n and 0x20 <= data[j] < 0x7F:
                j += 1
            out.append(data[i:j].decode("ascii"))
            i = j
        else:
            i += 1
    return out


_SOURCE_SUFFIXES = (".java", ".kt")


def _is_class_token(t: str) -> bool:
    """类名：点号分隔的 Java 标识符串，允许内部类的 `$`。"""
    if not t or " " in t or "(" in t or ";" in t or "/" in t:
        return False
    if t.endswith(_SOURCE_SUFFIXES):
        return False
    head = t.split(".", 1)[0]
    # 至少要有「a.b」这种形状，且首段不是纯数字/十六进制
    if "." not in t:
        return False
    return head[:1].isalpha() or head[:1] == "_"


def _is_method_token(t: str) -> bool:
    if not t or " " in t or "(" in t or ";" in t or "/" in t:
        return False
    if t.endswith(_SOURCE_SUFFIXES):
        return False
    # 方法名里不可能出现点（`<init>` / `<clinit>` 允许）
    return "." not in t


def _is_sig_token(t: str) -> bool:
    if not t.startswith("(") or ")" not in t:
        return False
    # 必须恰好是「(参数)返回类型」，中间不能有空格
    return " " not in t


def collect(data: bytes, only_prefix: str) -> Counter[tuple[str, str, str]]:
    strings = extract_strings(data)
    counter: Counter[tuple[str, str, str]] = Counter()
    for i, s in enumerate(strings):
        if not s.endswith(_SOURCE_SUFFIXES):
            continue
        if i < 3:
            continue
        sig, name, cls = strings[i - 1], strings[i - 2], strings[i - 3]
        if not _is_sig_token(sig):
            continue
        if not _is_method_token(name):
            continue
        if not _is_class_token(cls):
            continue
        if only_prefix and not cls.startswith(only_prefix):
            continue
        counter[(cls, name, sig)] += 1
    return counter


def to_rule(cls: str, name: str, sig: str) -> str:
    return f"HSPL{cls.replace('.', '/')};->{name}{sig}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("trace", type=Path)
    ap.add_argument("-o", "--out", type=Path, required=True)
    ap.add_argument("--top", type=int, default=3000)
    ap.add_argument("--only-prefix", default="com.eratw.port")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()

    data = args.trace.read_bytes()
    counter = collect(data, args.only_prefix)
    if not counter:
        raise SystemExit("没抽到任何方法记录：检查文件是否是 `am profile` 的 SLOW trace")

    total = sum(counter.values())
    top = counter.most_common(args.top)

    if args.stats:
        print(f"--- trace {args.trace.name}: {len(data)} B，命中 {len(counter)} 个方法，"
              f"record 合计 {total}（方法表条数不恒定，无频次意义）---")
        for (cls, name, sig), n in top[:50]:
            print(f"{n:8d}  {cls}->{name}{sig}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    # 注释行只用 ASCII：这个文件会被 AGP、profgen 和一堆编辑器各按各的默认编码读，
    # 中文注释在 GBK 默认的 PowerShell 下显示成乱码，容易让人误判成文件损坏。
    lines = [f"# T2-63 device-collected baseline profile ({len(top)} methods, "
             f"source trace {args.trace.name}, {total} records)"]
    lines += [to_rule(c, n, s) for (c, n, s), _ in top]
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"写出 {args.out}：{len(top)} 条规则（源 {len(counter)} 个方法，{total} 个样本）")


if __name__ == "__main__":
    main()
