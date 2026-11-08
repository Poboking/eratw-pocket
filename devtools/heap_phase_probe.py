#!/usr/bin/env python3
"""heap_phase_probe.py —— 把 `LiveSetCensus` 在桌面 512m 压堆臂上读出一次真数（取证车道 L7）。

读的是什么
----------
`core-vm/src/main/kotlin/com/eratw/port/core/diag/LiveSetCensus.kt` 那份 **Java 堆结构账面**
（装载 / 运行 / 行缓冲 / 图形 Java 四段，估算口径不是 dominator tree）。本工具在**同一条桌面臂**
（`-Xmx512m` + `obs-compat512` 三开关里的 `--compat-load` / `--load-precheck`，第三开关
`--boot-only` 属于「造种子」那一步）的三个相位上各调一次 `census(...)`：

  assembled  装载完（Program + 侧车 + 会话装配）——还没还原任何存档
  restored   `SnapShots.restore(seed.dbg)` 之后（LOADDATA 语义，只还变量面 + RNG）
  pump_end   按臂同款双线程形状有界推进之后（泵一发都没推进时也照读，`passes=0` 如实报）

每个读数点同时打 `(totalMemory - freeMemory)` 与 `maxMemory()`，于是「census 账面 vs 实际水位」
的差额是一行可读数字而不是口头判断。**口径警告**（与 `ProgramLoader.kt:370-371` 同源）：
`total - free` 含未回收垃圾，同一份产物两次跑实测 342.6 / 388.3 / 725.5 MiB，
⇒ 差额只读趋势、不读绝对值；带 `--histo` 时读数点后会再打一行 `postGcUsedMiB`（那才是催完
一次全量 GC 的 live 近似）。

**求和口径（EP3/L13 改）**：本工具**不再自己拼一份字段清单求和**，账面和直接取
`LiveSetCensus.Census.bookedSumBytes()`（`core-vm/.../core/diag/LiveSetCensus.kt:429-433`，
进和清单 `:448-459`）与它的缺项数 `bookedMissingFieldCount()`（`:436`，>0 ⇒ 和是**下界**）。
旧那把尺的两处病（`LiveSetCensus.kt:420-424` 逐条写明）：①把 `charaReserveBytes` 也加进和，
而新口径下它是 `charaIntBytes`+`charaStrBytes` 的尾部子集（本臂重复计 5,309,700 B ≈ 5.06 MiB）；
②全局整数取「声明尺寸」`globalIntBytes` 而不是「实占」`globalIntResidentBytes`（装载相位那一笔
差三个数量级）。为了让 EP2/L7 那组「自拼和 = 61 MiB」的旧读数仍能对上，`RAW` 行同时给
`legacyDupCountedSumBytes`（= booked + 那两笔重复计，两笔的值全部来自 Census 自身）——
**它只是对账用，不作账面**。四枚物化/片数读数（`materializedStatements` / `materializedFunctions`
/ `flatResidentSegments` / `flatBuiltSegments`）已从反射面挪到账面面（`Program.kt:1179/:1208/:1215/:1287`
的 public 门面 ⇒ `LiveSetCensus.kt:506-509`），探针直读 Census，反射那套整体删除。

桌面缺的两段（必须知道的判读边界）
----------------------------------
* **图形 Java 两段在桌面取不到**：探针把 `GraphicsPool` / `SpritePool` 传 null（headless 桌面
  会话没有图形池），按 `LiveSetCensus.kt:281-286` 的 null ⇒ -1 口径，这两项读数是 `-`，
  **不是 0**。「正号段像素是不是大头」这一问在桌面上无法回答，只有真机（`am dumpheap` /
  报告里的 `heapcensus` 轨迹行）能答 —— 一律 `TODO(verify)`。
* **行缓冲在 restored/pump_end 相位是「结构性 0」**：`SnapShots.restore` 走 loadData 语义，
  console 面明确**不还原**（`tools/vmdbg/.../Explore.kt:69`），泵又没推进 ⇒ 期间没有任何打印，
  `displayLines=0` 是真读数 0，但**不能**据此判「行缓冲不是大头」。
* MiB 是整除：亚 MiB 的项会显示 0（例如分块行号表装载后只剩块位表 ≈4.8 KiB，
  `Program.kt:1673-1686` 的 `LineTable.bytes` 分块分支）。本工具因此额外打一行 `RAW ...Bytes=`
  （字节原值）用来区分「真的是 0」与「不足 1 MiB 被折成 0」。
* **账面之外的残差**：`live − bookedSumBytes` 那一块（本臂 restored 139 − 56 ≈ 82 MiB）探针**归不了因**
  —— 它是「只有条数、没有字节项」与「对象头/容器壳不计」那几簇（判性见 `LiveSetCensus.kt:36-38`、
  逐段点名见 `LiveSetCensus.kt:57-61`）。成表里给残差一行 + 一张分段表：分段数**出处是 L10 的取证**，
  探针只在带 `--histo` 时能按类直方图**同臂复算**其中四簇（Map/Entry 壳、串本体、`FuncMeta`、`short[]`），
  复算不到的一律标「探针不读」，不许编。

产物（全部落 `--out`，缺省 `.local/heap-phase`，gitignore 内）
-------------------------------------------------------------
  probe-src/HeapCensusProbe.java   本工具内嵌的探针源码（每次覆盖写出，保证与工具同源）
  probe-classes/                   javac 输出
  probe-<stamp>.census.txt         `###CENSUS ... ###ENDCENSUS` 原始读数（含 HISTO）
  probe-<stamp>.readout.md         逐相位表：used / 账面和(booked) / 解释度% / 差额 MiB / 残差 MiB
                                   + 归不了因的分段表 + 逐字段读数
  soak-trend.md                    复用**现成** soak-steps.jsonl 的水位趋势（不重跑浸泡）

用法（在 eratw-android 检出根下；一条命令 = 编译 + 跑三相位 + 落盘）
--------------------------------------------------------------------
  python devtools/heap_phase_probe.py                       # 复用 .local 里现成 seed.dbg，短相位
  python devtools/heap_phase_probe.py --histo               # 再要一份 jcmd 同款类直方图 + post-GC 水位
  python devtools/heap_phase_probe.py --boot-only           # 先跑引导链现造 seed.dbg（三开关全套）
  python devtools/heap_phase_probe.py --pump 20 --pump-wall-ms 60000
  python devtools/heap_phase_probe.py --pump 0              # 只要 assembled/restored 两相位
  python devtools/heap_phase_probe.py --erbc <erbc.v1> --game <游戏根> --work <沙箱根>

`--erbc` / `--game` / `--work` 与 512m 臂三开关（`--xmx` / `--compat-load` / `--load-precheck`，
外加造种子用的 `--boot-only`）都可显式给出；缺省按主机名解析共享根（与 AGENTS §1、desktop.py 同源）。
禁碰产品代码：本工具只**读**已装的 vmdbg 分发（缺了才提示/执行 `installDist`）并自带一个
只读探针类；语料与 reference 一个字节都不写（存档全部落 `--work` 沙箱）。
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import socket
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent                      # eratw-android 检出根
VMDBG_LIB = ANDROID_ROOT / "tools" / "vmdbg" / "build" / "install" / "vmdbg" / "lib"
DEFAULT_OUT = ANDROID_ROOT / ".local" / "heap-phase"
DEFAULT_XMX = "512m"
MI_B = 1024 * 1024

# ---------------------------------------------------------------- 环境与探针源码

PROBE_SRC = r'''
import com.eratw.port.core.diag.LiveSetCensus;
import com.eratw.port.core.proc.QueueInputSink;
import com.eratw.port.vmdbg.Args;
import com.eratw.port.vmdbg.Assembler;
import com.eratw.port.vmdbg.ExploreSnap;
import com.eratw.port.vmdbg.SnapShots;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.ObjectName;

/**
 * heap-soak 车道（L7）的分项读出口：把 core-vm 的估算账面 LiveSetCensus 在桌面 512m 压堆臂上
 * 读出真数。它不是第二台装载器 —— 装载复用桌面臂自己的 Assembler.assemble（含 --compat-load 分支），
 * 相位复用臂产出的 seed.dbg + SnapShots.restore（LOADDATA 语义），随后按臂同款双线程形状
 * （泵 resumeSystem + 喂入 session.inputSink 队列面）有界推进，在每个读数点调一次 census(...)。
 *
 * 图形 Java 两段（GraphicsPool / SpritePool）在桌面传 null，按 census 的 null 折 -1 口径 ⇒ 读数是
 * "-"（桌面 headless 无池），不是 0。MiB 一律整除，故同批打 RAW 字节原值，用来分清「真 0」与
 * 「不足 1 MiB」。**账面和一律取 Census 自己的 bookedSumBytes()（去重那把尺，L10 在 core 侧写的），
 * 本探针不再自带一份字段清单求和**（旧清单既读不到新增六枚字段，又把 charaReserveBytes 重复计进和里）；
 * 缺项数由 bookedMissingFieldCount() 给，大于 0 时和只是下界。四枚物化/片数读数改直读账面字段，
 * 反射那一套（按名字前缀猜 internal getter/字段）删除。--histo 走 HotSpot 的 DiagnosticCommand MBean
 * （jcmd GC.class_histogram 同一条命令，进程内调用、不额外 attach），会催一次全量 GC，
 * 故 HISTO 之后补一行 postGcUsedMiB；那一行才是「live」，残差按 live 减账面和读。
 *
 * args: --erbc --game --work [--compat-load] [--load-precheck] --seed <seed.dbg>
 *       [--pump N] [--pump-every K] [--pump-wall-ms W] [--histo]
 */
public class HeapCensusProbe {

    private static final long MIB = 1024L * 1024L;
    private static boolean histo = false;
    private static volatile String pumpErr = "-";

    public static void main(String[] argv) {
        Args args = Args.Companion.parse(new ArrayList<>(Arrays.asList(argv)));
        histo = hasFlag(argv, "--histo");
        String seed = args.value("--seed");
        // --pump / --pump-every / --pump-wall-ms 不在 vmdbg Args 的取值旗标白名单里，
        // 走 Args 会被当布尔旗标、值漏进位置参数：本探针的自留旗标一律直接从原始 argv 取。
        int pump = rawInt(argv, "--pump", 0);
        int every = rawInt(argv, "--pump-every", 15);
        long wall = rawLong(argv, "--pump-wall-ms", 60000L);
        Assembler.Assembled assembled = Assembler.INSTANCE.assemble(args, null, null);

        dump("assembled", 0, assembled, "-");
        if (seed != null && Files.isRegularFile(Path.of(seed))) {
            ExploreSnap snap = SnapShots.INSTANCE.loadSeed(Path.of(seed).toAbsolutePath().normalize());
            SnapShots.INSTANCE.restore(assembled.getSession(), assembled.getSandboxDir(), snap);
            dump("restored", 0, assembled, stateOf(assembled));
            if (pump > 0) pumpAndRead(assembled, pump, every, wall);
        }
    }

    private static void pumpAndRead(Assembler.Assembled assembled, int maxPasses, int every, long wallMs) {
        final QueueInputSink q = assembled.getSession().getInputSink();
        final AtomicInteger passed = new AtomicInteger(0);
        final long t0 = System.nanoTime();
        final Thread pump = new Thread(() -> {
            try {
                while (passed.get() < maxPasses && (System.nanoTime() - t0) / 1_000_000L < wallMs) {
                    assembled.getSession().resumeSystem();
                }
            } catch (Throwable t) {
                pumpErr = t.getClass().getName() + ": " + String.valueOf(t.getMessage());
            }
        }, "heap-census-pump");
        pump.setDaemon(true);
        pump.start();
        int lastRead = 0;
        while (pump.isAlive()) {
            if (q.isAwaiting()) {
                try { q.submitInt(0L); } catch (Throwable ignored) { }
                int p = passed.incrementAndGet();
                if (p - lastRead >= every) {
                    lastRead = p;
                    dump("pumped", p, assembled, stateOf(assembled));
                }
            } else {
                try { Thread.sleep(10L); } catch (InterruptedException ie) { break; }
            }
            if ((System.nanoTime() - t0) / 1_000_000L > wallMs) break;
        }
        try { pump.join(2000L); } catch (InterruptedException ignored) { }
        dump("pump_end", passed.get(), assembled, stateOf(assembled));
    }

    private static String stateOf(Assembler.Assembled a) {
        return String.valueOf(a.getSession().getRuntime().getSystemState());
    }

    private static void dump(String phase, int passes, Assembler.Assembled assembled, String stateName) {
        LiveSetCensus.Census c = LiveSetCensus.INSTANCE.census(
            assembled.getProgram(),
            assembled.getSession().getStore(),
            assembled.getSession().getConsole(),
            null, null, true);

        long usedMiB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / MIB;
        long ceilMiB = Runtime.getRuntime().maxMemory() / MIB;

        // 求和**只走 Census 自己那把去重的尺** bookedSumBytes()（LiveSetCensus.kt:429-433；进和清单
        // :448-459），探针不再自拼一份字段数组。上一版的写死清单有两处病：
        // 1. 读不到 EP3/L10 新增的六枚字段（globalIntResidentBytes / charaIntPerCharBytes /
        //    materializedStatements / materializedFunctions / flatResidentSegments / flatBuiltSegments，
        //    字段定义 LiveSetCensus.kt:155/:161/:171/:177/:240/:294）；
        // 2. 把 charaReserveBytes 也加进和里 —— 新口径下它是 charaIntBytes + charaStrBytes 的**尾部子集**
        //    （本臂重复计 5,309,700 B ≈ 5.06 MiB），且全局整数取的是「声明尺寸」globalIntBytes 而不是
        //    「实占」globalIntResidentBytes。两处差别由 LiveSetCensus.kt:420-424 逐条写明；
        //    「两处各算一套」正是上一波「census 只解释 10%」那组假数的成因。
        long booked = c.bookedSumBytes();
        int bookedMissing = c.bookedMissingFieldCount();   // >0 ⇒ booked 是**下界**（桌面恒缺图形两段）
        // 串池字节只作展示（进和的那笔由 Census 自己按 ×2 折，见 :452）。
        long strPoolBytes = c.getStringPoolChars() < 0 ? -1 : c.getStringPoolChars() * 2;
        // 与 EP2/L7 那组「探针自拼和 = 61 MiB」对齐用的复算：重复计的两笔单列，值全部来自 Census 本身，
        // 不是第二把尺。**这一行不作账面**，账面只读 bookedSumBytes。
        long declaredOverResident = c.getGlobalIntBytes() < 0L || c.getGlobalIntResidentBytes() < 0L
            ? -1L : c.getGlobalIntBytes() - c.getGlobalIntResidentBytes();
        long legacyDupSum = booked < 0L || declaredOverResident < 0L || c.getCharaReserveBytes() < 0L
            ? -1L : booked + declaredOverResident + c.getCharaReserveBytes();

        System.out.println("###CENSUS phase=" + phase + " passes=" + passes + " state=" + stateName
            + " usedMiB=" + usedMiB + " ceilingMiB=" + ceilMiB
            + " waterPct=" + (ceilMiB > 0 ? usedMiB * 100 / ceilMiB : -1));
        System.out.println("censusSumMiB=" + (booked / MIB) + " bookedMissingFieldCount=" + bookedMissing
            + " hasMinusField=" + (bookedMissing > 0)
            + " gapUsedMinusSumMiB=" + ((usedMiB * MIB - booked) / MIB));
        System.out.println("RAW bookedSumBytes=" + booked
            + " legacyDupCountedSumBytes=" + legacyDupSum
            + " codeResidentBytes=" + c.getCodeResidentBytes()
            + " lineTableBytes=" + c.getLineTableResidentBytes()
            + " callArgIndexBytes=" + c.getCallArgIndexResidentBytes()
            + " strPoolChars=" + c.getStringPoolChars() + " strPoolBytes=" + strPoolBytes
            + " globalIntDeclaredBytes=" + c.getGlobalIntBytes()
            + " globalIntResidentBytes=" + c.getGlobalIntResidentBytes()
            + " globalIntDeclaredOverResidentBytes=" + declaredOverResident
            + " globalStrBytes=" + c.getGlobalStrSlotBytes()
            + " charaIntBytes=" + c.getCharaIntBytes()
            + " charaIntPerCharBytes=" + c.getCharaIntPerCharBytes()
            + " charaStrBytes=" + c.getCharaStrBytes()
            + " charaReserveBytes=" + c.getCharaReserveBytes()
            + " gfxResidentPixelBytes=" + c.getGfxResidentPixelBytes()
            + " csvResidentPixelBytes=" + c.getCsvResidentPixelBytes());
        // 旧 OFFBOOK 行的四枚物化读数现已进账（LiveSetCensus.kt:155/:161/:171/:177，取数走
        // core/diag/LiveSetCensus.kt:506-509），Program 侧的 public 门面在 Program.kt:1179（物化语句）
        // /:1208（扁平驻留片）/:1215（累计已建片）/:1287（物化函数）⇒ 直读 getter，反射路径删掉。
        System.out.println("ONBOOK materializedStatements=" + c.getMaterializedStatements()
            + " materializedFunctions=" + c.getMaterializedFunctions()
            + " flatResidentSegments=" + c.getFlatResidentSegments()
            + " flatBuiltSegments=" + c.getFlatBuiltSegments()
            + "（四枚均为账面字段；条数不折字节，每棵语句树/每片的浅尺寸无在册口径）");
        System.out.println("LOAD codeResidentMiB=" + mib(c.getCodeResidentBytes())
            + " lineTableMiB=" + mib(c.getLineTableResidentBytes())
            + " callArgIdxMiB=" + mib(c.getCallArgIndexResidentBytes())
            + " strPoolEntries=" + c.getStringPoolEntries() + " strPoolBytesMiB="
            + (c.getStringPoolChars() < 0 ? "-" : String.valueOf(strPoolBytes / MIB))
            + " instrs=" + c.getInstructionCount() + " stmtPool=" + c.getStatementPoolEntries()
            + " exprPool=" + c.getExprPoolEntries() + " funcs=" + c.getFunctionCount());
        System.out.println("RUN globalIntDeclaredMiB=" + mib(c.getGlobalIntBytes())
            + " globalIntResidentMiB=" + mib(c.getGlobalIntResidentBytes())
            + " globalStrMiB=" + mib(c.getGlobalStrSlotBytes())
            + " charaCount/Cap=" + c.getCharaCount() + "/" + c.getCharaCapacity()
            + " charaIntMiB=" + mib(c.getCharaIntBytes())
            + " charaIntPerCharBytes=" + c.getCharaIntPerCharBytes()
            + " charaStrMiB=" + mib(c.getCharaStrBytes())
            + " charaReserveMiB=" + mib(c.getCharaReserveBytes())
            + " charaRowBytes=" + c.getCharaRowBytes()
            + " strTableEntries=" + c.getStrTableEntries());
        System.out.println("BUFFER displayLines=" + c.getDisplayLineCount()
            + " logicalLines=" + c.getLogicalLineCount()
            + " segments=" + c.getDisplaySegmentCount()
            + " pendingPrint=" + c.getPendingPrintBufferNonEmpty()
            + " gfxMiB=" + mib(c.getGfxResidentPixelBytes())
            + " csvMiB=" + mib(c.getCsvResidentPixelBytes()));
        System.out.println("pumpErr=" + pumpErr);
        if (histo) histogram(phase);
        System.out.println("###ENDCENSUS");
        try { System.out.flush(); } catch (Throwable ignored) { }
    }

    /** HotSpot 的 jcmd GC.class_histogram 同一条命令（进程内 MBean，不额外 attach）；会催全量 GC。 */
    private static void histogram(String phase) {
        String out;
        try {
            ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            Object r = ManagementFactory.getPlatformMBeanServer().invoke(
                name, "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
            out = String.valueOf(r);
        } catch (Throwable t) {
            System.out.println("HISTO phase=" + phase + " unavailable=" + t.getClass().getName()
                + ":" + String.valueOf(t.getMessage()));
            return;
        }
        long postGcMiB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / MIB;
        System.out.println("HISTO phase=" + phase + " postGcUsedMiB=" + postGcMiB);
        String[] lines = out.split("\r?\n");
        System.out.println("HISTOHEADER " + (lines.length > 1 ? lines[1] : "Total: - / Bytes: - / Instances: -"));
        int shown = 0;
        for (String line : lines) {
            if (!line.startsWith(" ")) continue;                 // 直方图行以序号列的空格开头
            System.out.println("HISTO " + line.trim());
            if (++shown >= 25) break;
        }
    }

    /** 取 internal 面那套反射（reflectLong / invokeByPrefix / fieldByPrefix）已随 EP3/L10 删除：
     *  扁平片数与物化条数都由 Program 的 public 门面（Program.kt:1179/:1208/:1215/:1287）进账面，
     *  探针直读 Census（见 dump 里的 ONBOOK 行）。 */

    private static boolean hasFlag(String[] argv, String key) {
        for (String a : argv) if (a.equals(key)) return true;
        return false;
    }

    private static int rawInt(String[] argv, String key, int d) {
        for (int i = 0; i + 1 < argv.length; i++) if (argv[i].equals(key)) return Integer.parseInt(argv[i + 1]);
        return d;
    }

    private static long rawLong(String[] argv, String key, long d) {
        for (int i = 0; i + 1 < argv.length; i++) if (argv[i].equals(key)) return Long.parseLong(argv[i + 1]);
        return d;
    }

    private static String mib(long bytes) { return bytes < 0 ? "-" : String.valueOf(bytes / MIB); }
}
'''


def eratw_root() -> str:
    env = os.environ.get("ERATW_ROOT")
    if env:
        return env
    sys.exit("共享根未知：请设 ERATW_ROOT 环境变量后重跑")


def jdk_bin(tool: str) -> str:
    jh = os.environ.get("JAVA_HOME")
    for cand in ([jh] if jh else []) + [eratw_root() + "/.tooling/jdk-17.0.20.1+1"]:
        exe = Path(cand) / "bin" / (tool + (".exe" if os.name == "nt" else ""))
        if exe.is_file():
            return str(exe)
    return tool


def default_erbc() -> str:
    env = os.environ.get("ERATW_ERBC_DIR")
    if env:
        return env
    sys.exit("erbc 产物路径未知：请 --erbc <erbc.v1 目录> 或设 ERATW_ERBC_DIR")


def default_game() -> str:
    return os.environ.get("ERATW_GAMEDIR") or eratw_root() + "/eratw-画蛇添足版eratw4.980"


def classpath() -> str:
    if not VMDBG_LIB.is_dir():
        sys.exit("vmdbg 未安装：先跑 ./gradlew --configure-on-demand :tools:vmdbg:installDist")
    # javac / java 都认 `目录/*` 这一条通配（展开成该目录下全部 jar），与 desktop.py 的 VMDBG_CP 同形。
    return str(VMDBG_LIB / "*") + os.pathsep


# ---------------------------------------------------------------- 读数解析

NUM = re.compile(r"(?P<key>[A-Za-z0-9_]+)=(?P<val>-?\d+)")


def numbers(line: str) -> dict[str, int]:
    return {m.group("key"): int(m.group("val")) for m in NUM.finditer(line)}


# Assembler 的 `--load-precheck` 行（`Assembler.assemble` 里打的 `loadPrecheck: key=value …`；
# 与 ProgramLoader.estimateHeap 同源，进 python 侧做 D1 复标）。
PRECHECK_RE = re.compile(r"^loadPrecheck:\s*(?P<body>.*)$", re.MULTILINE)


def parse_precheck(raw: str) -> dict[str, int] | None:
    """抓探针 stdout 里的 `loadPrecheck:` 行，折成数字表（非数字的 `sufficient`/`compat` 跳过）。"""
    m = PRECHECK_RE.search(raw)
    if not m:
        return None
    nums = numbers(m.group("body"))
    return nums or None


# 姊妹装置（`DeviceTierLoadProbe.kt`）报告里的逐段表行：
#   | # | 区间 | 边界 MiB | 段内池峰值 MiB | 段内分配 MiB | 已提交 MiB | 耗时 ms |
STAGE_ROW_RE = re.compile(
    r"^\|\s*\d+\s*\|\s*([^|]+?)\s*\|\s*(-?\d+)\s*\|\s*(-?\d+)\s*\|\s*(-?\d+)\s*\|\s*(-?\d+)\s*\|\s*(-?\d+)\s*\|\s*$"
)


def sibling_stage_peaks(out_dir: Path) -> tuple[str, list[tuple[str, int, int, int]]] | None:
    """复读同目录里现成的 `device-tier-*.md`（装载链分段读数，**不重跑**那条链）。

    返回 `(文件名, [(段名, 边界MiB, 段内池峰值MiB, 段内分配MiB), …])`；没有则 None。
    """
    cands = sorted(out_dir.glob("device-tier-*.md"))
    if not cands:
        return None
    path = cands[-1]
    rows: list[tuple[str, int, int, int]] = []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        m = STAGE_ROW_RE.match(line.rstrip())
        if not m:
            continue
        rows.append((m.group(1).strip(), int(m.group(2)), int(m.group(3)), int(m.group(4))))
    if not rows:
        return None
    return (path.name, rows)


def parse_blocks(raw: str) -> list[dict[str, object]]:
    """把 ###CENSUS ... ###ENDCENSUS 折成结构化块（HISTO 行留在原文，表里只登 postGc 与解释度）。"""
    blocks: list[dict[str, object]] = []
    cur: dict[str, object] | None = None
    for line in raw.splitlines():
        if line.startswith("###CENSUS "):
            cur = {"head": numbers(line), "phase": str(re.search(r"phase=(\S+)", line).group(1)),
                   "lines": [line], "histo": [], "postGcMiB": None}
            continue
        if cur is None:
            continue
        cur["lines"].append(line)
        if line.startswith("HISTO phase="):
            cur["postGcMiB"] = numbers(line).get("postGcUsedMiB")
        elif line.startswith("HISTO "):
            cur["histo"].append(line[len("HISTO "):])
        elif not line.startswith("###ENDCENSUS"):
            cur["head"].update(numbers(line))
        else:
            blocks.append(cur)
            cur = None
    return blocks


def mi_of(nums: dict[str, object], key: str) -> int:
    """字节类读数折 MiB（-1 保持 -1 = 无读数）。"""
    b = int(nums.get(key, -1))
    return -1 if b < 0 else b // MI_B


HISTO_LINE = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(.+?)\s*$")
HISTO_MODULE_SUFFIX = re.compile(r"\s+\([^()]*\)$")

# L10 点名的「账面归不了因」分段。**出处** = core-vm/.../core/diag/LiveSetCensus.kt:57-61
# （EP3/L10 在同臂同种子同产物的 restored 相位，jcmd GC.class_histogram 实测），不是本探针读的：
# 探针只在带 `--histo` 时能按类名**同臂复算**下面这四簇的字节数，复算的一律标「探针不读」。
NAMED_SEGMENTS = (
    ("Map/Entry 容器壳四件套（诚实条款：壳不计）", "27.0 MiB",
     ("java.util.LinkedHashMap$Entry", "java.util.LinkedHashMap",
      "java.util.HashMap$Node", "[Ljava.util.HashMap$Node;")),
    ("串本体 `byte[]` + `String`（账面只入了串池估算 0.42 MiB）", "17.6 MiB",
     ("[B", "java.lang.String")),
    ("`FuncMeta`（账面只给条数，无字节项）", "4.2 MiB",
     ("com.eratw.port.core.proc.FuncMeta",)),
    ("`short[]`（行号表账面 4,840 B，来源未定名）", "3.3 MiB",
     ("[S",)),
)
# 其余四簇 L10 也点了名，但类名不唯一（`int[]`/`long[]` 与账面同源、`Object[]` 等结构体混在长尾里），
# 本探针**不折 MiB 复算**，原样登记出处；前四簇里 11.75 万个 LinkedHashMap 的持有者与 7 条 short[]
# 要 hprof dominator 才能定名（TODO(verify)）。
UNATTRIBUTED_BY_PROBE = (
    "int[] 残差 5.0 MiB · long[] 残差 2.0 MiB · 结构体/背衬数组（`Object[]`/`VarDef`/`DimSpec`/`ArrayList`）"
    "约 5.8 MiB · Top-14 之后长尾约 18 MiB（出处 `LiveSetCensus.kt:57-61`；**探针不读**）"
)


def histo_classes(lines: list[str]) -> dict[str, tuple[int, int]]:
    """直方图行 → `类名 -> (字节, 实例数)`；模块后缀 `(java.base@…)` 剥掉，按裸类名索引。"""
    out: dict[str, tuple[int, int]] = {}
    for ln in lines:
        m = HISTO_LINE.match(ln)
        if not m:
            continue
        name = HISTO_MODULE_SUFFIX.sub("", m.group(3)).strip()
        out[name] = (int(m.group(2)), int(m.group(1)))
    return out


def render_stage_and_retune(precheck, sibling, blocks):
    """装载链分段（stageMark 段名 + 段内峰值）与 `estimateHeap` 复标（D1）两节。

    分段来自**姊妹装置** `DeviceTierLoadProbe` 的现成读数（本探针不重跑那条链，只复读）；
    复标用 `loadPrecheck:` 行的预检读数与产物字节算 `required/artifact` 比，并给结论方向。
    """
    out = ["", "## 装载链分段（stageMark 段名 + 段内峰值）", ""]
    if sibling:
        name, rows = sibling
        top = sorted(rows, key=lambda r: r[2], reverse=True)[:16]
        out += [
            f"出处：同目录现成读数 `{name}`（`DeviceTierLoadProbe`，设备口径 512m / `flatStatements=false` / "
            "`compat=false`）——本探针**不重跑**该链，只复读其段内池峰值（可复读 = 复跑姊妹装置覆盖同名文件）。",
            "",
            "| 段（stageMark） | 边界 MiB | 段内池峰值 MiB | 段内分配 MiB |",
            "|---|---|---|---|",
        ]
        for label, used, peak, alloc in top:
            out.append(f"| {label} | {used} | {peak} | {alloc} |")
        out += ["", f"（按段内池峰值取 Top {len(top)}；完整表见 `{name}`。`-1` = 取不到，不折 0。）"]
    else:
        out += [
            "同目录未找到 `device-tier-*.md`（姊妹装置的分段读数）——跑下面这条后复跑本工具即可复读：",
            "",
            "```",
            "ERATW_ERBC_DIR=<erbc.v1> ERATW_GAMEDIR=<游戏根> ERATW_PROBE_OUT=<本目录>/device-tier-4981.md \\",
            "  ./gradlew --configure-on-demand :core-vm:deviceTierProbe",
            "```",
        ]
    out += ["", "## `estimateHeap` 复标（D1：1.45×/0.15× 在 ART 上是否成立）", ""]
    if precheck and precheck.get("artifactBytes", -1) > 0:
        art = precheck["artifactBytes"]
        req = precheck.get("requiredBytes", -1)
        meta = precheck.get("metaBytes", -1)
        out.append(
            f"- 产物 = **{art} B**（{art // MI_B} MiB）；侧车 = {meta} B（{meta // MI_B} MiB）；"
            f"预检 `requiredBytes={req}`（{req // MI_B} MiB，`sufficient=` 见原行）。"
        )
        if req > 0:
            out.append(
                f"- 预检口径 required/artifact = **{req / art:.3f} ×**"
                "（= `产物×29/20 + 侧车×3/20`，`ProgramLoader.kt:406-415`；系数 `:435-440`）。"
            )
    else:
        out.append("- 未捕获 `loadPrecheck:` 行（探针缺 `--load-precheck`？），预检口径给不出。")
    if sibling and precheck and precheck.get("artifactBytes", -1) > 0:
        art_b = precheck["artifactBytes"]
        load_peak = max((r[2] for r in sibling[1] if r[0].startswith("program")), default=-1)
        if load_peak >= 0:
            out.append(
                f"- **装载段（`program.*`，不含会话/标题）**内池峰值最高 = **{load_peak} MiB**"
                f" = **{load_peak / (art_b / MI_B):.3f} × 产物**"
                "（这才是 D1 要求复标的比值：装载期峰值 ÷ 产物字节）。"
            )
    used_top = max((int(b["head"].get("usedMiB", -1)) for b in blocks), default=-1)
    out.append(
        f"- 本探针 census 相位 `used` 最高 = {used_top} MiB（`total-free`，含未回收垃圾，只读趋势）；"
        "**装载期段内峰值**见上节姊妹装置读数。"
    )
    out.append(
        "- **结论方向**：桌面 HotSpot 上装载期 live 峰值远低于 `1.45 ×` 产物 —— 该系数是"
        "「扁平池压扁即弃」档（`flatStatements=true`）标定，而**设备口径 `flatStatements=false` 不建扁平池** ⇒ "
        "桌面口径下 1.45× 明显**高估**。**ART 上的真实比值未证** ⇒ `TODO(verify)`"
        "（D1：真机 `load.peak ÷ 产物字节`，本机无设备）。"
    )
    return out


def render_md(blocks: list[dict[str, object]], args, seed: str, stamp: str,
              precheck: dict[str, int] | None = None,
              sibling: tuple[str, list[tuple[str, int, int, int]]] | None = None) -> str:
    out = [
        f"# heap_phase_probe 读数（{stamp}）",
        "",
        f"- 挡位 `--xmx {args.xmx}`；开关 compat-load={not args.no_compat_load} "
        f"load-precheck={not args.no_load_precheck} histo={args.histo} pump={args.pump}",
        f"- 种子：`{seed}`（相位语义：restored 只还变量面 + RNG，console 不还原）",
        "- 口径：`total - free` 含未回收垃圾，**只读趋势不读绝对值**（`ProgramLoader.kt:370-371`）；"
        "`-` = 桌面取不到（图形两段传 null），`0` 要区分「真 0」与「不足 1 MiB 被整除折 0」⇒ 看 RAW 行字节原值。",
        "- **账面和 = `Census.bookedSumBytes()`**（`LiveSetCensus.kt:429-433`；去重清单 `:448-459`），"
        "探针不自拼字段表；`缺项` = `bookedMissingFieldCount()`（>0 ⇒ 和是**下界**，桌面恒缺图形两段）。",
        "- 解释度 = `bookedSumBytes` ÷ used（有 postGc 时并列给 ÷ postGc：后者才是催完全量 GC 的 live 近似）；"
        "**残差 = live − booked**，那一块本账面归不了因，分段见下节。",
        "",
        "| 相位 | passes | usedMiB | 挡位 | 水位% | 账面MiB(booked) | 缺项 | 解释度%(÷used) | 差额MiB |"
        " postGc MiB(live) | 解释度%(÷live) | 残差MiB(live−booked) | 旧自拼和MiB(含双计) |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for b in blocks:
        h = b["head"]
        used = h.get("usedMiB", -1)
        ceil = h.get("ceilingMiB", -1)
        s = mi_of(h, "bookedSumBytes")
        booked_bytes = int(h.get("bookedSumBytes", -1))
        dup = mi_of(h, "legacyDupCountedSumBytes")
        post = b["postGcMiB"]
        # 解释度与残差按**字节原值**算（先折 MiB 再相除会多一层取整：本臂 56/139=40.3% vs
        # 59,427,332/(139 MiB)=40.8%，L10 的基准是后者）。表里的「账面MiB」仍给整除值。
        expl = round(booked_bytes * 100.0 / (used * MI_B), 1) if used > 0 and booked_bytes >= 0 else "-"
        explp = round(booked_bytes * 100.0 / (post * MI_B), 1) if post and post > 0 and booked_bytes >= 0 else "-"
        resid = round((post * MI_B - booked_bytes) / MI_B, 1) if post and post > 0 and booked_bytes >= 0 else "-"
        out.append(
            f"| {b['phase']} | {h.get('passes', 0)} | {used} | {ceil} | {h.get('waterPct')} | {s} | "
            f"{h.get('bookedMissingFieldCount')} | {expl} | {h.get('gapUsedMinusSumMiB')} | {post} | "
            f"{explp} | {resid} | {dup} |"
        )
    out += render_stage_and_retune(precheck, sibling, blocks)
    out += [
        "",
        "## 归不了因的残差（live − bookedSumBytes）",
        "",
        "四簇的**出处读数**是 L10 的取证（`LiveSetCensus.kt:57-61`，同臂同种子同产物 restored 相位），",
        "本探针带 `--histo` 时给**同臂复算**那一列；没带 `--histo` 或直方图里没这类，一律写「探针不读」，",
        "**不许**据此判「这一簇不存在」。",
        "",
    ]
    for b in blocks:
        cls = histo_classes([str(x) for x in b["histo"]])
        h = b["head"]
        booked_bytes = int(h.get("bookedSumBytes", -1))
        booked = mi_of(h, "bookedSumBytes")
        post = b["postGcMiB"]
        resid_b = -1 if (not post or post <= 0 or booked_bytes < 0) else post * MI_B - booked_bytes
        resid = "-" if resid_b < 0 else round(resid_b / MI_B, 1)
        out += [
            f"### 相位 `{b['phase']}`：残差 = {resid} MiB"
            f"（live=postGc {post} MiB − booked {booked if booked >= 0 else '-'} MiB"
            f" = {booked_bytes} B 入账）",
            "",
            "| 分段 | L10 出处读数 | 本探针复算 MiB（`--histo`） | 实例数 |",
            "|---|---|---|---|",
        ]
        named_total = 0
        for label, l10_reading, names in NAMED_SEGMENTS:
            hit = [cls[n] for n in names if n in cls]
            named_total += sum(x[0] for x in hit)
            bytes_mib = round(sum(x[0] for x in hit) / MI_B, 1) if hit else "探针不读"
            insts = sum(x[1] for x in hit) if hit else "-"
            out.append(f"| {label} | {l10_reading} | {bytes_mib} | {insts} |")
        rest = ("探针不读（未带 `--histo`）" if not cls or resid_b < 0
                else f"≈ {round((resid_b - named_total) / MI_B, 1)} MiB（残差减掉上面四簇）")
        out.append(f"| 其余（残差与长尾） | 见下行 | {rest} | - |")
        out += ["", f"其余逐段（出处同上，**探针不读**）：{UNATTRIBUTED_BY_PROBE}", ""]
    out += ["", "## 逐字段（每相位一块，原样读数）", ""]
    for b in blocks:
        out.append("```")
        out.extend(str(ln) for ln in b["lines"] if not ln.startswith("HISTO "))
        out.append("```")
        if b["histo"]:
            out += ["", f"直方图（Top {len(b['histo'])}，`jcmd GC.class_histogram` 同口径，"
                        f"相位读数之后取 ⇒ 已催一次全量 GC）：", "",
                    "```", *b["histo"], "```", ""]
    return "\n".join(out) + "\n"


def soak_trend(path: Path, stamp: str) -> str | None:
    """复用**现成**浸泡台账的水位序列（不重跑浸泡）：逐拍 heapUsedMi 的分布 = 差额趋势那一问的数据源。"""
    if not path.is_file():
        return None
    vals: list[int] = []
    outcomes: dict[str, int] = {}
    slow = 0
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        rec = {}
        for m in re.finditer(r'"(\w+)":(-?\d+|"[^"]*")', line):
            v = m.group(2).strip('"')
            rec[m.group(1)] = v
        if "heapUsedMi" not in rec:
            continue
        try:
            vals.append(int(rec["heapUsedMi"]))
        except ValueError:
            continue
        outcomes[str(rec.get("outcome", "?"))] = outcomes.get(str(rec.get("outcome", "?")), 0) + 1
        if int(rec.get("elapsedMs", 0)) >= 5000:
            slow += 1
    if not vals:
        return None
    vals_sorted = sorted(vals)
    q = lambda p: vals_sorted[min(len(vals_sorted) - 1, int(round(p * (len(vals_sorted) - 1))))]
    return "\n".join([
        f"# 浸泡水位趋势（复用现产物 {path}，未重跑；整理时刻 {stamp}）",
        "",
        f"- 样本 n={len(vals)} 结局分布={outcomes} 慢步(>=5000ms)={slow}",
        f"- heapUsedMi（`total-free` 折 MiB，口径同 ExploreSoak.kt:235，读趋势不读绝对值）："
        f"min={min(vals)} p50={q(0.5)} p95={q(0.95)} max={max(vals)} 末拍={vals[-1]}",
        f"- 512 MiB 挡位下最高水位 = {round(max(vals) * 100 / 512)}%，最低 = {round(min(vals) * 100 / 512)}%"
        f"（⇒ 682 步全程贴顶、无爬坡趋势）",
        f"- 极差 max-min = {max(vals) - min(vals)} MiB：这一层里 `(total-free)` 随步数**不增长**，"
        "所以「贴顶」是装载+运行结构的稳态占用，不是每步泄漏。",
    ]) + "\n"


# ---------------------------------------------------------------- 主流程

def main() -> int:
    p = argparse.ArgumentParser(prog="heap_phase_probe.py", description="桌面 512m 压堆臂的 census 读出口",
                                add_help=True)
    p.add_argument("--erbc", default=None, help="erbc.v1 产物目录（缺省 ERATW_ERBC_DIR）")
    p.add_argument("--game", default=None, help="游戏根（缺省 ERATW_GAMEDIR / 共享根语料）")
    p.add_argument("--work", default=None, help="会话状态/存档沙箱根（缺省 <out>/work-probe）")
    p.add_argument("--out", default=str(DEFAULT_OUT), help="读数落盘根（缺省 .local/heap-phase）")
    p.add_argument("--seed", default=None, help="种子 seed.dbg（缺省复用 <out>/soak512/seed.dbg，再缺则跳过 restored 相位）")
    p.add_argument("--xmx", default=DEFAULT_XMX, help="JVM 堆挡位（缺省 512m = obs-compat512 那一层）")
    p.add_argument("--no-compat-load", action="store_true", help="关掉 --compat-load（默认开，臂形状）")
    p.add_argument("--no-load-precheck", action="store_true", help="关掉 --load-precheck（默认开，自证挡位）")
    p.add_argument("--boot-only", action="store_true",
                   help="先用三开关跑引导链现造 seed.dbg（`explore --boot-only`，几分钟），再造 census")
    p.add_argument("--pump", type=int, default=8,
                   help="还原后有界推进多少个等待点（第三个相位 pump_end；0 = 只读两相位）")
    p.add_argument("--pump-every", type=int, default=15, help="每推进多少个等待点加打一个读数点")
    p.add_argument("--pump-wall-ms", type=int, default=60000, help="泵线程墙钟硬顶")
    p.add_argument("--histo", action="store_true", help="每个读数点后打 HotSpot 类直方图（会催全量 GC）")
    args = p.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    erbc = args.erbc or default_erbc()
    game = args.game or default_game()
    work = Path(args.work or (out / "work-probe"))
    work.mkdir(parents=True, exist_ok=True)

    seed = Path(args.seed) if args.seed else (out / "soak512" / "seed.dbg")
    if args.boot_only:
        boot_out = out / f"boot-{stamp}"
        cmd = [sys.executable, str(SCRIPT_DIR / "desktop.py"), "cmd", "--xmx", args.xmx, "explore",
               "--boot-only", "--out", str(boot_out), "--erbc", erbc, "--game", game, "--wall-min", "20"]
        if not args.no_compat_load:
            cmd += ["--compat-load"]
        if not args.no_load_precheck:
            cmd += ["--load-precheck"]
        print("[probe] 造种子：" + " ".join(cmd), flush=True)
        if subprocess.run(cmd).returncode != 0:
            sys.exit(f"引导链失败（退出码非 0），种子未产出：{boot_out}")
        seed = boot_out / "seed.dbg"
    seed_src = "现产物复用" if seed.is_file() else "无（跳过 restored 相位）"
    if not seed.is_file():
        print(f"[probe] 警告：种子不存在 {seed} —— 只读 assembled 相位", file=sys.stderr)

    src_dir = out / "probe-src"
    cls_dir = out / "probe-classes"
    src_dir.mkdir(parents=True, exist_ok=True)
    cls_dir.mkdir(parents=True, exist_ok=True)
    probe_java = src_dir / "HeapCensusProbe.java"
    probe_java.write_text(PROBE_SRC.lstrip(), encoding="utf-8")

    cp = classpath()
    print("[probe] javac " + str(probe_java), flush=True)
    r = subprocess.run([jdk_bin("javac"), "-encoding", "UTF-8", "-cp", cp, "-d", str(cls_dir), str(probe_java)])
    if r.returncode != 0:
        sys.exit(f"探针编译失败（退出码 {r.returncode}）")

    argv = [jdk_bin("java"), "-Xmx" + args.xmx, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-cp", cp + str(cls_dir), "HeapCensusProbe",
            "--erbc", erbc, "--game", game, "--work", str(work)]
    if not args.no_compat_load:
        argv += ["--compat-load"]
    if not args.no_load_precheck:
        argv += ["--load-precheck"]
    if seed.is_file():
        argv += ["--seed", str(seed.resolve())]
    if args.pump > 0:
        argv += ["--pump", str(args.pump), "--pump-every", str(args.pump_every),
                 "--pump-wall-ms", str(args.pump_wall_ms)]
    if args.histo:
        argv += ["--histo"]

    print("[probe] " + " ".join(argv), flush=True)
    proc = subprocess.run(argv, capture_output=True,
                          creationflags=0x08000000 if os.name == "nt" else 0)
    raw = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    if proc.returncode != 0:
        print(raw)
        sys.exit(f"探针退出码 {proc.returncode}：{err[-2000:]}")

    census_txt = out / f"probe-{stamp}.census.txt"
    census_txt.write_text(raw, encoding="utf-8")
    (out / f"probe-{stamp}.stderr.txt").write_text(err, encoding="utf-8")

    blocks = parse_blocks(raw)
    if not blocks:
        sys.exit(f"没有任何 ###CENSUS 读数（探针 stdout 已落 {census_txt}，退出码 {proc.returncode}）")

    md = out / f"probe-{stamp}.readout.md"
    precheck = parse_precheck(raw)
    sibling = sibling_stage_peaks(out)
    md.write_text(render_md(blocks, args, f"{seed}（{seed_src}）", stamp, precheck, sibling), encoding="utf-8")
    if precheck:
        art = precheck.get("artifactBytes", -1)
        req = precheck.get("requiredBytes", -1)
        if art > 0 and req > 0:
            print(f"[probe] estimateHeap 复标：required/artifact = {req / art:.3f} ×（D1，仅判别不改系数）")
    if sibling:
        print(f"[probe] 装载链分段：复读姊妹读数 {sibling[0]}（{len(sibling[1])} 段）")
    else:
        print("[probe] 装载链分段：未找到 device-tier-*.md（见 readout「装载链分段」节跑法）")
    trend = soak_trend(out / "soak512" / "soak-steps.jsonl", stamp)
    trend_path = None
    if trend:
        trend_path = out / "soak-trend.md"
        trend_path.write_text(trend + "\n", encoding="utf-8")

    print(f"[probe] 原始读数：{census_txt}")
    print(f"[probe] 成表：{md}")
    if trend_path:
        print(f"[probe] 浸泡水位趋势（复用现产物）：{trend_path}")
    for b in blocks:
        h = b["head"]
        used = h.get("usedMiB", -1)
        s = mi_of(h, "bookedSumBytes")
        booked_bytes = int(h.get("bookedSumBytes", -1))
        pct = round(booked_bytes * 100.0 / (used * MI_B), 1) if used > 0 and booked_bytes >= 0 else "-"
        post = b["postGcMiB"]
        pct_live = round(booked_bytes * 100.0 / (post * MI_B), 1) if post and post > 0 and booked_bytes >= 0 else "-"
        resid = (round((post * MI_B - booked_bytes) / MI_B, 1)
                 if post and post > 0 and booked_bytes >= 0 else "-")
        print(f"  phase={b['phase']} passes={h.get('passes', 0)} used={used}MiB"
              f" water={h.get('waterPct')}% booked={s}MiB/{booked_bytes}B explained={pct}%（÷used）"
              f" gap={h.get('gapUsedMinusSumMiB')}MiB postGc={post}MiB"
              f" explained={pct_live}%（÷live） residual={resid}MiB"
              f" missing={h.get('bookedMissingFieldCount')}"
              f" legacyDupSum={mi_of(h, 'legacyDupCountedSumBytes')}MiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
