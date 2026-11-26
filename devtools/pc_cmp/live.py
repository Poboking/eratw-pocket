# devtools/pc_cmp/live.py — 一条命令一个会话：保持一个 PC 引擎实例活着，方便逐步发现启动提示
# (run.py 是一次性的；找到正确的菜单序列后写回 CASES[...]["menus"])。
# 红线与 run.py 完全一致：引擎只在 <work-root>/<stamp>-<host>-<case>-live/game 里启动，
# plugins/bad 真实复制，其余目录 junction，sav 私有；退出时打印语料根指纹对比。
#
# 用法:  python live.py --case buckets --host skia4981 [--keep] [--ttl 900]
#   然后往 <run_root>/cmds.ndjson 追加一行 JSON，例如
#     {"cmd":"log","id":9,"hideInfo":false}   拿整个控制台缓冲
#     {"cmd":"screen","id":10}                拿屏幕文本（找菜单编号）
#     {"cmd":"buttons","id":11}
#     {"cmd":"key","key":"skip"} / {"cmd":"input","text":"1"} / {"cmd":"state","id":12}
#     {"cmd":"__exit__"}                       关闭会话
#   子进程事件追写到 <run_root>/events.ndjson，心跳/统计在 <run_root>/status.json
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402  (同目录引擎：Session / build_copy_root / drop_copy_root / fingerprint)


def main():
    ap = argparse.ArgumentParser(description="PC 对拍装置 —— 交互式保活会话")
    ap.add_argument("--case", choices=sorted(R.CASES), default="buckets")
    ap.add_argument("--host", choices=sorted(R.HOSTS), default="skia4981")
    ap.add_argument("--ttl", type=int, default=900, help="最长保活秒数（无命令也会到时收尾）")
    ap.add_argument("--seed-saves", default="", help="存档源目录；空 = 用本 case 的默认种子")
    ap.add_argument("--no-seed", action="store_true")
    ap.add_argument("--keep", action="store_true", help="保留副本根供取证")
    a = ap.parse_args()

    stamp = time.strftime("%Y%m%d-%H%M%S")
    seed = a.seed_saves
    if not seed and not a.no_seed and R.CASES[a.case].get("seed"):
        seed = R.default_seed_dir()
    before = [R.fingerprint(p) for p in R.guarded_roots()]
    build = R.build_copy_root(a.case, a.host, stamp + "-live", seed or None)
    rr = build["run_root"]
    cmds_path = os.path.join(rr, "cmds.ndjson")
    ev_path = os.path.join(rr, "events.ndjson")
    st_path = os.path.join(rr, "status.json")
    for p in (cmds_path, ev_path):
        open(p, "w", encoding="utf-8").close()
    print("== live copy root (PC runs ONLY here) ==", flush=True)
    print("  game     : " + build["game"], flush=True)
    print("  exe      : " + os.path.basename(build["exe"]) + " " + " ".join(build["argv"]), flush=True)
    print("  junctions: %d  copied: %s  seeded: %s" % (len(build["links"]), ",".join(build["copied_dirs"]), ",".join(build["seeded_saves"])), flush=True)
    print("  cmds     : " + cmds_path, flush=True)
    print("  events   : " + ev_path, flush=True)
    sys.stdout.flush()

    s = R.Session(build["exe"], build["game"], build["argv"], os.path.join(rr, "stdout.jsonl"))
    s.start()
    forwarded = 0
    cpos = 0
    t0 = time.time()
    stop = False
    evh = open(ev_path, "a", encoding="utf-8")
    while not stop and time.time() - t0 < a.ttl:
        evs = s.snapshot()
        if len(evs) > forwarded:
            for e in evs[forwarded:]:
                evh.write(json.dumps(e, ensure_ascii=False) + R.NL)
            evh.flush()
            forwarded = len(evs)
        try:
            size = os.path.getsize(cmds_path)
        except OSError:
            size = 0
        if size > cpos:
            with open(cmds_path, "r", encoding="utf-8") as fh:
                fh.seek(cpos)
                chunk = fh.read()
            cpos = os.path.getsize(cmds_path)
            for line in chunk.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except ValueError:
                    print("  bad cmd line: " + line[:80], flush=True)
                    continue
                if obj.get("cmd") == "__exit__":
                    stop = True
                    break
                if obj.get("cmd") == "__advance__":
                    ans = R.advance_boot(s, list(obj.get("menus") or []),
                                         rounds=int(obj.get("rounds", 20)),
                                         idle=float(obj.get("idle", 6.0)))
                    print("  advanced: " + " | ".join(ans), flush=True)
                    continue
                s.send(obj)
        with open(st_path, "w", encoding="utf-8") as fh:
            json.dump({"alive": s.proc.poll() is None, "pid": s.proc.pid, "events": forwarded,
                       "uptime": round(time.time() - t0, 1), "game": build["game"]}, fh, ensure_ascii=False)
        time.sleep(0.5)
    print("== shutting down ==", flush=True)
    evh.flush()
    for e in s.snapshot()[forwarded:]:
        evh.write(json.dumps(e, ensure_ascii=False) + R.NL)
    evh.close()
    # Hard kill, never the graceful quit cmd: a clean exit rewrites <data_root>/sav/global.sav
    # (run.py module docstring rule 2; the depth-2 guard fired on exactly that at 23:49:38).
    import subprocess
    subprocess.call(["taskkill", "/T", "/F", "/PID", str(s.proc.pid)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    s.close()
    after = [R.fingerprint(p) for p in R.guarded_roots()]
    same = (before == after)
    print("CORPUS-UNTOUCHED: " + ("PASS" if same else "FAIL"), flush=True)
    if not same:
        for i, (x, y) in enumerate(zip(before, after)):
            if x != y:
                print("  changed: " + x.split(" | ")[0], flush=True)
    with open(os.path.join(rr, "guard.json"), "w", encoding="utf-8") as fh:
        json.dump({"before": before, "after": after, "same": same}, fh, ensure_ascii=False, indent=1)
    if not a.keep:
        R.drop_copy_root(rr, build["links"])
        print("dropped copy root " + rr, flush=True)
    else:
        print("kept copy root " + rr, flush=True)
    return 0 if same else 2


if __name__ == "__main__":
    sys.exit(main())
