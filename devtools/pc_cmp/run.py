#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PC-original 对拍装置 (devtools/pc_cmp/run.py) -- M3-2-B event buckets, reusable for KI-5.

ONE COMMAND, BOTH SIDES
  python run.py --case buckets              # PC + port, same corpus bytes, verdict printed
  python run.py --list                      # host / case table
  python run.py --case buckets --side pc    # PC only
  python run.py --side port --full-corpus   # port reading only

WHY THIS SHAPE (the only trustworthy witness for "how does PC really dispatch")
  Run the real engine and read its OWN console buffer back. The 4.981 fork ships a
  stdin/stdout JSON-Lines agent protocol:
    reference/emuera-skia-4.981/Emuera/Program.cs:79-84   option --CliDebug (alias -CliDebug)
    reference/emuera-skia-4.981/Emuera/Program.cs:108-115  CliDebugMode implies DebugMode + InitIO()
    reference/emuera-skia-4.981/Emuera/Program.cs:306-316  modal dialogs become stdout events
    reference/emuera-skia-4.981/Emuera/UI/CliDebug/CliDebugHost.cs:93-148  hello/ready/out/wait/state
    same file :269-301  inbound cmd table: input/click/key/debug/watch/trace/screen/state/buttons/log/quit
    same file :442-453  DoLog -> console.GetLog(hideInfo) = the WHOLE buffer, order = dispatch order
    same file :107-137  stdin loop; EOF (line == null) -> DispatchQuit
  --CliDebug is used instead of the golden runbook's OUTPUTLOG because the fork commented the
  ERB statement out entirely (Runtime/Script/Statements/BuiltInFunctionCode.cs:85 '//OUTPUTLOG,'),
  and instead of SendKeys because a pipe protocol is deterministic and survives a minimized window.
  The literals exist in the shipped exe as UTF-16LE (search Emuera_skiaV16_x64.exe for
  Buffer.from('--CliDebug','utf16le') -> present; latin1 -> absent).

HARD RED LINE (self-proved by every run)
  The exe runs ONLY inside a throwaway copy root under <wt-parent>/.pc-cmp-run/. No process is
  started with a corpus root (eraTW4.981-261111-EAGLOV3 / eraTW4.981-261017-EAGLOV3 /
  eratw-画蛇添足版eratw4.980) or eratw-sub-modding as cwd, and nothing is written there.
  Top-level mtime+size fingerprint of every root (depth 2: top entries + immediate children, so a
  write deeper inside still shows through the containing dir's mtime) is taken before and after and
  compared; the run prints 'CORPUS-UNTOUCHED: PASS/FAIL' plus the copy-root path and exits 2 on FAIL.

FOUR RULES ANY REUSE OF THIS RIG MUST KEEP (all learned the hard way, 2026-11-25 / 26):
  1. COPY_DIRS = plugins, bad are REAL copies, never junctions. The engine writes
     plugins/qol_data.db (and bad/ scratch) at boot; a junction writes straight THROUGH into the
     corpus root -- one such launch bumped 261111/plugins + bad mtimes before the guard caught it.
  2. Shutdown is 'taskkill /T /F'; the graceful 'quit' cmd is NOT sent. Measured reason that survives:
     the engine rewrites sav/global.sav INSIDE the copy root during a session (2026-11-25 23:53:22,
     517 B / sha256 c2dc2af97b619ffb), and this build refuses the debug cmd ('该功能不能在现版本使用',
     measured 2026-11-26 00:04), so where SavDir actually resolves is not observable from the rig ->
     staying away from any clean-exit path is belt-and-braces, not a proven necessity.
     CORRECTED ATTRIBUTION (lead forensics 2026-11-26): the 23:49:38 / 23:51:11 mtimes seen on
     <corpus>/sav/global.sav were NOT an engine exit save. Every engine-written global.sav here is
     517 B / c2dc2af97b619ffb, while the corpus copy is 514 B / fe95116d9d86f438 = the byte-for-byte
     seed source, and that run's copyroot.json already real-copied [bad, plugins] with no sav link
     (st_nlink=1 both sides, GetFileAttributes=0x10 both sides, i.e. no junction and no hardlink).
     => an old seed step wrote the corpus root directly; the destination lock
     (assert_write_dest) is what closes that hole. Note also that global.sav is not identical between
     installs (the 2026-10-18 snapshot <internal-workdir>/<sample-backup>/sav-20261018-110513/global.sav is 544 B /
     af8e331093c5, today's 4.980 is 514 B) and 261017/sav ships EMPTY, so whether the root file was
     overwritten or newly created stays TODO(verify); the file is left in place for forensics by the
     lead's order -- do not delete or "restore" it.
  3. Two build-time locks. assert_copy_root_policy (def :358, called :479) raises when the copy root
     sits inside a corpus root, when a writable dir name arrives as a junction, or when a junction
     target is missing. assert_write_dest (def :341) is called at EVERY write site -- engine-file copy,
     writable-dir copytree, ERB top copy, fixture injection, seed save, emuera.config rewrite -- and
     raises unless realpath(dest) is inside this run's copy root. The second lock exists because the
     first one could not see the 23:51:11 vector (that write had no junction in it at all).
  4. Prove both before reusing the rig for KI-5: 'python run.py --selfcheck' -- 12 assertions, no exe,
     no copy root, ~2 s; three of them are these locks' tripwires (writable junction, copy root inside
     a corpus root, seed destination outside the copy root).
  Also: 'wait' events are published when the console STARTS waiting; a skip delivered in the same
  tick can be dropped (23:46:50 run stayed parked at WaitInput/EnterKey), so advance_boot re-sends
  an unanswered answer every retry_after seconds of silence. The title menu is a 20 s timeout with
  defaultInt=2 (=DEMO), so the marker check runs first and exits as soon as the reading is complete.
"""

import argparse
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time

# Windows gives sys.stdout the active code page (cp936 on this host). One Japanese label or
# non-ASCII corpus path printed without PYTHONIOENCODING=utf-8 killed the rig inside say()
# mid-evidence (2026-11-26 run). utf-8 + errors=replace degrades a stray char to a glyph
# instead of losing the rest of the reading.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

NL = chr(10)
HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(os.path.dirname(HERE))       # <wt>/eratw-android
WT_ROOT = os.path.dirname(ANDROID)                      # <wt>
RUN_BASE = os.path.join(os.path.dirname(WT_ROOT), ".pc-cmp-run")   # <work-root>/.pc-cmp-run
FIXTURE_DIR = os.path.join(ANDROID, "fixtures", "pc-cmp")


def fixture_src_dir(spec):
    # golden-smoke (CONTRACT-GOLDROOT1) injects the READ-ONLY fixtures/golden suite instead of
    # pc-cmp; baseline files are only ever COPIED into the private run root, never written back.
    src = spec.get("fixtures_src")
    if not src:
        return FIXTURE_DIR
    p = os.path.join(ANDROID, "fixtures", src)
    if not os.path.isdir(p):
        raise SystemExit("fixtures_src dir missing: " + p)
    return p


EVIDENCE = os.path.join(WT_ROOT, "<internal-workdir>", "evidence", "pc-cmp")


def shared_root():
    env = os.environ.get("ERATW_ROOT")
    if env:
        return env.replace(chr(92), "/")
    name = (socket.gethostname() or os.environ.get("COMPUTERNAME") or "").lower()
    if name.startswith("homejia"):
        return "<work-root>/eratw-安卓移植计划"
    if name.startswith("haruki"):
        return "<work-root>/eratw-安卓移植计划"
    raise SystemExit("unknown host " + repr(name) + ": export ERATW_ROOT first (AGENTS.md 1)")


HOSTS = {
    # engine root (exe) | corpus root (ERB) | exe file | extra argv | emuera.config overrides
    "skia4981": {
        "exe_root": "eraTW4.981-261017-EAGLOV3",
        "data_root": "eraTW4.981-261111-EAGLOV3",
        "exe": "Emuera_skiaV16_x64.exe",
        "argv": ["--CliDebug"],
        "config": {"DISABLE BEFORE_ERROR/THROW EVENTS": "NO"},
        "note": "golden 采集根 exe + 复刻目标语料 261111; the fork has BEFORE_THROW/BEFORE_ERROR",
    },
    "lazy4980": {
        "exe_root": "eratw-画蛇添足版eratw4.980",
        "data_root": "eratw-画蛇添足版eratw4.980",
        "exe": "Emuera_LazyLoadingV3.0_x64.exe",
        "argv": [],
        "config": {},
        "note": "1.824 lineage cross-check: BEFORE_THROW is NOT an event name there, OUTPUTLOG exists",
    },
}

CASES = {
    # marker order is asserted on PCMARK-* lines only; corpus @EVENTLOAD members interleave.
    "buckets": {
        "fixtures": ["PC-CMP-01-BUCKETS.ERB"],
        "event": "EVENTLOAD",
        "expected": ["PCMARK-1-PL", "PCMARK-2-P", "PCMARK-3-N", "PCMARK-5-SINGLE0",
                     "PCMARK-6-AFTER-SINGLE", "PCMARK-7-SINGLE1", "PCMARK-1-PL", "PCMARK-4-L"],
        "absent": ["PCMARK-8-AFTER-SINGLE1"],
        "expect_counts": {"PCMARK-1-PL": 2},
        "settle": 25,
        "menus": ["1", "0", "0"],   # title 1=繼続遊戲 -> slot 0 -> confirm 0=是 (save load -> @EVENTLOAD)
        "seed": True,             # seed the private sav/ so a load is possible at all
        "idle": 45,
        "why": "#PRI + #LATER must register in bucket 1 AND 3 (LabelDictionary.cs:90-105 / skia :134-141); "
               "#SINGLE + RETURN 1 must truncate the tail of bucket 2 (ProcessState.cs:326-337)",
    },
    "bt-walk": {
        "fixtures": ["PC-CMP-02A-BT-WALK.ERB"],
        "event": "BEFORE_THROW",
        "expected": ["PCMARK-BT-DRIVER", "PCMARK-BT-PRI", "PCMARK-BT-PRI2", "PCMARK-BT-PL",
                     "PCMARK-BT-N", "PCMARK-BT-PL"],
        "absent": ["PCMARK-BT-THROWN"],
        "expect_counts": {"PCMARK-BT-PL": 2},
        "settle": 40,
        "menus": ["1", "0", "0"],   # title 1=繼続遊戲 -> slot 0 -> confirm 0=是 (save load -> @EVENTLOAD)
        "seed": True,
        "idle": 45,
        "why": "nested event dispatch inside an event is legal only for BEFORE_THROW/BEFORE_ERROR "
               "(skia Process.State.cs:598-614); gives a clean 1-2-3 walk with no #ONLY masking",
    },
    "bt-only": {
        "fixtures": ["PC-CMP-02B-BT-ONLY.ERB"],
        "event": "BEFORE_THROW",
        "expected": ["PCMARK-BT2-DRIVER", "PCMARK-BT2-ONLY"],
        "absent": ["PCMARK-BT2-PRI", "PCMARK-BT2-N", "PCMARK-BT2-THROWN"],
        "settle": 40,
        "menus": ["1", "0", "0"],   # title 1=繼続遊戲 -> slot 0 -> confirm 0=是 (save load -> @EVENTLOAD)
        "seed": True,
        "idle": 45,
        "why": "IsOnly lands in array[0] AND array[2]; Return() calls FinishEvent() on the first "
               "return of an IsOnly frame (ProcessState.cs:326-329), so the duplicate stays invisible",
    },
    "golden-smoke": {
        # 2026-11-26 CONTRACT-GOLDROOT1: capture-root self-consistency smoke. Proves the SYNTHESIZED
        # private root (261111 corpus + 261017 skiaV16 --CliDebug host) reproduces a golden run. The
        # GOLDEN suite rides its own #PRI @EVENTFIRST hook (GOLDEN_00_ROOT.ERB:90-92), fired by NEW
        # GAME: title answer 0 -> endOpenning (Process.cs:2167-2180) -> beginFirst -> callFunction
        # ("EVENTFIRST", force:true) (:2207). No save is touched: seed=False keeps sav/ the private
        # empty dir; fixtures come READ-ONLY from fixtures/golden (fixtures_src), baseline bytes stay
        # untouched in the repo. Verdict = raw boundary markers (GOLDEN_00_ROOT.ERB:113/:135) -- the
        # suite prints no PCMARK tokens -- plus the SECTION count. NOT an equivalence claim against
        # the Lazyloadingv3.0 baseline header (different host build; rerun/repin is a separate task).
        "fixtures": ["GOLDEN_00_ROOT.ERB", "GOLDEN_01_BASIC.ERB", "GOLDEN_02_PRINT.ERB",
                     "GOLDEN_03_FLOW.ERB", "GOLDEN_04_CHARA.ERB", "GOLDEN_05_DATA.ERB",
                     "GOLDEN_06_INPUT_AUTO.ERB", "GOLDEN_08_IO.ERB", "GOLDEN_09_MISC.ERB",
                     "GOLDEN_10_XMLMAP.ERB", "GOLDEN_11_GVIEW.ERB", "GOLDEN_12_SYMBOLS.ERB"],
        "fixtures_src": "golden",
        "event": "EVENTFIRST",
        "expected": [],
        "absent": [],
        "raw_expected": ["=== GOLDEN RUN START ===", "=== END ==="],
        "settle": 30,
        "menus": ["0"],
        "seed": False,
        "idle": 45,
        "why": "boundary markers + section count prove the whole GOLDEN run reproduced on skiaV16 "
               "from the synthesized root; runbook sec.2/3 primary path (OUTPUTLOG) is dead on this "
               "host (statement commented out in the fork, BuiltInFunctionCode.cs:85) -> capture "
               "rides the log-cmd GetLog snapshot plus the live stdout.jsonl stream",
    },
}

ENGINE_FILES = ["Emuera_skiaV16_x64.exe", "Emuera_LazyLoadingV3.0_x64.exe",
                "SoundTouch_x64.dll", "SoundTouch.dll", "emuera.config", "setting.json",
                "profile", "macro.txt", "reraconfig.toml", "lazyloading.cfg", "lazyloading.dat",
                "lazyloadingfiles.dat", "icon.ico", "pluginsAware.txt"]
# COPY_DIRS are real-copied, never junctioned: the engine WRITES into them at boot, so a junction
# writes straight THROUGH the link into the read-only corpus root. Measured 2026-11-25: a skia4981 run
# updated eraTW4.981-261111-EAGLOV3/plugins/qol_data.db (QoL plugin cache, birth 2026-11-15, mtime
# moved to 23:08:18, 1404928 bytes) because plugins/ had been junctioned. Copying is cheap
# (261111 plugins 3.4 MB, bad 52 MB; 4.980 plugins 1.3 MB), so only the big read-only trees stay
# junctioned (CSV 1.2 MB, resources 579 MB, sound, font).
# dir names the engine writes into at runtime: a junction here rewrites the corpus root.
WRITABLE_NAMES = ("plugins", "bad", "sav", "data")
COPY_DIRS = ["plugins", "bad"]
JUNCTION_DIRS = ["CSV", "resources", "sound", "font", "dat", "lang", "tool"]
NEVER_LINK = set(["ERB", "sav"])
# Lead ruling m00250 c(1), ratified 2026-11-26 04:07: the link-target assertion is DEFAULT-ON and
# bypasses no switch -- an unregistered junction whose target falls inside a corpus root rejects
# the copy-root build and names corpus-root / link / target, non-zero exit. Registered read-only
# junctions (JUNCTION_DIRS + ERB subdirs) stay, and EVERY created link is registered into
# copyroot.json for post-hoc audit. Unregistered corpus-internal top dirs are real-copied, never
# linked (build_copy_root). Tripwire: selfcheck t_unregistered.

# Rig self-describing version (lead ruling 2026-11-26): every reading states which rig produced it, so
# "the engine changed" can be told apart from "the rig changed".
RIG_VERSION = "v2.7"
RIG_NOTES = (
    "v2.7 (2026-11-26, bucketfix lane) = port_sequence dedupes erbcc already-expanded printed "
    "lines back to the unique member table before running the model; expansion_selfcheck pins the "
    "two structural rules (no member twice inside one bucket, and a member repeat count equals its "
    "own bucket count, so #PRI+#LATER repeats exactly twice); the note prints registered / "
    "bucket-expanded / dispatched. "
    "v2.5 (2026-11-26, bucketfix lane) = B23 modeling re-applied on the hardened v2.4 rig: "
    "bucket_set (four parallel ifs, LabelDictionary.cs:228-235) + dispatch_model (#ONLY chain kill "
    "Process.State.cs:505 / CalledFunction.cs:445-451; #SINGLE ret==1 own-bucket cut :507 / :433-443) drive "
    "port_sequence, so the port reading is the marker order the engine prints, not a raw registration "
    "dump; selfcheck gains t_model (12th assertion, first one after the hardened v2.4 items); --reverse-proof-exclusive is the B23 reverse-proof "
    "control (old exclusive one-bucket registration) and never feeds a production reading. Lead ruling "
    "2026-11-26: modeling must not contaminate the PC side. "
    "v2.4 = link-target registry (every junction recorded in copyroot.json with inside_corpus/registered_readonly; hard default-on link assertion per m00250 c ratified 2026-11-26 04:07) + nlink+mtime_ns sidecar baseline corpus-nlink.json whose drift folds into --hash-only. v1 = per-case idle 12 + both full mtime fingerprints inlined in the verdict (so "
             "verdict.txt ran to 522 KB - 1040 KB); v2 = per-case idle 45 + fingerprint sidecars + "
             "PC counts / PC state lines + <stamp>.pc-markers.txt; v2.1 = CORPUS-MTIME detail cut to "
             "the two leading fingerprint segments and PC state printed as the bare state name; "
             "v2.3 = golden-smoke case: per-case fixtures_src (fixtures/golden, read-only injection), "
             "raw_expected substring verdict with per-marker stream attribution (GetLog / stdout.jsonl), "
             "advance_boot marker_budget falls back to raw_expected when a case has no PCMARK tokens; "
             "v2.2 = destination lock assert_write_dest() on every write site (the vector the 2026-11-25 "
             "23:51:11 corpus sav touch proved open) + corrected quit attribution + 9-item selfcheck. "
             "Collection (mtime fingerprint + full-tree sha256 manifest) still runs inside the per-case "
             "teardown path.")


def say(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------- fingerprints
def fingerprint(root, depth=2):
    # Two levels: top-level entries PLUS the immediate children of every top-level dir. Any
    # add/remove/rename further down still shows up, because the containing directory's own mtime
    # changes one level above it. A full recursive walk of the three roots measures 65.6 s
    # (15284 + 1344 + 16122 files) -- too slow to run before and after every launch; two levels is
    # sub-second. Reads only: os.scandir + stat(follow_symlinks=False).
    if not os.path.isdir(root):
        return root + " | ABSENT"
    rows = []
    total = [0]

    def scan(d, level, prefix):
        for e in sorted(os.scandir(d), key=lambda x: x.name):
            st = e.stat(follow_symlinks=False)
            isfile = e.is_file(follow_symlinks=False)
            size = st.st_size if isfile else 0
            total[0] += size
            rows.append("%s%s|%d|%d" % (prefix, e.name, int(st.st_mtime), size))
            if not isfile and level > 1:
                scan(e.path, level - 1, prefix + e.name + chr(47))

    scan(root, depth, "")
    return "%s | entries=%d bytes=%d | %s" % (root, len(rows), total[0], ";".join(rows))


# ------------------------------------------------------- full-tree hash manifest
BASELINE_NAME = "corpus-baseline.json"
NLINK_NAME = "corpus-nlink.json"


def baseline_path():
    return os.path.join(EVIDENCE, BASELINE_NAME)


def nlink_path():
    return os.path.join(EVIDENCE, NLINK_NAME)


def sha_manifest(root, workers=12):
    """Whole-tree sha256 (first 16 hex) + size for every regular file under a guarded root.
    Cost measured on this machine: 261111 = 15,284 files / 42.5 s, 4.980 = 16,122 / 46.6 s,
    261017 = 1,344 / 1.2 s -> roughly 90 s for all three roots, once per run (lead ruling item B).
    os.walk does not follow junctions (followlinks defaults to False), so this only ever READS the
    corpus roots."""
    import hashlib
    from concurrent.futures import ThreadPoolExecutor

    def one(path):
        h = hashlib.sha256()
        try:
            with open(path, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
        except OSError as exc:
            return path, "err:" + type(exc).__name__
        return path, "%d:%s" % (os.path.getsize(path), h.hexdigest()[:16])

    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not is_reparse(os.path.join(dirpath, d))]
        for f in filenames:
            p = os.path.join(dirpath, f)
            if not is_reparse(p):
                files.append(p)
    out = {}
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for path, digest in ex.map(one, files):
            out[os.path.relpath(path, root).replace(chr(92), "/")] = digest
    return out


def nlink_manifest(root):
    """Whole-tree nlink + mtime_ns for every regular file under a guarded root (no hashing, tens
    of seconds per root). Companion to the sha baseline: the 2026-11-26 bad/ truncation arrived
    through a hardlink family (nlink 16) that a sha-only baseline could not see coming. Read-only
    walk; reparse points are skipped, so junction targets are never followed."""
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not is_reparse(os.path.join(dirpath, d))]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            if is_reparse(p):
                continue
            try:
                st = os.stat(p)
            except OSError:
                continue
            out[os.path.relpath(p, root).replace(chr(92), "/")] = "%d:%d" % (st.st_nlink, st.st_mtime_ns)
    return out


def take_nlink_manifest():
    return dict((os.path.basename(os.path.normpath(r)), nlink_manifest(r)) for r in guarded_roots())


def take_manifest():
    return dict((os.path.basename(os.path.normpath(r)), sha_manifest(r)) for r in guarded_roots())


def compare_manifest(base, cur, limit=12):
    """(changed, added, removed, lines) keyed by root/file."""
    changed, added, removed, lines = [], [], [], []
    for root in sorted(set(base) | set(cur)):
        b, c = base.get(root, {}), cur.get(root, {})
        for rel in sorted(set(b) & set(c)):
            if b[rel] != c[rel]:
                changed.append(root + "/" + rel)
                if len(lines) < limit:
                    lines.append("  CHANGED %s/%s: %s -> %s" % (root, rel, b[rel], c[rel]))
        for rel in sorted(set(c) - set(b)):
            added.append(root + "/" + rel)
            if len(lines) < limit:
                lines.append("  ADDED   %s/%s = %s" % (root, rel, c[rel]))
        for rel in sorted(set(b) - set(c)):
            removed.append(root + "/" + rel)
            if len(lines) < limit:
                lines.append("  REMOVED %s/%s (was %s)" % (root, rel, b[rel]))
    return changed, added, removed, lines


def fp_drift(before, after):
    """Names of depth-2 entries whose mtime/size tag moved, per guarded root (for the WARN line)."""
    out = []
    for b, a in zip(before, after):
        if b == a:
            continue
        head_b, _, tail_b = b.partition(" | entries=")
        head_a = a.split(" | entries=")[0]
        kb = dict((x.split("|")[0], x) for x in tail_b.split(";") if x) if tail_b else {}
        _, _, tail_a = a.partition(" | entries=")
        ka = dict((x.split("|")[0], x) for x in tail_a.split(";") if x) if tail_a else {}
        diff = sorted(k for k in set(kb) | set(ka) if kb.get(k) != ka.get(k))
        same_size = [k for k in diff if kb.get(k, "").split("|")[-1] == ka.get(k, "").split("|")[-1]]
        out.append("%s: %d moved (%d size-identical: %s)" % (os.path.basename(head_a), len(diff),
                    len(same_size), ", ".join(same_size[:6]) or "none"))
    return out


def default_seed_dir():
    # Slot saves copied into the private sav/ of the copy root. The canonical 4.980 root is the only
    # guarded root that ships them (save00.sav / save01.sav / global.sav); 261111/sav holds just
    # global.sav and 261017/sav is empty. Read-only source -> real copies; nothing links back.
    return os.path.join(shared_root(), "eratw-画蛇添足版eratw4.980", "sav")


def guarded_roots():
    r = shared_root()
    out = [os.path.join(r, "eraTW4.981-261111-EAGLOV3"),
           os.path.join(r, "eraTW4.981-261017-EAGLOV3"),
           os.path.join(r, "eratw-画蛇添足版eratw4.980")]
    for extra in ("<work-root>/eratw-sub-modding", "<work-root>/eratw-sub-modding"):
        if os.path.isdir(extra):
            out.append(extra)
    return out


# ------------------------------------------------------------------ copy root
def link_dir(link, target):
    """Windows junction into a read-only root. _winapi.CreateJunction(src, dst) is used instead of
    "cmd /c mklink /J" because mklink silently fails here and left a half-built copy root (0 links).
    Proven semantics (<internal-workdir>/_scratch_junction_probe.py, python 3.14.3): os.path.isjunction is
    True, os.rmdir(link) removes only the link, and shutil.rmtree over the parent leaves the target
    intact -- so nothing here can write into or delete the corpus roots."""
    if os.path.exists(link):
        return os.path.isdir(link)
    try:
        import _winapi
        _winapi.CreateJunction(target, link)
        return os.path.isdir(link)
    except Exception as exc:
        say("  junction FAILED " + link + " -> " + target + " : " + repr(exc))
        subprocess.call(["cmd", "/c", "mklink", "/J", link, target])
        return os.path.isdir(link)


def assert_write_dest(dest, game, what):
    """RED-LINE LOCK 2 (lead ruling 2026-11-26). EVERY write the rig performs -- engine-file copy,
    writable-dir copytree, ERB top copy, fixture injection, seed save, emuera.config rewrite -- must
    land inside this run's copy root. This is the vector the first lock missed: on 2026-11-25 23:51:11
    an old seed step wrote <corpus>/sav/global.sav directly (no junction involved: st_nlink=1 on both
    sides, GetFileAttributes=0x10 on both, and that run's copyroot.json already listed copied_dirs
    [bad, plugins] with no sav link). The lead's byte forensics: every engine-written global.sav on
    this machine is 517 B / sha256 c2dc2af97b619ffb, while the one that appeared in the corpus root is
    514 B / fe95116d9d86f438 = the seed source's own bytes -> the writer was our copy step, not the
    engine's exit save. Raises RuntimeError; proven by the 'seed destination' selfcheck tripwire."""
    d = os.path.normcase(os.path.realpath(dest))
    g = os.path.normcase(os.path.realpath(game))
    if d != g and not d.startswith(g + os.sep):
        raise RuntimeError("RED LINE: write destination %s (%s) is outside the copy root %s"
                           % (dest, what, game))


def assert_copy_root_policy(game, link_pairs):
    """RED-LINE LOCK (lead ruling 2026-11-26 item A). A copy root may never be built inside a guarded
    corpus root, and no writable dir name may reach the engine through a junction: a junction writes
    straight THROUGH (measured -- 261111/plugins/qol_data.db rewritten at 23:08:18 while plugins/ was
    linked, and 261111/sav/global.sav mtime moved at 23:49:38 / 23:51:11). Raises RuntimeError, so a
    future lane cannot repeat the mistake by editing COPY_DIRS; proven by 'run.py --selfcheck'."""
    for root in guarded_roots():
        rb = os.path.normcase(os.path.realpath(root))
        gb = os.path.normcase(os.path.realpath(game))
        if gb == rb or gb.startswith(rb + os.sep):
            raise RuntimeError("RED LINE: copy root %s lies inside corpus root %s" % (game, root))
    corpus = [os.path.normcase(os.path.realpath(r)) for r in guarded_roots()]
    registered = set(x.lower() for x in JUNCTION_DIRS)
    report = []
    for dst, src in link_pairs:
        name = os.path.basename(os.path.normpath(src)).lower()
        if name in WRITABLE_NAMES:
            raise RuntimeError("RED LINE: writable dir junctioned %s -> %s (must be a real copy)" % (dst, src))
        if not os.path.exists(src):
            raise RuntimeError("RED LINE: junction target does not exist: %s -> %s" % (dst, src))
        target = os.path.normcase(os.path.realpath(src))
        hit = ""
        for r in guarded_roots():
            rb = os.path.normcase(os.path.realpath(r))
            if target == rb or target.startswith(rb + os.sep):
                hit = r
                break
        parent = os.path.basename(os.path.normpath(os.path.dirname(dst))).lower()
        readonly_ok = name in registered or parent == "erb"
        report.append({"link": dst, "target": src, "inside_corpus": bool(hit),
                       "corpus_root": hit, "registered_readonly": readonly_ok})
        if hit and not readonly_ok:
            raise RuntimeError("RED LINE: unregistered junction into corpus root: root=%s link=%s target=%s"
                               % (hit, dst, src))
    return report


def build_copy_root(case, host, stamp, seed_saves=None):
    h = HOSTS[host]
    R = shared_root()
    exe_root = os.path.join(R, h["exe_root"])
    data_root = os.path.join(R, h["data_root"])
    if not os.path.isdir(exe_root):
        raise SystemExit("exe root missing: " + exe_root)
    run_root = os.path.join(RUN_BASE, "%s-%s-%s" % (stamp, host, case))
    game = os.path.join(run_root, "game")
    links = []
    link_pairs = []          # (dst, src) for every junction actually created -> fed to the lock
    os.makedirs(game, exist_ok=True)

    for f in ENGINE_FILES:
        for base in (exe_root, data_root):
            src = os.path.join(base, f)
            if os.path.isfile(src):
                dst = os.path.join(game, f)
                if not os.path.exists(dst):
                    assert_write_dest(dst, game, "engine file")
                    shutil.copy2(src, dst)
                break

    names = set()
    for base in (data_root, exe_root):
        if os.path.isdir(base):
            names |= set(e.name for e in os.scandir(base) if e.is_dir(follow_symlinks=False))
    order = [d for d in JUNCTION_DIRS if d in names] + sorted(names - set(JUNCTION_DIRS) - NEVER_LINK)
    copied = []
    for d in order:
        if d in NEVER_LINK:
            continue
        src = None
        for base in (data_root, exe_root):          # corpus root wins for game data
            cand = os.path.join(base, d)
            if os.path.isdir(cand):
                src = cand
                break
        dst = os.path.join(game, d)
        if d in COPY_DIRS or d.lower() in WRITABLE_NAMES:
            # writable dir: a link here writes straight into the corpus root -- real copy instead
            assert_write_dest(dst, game, "writable dir copy")
            shutil.copytree(src, dst, dirs_exist_ok=True)
            copied.append(d + ("(writable-lock)" if d.lower() in WRITABLE_NAMES and d not in COPY_DIRS else ""))
            continue
        srcn = os.path.normcase(os.path.realpath(src))
        in_corpus = any(srcn == os.path.normcase(os.path.realpath(r)) or
                        srcn.startswith(os.path.normcase(os.path.realpath(r)) + os.sep)
                        for r in guarded_roots())
        if in_corpus and d.lower() not in set(x.lower() for x in JUNCTION_DIRS):
            # m00250 c(1) ratified: only registered read-only names may junction back into a
            # corpus root; any other corpus dir reaching the engine as a link becomes a real copy.
            assert_write_dest(dst, game, "unregistered dir copy")
            shutil.copytree(src, dst, dirs_exist_ok=True)
            copied.append(d + "(readonly-copy)")
            continue
        if link_dir(dst, src):
            links.append(dst)
            link_pairs.append((dst, src))

    # ERB = real dir: top-level files copied (cheap: 28 files / 666 KB), subdirs junctioned,
    # fixture files added as real files so the PC sees them and the corpus root stays intact.
    erb = os.path.join(game, "ERB")
    os.makedirs(erb, exist_ok=True)
    erb_src = os.path.join(data_root, "ERB")
    top_files = 0
    for e in sorted(os.scandir(erb_src), key=lambda x: x.name):
        dst = os.path.join(erb, e.name)
        if e.is_dir(follow_symlinks=False):
            if link_dir(dst, e.path):
                links.append(dst)
                link_pairs.append((dst, e.path))
        else:
            assert_write_dest(dst, erb, "ERB top file")
            shutil.copy2(e.path, dst)
            top_files += 1
    fdir = fixture_src_dir(CASES[case])
    for fx in CASES[case]["fixtures"]:
        src = os.path.join(fdir, fx)
        if not os.path.isfile(src):
            raise SystemExit("fixture missing: " + src)
        assert_write_dest(os.path.join(erb, fx), erb, "fixture injection")
        shutil.copy2(src, os.path.join(erb, fx))

    os.makedirs(os.path.join(game, "sav"), exist_ok=True)   # private, empty, never linked
    # @EVENTLOAD fires ONLY inside the save-load chain: beginDataLoaded has exactly one caller
    # (Process.SystemProc.cs:1109, right after vEvaluator.LoadFrom) and endSystemLoad :816-827 then
    # calls callFunction("EVENTLOAD", false, true). A fresh boot / new game never reaches it -- the
    # golden baseline has no 時だよ line from SYSTEM.ERB:38 -- so seeding a save is what makes the
    # fixture run at all.
    seeded = []
    if seed_saves:
        for e in sorted(os.scandir(seed_saves), key=lambda x: x.name):
            if e.is_file(follow_symlinks=False) and e.name.lower().endswith(".sav"):
                # THE site that leaked on 2026-11-25 23:51:11 (see assert_write_dest docstring): the
                # destination is asserted, so a regression here stops the run instead of touching a root.
                assert_write_dest(os.path.join(game, "sav", e.name), game, "seed save")
                shutil.copy2(e.path, os.path.join(game, "sav", e.name))
                seeded.append(e.name)

    delta = []
    cfg = os.path.join(game, "emuera.config")
    assert_write_dest(cfg, game, "emuera.config rewrite")
    if h["config"] and os.path.isfile(cfg):
        with open(cfg, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
        for i, ln in enumerate(lines):
            key = ln.split(":")[0].strip().upper()
            for want, to in h["config"].items():
                if key == want.upper() and not ln.strip().upper().endswith(":" + to.upper()):
                    delta.append("emuera.config line %d: %s -> %s:%s" % (i + 1, ln.strip(), want, to))
                    lines[i] = want + ":" + to
        with open(cfg, "w", encoding="utf-8", newline=chr(13) + chr(10)) as fh:
            fh.write(NL.join(lines) + NL)
    junction_report = assert_copy_root_policy(game, link_pairs)
    return {"run_root": run_root, "game": game, "erb": erb, "exe": os.path.join(game, h["exe"]),
            "argv": h["argv"], "links": links, "link_pairs": link_pairs,
            "copied_dirs": copied, "seeded_saves": seeded, "junction_report": junction_report,
            "erb_top_files": top_files, "config_delta": delta}


# -------------------------------------------------------------------- pc side
class Session(object):
    """Child + continuous stdout reader. A pipe buffer left unread stalls the engine."""

    def __init__(self, exe, cwd, argv, raw_path):
        self.events = []
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.raw = open(raw_path, "w", encoding="utf-8")
        self.proc = subprocess.Popen([exe] + list(argv), cwd=cwd, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def reader(self):
        for raw in iter(self.proc.stdout.readline, b""):
            line = raw.decode("utf-8", "replace").strip()
            if line:
                self.raw.write(line + NL)
                self.raw.flush()
                if line.startswith("{") or '{"type"' in line:
                    try:
                        obj = json.loads(line)
                        with self.lock:
                            self.events.append(obj)
                    except ValueError:
                        pass
            if self.stop.is_set():
                break

    def start(self):
        t = threading.Thread(target=self.reader)
        t.daemon = True
        t.start()
        return t

    def snapshot(self):
        with self.lock:
            return list(self.events)

    def wait_type(self, kind, timeout):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.snapshot():
                if e.get("type") == kind:
                    return e
            if self.proc.poll() is not None:
                return None
            time.sleep(0.2)
        return None

    def send(self, obj):
        try:
            data = (json.dumps(obj, ensure_ascii=False) + NL).encode("utf-8")
            self.proc.stdin.write(data)
            self.proc.stdin.flush()
            return True
        except (OSError, ValueError):
            return False

    def wait_result(self, ident, timeout):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.snapshot():
                if e.get("type") == "result" and e.get("id") == ident:
                    return e
            time.sleep(0.3)
        return None

    def close(self):
        self.stop.set()
        try:
            self.proc.stdin.close()      # stdin EOF -> CliDebugHost DispatchQuit (:127-131)
        except OSError:
            pass
        try:
            self.raw.close()
        except OSError:
            pass


def markers_in(text):
    """Every PCMARK-* token, in the order it appears (log buffer order == dispatch order)."""
    out = []
    i = 0
    while True:
        j = text.find("PCMARK-", i)
        if j < 0:
            return out
        k = j
        while k < len(text) and (text[k].isalnum() or text[k] in "-_"):
            k += 1
        out.append(text[j:k])
        i = k


# answers every wait the boot raises; see advance_boot
def advance_boot(s, menus, rounds=60, idle=25.0, marker_budget=None, first_idle=170.0,
                 deadline=460.0, retry_after=9.0, max_retry=4):
    """Answer every wait the boot raises until the markers are on screen.

    EnterKey-type waits are cleared with the skip branch of DoKey (CliDebugHost.cs:315-320 ->
    console.PressEnterKey(key == "skip", "", false)); value/button waits (IntButton/StrButton/
    IntValue/StrValue) are answered by typing, i.e. DoInput (:303-313, PressEnterKey(false, text,
    false)) -- the same thing the golden baseline did by hand (typed 0). menus gives the answers in
    order; leftovers use 0.

    Two measured facts drive the shape (2026-11-25):
      * the 261111 corpus prints NOTHING for ~45 s after 'ready' and then goes silent for the
        60-90 s parse, so the quiet window stays first_idle until the FIRST wait prompt appears and
        only then narrows to idle (booted alone is not interactive -- see the 2026-11-26 note);
      * the 'wait' event is published when the console STARTS waiting, and a skip sent inside that
        same ~0.4 s tick can be dropped: the 23:46:50 run sent key-skip, saw no further event, and
        stayed parked at WaitInput/EnterKey. Hence every answered wait is RE-SENT after
        retry_after seconds of silence (up to max_retry times) until new events appear.
    The marker check runs before the wait bookkeeping so a complete reading exits immediately -- the
    title menu is a 20 s timeout with defaultInt=2 (=DEMO), so dawdling silently diverts the run.
    """
    answers = []
    handled = 0
    menu_i = 0
    booted = False
    last_move = time.time()
    last_n = 0
    pending = None          # {"cmd":..., "why":...} last answer sent, awaiting effect
    sent_at = 0.0
    retries = 0
    end_at = time.time() + deadline
    while time.time() < end_at:
        evs = s.snapshot()
        if len(evs) != last_n:
            last_n = len(evs)
            last_move = time.time()
            pending = None      # the answer took effect
            retries = 0
        if not booted and any(e.get("type") in ("out", "wait", "dialog") for e in evs):
            booted = True
            last_move = time.time()
        if marker_budget:
            text = "".join(e.get("text", "") for e in evs if e.get("type") == "out")
            if all(m in text for m in marker_budget):
                answers.append("markers-complete")
                break
        if s.proc.poll() is not None:
            answers.append("child-exited")
            break
        waits = [i for i, e in enumerate(evs) if e.get("type") == "wait"]
        if handled >= len(waits):
            if pending and time.time() - sent_at > retry_after:
                if retries < max_retry:
                    s.send(pending["cmd"])
                    retries += 1
                    sent_at = time.time()
                    answers.append("retry%d:%s" % (retries, pending["why"]))
                else:
                    answers.append("stuck:" + pending["why"])
                    pending = None
            # window keys off the FIRST WAIT, not the first out line: measured 2026-11-26
            # 00:04:02 and 00:17:43 -- 'booted' goes True on the loader's first line, then the 261111
            # corpus parses in silence for 60-90 s, so an idle window measured from 'booted' breaks
            # the loop seconds before the title prompt (PC observed empty, PC-MATCH NO twice).
            window = idle if waits else first_idle
            if time.time() - last_move > window:
                answers.append("quiet(booted=" + str(booted) + ",waits=" + str(len(waits))
                               + ",window=" + str(int(window)) + ")")
                break
            time.sleep(0.4)
            continue
        ev = evs[waits[handled]]
        kind = ev.get("inputType") or ""
        if kind in ("EnterKey", "AnyKey", "AnyValue", "Void", "PrimitiveMouseKey"):
            cmd = {"cmd": "key", "key": "skip"}
            why = "key-skip(" + kind + ")"
        else:
            val = menus[menu_i] if menu_i < len(menus) else "0"
            menu_i += 1
            cmd = {"cmd": "input", "text": val}
            why = "input:" + val + "(" + kind + ")"
        if not s.send(cmd):
            answers.append("send-FAILED:" + why)
            break
        answers.append(why)
        handled += 1
        pending = {"cmd": cmd, "why": why}
        sent_at = time.time()
        retries = 0
        last_move = time.time()
        time.sleep(1.5)
    return answers


def run_pc(case, host, build, settle, keep_log_to, menus=None):
    spec = CASES[case]
    raw = keep_log_to + ".stdout.jsonl"
    if not os.path.isfile(build["exe"]):
        raise SystemExit("exe not in copy root: " + build["exe"])
    s = Session(build["exe"], build["game"], build["argv"], raw)
    s.start()
    hello = s.wait_type("hello", 180)
    say("  hello  : " + (json.dumps(hello, ensure_ascii=False) if hello else "MISSING (no --CliDebug handshake; process exited " + str(s.proc.poll()) + ")"))
    ready = s.wait_type("ready", 240)
    say("  ready  : " + ("yes" if ready else "TIMEOUT/none"))
    if ready is None and s.proc.poll() is not None:
        s.close()
        return {"markers": [], "log": "", "state": "child died early; see " + raw}
    answered = advance_boot(s, menus or [], rounds=CASES[case].get("rounds", 40),
                            idle=CASES[case].get("idle", 20.0),
                            marker_budget=CASES[case].get("expected") or CASES[case].get("raw_expected"))
    say("  advanced : " + (" | ".join(answered) or "(no wait prompt seen)"))
    time.sleep(settle)
    s.send({"cmd": "state", "id": 1})
    st = s.wait_result(1, 20)
    s.send({"cmd": "log", "id": 2, "hideInfo": False})
    res = s.wait_result(2, 40)
    console = (res or {}).get("log", "") or ""
    # Measured 2026-11-26 00:04:02: this build (SkiaV16) answers the debug cmd with
    # '该功能不能在现版本使用' -- the DebugConsole face is gated -- so the SavDir mechanism behind the
    # sav/global.sav mtime bump stays TODO(verify). The refusal text itself is what lands in the
    # verdict, instead of pretending the probe resolved it.
    # RED LINE: hard kill, never the graceful "quit" cmd. Read the attribution carefully:
    # the 2026-11-25 23:49:38 / 23:51:11 mtime bumps on <corpus>/sav/global.sav were first blamed on a
    # clean exit and are NOT that -- the lead's byte forensics (2026-11-26) show every engine-written
    # global.sav on this machine is 517 B / c2dc2af97b619ffb (e.g. the copy-root one at 23:53:22) while
    # the file that appeared in the corpus root is 514 B / fe95116d9d86f438 = our own seed source, with
    # no junction and no hardlink involved. Cause = the seed step of the rig build at that revision;
    # closed by assert_write_dest on the seed copy. Quit stays forbidden as belt-and-braces only: the
    # engine DOES rewrite sav/global.sav inside the copy root mid-session, and this build refuses the
    # debug cmd, so SavDir's resolved location is not observable from the rig (the two probes below
    # record that refusal). Nothing is lost by killing hard -- the whole console buffer already came
    # back from the "log" cmd.
    for did, expr in ((10, "Program.ExeDir"), (11, "Config.Config.SavDir")):
        s.send({"cmd": "debug", "expr": expr, "id": did})
        time.sleep(0.6)
    probes = {}
    for did, expr in ((10, "Program.ExeDir"), (11, "Config.Config.SavDir")):
        r = s.wait_result(did, 8)
        probes[expr] = json.dumps(r, ensure_ascii=False)[:400] if r else "no result"
    say("  savdir   : " + NL.join("  " + k + " = " + v for k, v in probes.items()))
    build["savdir_probe"] = probes
    subprocess.call(["taskkill", "/T", "/F", "/PID", str(s.proc.pid)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        s.proc.wait(timeout=25)
    except subprocess.TimeoutExpired:
        pass
    s.close()
    with open(keep_log_to + ".console.txt", "w", encoding="utf-8") as fh:
        fh.write(console if console else NL.join(e.get("text", "") for e in s.snapshot() if e.get("type") == "out"))
    streamed = []
    for e in s.snapshot():
        if e.get("type") == "out":
            streamed.extend(markers_in(e.get("text", "")))
    return {"markers": markers_in(console), "streamed": streamed, "console": console,
            "state": json.dumps(st, ensure_ascii=False), "pid": s.proc.pid, "advanced": answered}


# ----------------------------------------------------------------- port side
def erbcc_install():
    d = os.path.join(ANDROID, "tools", "erbcc", "build", "install", "erbcc")
    return d if os.path.isdir(os.path.join(d, "lib")) else None


def java_exe():
    """AGENTS.md 5: the toolchain JDK is ERATW_ROOT/.tooling/jdk-17*. erbcc.bat dies with 9009
    when JAVA_HOME is absent from this shell, so resolve java.exe explicitly and call java direct."""
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.isfile(os.path.join(jh, "bin", "java.exe")):
        return os.path.join(jh, "bin", "java.exe").replace(chr(92), "/")
    tool = os.path.join(shared_root(), ".tooling")
    if os.path.isdir(tool):
        for e in sorted(os.scandir(tool), key=lambda x: x.name, reverse=True):
            cand = os.path.join(e.path, "bin", "java.exe")
            if e.is_dir(follow_symlinks=False) and os.path.isfile(cand):
                return cand.replace(chr(92), '/')
    return None


def fixture_marker_map(case):
    """(file, label_line) -> first PCMARK token in that body, read from the fixture sources."""
    ev = CASES[case]["event"]
    m = {}
    for fx in CASES[case]["fixtures"]:
        path = os.path.join(FIXTURE_DIR, fx)
        label = None
        with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
            for i, raw in enumerate(fh.read().splitlines(), 1):
                s = raw.strip()
                if s.startswith("@"):
                    label = (fx, i) if s[1:].strip().upper() == ev else None
                elif label and "PCMARK-" in s:
                    toks = markers_in(s)
                    if toks:
                        m[label] = toks[0]
                        label = None
    return m


EXCLUSIVE_CONTROL = False  # --reverse-proof-exclusive: B23 reverse proof only, never a reading


def bucket_set(flags, exclusive=False):
    """Event-member bucket set, C# verbatim: 4.981 LabelDictionary.cs:134-141 / :228-235 = FOUR
    PARALLEL ifs (IsOnly -> 0, IsPri -> 1, not IsPri and not IsLater -> 2, IsLater -> 3; the :233
    comment reads "#PRIかつ#LATERなら二重に登録する。eramakerの仕様"). So #PRI+#LATER -> {1,3} (never 2)
    and #ONLY -> {0,2} (LogicalLineParser.cs:135-145 clears PRI/LATER under #ONLY). exclusive=True
    reproduces the OLD one-bucket-per-member port rule and exists ONLY as the reverse-proof control;
    it must never feed a PC-side or reported reading (lead ruling 2026-11-26).
    """
    pri = "#PRI" in flags
    later = "#LATER" in flags
    if exclusive:
        if "#ONLY" in flags:
            return [0]
        if pri:
            return [1]
        if later:
            return [3]
        return [2]
    out = []
    if "#ONLY" in flags:
        out.append(0)
    if pri:
        out.append(1)
    if not pri and not later:
        out.append(2)
    if later:
        out.append(3)
    return out


def fixture_return_map(case):
    """(file, label_line) -> that member's RETURN value (bare or absent RETURN = 0).

    #SINGLE only truncates when the member returns 1 (4.981 Process.State.cs:505-510; 1.824
    ProcessState.cs:312-347). fixture_marker_map stops at the first PCMARK token, so it never sees
    the RETURN line -- this map carries it.
    """
    ev = CASES[case]["event"]
    out = {}
    for fx in CASES[case]["fixtures"]:
        label = None
        with io.open(os.path.join(FIXTURE_DIR, fx), "r", encoding="utf-8-sig", errors="replace") as fh:
            for i, raw in enumerate(fh.read().splitlines(), 1):
                s = raw.strip()
                if s.startswith("@"):
                    label = (fx, i) if s[1:].strip().upper() == ev else None
                    if label:
                        out[label] = 0
                elif label and (s == "RETURN" or s.startswith("RETURN ")):
                    arg = s[6:].strip()
                    out[label] = int(arg) if arg.isdigit() else 0
    return out


def fixture_entries(case):
    """Fixture bytes -> [(key, flags)] in registration (load) order, for the selfcheck model."""
    ev = CASES[case]["event"]
    out = []
    for fx in CASES[case]["fixtures"]:
        label = None
        flags = []
        with io.open(os.path.join(FIXTURE_DIR, fx), "r", encoding="utf-8-sig", errors="replace") as fh:
            for i, raw in enumerate(fh.read().splitlines(), 1):
                s = raw.strip()
                if s.startswith("@"):
                    if label is not None:
                        out.append((label, flags))
                    label = (fx, i) if s[1:].strip().upper() == ev else None
                    flags = []
                elif s.startswith("#") and label is not None:
                    flags.append(s.upper())
        if label is not None:
            out.append((label, flags))
    return out


def dispatch_model(entries, returns=None, exclusive=False):
    """Registration order + flags -> the marker order the engine actually calls.

    Outer loop bucket 0..3, inner loop load order (FuncMeta.kt:533-542 eventDispatchOrder).
    #ONLY kills the WHOLE chain (Process.State.cs:505 -> CalledFunction.cs:445-451 FinishEvent);
    #SINGLE returning 1 cuts only its OWN bucket tail (Process.State.cs:507 -> :433-443
    ShiftNextGroup). If one member carried both, #ONLY wins (IsOnly is tested first, and
    LogicalLineParser.cs:135-145 clears #SINGLE under #ONLY anyway). Returns (keys, notes).
    """
    returns = returns or {}
    seq, notes = [], []
    for bucket in (0, 1, 2, 3):
        run = [(k, f) for (k, f) in entries if bucket in bucket_set(f, exclusive)]
        for j, (key, flags) in enumerate(run):
            seq.append(key)
            if "#ONLY" in flags:
                killed = sum(1 for (k2, f2) in entries
                             if any(b > bucket for b in bucket_set(f2, exclusive)))
                notes.append("bucket %d: #ONLY -> FinishEvent (:445-451), chain killed, "
                             "%d later member(s) never dispatched" % (bucket, killed))
                return seq, notes
            if "#SINGLE" in flags and returns.get(key, 0) == 1:
                notes.append("bucket %d: #SINGLE returning 1 -> ShiftNextGroup (:433-443), "
                             "%d tail member(s) dropped" % (bucket, len(run) - j - 1))
                break
    return seq, notes


def expansion_selfcheck(entries, expanded, exclusive=False):
    """Two structural gates on the model expansion itself (ledger B23, lead ruling 2026-11-26).

    Deduping happens at the registration face: one member is registered once PER BUCKET its
    flags select, never twice inside the same bucket. So (a) no member may repeat within one
    bucket run and (b) a member total repeat across buckets must equal the number of buckets its
    flags select -- a #PRI+#LATER member repeats exactly twice (that is the B23 witness),
    anything else is a modelling leak. Returns human-readable problems, empty when clean.
    """
    problems = []
    for bucket in (0, 1, 2, 3):
        run = [k for (k, fl) in entries if bucket in bucket_set(fl, exclusive)]
        if len(run) != len(set(run)):
            dup = sorted({k for k in run if run.count(k) > 1})
            problems.append("same member registered twice in bucket %d: %s" % (bucket, dup))
    for key, fl in entries:
        want = bucket_set(fl, exclusive)
        got = sum(1 for k in expanded if k == key)
        if got != len(want):
            problems.append("member %s repeated %d times, its buckets are %s" % (key, got, want))
    return problems


def port_sequence(case, text, mmap, rmap=None, exclusive=False):
    """Reduce erbcc's printed eventCallOrder for this event to the fixture's MARKERS.

    Since B23 the printed list is the bucket-EXPANDED member sequence: a #PRI+#LATER member is listed
    once per bucket it is registered in. So the model first dedupes the printed lines back to the
    unique member table, ordered by (fileIndex, line) -- registration order for the isolated
    single-file fixture -- then expands each member through bucket_set exactly once, and cross-checks
    that expansion against the order erbcc actually printed (a mismatch is reported in the note: the
    registration face and the model would disagree, which is the ledger witness). dispatch_model then
    applies the #ONLY chain kill and the #SINGLE own-bucket cut. Full-list comparison, never loosened
    to a prefix match (lead ruling 2026-11-26).
    """
    event = CASES[case]["event"]
    heads = ["[事件] @" + event, "[普通] @" + event]
    order, in_group, saw_group = [], False, False
    for raw in text.splitlines():
        s = raw.strip()
        if s.startswith("[事件] @") or s.startswith("[普通] @"):
            in_group = any(s.startswith(h) for h in heads)
            if in_group:
                saw_group = True
            continue
        if not in_group or not s:
            continue
        head, sep, rest = s.partition(". ")
        if not sep or not head.isdigit():
            continue
        toks = rest.split()
        f, colon, ln = toks[0].rpartition(":")
        if not colon or not ln.isdigit():
            continue
        key = (os.path.basename(f), int(ln))
        fidx = 0
        for t in toks[1:]:
            if t.startswith("(fileIndex="):
                fidx = int(t.split("=", 1)[1].rstrip(")"))
        order.append((key, fidx, [t.upper() for t in toks[1:] if t.startswith("#")]))
    members = {}
    for key, fidx, flags in order:
        if key not in members:
            members[key] = (fidx, flags)
    entries = [(k, v[1]) for k, v in sorted(members.items(), key=lambda kv: (kv[1][0], kv[0][1]))]
    printed = [k for k, _f, _i in order]
    expanded = [k for b in (0, 1, 2, 3) for (k, fl) in entries if b in bucket_set(fl, exclusive)]
    struct = expansion_selfcheck(entries, expanded, exclusive)
    keys, cut_notes = dispatch_model(entries, rmap, exclusive)
    out = [mmap[k] for k in keys if k in mmap]
    note = ""
    if struct:
        note = "MODEL-STRUCT: " + "; ".join(struct)
    elif not saw_group:
        note = ("group @" + event + " absent from the port output: the name is not in the port "
                "event whitelist, so the port treats it as an ordinary function (no event dispatch)")
    elif expanded != printed:
        note = ("model bucket expansion != erbcc printed expansion: model %s vs printed %s"
                % (expanded, printed))
    elif not out:
        note = "group present but no fixture member mapped; check file/line columns"
    elif cut_notes:
        note = ("; ".join(cut_notes) + " (registered members: %d, bucket-expanded calls: %d, "
                "dispatched: %d, model struct: ok)"
                % (len(entries), len(expanded), len(out)))
    return out, note


def run_port(case, erb_dir, json_out, txt_out, ordinal=False):
    """Port reading: the same front-end the device uses (core.frontend.Symbols eventCallOrder).

    Chosen instrument and why: erbcc 'symbols' CLI is headless, needs no Android SDK, and prints
    table.eventCallOrder(name) = flatten(eventBuckets(name)) -- the very list the VM dispatches
    (core-vm/.../proc/FuncMeta.kt:533-542 eventDispatchOrder, core/frontend/Symbols.kt:606-617).
    Since ledger B23 the registration face adds to buckets in parallel (4.981
    LabelDictionary.cs:228-235), so a #PRI+#LATER member is listed twice; port_sequence then runs
    the dispatch model (#ONLY chain kill, #SINGLE own-bucket cut) to get the marker order the engine
    prints. A runtime reading on the port would need :tools:vmdbg to boot the whole corpus and CALL
    an EVENT member, which is not supported for event labels (docs/golden-runbook.md 2 勘误1);
    EventRegistryTest / EventDispatchWireTest / SymbolsTest assertions are the encoded witnesses of
    the same rule and are quoted in the verdict instead.
    """
    inst = erbcc_install()
    jv = java_exe()
    if not inst:
        return {"error": "erbcc installDist missing: gradlew --configure-on-demand :tools:erbcc:installDist"}
    if not jv:
        return {"error": "no java: set JAVA_HOME or put a jdk-17 under ERATW_ROOT/.tooling"}
    jars = sorted(os.path.join(inst, "lib", f).replace(chr(92), "/")
                  for f in os.listdir(os.path.join(inst, "lib")) if f.endswith(".jar"))
    argv = [jv, "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(jars),
            "com.eratw.port.erbcc.MainKt", "symbols",
            "--corpus", erb_dir.replace(chr(92), "/"), "--json", json_out.replace(chr(92), "/")]
    if ordinal:
        argv.append("--ordinal")
    p = subprocess.run(argv, cwd=ANDROID, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = p.stdout.decode("utf-8", "replace")
    with open(txt_out, "w", encoding="utf-8") as fh:
        fh.write(text)
    mmap = fixture_marker_map(case)
    seq, note = port_sequence(case, text, mmap, fixture_return_map(case), EXCLUSIVE_CONTROL)
    return {"exit": p.returncode, "text": text, "seq": seq, "note": note, "map": len(mmap)}


# ------------------------------------------------------------------- selfcheck
def selfcheck():
    """Red-line self-test (lead ruling 2026-11-26 item A): proves the LOCK raises, the teardown order
    cannot delete a junction target, and the hash oracle detects a byte change. No exe, no copy root,
    a few seconds. Non-zero exit if any item fails, so a lane reusing this rig can gate on it."""
    results = []

    def check(name, fn_, expect="ok"):
        try:
            detail = fn_()
            results.append((name, True, detail or ""))
        except Exception as exc:
            results.append((name, False, type(exc).__name__ + ": " + str(exc)[:160]))

    def raises_ro(fn_):
        try:
            fn_()
        except RuntimeError as exc:
            return str(exc)[:90]
        raise AssertionError("RED-LINE LOCK did not fire")

    tmp = os.path.join(RUN_BASE, "selfcheck-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(tmp, exist_ok=True)
    guarded = guarded_roots()
    if not guarded:
        raise SystemExit("no guarded roots to test against")

    def t_writable():
        # src basename must look like a writable corpus dir, else the lock correctly ignores it
        return raises_ro(lambda: assert_copy_root_policy(
            os.path.join(tmp, "game"),
            [(os.path.join(tmp, "game", "plugins"), os.path.join(guarded[0], "plugins"))]))

    def t_inside():
        return raises_ro(lambda: assert_copy_root_policy(
            os.path.join(guarded[0], "game", "x"), []))

    def t_missing():
        return raises_ro(lambda: assert_copy_root_policy(
            os.path.join(tmp, "game"), [(os.path.join(tmp, "game", "CSV"),
                                         os.path.join(tmp, "no-such-dir-xyz"))]))

    def t_coverage():
        bad = []
        for root in guarded:
            if not os.path.isdir(root):
                continue
            for e in os.scandir(root):
                if e.is_dir(follow_symlinks=False) and e.name.lower() in WRITABLE_NAMES:
                    if e.name not in COPY_DIRS and e.name.lower() not in WRITABLE_NAMES:
                        bad.append(root + "/" + e.name)
        if bad:
            raise AssertionError("writable dirs not covered: " + ", ".join(bad))
        if not set(["sav", "ERB"]).issubset(NEVER_LINK):
            raise AssertionError("NEVER_LINK must hold sav and ERB")
        return "writable dirs in the 3 roots: " + ", ".join(sorted(set(
            e.name for r in guarded if os.path.isdir(r) for e in os.scandir(r)
            if e.is_dir(follow_symlinks=False) and e.name.lower() in WRITABLE_NAMES)))

    def t_cases():
        for c, spec in sorted(CASES.items()):
            text = ""
            fdir = fixture_src_dir(spec)
            for fx in spec["fixtures"]:
                p = os.path.join(fdir, fx)
                if not os.path.isfile(p):
                    raise AssertionError("missing fixture " + p)
                text += io.open(p, encoding="utf-8-sig", errors="replace").read()
            if set(spec["expected"]) & set(spec["absent"]):
                raise AssertionError(c + ": expected and absent overlap")
            for m in set(spec["expected"]) | set(spec["absent"]):
                if m not in text:
                    raise AssertionError(c + ": marker " + m + " not in any fixture body")
            for m in spec.get("raw_expected", []):
                if m not in text:
                    raise AssertionError(c + ": raw marker " + m + " not in any fixture body")
            for m, want in sorted(spec.get("expect_counts", {}).items()):
                if spec["expected"].count(m) != want:
                    raise AssertionError(c + ": expect_counts " + m + "=" + str(want)
                                         + " but expected lists it " + str(spec["expected"].count(m)))
                if text.count(m + chr(10)) + text.count(m + " ") < 1:
                    raise AssertionError(c + ": " + m + " defined nowhere")
        return "cases " + ", ".join(sorted(CASES)) + " consistent with fixtures"

    def t_markers():
        got = markers_in("x PCMARK-A y PCMARK-A z PCMARK-B")
        if got != ["PCMARK-A", "PCMARK-A", "PCMARK-B"]:
            raise AssertionError("markers_in lost the duplicate: " + repr(got))
        return "duplicate-preserving order ok"

    def t_hash():
        d = os.path.join(tmp, "hashtub")
        os.makedirs(d, exist_ok=True)
        io.open(os.path.join(d, "a.txt"), "w").write("one")
        os.makedirs(os.path.join(d, "sub"), exist_ok=True)
        io.open(os.path.join(d, "sub", "b.txt"), "w").write("two")
        import hashlib
        global guarded_roots
        keep = guarded_roots
        try:
            guarded_roots = lambda: [d]
            m1 = take_manifest()
            io.open(os.path.join(d, "a.txt"), "w").write("ONE")
            io.open(os.path.join(d, "sub", "c.txt"), "w").write("new")
            m2 = take_manifest()
            ch, ad, rm, lines = compare_manifest(m1, m2)
            if ch != ["hashtub/a.txt"] or ad != ["hashtub/sub/c.txt"]:
                raise AssertionError("oracle missed a change: " + repr(ch) + " " + repr(ad))
        finally:
            guarded_roots = keep
        return "byte change + new file both detected"

    def t_teardown():
        target = os.path.join(tmp, "junction-target")
        os.makedirs(target, exist_ok=True)
        io.open(os.path.join(target, "keepme.txt"), "w").write("precious")
        game = os.path.join(tmp, "game")
        os.makedirs(game, exist_ok=True)
        if not link_dir(os.path.join(game, "CSV"), target):
            raise AssertionError("junction creation failed (needs Windows junctions)")
        io.open(os.path.join(game, "realfile.txt"), "w").write("x")
        drop_copy_root(game, [os.path.join(game, "CSV")])
        if not os.path.isfile(os.path.join(target, "keepme.txt")):
            raise AssertionError("TEARDOWN ATE THE JUNCTION TARGET")
        if os.path.exists(game):
            raise AssertionError("copy root survived teardown")
        return "junction unlinked, target file intact"

    def t_dest():
        for root in guarded_roots()[:1]:
            try:
                assert_write_dest(os.path.join(root, "sav", "global.sav"), tmp, "seed save")
            except RuntimeError as exc:
                return "tripwire fired: " + str(exc)[:110]
            raise AssertionError("destination lock did not fire for a corpus-root write")
        return "no guarded root present"

    def t_nlink():
        import re as _re
        d = os.path.join(tmp, "nlink-probe")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "f.txt"), "w") as fh:
            fh.write("x")
        v = nlink_manifest(d).get("f.txt", "")
        if not (_re.match("^\\d+:\\d+$", v) and v.split(":")[0] == "1"):
            raise AssertionError("bad sidecar value: " + repr(v))
        return "nlink:mtime_ns shape ok, fresh file nlink=1"

    def t_model():
        spec = CASES["buckets"]
        mmap = fixture_marker_map("buckets")
        rmap = fixture_return_map("buckets")
        ent = fixture_entries("buckets")
        keys, notes = dispatch_model(ent, rmap)
        markers = [mmap[k] for k in keys if k in mmap]
        flat = [k for b in (0, 1, 2, 3) for (k, fl) in ent if b in bucket_set(fl)]
        expanded = len(flat)
        problems = expansion_selfcheck(ent, flat)
        if problems:
            raise AssertionError("model expansion not structural: " + "; ".join(problems))
        if keys.count(ent[0][0]) != 2:
            raise AssertionError("model did not dispatch the #PRI+#LATER member twice: " + repr(keys))
        if len(keys) != expanded - 1:
            raise AssertionError("dispatch count != expanded minus one cut tail: keys=%d expanded=%d"
                                 % (len(keys), expanded))
        if markers != spec["expected"]:
            raise AssertionError("model != PC reading: got %s want %s" % (markers, spec["expected"]))
        leaked = [m for m in spec["absent"] if m in markers]
        if leaked:
            raise AssertionError("model prints an absent marker: " + repr(leaked))
        cnt = {}
        for m in markers:
            cnt[m] = cnt.get(m, 0) + 1
        bad = [k for k, v in spec.get("expect_counts", {}).items() if cnt.get(k) != v]
        if bad:
            raise AssertionError("model marker counts wrong: " + repr(bad))
        stale_exp = [k for b in (0, 1, 2, 3) for (k, fl) in ent if b in bucket_set(fl, True)]
        stale_problems = expansion_selfcheck(ent, stale_exp, True)
        if stale_problems:
            raise AssertionError("exclusive control not structural either: " + "; ".join(stale_problems))
        stale, _n = dispatch_model(ent, rmap, exclusive=True)
        stale_markers = [mmap[k] for k in stale if k in mmap]
        if stale_markers.count("PCMARK-1-PL") != 1 or stale_markers == markers:
            raise AssertionError("exclusive control does not differ from the parallel model: "
                                 + repr(stale_markers))
        return ("buckets: %d members -> %d bucket-expanded registrations -> %d calls, "
                "1 #SINGLE tail cut; exclusive control differs (%s)"
                % (len(ent), expanded, len(markers), "; ".join(notes) or "no cut"))

    check("lock: every write destination stays inside the copy root", t_dest)
    check("lock: writable dir may not be junctioned", t_writable)
    check("lock: copy root may not live in a corpus root", t_inside)
    check("lock: junction target must exist", t_missing)
    check("writable dirs of the 3 roots are real-copied", t_coverage)
    check("cases match fixtures (markers, counts, disjointness)", t_cases)
    check("markers_in preserves duplicates (the whole point of ledger B23)", t_markers)
    check("hash oracle detects byte change and new file", t_hash)
    check("teardown unlinks junctions without walking them", t_teardown)
    def t_unregistered():
        reg = set(x.lower() for x in JUNCTION_DIRS)
        for r in guarded_roots():
            if not os.path.isdir(r):
                continue
            for e in os.scandir(r):
                n = e.name.lower()
                if e.is_dir(follow_symlinks=False) and n not in reg and n not in WRITABLE_NAMES:
                    try:
                        assert_copy_root_policy(tmp, [(os.path.join(tmp, "evil"), e.path)])
                    except RuntimeError as exc:
                        return "tripwire fired on " + e.name + ": " + str(exc)[:90]
                    raise AssertionError("unregistered corpus junction not rejected: " + e.path)
        return "no unregistered corpus dir available to probe"

    check("nlink sidecar manifest shape (nlink:mtime_ns)", t_nlink)
    check("unregistered corpus-root junction rejected by default", t_unregistered)
    check("port dispatch model reproduces the PC reading (B23)", t_model)
    shutil.rmtree(tmp, ignore_errors=True)

    say("== selfcheck ==")
    bad = 0
    for name, ok, detail in results:
        say("  %s %s%s" % ("PASS" if ok else "FAIL", name, (" -> " + detail) if detail else ""))
        if not ok:
            bad += 1
    say("selfcheck: " + ("ALL PASS" if not bad else "%d FAILED" % bad))
    return 0 if not bad else 1


# ---------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description="PC-original 对拍装置")
    ap.add_argument("--case", choices=sorted(CASES), default="buckets")
    ap.add_argument("--host", choices=sorted(HOSTS), default="skia4981")
    ap.add_argument("--side", choices=["both", "pc", "port"], default="both")
    ap.add_argument("--settle", type=int, default=None, help="seconds to wait after ready before pulling the log")
    ap.add_argument("--full-corpus", action="store_true", help="also run the port reading over the whole copy-root ERB (slow)")
    ap.add_argument("--keep", action="store_true", help="keep the copy root for forensics")
    ap.add_argument("--no-launch", action="store_true", help="only build the copy root")
    ap.add_argument("--seed-saves", default="", help="dir of .sav files to copy into the private sav/ (default: the case seed)")
    ap.add_argument("--no-seed", action="store_true", help="leave sav/ empty (then @EVENTLOAD cannot fire)")
    ap.add_argument("--menus", default="", help="comma-separated answers for value/button prompts, overriding the case")
    ap.add_argument("--reverse-proof-exclusive", action="store_true",
                    help="B23 reverse proof: force the old one-bucket-per-member registration rule in the port model (never a production reading)")
    ap.add_argument("--fixtures-only-port", action="store_true", help="port side reads an isolated fixture-only corpus dir (default)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--manifest-init", action="store_true",
                    help=("write the full-tree sha256 baseline (corpus-baseline.json) and exit",
                         " also refreshes the nlink sidecar"))
    ap.add_argument("--nlink-init", action="store_true",
                    help="write ONLY the nlink+mtime_ns sidecar baseline (corpus-nlink.json);"
                         "keeps the pinned sha baseline untouched")
    ap.add_argument("--no-manifest", action="store_true",
                    help="skip the hash manifest pass (fingerprint guard still runs)")
    ap.add_argument("--hash-only", action="store_true",
                    help="post-batch red-line closure: re-hash the three corpus roots and diff them "
                         "against corpus-baseline.json; no exe, no copy root (~90 s)")
    ap.add_argument("--selfcheck", action="store_true",
                    help="prove the red-line lock, the teardown order and the hash oracle fire; no exe, no copy root")
    a = ap.parse_args()

    if a.selfcheck:
        return selfcheck()
    if a.manifest_init:
        os.makedirs(EVIDENCE, exist_ok=True)
        man = take_manifest()
        with open(baseline_path(), "w", encoding="utf-8") as fh:
            json.dump({"taken_at": time.strftime("%Y-%m-%d %H:%M:%S"), "roots": man}, fh,
                      ensure_ascii=False, sort_keys=True)
        say("baseline written: " + baseline_path())
        for k, v in sorted(man.items()):
            say("  %-32s %d files" % (k, len(v)))
        data = {"taken_at": time.strftime("%Y-%m-%d %H:%M:%S"), "roots": take_nlink_manifest()}
        with open(nlink_path(), "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, sort_keys=True)
        say("nlink sidecar written: " + nlink_path())
        return 0

    if a.nlink_init:
        os.makedirs(EVIDENCE, exist_ok=True)
        data = {"taken_at": time.strftime("%Y-%m-%d %H:%M:%S"), "roots": take_nlink_manifest()}
        with open(nlink_path(), "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, sort_keys=True)
        say("nlink sidecar written: " + nlink_path())
        for k, v in sorted(data["roots"].items()):
            say("  %-32s %d files" % (k, len(v)))
        return 0

    if a.hash_only:
        os.makedirs(EVIDENCE, exist_ok=True)
        bp = baseline_path()
        if not os.path.isfile(bp):
            say("no baseline yet -- run --manifest-init first")
            return 2
        base_man = json.load(open(bp, "r", encoding="utf-8"))["roots"]
        st = time.strftime("%Y%m%d-%H%M%S")
        cur = take_manifest()
        out = os.path.join(EVIDENCE, "%s-hashcheck.json" % st)
        with open(out, "w", encoding="utf-8") as fh:
            json.dump({"taken_at": st, "baseline": bp, "roots": cur}, fh,
                      ensure_ascii=False, sort_keys=True)
        changed, added, removed, lines = compare_manifest(base_man, cur)
        for line in lines:
            say(line)
        say("  files hashed : %d" % sum(len(v) for v in cur.values()))
        for r in guarded_roots():
            say("  mtime fp     : " + fingerprint(r).split(" | items=")[0])
        nlink_drift = []
        sp = nlink_path()
        if os.path.isfile(sp):
            nbase = json.load(open(sp, "r", encoding="utf-8"))["roots"]
            ncur = take_nlink_manifest()
            for root in sorted(set(nbase) | set(ncur)):
                nb, nc = nbase.get(root, {}), ncur.get(root, {})
                for rel in sorted(set(nb) & set(nc)):
                    if nb[rel] != nc[rel]:
                        nlink_drift.append("NLINK-DRIFT %s/%s: %s -> %s" % (root, rel, nb[rel], nc[rel]))
                for rel in sorted(set(nc) - set(nb)):
                    nlink_drift.append("NLINK-ADDED %s/%s = %s" % (root, rel, nc[rel]))
                for rel in sorted(set(nb) - set(nc)):
                    nlink_drift.append("NLINK-REMOVED %s/%s (was %s)" % (root, rel, nb[rel]))
            with open(os.path.join(EVIDENCE, "%s-nlinkcheck.json" % st), "w", encoding="utf-8") as fh:
                json.dump({"taken_at": st, "sidecar": sp, "drift": nlink_drift}, fh,
                          ensure_ascii=False, indent=1)
            say("  NLINK-CHECK  : " + ("clean" if not nlink_drift else "drift=%d" % len(nlink_drift)))
            for line in nlink_drift[:12]:
                say("  " + line)
        dirty = bool(changed or added or removed) or bool(nlink_drift)
        say("  HASH-CHECK   : " + ("CLEAN (changed 0 / added 0 / removed 0)" if not dirty else
                                   "DIRTY changed=%d added=%d removed=%d" % (len(changed), len(added), len(removed))))
        say("  evidence     : " + out + "   baseline: " + bp)
        return 2 if dirty else 0

    if a.list:
        for k, v in HOSTS.items():
            say("host %-9s exe=%s argv=%s" % (k, v["exe"], v["argv"]))
            say("     roots: %s | %s" % (v["exe_root"], v["data_root"]))
            say("     note : " + v["note"])
        for k, v in CASES.items():
            say("case %-9s fixtures=%s expected=%s absent=%s" % (k, v["fixtures"], v["expected"], v["absent"]))
        return 0

    global EXCLUSIVE_CONTROL
    EXCLUSIVE_CONTROL = bool(getattr(a, "reverse_proof_exclusive", False))
    stamp = time.strftime("%Y%m%d-%H%M%S")
    spec0 = CASES[a.case]
    seed = a.seed_saves
    if not seed and spec0.get("seed") and not a.no_seed and a.side in ("both", "pc") and not a.no_launch:
        seed = default_seed_dir()
    if seed and not os.path.isdir(seed):
        say("NOTE: seed dir absent, sav/ stays empty: " + seed)
        seed = ""
    menus = [x for x in a.menus.split(",") if x] if a.menus else list(spec0.get("menus", []))
    os.makedirs(EVIDENCE, exist_ok=True)
    base = os.path.join(EVIDENCE, "%s-%s-%s" % (stamp, a.host, a.case))

    before = [fingerprint(p) for p in guarded_roots()]
    build = build_copy_root(a.case, a.host, stamp, seed or None)
    say("== copy root (PC runs ONLY here) ==")
    say("  run_root : " + build["run_root"])
    say("  game     : " + build["game"])
    say("  exe      : " + build["exe"] + " " + " ".join(build["argv"]))
    say("  junctions: %d   ERB top files: %d   fixtures: %s" % (len(build["links"]), build["erb_top_files"], ", ".join(CASES[a.case]["fixtures"])))
    say("  copied   : %s   seeded sav: %s" % (", ".join(build["copied_dirs"]) or "none", ", ".join(build["seeded_saves"]) or "none"))
    say("  menus    : " + (", ".join(menus) or "(enter/skip only)"))
    for d in build["config_delta"]:
        say("  " + d)
    with open(base + ".copyroot.json", "w", encoding="utf-8") as fh:
        json.dump(build, fh, ensure_ascii=False, indent=1)

    rc = 0
    spec = CASES[a.case]
    pc = None
    pc_counts = {}
    pc_seq_out = None
    port_seq = None
    port_note = ""
    port_line = "(port side skipped)"
    raw_exp = []
    raw_hits = {}
    if a.side in ("both", "pc") and not a.no_launch:
        say("== PC side ==")
        say("  cwd      : " + build["game"] + "   (corpus roots untouched)")
        pc = run_pc(a.case, a.host, build, a.settle if a.settle is not None else spec["settle"], base + ".pc", menus=menus)
        got = pc["markers"] or pc.get("streamed", [])
        pc_seq_out = got
        exp = spec["expected"]
        missing = [m for m in exp if m not in got]
        leaked = [m for m in spec["absent"] if m in got]
        counts = {}
        for m in got:
            counts[m] = counts.get(m, 0) + 1
        bad_counts = [k for k, v in spec.get("expect_counts", {}).items() if counts.get(k) != v]
        pc_counts = counts
        # golden-smoke (v2.3): no PCMARK tokens in the GOLDEN suite -- the verdict rides raw
        # substrings. Each marker records WHICH stream it is visible in (GetLog snapshot via the
        # log cmd vs the live stdout.jsonl events), per the 2026-11-26 lead ruling: no guessing.
        raw_exp = spec.get("raw_expected", [])
        raw_hits = {}
        if raw_exp:
            try:
                jsonl_text = io.open(base + ".pc.stdout.jsonl", encoding="utf-8",
                                     errors="replace").read()
            except OSError:
                jsonl_text = ""
            for m in raw_exp:
                vis = []
                if m in (pc.get("console") or ""):
                    vis.append("GetLog")
                if m in jsonl_text:
                    vis.append("stdout.jsonl")
                raw_hits[m] = "+".join(vis) or "NONE"
        raw_ok = (not raw_exp) or all(v != "NONE" for v in raw_hits.values())
        match = (got == exp) and not missing and not leaked and not bad_counts and raw_ok
        say("  observed : " + ", ".join(got))
        say("  counts   : " + ", ".join("%s=%d" % (k, v) for k, v in sorted(counts.items())))
        say("  expected : " + ", ".join(exp))
        say("  missing  : " + (", ".join(missing) or "none"))
        say("  leaked   : " + (", ".join(leaked) or "none"))
        say("  x-count  : " + (", ".join("%s want %d got %d" % (k, spec["expect_counts"][k], counts.get(k, 0)) for k in bad_counts) or "ok"))
        say("  streamed : " + ", ".join(pc.get("streamed", [])))
        if raw_exp:
            say("  raw      : " + ", ".join("%s -> %s" % (m, raw_hits[m]) for m in raw_exp))
            say("  RAW-MATCH: " + ("YES" if raw_ok else "NO"))
        say("  state    : " + pc["state"])
        say("  PC-MATCH : " + ("YES" if match else "NO"))
        write_pc_markers(base + ".pc-markers.txt", got, pc_counts, pc["state"],
                         pc.get("advanced", []), base + ".pc.console.txt")
        if not match:
            rc = 1
        pc_match = match
    else:
        pc_match = None
        if a.no_launch:
            say("--no-launch: copy root built, nothing launched")

    if a.side in ("both", "port"):
        say("== port side (same fixture bytes, parallel bucket registration + dispatch model) ==")
        if EXCLUSIVE_CONTROL:
            say("  !! REVERSE PROOF MODE: buckets forced exclusive (B23), NOT a production reading !!")
        iso = os.path.join(build["run_root"], "port-erb")
        os.makedirs(iso, exist_ok=True)
        pdir = fixture_src_dir(spec)
        for fx in spec["fixtures"]:
            shutil.copy2(os.path.join(pdir, fx), os.path.join(iso, fx))
        r1 = run_port(a.case, iso, base + ".port-iso.json", base + ".port-iso.txt")
        if "error" in r1:
            say("  " + r1["error"])
            rc = 1
        else:
            say("  isolated : exit=%s mapped-members=%d -> %s" % (r1.get("exit"), r1.get("map", 0), base + ".port-iso.txt"))
            say("  port seq : " + (", ".join(r1.get("seq", [])) or "(none)"))
            if r1.get("note"):
                say("  port note: " + r1["note"])
            pdiff = r1.get("seq", []) != spec["expected"]
            say("  PORT-vs-EXPECTED : " + ("DIFF (expected " + ", ".join(spec["expected"]) + ")" if pdiff else "same"))
            port_seq = r1.get("seq", [])
            port_note = r1.get("note", "")
            port_line = ", ".join(port_seq) or ("(none)" + (" / " + port_note if port_note else ""))
        if a.full_corpus:
            r2 = run_port(a.case, build["erb"], base + ".port-full.json", base + ".port-full.txt")
            say("  fullcorp : exit=%s -> %s" % (r2.get("exit", "?"), base + ".port-full.txt"))

    after = [fingerprint(p) for p in guarded_roots()]
    proof = before == after
    say("== self proof ==")
    say("  copy root: " + build["run_root"])
    for b, af in zip(before, after):
        tag = "SAME" if b == af else "DIFF"
        say("  %s %s" % (tag, b.split(" | ")[0]))
        if b != af:
            say("    before: " + b)
            say("    after : " + af)
    say("  CORPUS-UNTOUCHED: " + ("PASS" if proof else "FAIL"))
    if not proof:
        rc = 2
        for line in fp_drift(before, after):
            say("  drift " + line)

    # Lead ruling 2026-11-26 item B: the mtime fingerprint is a tripwire, the byte-level verdict is
    # a full-tree sha256 manifest. Hash change = FAIL (case goes red); mtime-only change = WARN.
    hash_line = "(manifest pass skipped)"
    if not a.no_manifest:
        say("== corpus manifest (full-tree sha256, ~90 s) ==")
        os.makedirs(EVIDENCE, exist_ok=True)
        bp = baseline_path()
        if not os.path.isfile(bp):
            man0 = take_manifest()
            with open(bp, "w", encoding="utf-8") as fh:
                json.dump({"taken_at": time.strftime("%Y-%m-%d %H:%M:%S"), "roots": man0,
                           "auto": True}, fh, ensure_ascii=False, sort_keys=True)
            say("  baseline auto-created (no prior manifest): " + bp)
        base_man = json.load(open(bp, "r", encoding="utf-8"))["roots"]
        cur = take_manifest()
        with open(base + ".corpus-manifest.json", "w", encoding="utf-8") as fh:
            json.dump({"stamp": stamp, "case": a.case, "host": a.host, "roots": cur}, fh,
                      ensure_ascii=False, sort_keys=True)
        changed, added, removed, lines = compare_manifest(base_man, cur)
        for line in lines:
            say(line)
        nfiles = sum(len(v) for v in cur.values())
        hash_ok = not (changed or added or removed)
        hash_line = ("PASS (%d files hashed, baseline %s)" % (nfiles, os.path.basename(bp))
                     if hash_ok else
                     "FAIL changed=%d added=%d removed=%d" % (len(changed), len(added), len(removed)))
        say("  CORPUS-HASH: " + hash_line)
        if not hash_ok:
            rc = 2
            say("  CASE-RED: corpus bytes moved -- this case is judged FAIL regardless of PC-MATCH")
        if proof and (changed or added or removed):
            say("  NOTE: mtime fingerprint said SAME but the hashes moved -> deepen fingerprint depth")

    # Lead ruling 2026-11-26 item 1: verdict.txt hit 1,040,542 B because both full mtime blobs were
    # inlined (lines "roots before/after"). The blobs move to a sidecar; the verdict keeps one summary
    # line per root plus the evidence paths. Collection stays in the per-case path (m00932).
    fp_path = base + ".fingerprint.txt"
    with open(fp_path, "w", encoding="utf-8") as fh:
        fh.write("roots before:" + NL + NL.join("  " + b for b in before) + NL
                 + "roots after:" + NL + NL.join("  " + b for b in after) + NL)
    with open(base + ".verdict.txt", "w", encoding="utf-8") as fh:
        fh.write(NL.join([
            "case " + a.case + " host " + a.host + " stamp " + stamp,
            "copy root (PC cwd) : " + build["game"],
            "exe + argv        : " + os.path.basename(build["exe"]) + " " + " ".join(build["argv"]),
            "fixtures          : " + ", ".join(spec["fixtures"]),
            "config delta      : " + ("; ".join(build["config_delta"]) or "none"),
            "PC expected       : " + ", ".join(spec["expected"]),
            "PC observed       : " + (", ".join(pc_seq_out) if pc_seq_out is not None else "(pc side skipped)"),
            "PC absent terms   : " + ", ".join(spec["absent"]),
            "PC-MATCH          : " + ("-" if pc_match is None else ("YES" if pc_match else "NO")),
            "PC raw markers    : " + (", ".join("%s=%s" % (m, raw_hits[m]) for m in raw_exp) if raw_exp else "(PCMARK case: n/a)"),
            "PC sections       : " + (str((pc.get("console") or "").count("=== SECTION:")) if raw_exp and pc else "-"),
            "KNOWN-DEVIATIONS  : " + ("skiaV16 host: OUTPUTLOG statement commented out in the fork "
            "(BuiltInFunctionCode.cs:85) -> runbook sec.3 OUTPUTLOG primary path is dead here, "
            "capture rides log-cmd GetLog + stdout.jsonl dual stream; debug cmd refused by this "
            "build (prior reading 2026-11-26 00:04) -> SavDir location stays TODO(verify), not "
            "guessed; suite-internal value waits are answered 0 by advance_boot (baseline hand-ran "
            "title=0 likewise)" if a.host == "skia4981" else "(lazy host: OUTPUTLOG available)"),
            "PC boot answers   : " + (" | ".join(x for x in (pc.get("advanced") or []) if x)
                                      if pc else "(pc side skipped)"),
            "PC shutdown       : taskkill /T /F (quit not sent -- belt-and-braces; the 2026-11-25 "
            "23:51 corpus sav touch was this rig's own old seed step, see the module docstring rule 2)",
            "PC savdir probe   : " + json.dumps(build.get("savdir_probe") or {}, ensure_ascii=False)[:600],
            "port instrument   : erbcc symbols (core.frontend.Symbols eventCallOrder, parallel bucket "
            "registration + #SINGLE/#ONLY dispatch model)",
            "port observed     : " + port_line,
            "port note         : " + (port_note or "none"),
            "port why          : erbcc symbols = core.frontend.Symbols eventCallOrder, the list the port VM dispatches; headless, no Android SDK (see run_port docstring)",
            "evidence jsonl    : " + base + ".pc.stdout.jsonl",
            "evidence console  : " + base + ".pc.console.txt",
            "rig version       : " + RIG_VERSION + " (" + RIG_NOTES + ")",
            "advance_boot      : case idle=%s first_idle=170.0 deadline=460.0" % CASES[a.case].get("idle"),
            "PC counts         : " + (", ".join("%s=%d" % (k, v) for k, v in sorted(pc_counts.items()))
                                      if pc else "(pc side skipped)"),
            "PC state          : " + (state_name(pc["state"]) if pc else "(pc side skipped)",)[0],
            "CORPUS-UNTOUCHED  : " + ("PASS" if proof else "FAIL"),
            "CORPUS-MTIME      : " + ("; ".join(fp_drift(before, after)) or "no drift"),
            "CORPUS-MTIME detail : " + "; ".join(
                "%s %s" % ("SAME" if b == af else "DIFF", " | ".join(b.split(" | ")[:2]))
                for b, af in zip(before, after)),
            "CORPUS-HASH       : " + hash_line,
            "manifest evidence : " + base + ".corpus-manifest.json",
            "fingerprint evidence : " + fp_path,
            "pc markers evidence  : " + base + ".pc-markers.txt",
        ]) + NL)
    say("verdict    : " + base + ".verdict.txt")

    if not a.keep:
        drop_copy_root(build["run_root"], build["links"])
        say("copy root removed (junctions unlinked first, targets never walked): " + build["run_root"])
    else:
        say("copy root kept   : " + build["run_root"])
    return rc


def state_name(raw):
    # The state cmd answers a whole JSON object; the verdict only wants the engine state name.
    try:
        return json.loads(raw).get("state") or "(none)"
    except Exception:
        return (raw or "(none)")[:120]


def write_pc_markers(path, seq, counts, state, answers, console_path):
    # Lead ruling 2026-11-26: the ordered PCMARK sequence, its counts and the loader warnings are the
    # citation surface for a case, so they get a small text file instead of being reconstructed from a
    # 200 KB console dump.
    warns = []
    try:
        with open(console_path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                t = line.strip()
                if t.startswith("警告") or t.startswith("错误"):
                    warns.append(t)
    except OSError as exc:
        warns.append("(console file unreadable: %s)" % exc)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(NL.join([
            "ordered PCMARK lines (dispatch order, duplicates preserved):",
            ", ".join(seq) if seq else "(none)",
            "counts: " + (", ".join("%s=%d" % (k, v) for k, v in sorted(counts.items())) or "(none)"),
            "state: " + state + "  (bare: " + state_name(state) + ")",
            "boot answers: " + (" | ".join(x for x in (answers or []) if x) or "(none)"),
            "warning / error lines from the whole console buffer:",
            NL.join(warns) if warns else "(none)",
        ]) + NL)


def is_reparse(p):
    """True for a junction/symlink: st_file_attributes exists only on Windows CPython."""
    try:
        return bool(os.stat(p, follow_symlinks=False).st_file_attributes & 1024)
    except (OSError, AttributeError):
        return False


def drop_copy_root(path, links):
    """Unlink every junction with bare rmdir first (rmdir /S would follow the link into the
    corpus root), then delete the real throwaway tree."""
    for p in links:
        subprocess.call(["cmd", "/c", "rmdir", p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for dirpath, dirnames, _ in os.walk(path):
        for d in list(dirnames):
            p = os.path.join(dirpath, d)
            if is_reparse(p):
                subprocess.call(["cmd", "/c", "rmdir", p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                dirnames.remove(d)
    shutil.rmtree(path, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
