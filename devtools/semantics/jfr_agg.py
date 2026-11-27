"""P0 JFR 热点聚合：把 jfr print 的栈样本聚合成自耗时表 / 相位归属表。

只读文本，不碰任何业务代码。用法：
  python jfr_agg.py samples.txt [--phase]
"""
import re
import sys
from collections import Counter, defaultdict

FRAME = re.compile(r"^\s{4,}.*$")


def frames(path):
    events = []
    cur = None
    in_stack = False
    for line in open(path, encoding="utf-8", errors="replace"):
        s = line.rstrip("\n")
        if s.startswith("jdk.") and s.endswith("{"):
            cur = {"type": s.split(" {")[0].strip(), "frames": []}
            in_stack = False
            continue
        if cur is None:
            continue
        if "stackTrace = [" in s:
            in_stack = True
            continue
        if in_stack:
            if s.strip() == "]":
                in_stack = False
                events.append(cur)
                cur = None
                continue
            f = s.strip()
            if f:
                cur["frames"].append(f)
            continue
        if s.strip() == "}":
            if cur["frames"]:
                events.append(cur)
            cur = None
    return events


def short(f):
    # com.eratw.port.erbcc.fn.Foo.bar(Foo.kt:12) -> erbcc.fn.Foo.bar
    f = re.sub(r"\(.*", "", f)
    parts = f.split(".")
    if len(parts) >= 2:
        return ".".join(parts[-3:]) if len(parts) >= 3 else f
    return f


def main():
    path = sys.argv[1]
    ev = frames(path)
    by_type = Counter(e["type"] for e in ev)
    self_c = Counter()
    stack2 = Counter()
    for e in ev:
        if not e["frames"]:
            continue
        self_c[short(e["frames"][0])] += 1
        if len(e["frames"]) > 1:
            stack2[short(e["frames"][1])] += 1
    total = sum(by_type.values())
    print(f"events total={total}")
    for k, v in by_type.most_common():
        print(f"  {k}: {v}")
    print("\n== self (top frame) ==")
    for name, n in self_c.most_common(30):
        print(f"{n:7d} {100.0*n/total:6.2f}%  {name}")
    print("\n== caller (2nd frame) ==")
    for name, n in stack2.most_common(20):
        print(f"{n:7d} {100.0*n/total:6.2f}%  {name}")

    if "--phase" in sys.argv:
        markers = [
            ("切片/预扫描 slice", ["sliceCorpus", "slice", "prescan", "preScan"]),
            ("符号表/对齐 symbols", ["SymbolTable", "symbols", "align"]),
            ("逐函数编译 compileFunction", ["compileFunction", "compileSlice", "FnCompiler"]),
            ("常量折叠 fold", ["Fold", "fold", "PureMethods"]),
            ("事件入口 entry", ["eventEntry", "EventEntr"]),
            ("冻结 freeze", ["freeze", "Freeze"]),
            ("落盘 writer", ["ErbcWriter", "write", "flush", "FileChannel"]),
        ]
        print("\n== 相位归属（栈里出现即计一次）==")
        for label, keys in markers:
            n = sum(1 for e in ev if any(any(k in fr for k in keys) for fr in e["frames"]))
            print(f"{n:7d} {100.0*n/total:6.2f}%  {label}")


if __name__ == "__main__":
    main()
