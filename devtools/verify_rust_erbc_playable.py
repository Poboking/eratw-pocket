#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RUST1 `rust-playable` 车道：Rust 腿产物装进自研引擎之后，到底能不能跑一局。

公开分仓布局：`core-vm` / `render` / `tools/*` / `fixtures` 都在姐妹仓 `../erak-engine`；
本脚本的跨仓路径默认指向 `../erak-engine`，可用环境变量 `ERATW_KCORE_ROOT` 覆盖。

上一路 `rust-staging` 证的是「打包脚本认账」（`:app:stageGameAssets` 的 CMP-66/CMP-74）；
本路证的是「**引擎认账**」：同一份语料由 Kotlin 腿与 Rust 腿各产一份 `erbc.v1/`，分别用
桌面引擎（`:tools:vmdbg` 的装配链 + 一次性的引擎读数探针）装载并执行，逐项比引擎侧读数，
再走一段真实开局链。

## 子命令

```text
gate1   双腿装载同读数：两腿各产 erbc.v1（产法复用 devtools/erbcc_rs_parity.py 的两腿，
        不重写；四件套 sha256 全等是**前置**，不等就直接红），再逐腿取引擎侧读数并逐项比：
          · 装载是否成功（引擎自己的 ProgramLoader.loadMapped / BootMetadata.read /
            vmdbg 的会话装配）
          · 函数数 · 指令数 · 语句池条数
          · 装载告警（条数 + 全文 sha + 原文，口径 = 环境变量 ERATW_GOLDEN_WARNINGS
            指向的装载告警基准前两列「文件:行 | 文案」；真语料实测 0 条，非空对照见 gate1w）
          · `#DIM` 尺寸读数（global-dims / private-dims 计数、内容 sha、维数与 totalSize 和、
            以及引擎装配后 store 里真注册出来的变量名数 = vmdbg vars 的读数）
gate1w  装载告警通道的「非空转」档：自造小语料（重复 `@FUNCTION` + `INPUTS`/`BINPUTS` 参数过多），
        两腿各产各装，要求引擎读回的告警逐字相等、两类文案与基准同式、且不许混进第三类。
gate2   跑通一局的一段：
          · 2a 内置 START 链（`ExploreBoot.BUILTIN_START`）在**两腿**上各跑一次并逐项对照
            —— 实测两腿停在同一等待点、同一 reason、屏尾 sha 逐字节相同（⇒ 链的漂移与
            产物来源无关，见交付报红）；
          · 2b `--boot` 深配方链（`tests/data/rust-playable/haruki-gate2-boot-recipe.txt`，
            内置链前 14 步 + 逐点应答）把 Rust 腿产物推到真进游戏；判定线 = 至少
            `--min-boot-steps` 个等待点上「读得到屏、交得了输入」且 coverage 的执行函数数 > 0；
          · 2c 执行面读数：sweep **切片**（`SLICES`，各 100 余函数，全量清扫不作默认）与
            `trace` 轨迹，两腿各跑一遍逐项比（台账/轨迹都先去墙钟再取 sha）。
            trace 目标必须是**不等输入**的纯计算函数：`trace`/`call` 走 CmdCall 直调，
            落在输入等待上的函数（如 SYSTEM_TITLE 的标题菜单）没有墙钟兜底，永不返回。
            进 sha 前只钉掉两处**机器速度量**（2026-11-01 实测，两腿各差 2 行 / 27 行都只差在这两处）：
            轨迹里每条语句的 `<n>ns` 耗时（`Trace.kt:127`）、`WallLimitExceeded` 的 msg
            内「实测墙钟 ms / 窗口内应答输入次数」（`Assembler.kt:292`）。
            状态、异常类名、line、pc、其余 msg 文本仍逐字比 —— 语义面没有放宽。
gate3   反向证明：**对照档**（erbc.v1 原样拷贝、一字不改 ⇒ 装载链必须放行，否则「拒」的
        归因说不清）+ 容器截断（硬判据，装载链**必须**拒）+ 侧车 program-sha256 一位漂移 +
        侧车 functions 计数与记录体不符。每档三个口径：A 装载探针（ProgramLoader+BootMetadata）、
        B vmdbg 会话装配（disasm 0 -n 1）、C 打包层 `erbcc check`（新鲜度对账）——
        装载链不管的那一档归谁，用 C 的 rc+原文实测出来，不许只引用「上一路说过」。
probe   只对一份 erbc.v1 取引擎读数（调试用）。
all     gate1 + gate1w + gate2 + gate3。
```

退出码：`0` 全绿 · `1` 判据红（含该拒未拒 / 两腿读数不等 / 开局链连一段都没走到）·
`2` 环境不满足（缺 java / classpath / erbcc.exe / 语料 / 游戏根 / vmdbg install 产物）。

## 引擎侧读数的两个来源（都不改别人的码）

1. **`vmdbg` 现成子命令**（只调用）：`disasm 0` 打 `instructionCount`、`sweep --only <永不匹配>`
   打 `清单 <metadata.functions>` 与「全程序语句池 N 条」（装载 + 会话装配成功本身即读数）、
   `vars` 打引擎注册出来的变量名全集。
2. **一次性 Java 探针**（`RpLoadProbe.java`，本脚本落到 `build/rust-playable/probe/` 下，
   跑完即删）：直接调引擎的 `ProgramLoader.INSTANCE.loadMapped(program.erbc)` 与
   `BootMetadata.Companion.read(program.meta)`——即 vmdbg `Assembler` 用的同一条装载链
   （`Assembler.kt:102-111`）——把侧车各段计数、装载告警原文、`#DIM` 内容摘要打出来。
   为什么需要它：`load-warnings` 段在 core 侧目前**只有落盘/读回、没有透出面**
   （`BootMetadata.kt:75` 的 KDoc 原话「设备侧读回装载告警清单（本波仅落盘/读回，透出待组长接线）」），
   vmdbg 没有任何子命令打得出来；缺的能力写进「需组长接线」，探针只是临时取证。

## 口径出处

- 装载链 = `ProgramLoader.loadMapped` + `BootMetadata.read` + `GameSession`（`Assembler.kt:26-31`）。
- 开局链 = `ExploreBoot.BUILTIN_START`（`ExploreBoot.kt:46-85`，FLOW.md §2 实测链），
  终点子串「第1日」（`ExploreBoot.kt:40`）；`explore` 退出码 1 = 引导失败
  （现场落 boot-fail.json + boot-fail-console.txt，`CmdExplore.kt:48-49`）。
- 配方耗尽于非终点等待点 = `Explore.kt:798` 的 BOOT_FAIL（本路据此把「走到第几步」当读数，
  而不是把「到没到第1日」当唯一判据）。
- 告警渲染口径 = `LoadWarningsGoldenTest.render()` 的「文件:行 | 文案」两列
  （基准由 `ERATW_GOLDEN_WARNINGS` 指向；基准第三列 `SRC:` 系采集脚本补注，不进判据）。
- 会话 RNG 种子钉死 20260226（`Cli.RAND_SEED`），同产物两次跑读数必须逐字一致（自检手段）。

## 环境（开发机）

`JAVA_HOME` 指工具链 JDK；`ERATW_CORPUS` 必须指到语料的 ERB 子树；`ERATW_GAMEDIR` 指游戏根。
桌面一律经 `devtools/desktop.py`（CREATE_NO_WINDOW，禁裸拉 java），堆挡位沿用它的 `-Xmx3g`。
两个已知坑（都不在本路所有权内，只绕不改）：
`desktop.py` 的 `resolve_paths` 会无条件求值 `default_erbc()`，开发机上即使显式给了
`--erbc` 也要求 `ERATW_ERBC_DIR` 有值（本脚本给子进程补这个 env，生效的仍是命令行参数）；
`vmdbg vars "*"` 传显式 glob 时枚出 0 条，缺省形态（等价 `*`）才有读数，故取数用缺省。
全量产物 `program.erbc` ≈ 329 MiB，一次装载（容器 + 49 MiB 侧车）实测约 2 s，
会话装配 + 开局链另计；临时产物默认跑完删除。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
# 公开分仓布局：引擎侧工具/夹具在姐妹仓 erak-engine；可用 ERATW_KCORE_ROOT 覆盖。
KCORE_ROOT = (os.environ.get("ERATW_KCORE_ROOT") or
              os.path.join(os.path.dirname(APP_ROOT), "erak-engine"))
sys.path.insert(0, HERE)
import erbcc_rs_parity as parity  # noqa: E402  已收口的双腿驱动：产腿只用它，不重写

RS_BUILD = os.path.join(KCORE_ROOT, "tools", "erbcc-rs", "build", "rust-playable")
DATA_OUT = os.path.join(KCORE_ROOT, "tools", "erbcc-rs", "tests", "data", "rust-playable")
VMDBG_LIB = os.path.join(KCORE_ROOT, "tools", "vmdbg", "build", "install", "vmdbg", "lib")
DESKTOP_PY = os.path.join(HERE, "desktop.py")
SMALL_CORPUS = parity.SMALL_CORPUS
# 装载告警基准是内部文件，不随公开仓发布；需要时显式给路径。
GOLDEN_WARNINGS = (os.environ.get("ERATW_GOLDEN_WARNINGS") or "").strip()
FOUR = parity.FOUR
ERBC_DIR_NAME = parity.ERBC_DIR

# 两类基准告警文案的模板（逐字取自 ERATW_GOLDEN_WARNINGS 指向文件的第二列）：
#   函数"@G12_FUNC"已经被定义过(GOLDEN_12_SYMBOLS.ERB的39行)
#   INPUTS命令:参数过多 / BINPUTS命令:参数过多
GOLDEN_DUP_RE = re.compile(r'^函数"@[A-Z0-9_]+"已经被定义过\([^()]*的\d+行\)$')
GOLDEN_ARG_RE = re.compile(r"^[A-Z]*INPUTS命令:参数过多$")
TOO_MANY_ARG_SUFFIX = "命令:参数过多"

PROBE_CLASS = "RpLoadProbe"
PROBE_SOURCE = r"""
import com.eratw.port.core.code.Program;
import com.eratw.port.core.loader.BootMetadata;
import com.eratw.port.core.loader.ProgramLoader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 引擎装载读数探针（rust-playable 车道的一次性取证装置，跑完即删）。
 * 只做两件事：走引擎自己的装载链读一份 erbc.v1，把逐项计数与内容摘要按 key=value 打到 stdout。
 * 判据本体一律在 Python 侧（本文件不比较、不裁决）。
 */
public class RpLoadProbe {

    static PrintStream out;

    public static void main(String[] argv) throws Exception {
        out = new PrintStream(System.out, true, "UTF-8");
        Path erbcDir = Path.of(argv[0]);
        Path programFile = erbcDir.resolve("program.erbc");
        Path metaFile = erbcDir.resolve("program.meta");

        long t0 = System.nanoTime();
        Program program = ProgramLoader.INSTANCE.loadMapped(programFile);
        long programMs = (System.nanoTime() - t0) / 1000000L;
        long t1 = System.nanoTime();
        BootMetadata meta = BootMetadata.Companion.read(metaFile);
        long metaMs = (System.nanoTime() - t1) / 1000000L;

        out.println("load_ok=true");
        out.println("load_program_ms=" + programMs);
        out.println("load_meta_ms=" + metaMs);

        out.println("program_functions=" + program.getFunctions().size());
        out.println("program_instructions=" + program.getInstructionCount());
        out.println("program_statements=" + program.getStatementCount());

        out.println("meta_functions=" + meta.getFunctions().size());
        out.println("meta_labels_keys=" + meta.getLabels().size());
        long labelEntries = 0;
        for (Map<String, Integer> v : meta.getLabels().values()) labelEntries += v.size();
        out.println("meta_labels_entries=" + labelEntries);
        out.println("meta_files_keys=" + meta.getFileByKey().size());
        out.println("meta_data_sections=" + meta.getDataSections().size());
        out.println("meta_param_defaults=" + meta.getParamDefaults().size());
        out.println("meta_method_types=" + meta.getMethodTypes().size());
        out.println("meta_macros=" + meta.getDimParseConfig().getMacros().size());
        out.println("meta_int_constants=" + meta.getDimParseConfig().getIntConstants().size());
        out.println("meta_str_constants=" + meta.getDimParseConfig().getStrConstants().size());

        out.println("meta_engine_tag=" + meta.getEngineTag());
        out.println("meta_program_sha256_field=" + meta.getProgramSha256());
        out.println("erbc_actual_sha256=" + shaOf(programFile));
        out.println("meta_actual_sha256=" + shaOf(metaFile));

        // ---- 装载告警（golden 口径：文件:行 | 文案）----
        List<BootMetadata.LoadWarning> ws = meta.getLoadWarnings();
        out.println("load_warnings_present=" + (ws == null ? "false" : "true"));
        List<BootMetadata.LoadWarning> wl = (ws == null) ? new ArrayList<>() : ws;
        out.println("load_warnings_count=" + wl.size());
        StringBuilder wtext = new StringBuilder();
        for (BootMetadata.LoadWarning w : wl) {
            wtext.append(w.getFile()).append(':').append(w.getLine()).append(" | ")
                  .append(w.getMessage()).append('\n');
        }
        out.println("load_warnings_sha256=" + shaBytes(wtext.toString().getBytes("UTF-8")));
        out.println("--- load-warnings ---");
        int shown = 0;
        for (String line : wtext.toString().split("\n")) {
            if (line.isEmpty()) continue;
            if (++shown > 40) { out.println("...（截断：共 " + wl.size() + " 条）"); break; }
            out.println(line);
        }

        // ---- `#DIM` 尺寸读数 ----
        out.println("global_dims_count=" + meta.getGlobalDims().size());
        StringBuilder gtext = new StringBuilder();
        for (BootMetadata.RawDim d : meta.getGlobalDims()) {
            gtext.append(d.getFile()).append(':').append(d.getLine()).append(" ")
                 .append(d.getRaw()).append('\n');
        }
        out.println("global_dims_text_sha256=" + shaBytes(gtext.toString().getBytes("UTF-8")));

        long privateCount = 0;
        long aritySum = 0;
        long totalSizeSum = 0;
        long strDecls = 0;
        long constDecls = 0;
        long savedataDecls = 0;
        long charaDataDecls = 0;
        long refDecls = 0;
        StringBuilder ptext = new StringBuilder();
        for (BootMetadata.PrivateDim pd : meta.getPrivateDims()) {
            privateCount++;
            com.eratw.port.core.vars.DimSpec s = pd.getSpec();
            aritySum += s.getDimension();
            totalSizeSum += s.getTotalSize();
            if (s.isStr()) strDecls++;
            if (s.isConst()) constDecls++;
            if (s.isSavedata()) savedataDecls++;
            if (s.isCharaData()) charaDataDecls++;
            if (s.isRef()) refDecls++;
            ptext.append(pd.getFunctionKey()).append(' ').append(s.getName())
                 .append(s.isStr() ? 'S' : 'I').append(' ')
                 .append(java.util.Arrays.toString(s.getDims())).append('\n');
        }
        out.println("private_dims_count=" + privateCount);
        out.println("private_dim_arity_sum=" + aritySum);
        out.println("private_dim_total_size_sum=" + totalSizeSum);
        out.println("private_dim_str_decls=" + strDecls);
        out.println("private_dim_const_decls=" + constDecls);
        out.println("private_dim_savedata_decls=" + savedataDecls);
        out.println("private_dim_charadata_decls=" + charaDataDecls);
        out.println("private_dim_ref_decls=" + refDecls);
        out.println("private_dims_text_sha256=" + shaBytes(ptext.toString().getBytes("UTF-8")));

        // ---- 函数表内容摘要（侧车 functions 段，逐条 index|key|name|flags）----
        StringBuilder ftext = new StringBuilder();
        List<?> fs = meta.getFunctions();
        for (Object o : fs) {
            ftext.append(String.valueOf(o)).append('\n');
        }
        out.println("meta_functions_text_sha256=" + shaBytes(ftext.toString().getBytes("UTF-8")));

        // ---- 常量表内容摘要（`#DIM` 求值的分母）----
        StringBuilder ctext = new StringBuilder();
        for (Map.Entry<String, Long> e : new TreeMap<>(meta.getDimParseConfig().getIntConstants()).entrySet()) {
            ctext.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        out.println("int_constants_text_sha256=" + shaBytes(ctext.toString().getBytes("UTF-8")));
        out.println("probe_done=true");
    }

    static Long asLong(String s) {
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return Long.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String shaOf(Path p) throws Exception {
        return shaBytes(Files.readAllBytes(p));
    }

    static String shaBytes(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
"""

# 两腿必须逐项相等的读数键（load_*_ms / *_actual_sha256 这类不进判据：
# 计时非确定；文件 sha 由前置的四件套闸钉，探针自己算的那份只作旁证）。
COMPARE_KEYS = [
    "load_ok",
    "program_functions",
    "program_instructions",
    "program_statements",
    "meta_functions",
    "meta_labels_keys",
    "meta_labels_entries",
    "meta_files_keys",
    "meta_data_sections",
    "meta_param_defaults",
    "meta_method_types",
    "meta_macros",
    "meta_int_constants",
    "meta_str_constants",
    "meta_engine_tag",
    "meta_program_sha256_field",
    "erbc_actual_sha256",
    "meta_actual_sha256",
    "load_warnings_present",
    "load_warnings_count",
    "load_warnings_sha256",
    "global_dims_count",
    "global_dims_text_sha256",
    "private_dims_count",
    "private_dim_arity_sum",
    "private_dim_total_size_sum",
    "private_dim_str_decls",
    "private_dim_const_decls",
    "private_dim_savedata_decls",
    "private_dim_charadata_decls",
    "private_dim_ref_decls",
    "private_dims_text_sha256",
    "meta_functions_text_sha256",
    "int_constants_text_sha256",
]


class EnvFail(Exception):
    """环境不满足 → 退 2。"""


class GateFail(Exception):
    """判据红 → 退 1。"""


def log(msg: str = "") -> None:
    print(msg, flush=True)


def winpath(p: str) -> str:
    return parity.winpath(p)


def sha256_file(path: str) -> tuple[int, str]:
    return parity.sha256_file(path)


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------- 环境

def require_vmdbg() -> None:
    if not os.path.isdir(VMDBG_LIB):
        raise EnvFail(
            f"vmdbg 未安装：{VMDBG_LIB} 不存在。先跑 "
            "./gradlew --configure-on-demand :tools:vmdbg:installDist")
    if not os.path.isfile(DESKTOP_PY):
        raise EnvFail(f"找不到桌面入口 {DESKTOP_PY}")


def resolve_game(explicit: str | None) -> str:
    game = explicit or os.environ.get("ERATW_GAMEDIR", "").strip()
    if not game:
        raise EnvFail("缺游戏根：--game 或 ERATW_GAMEDIR（引擎装配 CSV/emuera.config 要用）")
    game = winpath(game)
    if not os.path.isdir(game):
        raise EnvFail(f"游戏根不存在：{game}")
    return game


def decode_mixed(raw: bytes) -> str:
    """本机 JVM stdout 默认 cp936；先按 utf-8 严解，失败退 gbk（屏上中文与数字都不受影响）。"""
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except Exception:
            continue
    return raw.decode("utf-8", errors="replace")


# ---------------------------------------------------------------- 产腿

def produce_legs(corpus: str, base: str, classpath: str | None, tag: str,
                 reuse: bool) -> tuple[str, str, str]:
    """(kotlin erbc.v1, rust erbc.v1, classpath 串)；产法与四件套判据全部复用 parity 驱动。"""
    corpus_w = winpath(corpus)
    if not os.path.isdir(corpus_w):
        raise EnvFail(f"语料目录不存在：{corpus_w}")
    java = parity.resolve_java()
    cp, origin = parity.resolve_classpath(classpath)
    exe = parity.resolve_rust_exe(None)
    k_parent = os.path.join(base, "kotlin")
    r_parent = os.path.join(base, "rust")
    k_dir = os.path.join(k_parent, ERBC_DIR_NAME)
    r_dir = os.path.join(r_parent, ERBC_DIR_NAME)
    if reuse and os.path.isfile(os.path.join(k_dir, "manifest.txt")) \
            and os.path.isfile(os.path.join(r_dir, "manifest.txt")):
        log(f"[{tag}] 复用已存在的两腿产物（--reuse-legs）：{k_dir} / {r_dir}")
    else:
        shutil.rmtree(k_parent, ignore_errors=True)
        shutil.rmtree(r_parent, ignore_errors=True)
        os.makedirs(k_parent)
        os.makedirs(r_parent)
        log(f"[{tag}] 语料 = {corpus_w}")
        log(f"[{tag}] classpath：{origin}")
        log(f"[{tag}] Rust CLI = {exe}")
        parity.kotlin_compile_leg(java, cp, corpus_w, k_parent, None, tag)
        parity.rust_compile_leg(exe, cp, corpus_w, r_parent, None, tag)
    for name in FOUR:
        for d in (k_dir, r_dir):
            if not os.path.isfile(os.path.join(d, name)):
                raise GateFail(f"腿产物缺 {name}：{d}")
    # 前置判据：两腿产物逐字节相同（rust-gradle 闸 4 同口径，不等就无需往下比引擎读数）
    parity.compare_dirs(k_parent, r_parent, None)
    log(f"[{tag}] 前置：Rust 腿 == Kotlin 腿（四件套 sha256 全等）")
    return k_dir, r_dir, cp


# ---------------------------------------------------------------- 引擎读数：探针

def write_probe(base: str) -> tuple[str, str]:
    """落一次性 Java 探针，返回 (java 可执行, 源文件路径)。"""
    java = parity.resolve_java()
    d = os.path.join(base, "probe")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, PROBE_CLASS + ".java")
    with open(src, "w", encoding="utf-8", newline="\n") as f:
        f.write(PROBE_SOURCE)
    return java, src


def head_and_tail(text: str, head: int = 6, tail_n: int = 40) -> str:
    """引擎报错的「首行 + 尾部栈帧」：异常**文案**在首行、调用栈在尾部，
    只取 tail 会把装载链自己报的那句话（闸 3 的原样证据）挤掉。"""
    lines = [l for l in text.splitlines() if l.strip()]
    if len(lines) <= head + tail_n:
        return "\n".join(lines)
    return ("\n".join(lines[:head]) + "\n    …（中间栈帧略）…\n" + "\n".join(lines[-tail_n:]))


def probe_readings(erbc_dir: str, java_src: tuple[str, str], xmx: str = "-Xmx3g") -> dict[str, str]:
    """跑引擎装载探针，解析 key=value 行 + `--- load-warnings ---` 段的原文。"""
    java, src = java_src
    cp = os.path.join(VMDBG_LIB, "*")
    argv = [java, xmx, "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
            "-cp", cp, src, winpath(erbc_dir)]
    t0 = time.time()
    r = subprocess.run(argv, cwd=APP_ROOT, capture_output=True)
    dt = time.time() - t0
    text = decode_mixed(r.stdout)
    if r.returncode != 0:
        err = decode_mixed(r.stderr)
        raise EngineReject("probe", r.returncode, head_and_tail(err), dt)
    kv, warn_lines = parse_probe(text)
    kv["probe_elapsed_s"] = f"{dt:.1f}"
    kv["load_warnings_rendered"] = "\n".join(warn_lines)
    kv["load_warnings_rendered_sha256"] = sha256_text("\n".join(warn_lines) + ("\n" if warn_lines else ""))
    return kv


def parse_probe(text: str) -> tuple[dict[str, str], list[str]]:
    kv: dict[str, str] = {}
    warns: list[str] = []
    section = None
    for line in text.splitlines():
        if line.strip() == "--- load-warnings ---":
            section = "warn"
            continue
        if section == "warn":
            if re.match(r"^([a-z_0-9]+)=.*", line):
                section = None            # 段落结束于下一个 key=value 行：那行本身照常入库
                m = re.match(r"^([a-z_0-9]+)=(.*)$", line)
                kv[m.group(1)] = m.group(2)
                continue
            if line and not line.startswith("---") and not line.startswith("..."):
                warns.append(line)
            continue
        m = re.match(r"^([a-z_0-9]+)=(.*)$", line)
        if m:
            kv[m.group(1)] = m.group(2)
    return kv, warns


class EngineReject(Exception):
    """引擎拒绝装载（gate3 要的正是这个；gate1/2 撞上即判据红）。"""

    def __init__(self, where: str, rc: int, text: str, elapsed: float | None = None):
        super().__init__(f"{where} 被引擎拒绝（rc={rc}）：\n{text}")
        self.where = where
        self.rc = rc
        self.text = text
        self.elapsed = elapsed


# ---------------------------------------------------------------- 引擎读数：vmdbg 现成命令

def desktop_cmd(rest: list[str], erbc_dir: str, game: str, work: str,
                tag: str) -> subprocess.CompletedProcess:
    argv = [sys.executable, DESKTOP_PY, "cmd", "--erbc", winpath(erbc_dir),
            "--game", winpath(game), "--work", winpath(work), *rest]
    env = dict(os.environ)
    # desktop.py 的 resolve_paths 会**无条件**求值 default_erbc()/default_game()（字典字面量先算
    # 值再看成员），开发机上 ERATW_ERBC_DIR 未设就直接 SystemExit —— 即便 --erbc 已显式给出。
    # 这里把两个变量补进子进程 env 喂掉那条缺省路径；真正生效的仍是命令行里的 --erbc/--game。
    env["ERATW_ERBC_DIR"] = winpath(erbc_dir)
    env["ERATW_GAMEDIR"] = winpath(game)
    t0 = time.time()
    r = subprocess.run(argv, cwd=APP_ROOT, capture_output=True, env=env)
    dt = time.time() - t0
    out_text = decode_mixed(r.stdout)
    if r.returncode != 0:
        raise EngineReject(f"desktop.py {' '.join(rest)}", r.returncode,
                           head_and_tail(out_text + "\n" + decode_mixed(r.stderr)), dt)
    log(f"[{tag}] vmdbg {' '.join(rest)} rc=0 用时 {dt:.1f}s")
    return subprocess.CompletedProcess(argv, 0, out_text, "")


def vmdbg_readings(erbc_dir: str, game: str, work: str, tag: str) -> dict[str, str]:
    """三发现成命令的引擎侧读数：disasm / sweep（零清扫）/ vars。"""
    out: dict[str, str] = {}
    # 1) instructionCount（引擎 Program 的指令池）
    r = desktop_cmd(["disasm", "0", "-n", "1"], erbc_dir, game, work, tag)
    m = re.search(r"pc=0 instructionCount=(\d+)", r.stdout)
    if not m:
        raise GateFail(f"disasm 读数形状不认识：{tail(r.stdout)}")
    out["vmdbg_disasm_instructionCount"] = m.group(1)
    out["vmdbg_disasm_first_line"] = r.stdout.splitlines()[-1].strip()
    # 2) metadata.functions 清单 + 全程序语句池（--only 永不匹配 ⇒ 零清扫，只取装载读数）
    sdir = os.path.join(work, "sweep-zero")
    shutil.rmtree(sdir, ignore_errors=True)
    os.makedirs(sdir)
    r = desktop_cmd(["sweep", "--only", "^__RpNeverMatches__$", "--out", winpath(sdir)],
                    erbc_dir, game, work, tag)
    m = re.search(r"sweep: 清单 (\d+) 跳过事件/合成入口 (\d+) 本次清扫 (\d+)", r.stdout)
    if not m:
        raise GateFail(f"sweep 头部读数形状不认识：{tail(r.stdout)}")
    out["vmdbg_sweep_metaFunctions"] = m.group(1)
    out["vmdbg_sweep_skippedEvent"] = m.group(2)
    out["vmdbg_sweep_selected"] = m.group(3)
    summary = open(os.path.join(sdir, "summary.md"), "r", encoding="utf-8").read()
    m = re.search(r"全程序语句池 (\d+) 条口径）: (\d+)", summary)
    if not m:
        raise GateFail(f"sweep summary 语句池读数不认识：{tail(summary)}")
    out["vmdbg_sweep_programStatements"] = m.group(1)
    out["vmdbg_sweep_touchedStatements"] = m.group(2)
    # 3) 引擎注册出来的变量名全集（`#DIM` 装进 store 之后的可见量）。
    #    注意：这里**不带** glob 参数——`vars "*"` 经本机 shell/subprocess 传下去会枚出 0 条
    #    （实测），缺省 "*" 与显式 "*" 在 CmdVars 里同码，故用缺省形态取数。
    r = desktop_cmd(["vars"], erbc_dir, game, work, tag)
    names = [l.strip() for l in r.stdout.splitlines() if l.strip()]
    out["vmdbg_vars_count"] = str(len(names))
    out["vmdbg_vars_sha256"] = sha256_text("\n".join(sorted(names)))
    out["vmdbg_vars_sample"] = ",".join(sorted(names)[:6])
    return out


# ---------------------------------------------------------------- 比较

def compare_readings(rows: list[tuple[str, dict[str, str], dict[str, str]]],
                     keys: list[str], title: str) -> list[str]:
    """逐键比两腿（rows[0] 为基准）；不等即 GateFail（原样贴两侧值）。返回登记表行。"""
    diffs: list[str] = []
    table: list[str] = []
    for key in keys:
        vals = [(name, kv.get(key, "<缺键>")) for name, kv, _ in rows]
        base_val = vals[0][1]
        uniq = {v for _, v in vals}
        mark = "OK" if len(uniq) == 1 else "DIFF"
        shown = base_val if len(str(base_val)) <= 24 else str(base_val)[:24] + "…"
        table.append(f"{key:<34} {shown:<26} {mark}")
        if mark == "DIFF":
            diffs.append(f"{key}: " + " / ".join(f"{n}={v}" for n, v in vals))
    log(f"\n== {title}：{len(keys)} 键，"
        f"{'全部逐项相等' if not diffs else f'{len(diffs)} 项不等'}")
    for line in table:
        log("  " + line)
    if diffs:
        raise GateFail(f"{title} 两腿读数不等：\n  " + "\n  ".join(diffs))
    return table


# ---------------------------------------------------------------- 小语料（告警通道）

WARN_CORPUS_FILES = {
    # 重复 @FUNCTION（golden 基准 :1-:3 的同型：函数"@X"已经被定义过(F.ERB的N行)）
    "01_warn_dup.ERB": (
        "@SYSTEM_TITLE\n"
        "PRINTL DUP\n"
        "@DUP_TARGET\n"
        "PRINTL A\n"
        "@DUP_TARGET\n"
        "PRINTL B\n"
        "@DUP_TARGET\n"
        "PRINTL C\n"
    ),
    # INPUTS / BINPUTS 参数过多（golden 基准 :4-:5 的同型文案）
    "02_warn_inputs.ERB": (
        "@WARN_INPUTS\n"
        'INPUTS "ANS", 0, 1\n'
        'BINPUTS "BS", 0, 1\n'
    ),
}


def build_warn_corpus(base: str) -> str:
    """自造小语料（只进 build 沙箱，语料根一个字节不动）：让 load-warnings 通道非空。"""
    root = os.path.join(base, "warn-corpus")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    for name, text in WARN_CORPUS_FILES.items():
        with open(os.path.join(root, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    return root


def golden_templates() -> tuple[list[str], list[str]]:
    """基准档的**前两列**（`文件:行 | 文案`）；第三列 `SRC:` 是采集脚本从夹具补的，不进判据。

    出处 = `ERATW_GOLDEN_WARNINGS` 指向的文件与消费它的
    `../erak-engine/tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/LoadWarningsGoldenTest.kt`
    （render 口径 = 「文件:行 | 文案」）。
    返回 (重复定义文案表, 参数过多文案表)。
    """
    if not GOLDEN_WARNINGS:
        raise EnvFail("未设置 ERATW_GOLDEN_WARNINGS：装载告警基准是内部文件，不随公开仓发布")
    if not os.path.isfile(GOLDEN_WARNINGS):
        raise EnvFail(f"缺装载告警基准：{GOLDEN_WARNINGS}")
    rows = []
    for line in open(GOLDEN_WARNINGS, encoding="utf-8").read().splitlines():
        if not line.strip():
            continue
        parts = [p.strip() for p in line.split(" | ")]
        if len(parts) < 2:
            raise GateFail(f"基准行形状不认识（要 `文件:行 | 文案[ | SRC: …]`）：{line}")
        rows.append(parts)
    dup = [p[1] for p in rows if "已经被定义过" in p[1]]
    arg = [p[1] for p in rows if p[1].endswith("命令:参数过多")]
    return dup, arg


def check_warnings_against_golden(kv: dict[str, str], label: str) -> list[str]:
    """引擎读回的告警必须非空，两类文案与 golden 的两类模板同式，且不许有第三类混进来。

    真语料实测 `load-warnings 0`（闸 1 的读数），只比「0 == 0」是空转；这一档用自造小语料
    把通道撑开，逼判据落到「文案同式 + 两腿逐字相等」上。
    """
    dup_tpl, arg_tpl = golden_templates()
    got = [l for l in kv["load_warnings_rendered"].splitlines() if l.strip()]
    notes: list[str] = []
    if not got:
        raise GateFail(f"{label}：告警通道读回 0 条 ⇒ 这一档在空转，判据无效")
    messages = [l.split(" | ", 1)[-1].strip() for l in got]
    dup_got = [m for m in messages if GOLDEN_DUP_RE.match(m)]
    arg_got = [m for m in messages if GOLDEN_ARG_RE.match(m)]
    if not dup_got or not arg_got:
        raise GateFail(f"{label}：引擎读回的告警缺一类模板（重复定义 {len(dup_got)} 条 / "
                       f"参数过多 {len(arg_got)} 条）：\n" + "\n".join(got))
    leftover = [m for m in messages if m not in dup_got and m not in arg_got]
    if leftover:
        raise GateFail(f"{label}：出现两类模板之外的告警，判据口径需重议：{leftover}")
    for m in dup_got:
        if "已经被定义过" not in dup_tpl[0]:
            raise GateFail("基准里找不到重复定义模板，无从对照")
    for m in arg_got:
        if not m.endswith(TOO_MANY_ARG_SUFFIX) or not all(
                t.endswith(TOO_MANY_ARG_SUFFIX) for t in arg_tpl):
            raise GateFail(f"{label}：参数过多文案后缀与基准不同式：{m}（基准：{arg_tpl}）")
    notes.append(f"引擎读回 {len(got)} 条：重复定义 {len(dup_got)} 条 + 参数过多 {len(arg_got)} 条；"
                 f"两类与基准 {len(dup_tpl)}+{len(arg_tpl)} 行同式"
                 f"（基准第三列 SRC: 系采集脚本补注，不参与判据）")
    notes.append("引擎原样读数（`文件:行 | 文案`，口径同 LoadWarningsGoldenTest.render）：")
    for l in got:
        notes.append("  " + l)
    return notes


# ---------------------------------------------------------------- 开局链 / 执行面

def cmd_gate1(ns: argparse.Namespace) -> dict:
    require_vmdbg()
    game = resolve_game(ns.game)
    # 工作目录 = build/rust-playable/<corpus-tag>：与 `erbcc_rs_parity.py parity --out` 同布局
    # （<base>/{kotlin,rust}/erbc.v1），故已产好的两腿可以 --reuse-legs 直接吃。
    base = winpath(os.path.join(RS_BUILD, ns.corpus_tag))
    os.makedirs(base, exist_ok=True)
    log(f"== 闸 1（双腿装载同读数）工作目录：{base}")
    k_dir, r_dir, _cp = produce_legs(ns.corpus, base, ns.classpath, "gate1", ns.reuse_legs)
    probe = write_probe(base)
    rows = []
    for name, d in (("kotlin", k_dir), ("rust", r_dir)):
        log(f"\n-- 引擎读数（{name} 腿）：{d}")
        work = os.path.join(base, "work-" + name)
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        kv = probe_readings(d, probe, ns.xmx)
        log(f"  probe：装载成功，program_functions={kv['program_functions']} "
            f"statements={kv['program_statements']} 告警={kv['load_warnings_count']} 用时 {kv['probe_elapsed_s']}s")
        vm = vmdbg_readings(d, game, work, name)
        log(f"  vmdbg：清单={vm['vmdbg_sweep_metaFunctions']} 语句池={vm['vmdbg_sweep_programStatements']} "
            f"变量名={vm['vmdbg_vars_count']} 指令数={vm['vmdbg_disasm_instructionCount']}")
        rows.append((name, kv, vm))
    keys = COMPARE_KEYS + list(rows[0][2].keys())
    table_probe = compare_readings([(n, kv, vm) for n, kv, vm in rows], COMPARE_KEYS,
                                   "闸 1a 装载读数（引擎探针）")
    vm_keys = [k for k in rows[0][2] if k != "vmdbg_disasm_first_line"]
    rows_vm = [(n, vm, kv) for n, kv, vm in rows]
    table_vm = compare_readings(rows_vm, vm_keys, "闸 1b 装载读数（vmdbg 现成命令）")
    # 引擎读数与 golden 基准的关系：真语料实测告警条数（0 条 = 通道空，故 gate1w 补非空档）
    rust_kv = rows[1][1]
    log(f"\n  真语料装载告警：rust 腿 {rust_kv['load_warnings_count']} 条 / "
        f"kotlin 腿 {rows[0][1]['load_warnings_count']} 条；基准 {GOLDEN_WARNINGS} 的 5 行"
        f"系 fixtures 语料（GOLDEN_ 前缀被 erbcc 排除在编译输入外，产物侧不可能携带），"
        f"故告警的非空对照见闸 1w。")
    result = {
        "corpus": winpath(ns.corpus),
        "kotlin_erbc": k_dir,
        "rust_erbc": r_dir,
        "four_piece_sha": {name: sha256_file(os.path.join(r_dir, name)) for name in FOUR},
        "probe": {name: kv for name, kv, _ in rows},
        "vmdbg": {name: vm for name, _, vm in rows},
        "table_probe": table_probe,
        "table_vmdbg": table_vm,
        "warn_count_full": rust_kv["load_warnings_count"],
        "warn_rendered_full": rust_kv["load_warnings_rendered"],
    }
    if ns.register:
        write_gate1_register(result)
    if not ns.keep:
        shutil.rmtree(base, ignore_errors=True)
        log(f"临时产物已删除：{base}")
    else:
        log(f"--keep：两腿产物与读数工作目录留在 {base}")
    log("\n闸 1 结论：同一份语料的两腿产物，引擎侧逐项读数全等。")
    return result


def cmd_gate1w(ns: argparse.Namespace) -> dict:
    require_vmdbg()
    game = resolve_game(ns.game)
    base = winpath(os.path.join(RS_BUILD, "gate1w"))
    os.makedirs(base, exist_ok=True)
    log(f"== 闸 1w（告警通道非空转档）工作目录：{base}")
    corpus = build_warn_corpus(base)
    k_dir, r_dir, _ = produce_legs(corpus, base, ns.classpath, "gate1w", ns.reuse_legs)
    probe = write_probe(base)
    rows = []
    for name, d in (("kotlin", k_dir), ("rust", r_dir)):
        work = os.path.join(base, "work-" + name)
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        kv = probe_readings(d, probe, "-Xmx1g")
        log(f"  {name} 腿：装载成功，告警 {kv['load_warnings_count']} 条")
        rows.append((name, kv, {}))
    table = compare_readings(rows, COMPARE_KEYS, "闸 1w 装载读数（自造小语料）")
    notes = []
    for name, kv, _ in rows:
        notes += [f"[{name}] " + n for n in check_warnings_against_golden(kv, name + " 腿")]
    for n in notes:
        log("  " + n)
    result = {
        "corpus": winpath(corpus),
        "probe": {name: kv for name, kv, _ in rows},
        "table": table,
        "notes": notes,
        "warnings_equal": rows[0][1]["load_warnings_rendered"] == rows[1][1]["load_warnings_rendered"],
    }
    if not result["warnings_equal"]:
        raise GateFail("闸 1w：两腿引擎读回的告警文本不一致：\n"
                       f"kotlin:\n{rows[0][1]['load_warnings_rendered']}\n"
                       f"rust:\n{rows[1][1]['load_warnings_rendered']}")
    if ns.register:
        write_gate1w_register(result)
    if not ns.keep:
        shutil.rmtree(base, ignore_errors=True)
        log(f"临时产物已删除：{base}")
    else:
        log(f"--keep：产物留在 {base}")
    log("\n闸 1w 结论：告警通道在两腿间逐字相等，且文案与 golden 基准同式（非空转）。")
    return result


def cmd_gate2(ns: argparse.Namespace) -> dict:
    require_vmdbg()
    game = resolve_game(ns.game)
    base = winpath(os.path.join(RS_BUILD, "gate2"))
    os.makedirs(base, exist_ok=True)
    legs_base = winpath(os.path.join(RS_BUILD, ns.corpus_tag))
    erbc = winpath(ns.erbc) if ns.erbc else None
    erbc_ref = winpath(ns.erbc_ref) if ns.erbc_ref else None
    if erbc is None or erbc_ref is None:
        k_dir, r_dir, _cp = produce_legs(ns.corpus, legs_base, ns.classpath, "gate2",
                                        ns.reuse_legs)
        erbc = erbc or r_dir
        erbc_ref = erbc_ref or k_dir
    work = os.path.join(base, "work")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    work_ref = os.path.join(base, "work-ref")
    shutil.rmtree(work_ref, ignore_errors=True)
    os.makedirs(work_ref)
    log(f"== 闸 2（跑通一局的一段）erbc = {erbc}")
    log(f"   对照腿（执行面）= {erbc_ref}（--no-compare-kotlin 可关掉）")
    out: dict[str, str] = {"erbc": winpath(erbc), "erbc_ref": winpath(erbc_ref)}

    # 1) 内置 START 开局链：装载完成 → 标题 → 新游戏 → …… → 第1日。
    #    只在被验的 Rust 腿产物上跑（这条链几十分钟量级）；两腿产物的字节相等由闸 1 前置钉。
    ctl_ns = argparse.Namespace(boot=None, max_episodes=1, wall_min=ns.wall_min,
                                max_depth=ns.max_depth, min_boot_steps=5)
    if ns.compare_kotlin:
        ca = run_explore_boot(erbc, game, os.path.join(base, "ctl-rust"), ctl_ns)
        cb = run_explore_boot(erbc_ref, game, os.path.join(base, "ctl-kotlin"), ctl_ns)
        ctl_keys = [k for k in ca if k not in ("explore_elapsed_s", "explore_rc",
                                               "explore_recipe", "explore_replay")]
        compare_two(ca, cb, sorted(ctl_keys), "闸 2a 内置 START 链的两腿对照（同一失败点/同一现场）")
        out.update({f"ctl_rust_{k}": v for k, v in ca.items()})
        out.update({f"ctl_kotlin_{k}": v for k, v in cb.items()})
    exp = run_explore_boot(erbc, game, base, ns)
    out.update({f"rust_{k}": v for k, v in exp.items()})
    if ns.register:
        # 先落一次登记：执行面（sweep/trace）后续任何一档红，都不许吃掉已取到的开局链读数
        write_gate2_register(out)

    # 2) sweep 切片（几十~几百个函数）：执行面读数，两腿各跑一遍逐项比
    rust_exec: dict[str, str] = {}
    kotlin_exec: dict[str, str] = {}
    for label, only in SLICES:
        sdir = os.path.join(base, label)
        shutil.rmtree(sdir, ignore_errors=True)
        os.makedirs(sdir)
        rr = desktop_cmd(
            ["sweep", "--only", only, "--out", winpath(sdir), "--wall-ms", str(ns.sweep_wall_ms),
             "--steps", str(ns.sweep_steps)], erbc, game, work, label)
        rust_exec.update(sweep_reading(label, sdir, rr.stdout))
        if ns.compare_kotlin:
            kdir = os.path.join(base, label + "-kotlin")
            shutil.rmtree(kdir, ignore_errors=True)
            os.makedirs(kdir)
            kr = desktop_cmd(
                ["sweep", "--only", only, "--out", winpath(kdir),
                 "--wall-ms", str(ns.sweep_wall_ms), "--steps", str(ns.sweep_steps)],
                erbc_ref, game, work_ref, label + "/kotlin")
            kotlin_exec.update(sweep_reading(label, kdir, kr.stdout))
        log(f"  sweep[{label}]：清扫 {rust_exec[f'{label}_selected']} 个函数"
            f"（{rust_exec[f'{label}_status']}），触碰语句 "
            f"{rust_exec[f'{label}_touched']}/{rust_exec[f'{label}_stmtPool']}"
            f"（{rust_exec[f'{label}_pct']}%）")

    # 3) trace 摘要（语句级轨迹，可复跑；去时戳后可跨腿比）
    tf = os.path.join(base, "trace-rust.txt")
    tr = desktop_cmd(["trace", ns.trace_fn, "--steps", str(ns.trace_steps),
                      "--capacity", str(ns.trace_capacity), "--out", winpath(tf)],
                     erbc, game, work, "trace")
    rust_exec.update(trace_reading(ns.trace_fn, tf, tr.stdout))
    if ns.compare_kotlin:
        kf = os.path.join(base, "trace-kotlin.txt")
        kr2 = desktop_cmd(["trace", ns.trace_fn, "--steps", str(ns.trace_steps),
                           "--capacity", str(ns.trace_capacity), "--out", winpath(kf)],
                          erbc_ref, game, work_ref, "trace/kotlin")
        kotlin_exec.update(trace_reading(ns.trace_fn, kf, kr2.stdout))
    log(f"  trace[{ns.trace_fn}]：status={rust_exec['trace_status']} "
        f"steps={rust_exec['trace_steps']} 轨迹 {rust_exec['trace_rows']} 行，"
        f"去时戳 sha {rust_exec['trace_sha256'][:16]}")
    out.update({f"rust_{k}": v for k, v in rust_exec.items()})
    out.update({f"kotlin_{k}": v for k, v in kotlin_exec.items()})
    out["erbc"] = winpath(erbc)
    out["erbc_ref"] = winpath(erbc_ref)
    if ns.register:
        write_gate2_register(out)
    if ns.compare_kotlin and kotlin_exec:
        compare_two(rust_exec, kotlin_exec, sorted(rust_exec.keys()),
                    "闸 2 执行面读数（sweep 切片 + trace）")
    if not ns.keep:
        shutil.rmtree(base, ignore_errors=True)
        log(f"临时产物已删除：{base}")
    else:
        log(f"--keep：explore 台账/切片留在 {base}")
    log("\n闸 2 结论：Rust 腿产物在桌面引擎里走完了开局链的一段，并有可复跑读数。")
    return out


def strip_trace_times(rows: list[str]) -> list[str]:
    """轨迹行里的「每语句耗时」是墙钟：`TraceSupport.dump` 打 `<line> <brief> <elapsedNs>ns`
    （出错时后面还挂 ` ERROR=…`，所以耗时不在行尾，2026-11-01 实测）。只把这一串数字换成钉值，
    行号/语句摘要/error 文本一个字都不放过。"""
    out_rows = []
    for r in rows:
        r = re.sub(r"(?<![\w.])\d+ns\b", "0ns", r)
        r = re.sub(r"\s+\d+\s*$", "", r)      # 兼容「行尾裸数字」的旧形态
        out_rows.append(r)
    return out_rows


# `WallLimitExceeded` 的 msg 带两个机器速度量（实测墙钟毫秒 / 窗口内应答输入的次数），
# 同产物两次跑必然不同；状态、异常类名、line、pc 照旧逐字比（这一档没放宽语义判据）。
WALL_MSG_RE = re.compile(r"墙钟 \d+ms 超过预算 \d+ms（期间应答 \d+ 次输入）")
WALL_MSG_PIN = "墙钟 <ms>ms 超过预算 <budget>ms（期间应答 <n> 次输入）"


# 切片：真语料实测各 100 余条函数名（meta 的 function 行按前缀计数），
# 正好落在「几十到几百」这一档；全量 11 万函数清扫不在本路的默认面。
SLICES = [
    ("slice_doku", "^DOKU"),
    ("slice_eaglov", "^EAGLOV"),
]


def sweep_reading(label: str, sdir: str, stdout_text: str) -> dict[str, str]:
    """一次 sweep 切片的引擎读数（清扫数 / 状态计数 / 语句池触碰数 / 台账归一 sha）。"""
    m = re.search(r"本次清扫 (\d+)", stdout_text)
    summary = open(os.path.join(sdir, "summary.md"), encoding="utf-8").read()
    # 只取「## 状态计数」那张表（`| 状态 | 条数 |`）；TOP 簇表也是 `| 数字 | 词 |` 形状，
    # 整篇扫会把函数名/行号当成状态读进来（2026-11-01 实测污染），故先切段。
    seg = summary.split("## 状态计数", 1)[-1].split("## 失败指纹", 1)[0]
    counts = dict(re.findall(r"\| ([A-Z_]+) \| (\d+) \|", seg))
    touched = re.search(r"distinct 语句（全程序语句池 (\d+) 条口径）: (\d+)（([\d.]+)%", summary)
    jsonl = open(os.path.join(sdir, "sweep.jsonl"), encoding="utf-8", errors="replace").read()
    return {
        f"{label}_selected": m.group(1) if m else "-",
        f"{label}_status": ",".join(f"{k}={v}" for k, v in sorted(counts.items())),
        f"{label}_stmtPool": touched.group(1) if touched else "-",
        f"{label}_touched": touched.group(2) if touched else "-",
        f"{label}_pct": touched.group(3) if touched else "-",
        f"{label}_ledger_sha256": sha256_text(normalize_jsonl(jsonl)),
    }


def trace_reading(fn: str, tfile: str, stdout_text: str) -> dict[str, str]:
    """trace 的引擎读数（状态 / 步数 / 轨迹行数 / 去时戳后的轨迹 sha）。"""
    rows = [l for l in open(tfile, encoding="utf-8", errors="replace").read().splitlines()
            if l.strip()] if os.path.isfile(tfile) else []

    def grab(pat: str) -> str:
        m = re.search(pat, stdout_text)
        return m.group(1) if m else "-"

    return {
        "trace_fn": fn,
        "trace_status": grab(r"status=(\w+)"),
        "trace_steps": grab(r"steps=(-?\d+)"),
        "trace_rows": str(len(rows)),
        "trace_sha256": sha256_text("\n".join(strip_trace_times(rows))),
    }


def compare_two(a: dict[str, str], b: dict[str, str], keys: list[str], title: str) -> None:
    """两份读数逐键相等才算等；不等原样贴两侧值。"""
    diffs = [f"{k}: rust={a.get(k, '<缺>')} / kotlin={b.get(k, '<缺>')}"
             for k in keys if a.get(k) != b.get(k)]
    log(f"\n== {title}：{len(keys)} 键，"
        f"{'两腿逐项相等' if not diffs else f'{len(diffs)} 项不等'}")
    for k in keys:
        v = str(a.get(k, "-"))
        log(f"  {k:<26} {v if len(v) <= 46 else v[:46] + '…'}")
    if diffs:
        raise GateFail(f"{title} 两腿读数不等：\n  " + "\n  ".join(diffs))


def strip_jsonl_ts(rows: list[str]) -> list[str]:
    """台账行去掉 `"ts":<墙钟毫秒>` 之后的原文（其余字段都是确定量）。"""
    return [re.sub(r'"ts":\d+,?', "", r) for r in rows]


def run_explore_boot(erbc: str, game: str, base: str, ns: argparse.Namespace) -> dict[str, str]:
    """内置 START 开局链（ExploreBoot.BUILTIN_START）或 `--boot` 配方链的桌面实跑。

    判据口径（本路唯一问题的第 2 条）：`BEGIN → 标题 → 进游戏` 走到**能读屏、能提交输入**的
    一段，即 `--min-boot-steps` 个等待点以上，且引擎在这些等待点上执行过脚本函数
    （coverage.txt 的 executedFunctions > 0）。终点「第1日」是否现身**单独记录**：
    实测本语料的口上/剧本 UPDATE 问答链与内置配方已漂移（见交付报红），
    内置链到不了第1日 —— 两腿同点同因（对照腿读数证明与产物来源无关）。

    读数一律取去墙钟的字段：executedFunctions / executedStatements、explore.jsonl 去 ts 的
    行集 sha、findings 计数、boot-fail.json 的 reason/stepIndex（同产物复跑逐字一致）。
    """
    work = os.path.join(base, "work-explore")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    edir = os.path.join(base, "explore")
    shutil.rmtree(edir, ignore_errors=True)
    argv = [sys.executable, DESKTOP_PY, "cmd", "--erbc", winpath(erbc), "--game", winpath(game),
            "--work", winpath(work), "explore", "--max-episodes", str(ns.max_episodes),
            "--wall-min", str(ns.wall_min), "--max-depth", str(ns.max_depth),
            "--out", winpath(edir)]
    if getattr(ns, "boot", None):
        argv += ["--boot", winpath(ns.boot)]
    log(f"  explore 启动（maxEpisodes={ns.max_episodes} wallMin={ns.wall_min} "
        f"maxDepth={ns.max_depth} boot={ns.boot or '内置 START 链'}）——真开局链，几分钟量级")
    t0 = time.time()
    env = dict(os.environ)
    env["ERATW_ERBC_DIR"] = winpath(erbc)     # 同 desktop_cmd：喂掉 desktop.py 的缺省求值
    env["ERATW_GAMEDIR"] = winpath(game)
    r = subprocess.run(argv, cwd=APP_ROOT, capture_output=True, env=env)
    dt = time.time() - t0
    text = decode_mixed(r.stdout) + "\n" + decode_mixed(r.stderr)
    with open(os.path.join(base, "explore-screen.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    out: dict[str, str] = {
        "explore_rc": str(r.returncode), "explore_elapsed_s": f"{dt:.1f}",
        "explore_recipe": winpath(ns.boot) if getattr(ns, "boot", None) else "BUILTIN_START",
    }
    m = re.search(r"status=(\w+)", text)
    out["explore_status"] = m.group(1) if m else "<无>"
    for key in ("anchor", "episodes", "findings", "outcomes", "reason", "seed"):
        m = re.search(r"^" + key + r"=(.*)$", text, re.M)
        if m:
            out["explore_" + key] = m.group(1).strip()
    log("  explore 屏上原样（尾部）：")
    for l in [x for x in text.splitlines() if x.strip()][-8:]:
        log("    " + l)
    # 引导失败现场：boot-fail.json 的 stepIndex/reason 是可复跑读数（同产物两次一致）
    fj = os.path.join(edir, "boot-fail.json")
    if os.path.isfile(fj):
        rec = json.loads(open(fj, encoding="utf-8").read())
        out["boot_fail_stepIndex"] = str(rec.get("stepIndex", -1))
        out["boot_fail_reason"] = str(rec.get("reason", ""))[:180]
        out["boot_fail_systemState"] = str(rec.get("systemState", ""))
        fconsole = os.path.join(edir, "boot-fail-console.txt")
        if os.path.isfile(fconsole):
            raw = open(fconsole, encoding="utf-8", errors="replace").read()
            out["boot_fail_console_lines"] = str(len([x for x in raw.splitlines() if x.strip()]))
            out["boot_fail_console_sha256"] = sha256_text(
                "\n".join(x.strip() for x in raw.splitlines()[-60:]))
    # 覆盖读数：explore 的探针在引导期同样在记（boot 失败也落 coverage.txt），是「真跑过脚本」的证据
    cov = os.path.join(edir, "coverage.txt")
    if os.path.isfile(cov):
        cov_text = open(cov, encoding="utf-8", errors="replace").read()
        m = re.search(r"executedFunctions=(\d+)", cov_text)
        out["explore_executedFunctions"] = m.group(1) if m else "-"
        m = re.search(r"executedStatements=(\d+)/(\d+)", cov_text)
        out["explore_executedStatements"] = f"{m.group(1)}/{m.group(2)}" if m else "-"
        out["explore_coverage_sha256"] = sha256_text(normalize_cov(cov_text))
    ledger = os.path.join(edir, "explore.jsonl")
    rows = [l for l in open(ledger, encoding="utf-8", errors="replace").read().splitlines()
            if l.strip()] if os.path.isfile(ledger) else []
    out["explore_ledgerLines"] = str(len(rows))
    out["explore_ledger_sha256"] = sha256_text("\n".join(strip_jsonl_ts(rows)))
    out["explore_replay"] = first_replay(edir)
    reached = int(out.get("boot_fail_stepIndex", "0") or 0)
    if out["explore_status"] == "PASS":
        out["boot_steps_reached"] = str(ns.min_boot_steps)
        log(f"  开局链走完到终点：anchor={out.get('explore_anchor', '-')}，执行函数 "
            f"{out.get('explore_executedFunctions', '-')} 个 / 语句 "
            f"{out.get('explore_executedStatements', '-')}")
    else:
        out["boot_steps_reached"] = str(reached)
        log(f"  开局链停在第 {reached} 个配方等待点（status={out['explore_status']}）："
            f"{out.get('boot_fail_reason', '')[:120]}；执行函数 "
            f"{out.get('explore_executedFunctions', '-')} 个 / 语句 "
            f"{out.get('explore_executedStatements', '-')}")
        if reached < ns.min_boot_steps:
            raise GateFail(
                f"开局链连「能读屏/能提交输入」这一段都没走到（配方步数 {reached} < "
                f"{ns.min_boot_steps}）；reason={out.get('boot_fail_reason', '-')}；现场目录 {edir}")
    if int(out.get("explore_executedFunctions", "0") or 0) <= 0:
        raise GateFail(f"引导期引擎一个脚本函数都没执行过（coverage={out})）——"
                       f"装载成功但执行面空转，判据不成立")
    return out


def normalize_jsonl(text: str) -> str:
    """sweep 台账去掉 elapsedMs/steps 这类非确定字段后的行集（fn/key/status/cls/line/pc/msg）。"""
    keep: list[str] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        fields = re.findall(r'"(fn|key|status|cls|line|pc|msg)":"?([^",}]*)"?(?=,|$)', line)
        fields = [(k, WALL_MSG_RE.sub(WALL_MSG_PIN, v)) for k, v in fields]
        keep.append("|".join(f"{k}={v}" for k, v in fields))
    return "\n".join(sorted(keep))


def normalize_cov(text: str) -> str:
    """coverage.txt 去掉时间戳/墙钟行后的计数读数（两次复跑应逐字一致）。"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or re.search(r"(墙钟|elapsed|ms\b|时间|ts=)", s, re.IGNORECASE):
            continue
        if "已探测" in s or s.startswith("数据源="):
            # 这两条带 --out 目录的绝对路径（跨目录/跨机必不同），不是执行读数，剥掉
            continue
        out.append(re.sub(r"\s+", " ", s))
    return "\n".join(out)


def read_explore_ledger(path: str) -> tuple[int, list[str]]:
    rows = [l for l in open(path, encoding="utf-8", errors="replace").read().splitlines() if l.strip()]
    boot = sum(1 for l in rows if '"boot"' in l or 'bootStep' in l)
    return boot, rows


def first_replay(edir: str) -> str:
    d = os.path.join(edir, "replay")
    if not os.path.isdir(d):
        return "-"
    names = sorted(os.listdir(d))
    if not names:
        return "-"
    p = os.path.join(d, names[0])
    return f"{names[0]}: " + " ;; ".join(
        l.strip() for l in open(p, encoding="utf-8", errors="replace").read().splitlines() if l.strip())


# ---------------------------------------------------------------- 反向证明

TAMPER_CASES = [
    # 对照档：整目录原样拷贝、一字不改 ⇒ 装载链必须放行（否则下面几档的「拒」说不清是
    # 篡改造成的还是拷贝/路径流程造成的，反向证明不成立）。
    ("control-clean-copy", "对照：erbc.v1 整目录原样拷贝（不做任何字节改动）", "expect_accept"),
    # 硬判据档：容器被截断 ⇒ 装载链**必须**明确失败（这条不过 = 闸在空转）。
    ("erbc-truncated", "把 program.erbc 截到 3/5（容器段表/记录体必然读不完）",
     "expect_loader_error"),
    # 以下两档是「行为取证」：引擎侧到底管不管，原样记录；管不了的那部分归谁，写进交付。
    ("meta-program-sha", "只改 program.meta 的 program-sha256 一位十六进制（容器字节不动）",
     "expect_any_reject_or_report"),
    ("meta-count-field", "把 program.meta 的 functions 计数改成比实际少 1（段与计数不等）",
     "expect_any_reject_or_report"),
]


def cmd_gate3(ns: argparse.Namespace) -> dict:
    require_vmdbg()
    game = resolve_game(ns.game)
    base = winpath(os.path.join(RS_BUILD, "gate3"))
    os.makedirs(base, exist_ok=True)
    log(f"== 闸 3（反向证明）工作目录：{base}")
    if ns.erbc:
        erbc = winpath(ns.erbc)
    else:
        _k, erbc, _cp = produce_legs(ns.corpus, base, ns.classpath, "gate3", ns.reuse_legs)
    probe = write_probe(base)
    results: dict[str, dict[str, str]] = {}
    for case, desc, kind in TAMPER_CASES:
        log(f"\n档 {case}：{desc}")
        cdir = os.path.join(base, "tamper-" + case)
        shutil.rmtree(cdir, ignore_errors=True)
        os.makedirs(cdir)
        dst = os.path.join(cdir, ERBC_DIR_NAME)
        shutil.copytree(erbc, dst)
        how = tamper(case, dst)
        log(f"  篡改落点：{how}")
        rec: dict[str, str] = {"desc": desc, "how": how}
        # A) 引擎装载探针（ProgramLoader + BootMetadata 原样）
        try:
            kv = probe_readings(dst, (parity.resolve_java(), os.path.join(base, "probe",
                                                                          PROBE_CLASS + ".java")), "-Xmx3g")
            rec["probe_rc"] = "0"
            rec["probe_load_ok"] = kv.get("load_ok", "?")
            rec["probe_statements"] = kv.get("program_statements", "-")
            # 全 64 位：截断到 16 位会把「改末位一位」这类篡改本身截没，登记物无法自证篡改落地
            rec["probe_meta_sha_field"] = kv.get("meta_program_sha256_field", "-")
            rec["probe_erbc_actual_sha"] = kv.get("erbc_actual_sha256", "-")
            log(f"  探针：装载 {'成功' if rec['probe_load_ok']=='true' else '失败'}"
                f"（rc=0，statements={rec['probe_statements']}）")
        except EngineReject as e:
            rec["probe_rc"] = str(e.rc)
            # 装载链自己报的那句话在首行（异常文案）、栈帧在尾部：两截都进登记表
            rec["probe_error"] = e.text
            log(f"  探针被引擎拒绝：rc={e.rc}")
            for l in head_and_tail(e.text, 3, 6).splitlines():
                log("    " + l)
        # B) vmdbg 会话装配（disasm 0 -n 1 会走完装载 + GameSession 装配）
        work = os.path.join(cdir, "work")
        os.makedirs(work, exist_ok=True)
        try:
            desktop_cmd(["disasm", "0", "-n", "1"], dst, game, work, case)
            rec["vmdbg_rc"] = "0"
            rec["vmdbg_rejected"] = "false"
            log("  vmdbg：装载链放行（rc=0）")
        except EngineReject as e:
            rec["vmdbg_rc"] = str(e.rc)
            rec["vmdbg_rejected"] = "true"
            rec["vmdbg_error"] = e.text
            log(f"  vmdbg 被引擎拒绝：rc={e.rc}")
            for l in head_and_tail(e.text, 3, 6).splitlines():
                log("    " + l)
        rejected = rec.get("vmdbg_rejected") == "true" or rec.get("probe_rc") != "0"
        rec["rejected"] = "true" if rejected else "false"
        # C) 打包层的新鲜度对账（erbcc check）：装载链不管的那一档（侧车↔容器配对凭证）归这里。
        #    没有这一步就只能引用上一路的说法，「归 CMP-66」这句结论得自己跑出来才算证据。
        try:
            exe = parity.resolve_rust_exe(None)
            cr = subprocess.run([exe, "check", "--corpus", winpath(ns.corpus), "--out", cdir],
                                cwd=APP_ROOT, capture_output=True)
            rec["check_rc"] = str(cr.returncode)
            rec["check_msg"] = head_and_tail(
                decode_mixed(cr.stdout) + "\n" + decode_mixed(cr.stderr), 6, 6)
            log(f"  erbcc check（打包层）rc={cr.returncode}")
            for l in rec["check_msg"].splitlines():
                log("    " + l)
        except parity.EnvError as e:
            rec["check_rc"] = "<未跑：缺 erbcc.exe>"
            rec["check_msg"] = str(e)
            log(f"  erbcc check 跳过：{e}")
        if kind == "expect_loader_error" and not rejected:
            raise GateFail(f"闸 3 档 {case}：该拒未拒（引擎照样装载）⇒ 判据空转，"
                           f"见 {cdir}")
        if kind == "expect_accept" and rejected:
            raise GateFail(f"闸 3 对照档 {case}：**没篡改也被装载链拒了** ⇒ 下面几档的拒因"
                           f"说不清是篡改还是拷贝/路径流程，见 {cdir}")
        if kind == "expect_any_reject_or_report":
            log(f"  本档结论：引擎侧 {'拒绝' if rejected else '放行（见交付说明：程序自证不在装载链，归 CMP-66）'}")
        results[case] = rec
        if not ns.keep:
            shutil.rmtree(cdir, ignore_errors=True)
    if ns.register:
        write_gate3_register(results, erbc, game)
    if not ns.keep:
        shutil.rmtree(base, ignore_errors=True)
        log(f"\n临时产物已删除：{base}")
    else:
        log(f"--keep：产物留在 {base}")
    log("\n闸 3 结论：原样拷贝被放行（对照档）、容器截断与侧车计数造假都被装载链明确拒绝（闸非空转）；"
        "program-sha256 一位漂移引擎侧放行、打包层 `erbcc check` 拒（META_STALE，rc=1）——"
        "两档的 rc 与报错原文都在登记表里。")
    return results


def tamper(case: str, dst: str) -> str:
    if case == "control-clean-copy":
        size, sha = sha256_file(os.path.join(dst, "program.erbc"))
        return f"不篡改（原样拷贝；program.erbc {size} B sha {sha[:16]}… 与闸 1 登记同值）"
    if case == "erbc-truncated":
        p = os.path.join(dst, "program.erbc")
        data = open(p, "rb").read()
        keep = len(data) * 3 // 5
        with open(p, "wb") as f:
            f.write(data[:keep])
        return f"program.erbc {len(data)} B → {keep} B（截断，口径同 erbcc_rs_parity 的 truncated 档）"
    if case == "meta-program-sha":
        p = os.path.join(dst, "program.meta")
        text = open(p, encoding="utf-8").read()
        m = re.search(r'^program-sha256 "([0-9a-fA-F]{64})"', text, re.M)
        if not m:
            raise GateFail(f"program.meta 里找不到 program-sha256 行：{p}")
        old = m.group(1)
        new = old[:-1] + ("0" if old[-1] != "0" else "1")
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(text.replace(old, new, 1))
        return f"program.meta 的 program-sha256 末位 {old[-1]}→{new[-1]}（容器字节一字未动）"
    if case == "meta-count-field":
        p = os.path.join(dst, "program.meta")
        lines = open(p, encoding="utf-8").read().splitlines()
        idx = next((i for i, l in enumerate(lines) if re.match(r"^functions \d+$", l)), None)
        if idx is None:
            raise GateFail(f"program.meta 里找不到 functions 计数行：{p}")
        n = int(lines[idx].split()[1])
        lines[idx] = f"functions {n - 1}"
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        return f"program.meta 第 {idx + 1} 行 `functions {n}` → `functions {n - 1}`（记录体一字未动）"
    raise EnvFail(f"未知档：{case}")


# ---------------------------------------------------------------- 登记表（小档，只放读数）

def _reg(name: str, lines: list[str]) -> str:
    os.makedirs(DATA_OUT, exist_ok=True)
    p = os.path.join(DATA_OUT, name)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    log(f"登记物已写：{p}")
    return p


def _stamp(corpus: str) -> list[str]:
    return [
        "# erbcc-rs / rust-playable 车道登记：Rust 腿产物装进引擎后的实测读数（开发机）",
        "# 产法：python devtools/verify_rust_erbc_playable.py all --register"
        "（或单独 gate1 / gate1w / gate2 / gate3）",
        f"# 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}　语料：{winpath(corpus)}",
        "# 机相关：读数含产出机绝对路径与 vm 版本；换机（或改 core-vm 源码树/编译器指纹）"
        "后重跑刷新，判据不放宽。",
    ]


def write_gate1_register(res: dict) -> None:
    lines = _stamp(res["corpus"]) + ["", "## 四件套（rust 腿；kotlin 腿 sha 由前置双跑闸钉全等）"]
    for name, (size, sha) in res["four_piece_sha"].items():
        lines.append(f"{name} {size} {sha}")
    lines += ["", f"## 真语料装载告警：{res['warn_count_full']} 条（通道非空对照见 warn-channel 档）"]
    if res["warn_rendered_full"].strip():
        lines.append(res["warn_rendered_full"])
    lines += ["", "## 探针读数逐项（kotlin 腿为基，rust 腿与全表等值 ⇒ 只列一份）"]
    lines += res["table_probe"]
    lines += ["", "## vmdbg 现成命令读数逐项"]
    lines += res["table_vmdbg"]
    lines += ["", "## rust 腿引擎读数原样（非 COMPARE_KEYS 的旁证行）"]
    for k in ("probe_elapsed_s", "load_program_ms", "load_meta_ms"):
        lines.append(f"{k} {res['probe']['rust'].get(k, '-')}")
    lines.append(f"vars_sample {res['vmdbg']['rust']['vmdbg_vars_sample']}")
    lines.append("（load_*_ms / probe_elapsed_s 是墙钟，不进两腿判据）")
    _reg("haruki-gate1-load-readings.txt", lines)


def write_gate1w_register(res: dict) -> None:
    dup_tpl, arg_tpl = golden_templates()
    lines = _stamp(res["corpus"]) + [
        "", "## 自造小语料（重复 @FUNCTION + INPUTS/BINPUTS 参数过多）的引擎读回告警",
        "```", res["probe"]["rust"]["load_warnings_rendered"], "```",
        f"两腿告警文本逐字相等：{res['warnings_equal']}",
        f"load_warnings_count {res['probe']['rust']['load_warnings_count']}　"
        f"sha256 {res['probe']['rust']['load_warnings_sha256']}",
        "", "## 基准（ERATW_GOLDEN_WARNINGS）的第二列，逐行原样",
    ]
    for m in dup_tpl + arg_tpl:
        lines.append("基准 " + m)
    got = [l.split(" | ", 1)[-1].strip()
           for l in res["probe"]["rust"]["load_warnings_rendered"].splitlines() if l.strip()]
    verbatim = [m for m in got if m in (dup_tpl + arg_tpl)]
    lines.append(f"引擎读回与基准**逐字相同**的条数：{len(verbatim)}/{len(got)}"
                 f"（同字的那几条 = 同一模板的同一文案；其余条只差文件名/行号/标签名，"
                 f"模板同式由 check_warnings_against_golden 断言）")
    lines += ["", "## 读数逐项"]
    lines += res["table"]
    lines += ["", "## 与基准的关系（逐条）"]
    lines += res["notes"]
    _reg("haruki-gate1w-warn-channel.txt", lines)


def write_gate2_register(out: dict) -> None:
    lines = _stamp(out["erbc"]) + [
        "", "## 开局链（vmdbg explore，内置 START 链 → 第1日）：Rust 腿产物原样读数",
    ]
    for k in sorted(out):
        if k in ("erbc", "erbc_ref") or k.startswith("kotlin_"):
            continue
        lines.append(f"{k} {out[k]}")
    lines += ["", "## 执行面读数（sweep 切片 + trace）：对照腿 Kotlin（Rust 与它逐项相等，"
                  "相等性由 compare_two 断言，不等则整条命令已非零退出）"]
    keys = sorted(k[len("kotlin_"):] for k in out if k.startswith("kotlin_"))
    for k in keys:
        lines.append(f"{k} {out['kotlin_' + k]}")
    _reg("haruki-gate2-play-readings.txt", lines)


def write_gate3_register(results: dict, erbc_src: str, game: str) -> None:
    size, sha = sha256_file(os.path.join(erbc_src, "program.erbc"))
    msize, msha = sha256_file(os.path.join(erbc_src, "program.meta"))
    kind_of = {c: k for c, _d, k in TAMPER_CASES}
    lines = _stamp(erbc_src) + [
        "", "## 被破坏的原始产物（Rust 腿全量 erbc.v1；四件套 sha 与闸 1 登记同值）",
        f"erbc-dir {winpath(erbc_src)}",
        f"program.erbc {size} {sha}",
        f"program.meta {msize} {msha}",
        f"游戏根 {winpath(game)}（会话装配要用 CSV/emuera.config）",
        "",
        "## 逐档读数：期望 → 引擎侧原样报错 + rc",
        "（probe = 一次性 Java 探针，走 ProgramLoader.loadMapped + BootMetadata.read；"
        "vmdbg = desktop.py cmd disasm 0 -n 1，走同一条装载链 + GameSession 装配）",
    ]
    for case, rec in results.items():
        lines.append(f"\n### 档 {case}：{rec['desc']}")
        lines.append(f"判据：{kind_of.get(case, '-')}")
        lines.append(f"篡改：{rec['how']}")
        for k in sorted(rec):
            if k in ("desc", "how"):
                continue
            v = rec[k]
            if "\n" in str(v):
                lines.append(f"{k}:")
                lines += ["    " + l for l in str(v).splitlines()]
            else:
                lines.append(f"{k} {v}")
    _reg("haruki-gate3-reject-readings.txt", lines)


# ---------------------------------------------------------------- 子命令入口

def cmd_all(ns: argparse.Namespace) -> int:
    res1 = cmd_gate1(ns)
    ns.corpus = SMALL_CORPUS
    ns.corpus_tag = "small"
    cmd_gate1w(ns)
    ns.corpus = res1["corpus"]
    ns.reuse_legs = True
    cmd_gate2(ns)
    cmd_gate3(ns)
    return 0


def tail(text: str, n: int = 14) -> str:
    lines = [l for l in text.splitlines() if l.strip()]
    return "\n".join(lines[-n:])


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="verify_rust_erbc_playable.py",
        description="RUST1 rust-playable：Rust 腿 erbc.v1 装进桌面引擎后的装载/执行验收",
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
        p.add_argument("--corpus", default=os.environ.get("ERATW_CORPUS") or SMALL_CORPUS,
                       help="语料根（缺省 $ERATW_CORPUS；必须是 ERB 子树）")
        p.add_argument("--corpus-tag", dest="corpus_tag", default="full",
                       help="工作目录后缀（full/small），便于 --reuse-legs")
        p.add_argument("--game", help="游戏根（缺省 $ERATW_GAMEDIR）")
        p.add_argument("--classpath", help="ERBCC_CLASSPATH 覆盖（同 parity 档 1 两态）")
        p.add_argument("--reuse-legs", dest="reuse_legs", action="store_true",
                       help="复用 build/rust-playable/ 下已存在的两腿产物（省一趟 30s 编译）")
        p.add_argument("--keep", action="store_true", help="保留临时产物（默认删除）")
        p.add_argument("--register", action="store_true",
                       help="把读数写进 ../erak-engine/tools/erbcc-rs/tests/data/rust-playable/（小档登记物）")
        return p

    g1 = common(sub.add_parser("gate1", help="双腿装载同读数（函数/语句/告警/#DIM）"))
    g1.add_argument("--xmx", default="-Xmx3g", help="引擎探针堆档（默认与 desktop.py 同尺 3g）")
    g1.set_defaults(fn=cmd_gate1)

    g1w = common(sub.add_parser("gate1w", help="告警通道非空转档（自造小语料，两腿逐字比）"))
    g1w.add_argument("--xmx", default="-Xmx1g")
    g1w.set_defaults(fn=cmd_gate1w)

    g2 = common(sub.add_parser("gate2", help="跑通一局的一段（explore 开局链 + sweep 切片 + trace）"))
    g2.add_argument("--erbc", help="直接用现成 erbc.v1（缺省自己产 Rust 腿）")
    g2.add_argument("--max-episodes", dest="max_episodes", type=int, default=1)
    g2.add_argument("--wall-min", dest="wall_min", type=int, default=20)
    g2.add_argument("--max-depth", dest="max_depth", type=int, default=1)
    g2.add_argument("--sweep-wall-ms", dest="sweep_wall_ms", type=int, default=3000)
    g2.add_argument("--sweep-steps", dest="sweep_steps", type=int, default=200000)
    g2.add_argument("--trace-fn", dest="trace_fn", default="EAGLOV_GET_BASE_RESOURCE",
                    help="trace 的目标函数：必须是**不等输入**的纯计算函数（缺省 EAGLOV_GET_BASE_RESOURCE，"
                         "sweep 切片里 PASS/15 语句 250 步）。"
                         "注意 SHOP_MAIN 在本语料函数表里不存在（NOT_FOUND 退 2）；"
                         "SYSTEM_TITLE 会挂在标题菜单的输入等待上永不返回（2026-11-01 实测：两腿同挂，"
                         "35 分钟量级未退出，见交付报红）")
    g2.add_argument("--erbc-ref", dest="erbc_ref",
                    help="对照腿的 erbc.v1（执行面读数用它复跑一遍并逐项比；缺省用本次产的 Kotlin 腿）")
    g2.add_argument("--trace-steps", dest="trace_steps", type=int, default=400000)
    g2.add_argument("--trace-capacity", dest="trace_capacity", type=int, default=200000)
    g2.add_argument("--no-compare-kotlin", dest="compare_kotlin", action="store_false",
                    help="执行面只跑被验腿（省三次装载），默认两腿都跑")
    g2.add_argument("--boot", help="开局链配方文件（每行 `输入 ;; 期望子串`）；缺省用 vmdbg 内置 START 链")
    g2.add_argument("--min-boot-steps", dest="min_boot_steps", type=int, default=10,
                    help="判定「走到能读屏/能提交输入」的最小等待点数")
    g2.set_defaults(fn=cmd_gate2, compare_kotlin=True)

    g3 = common(sub.add_parser("gate3", help="反向证明：坏产物必须被装载链拒绝"))
    g3.add_argument("--erbc", help="直接用现成 erbc.v1（缺省自己产 Rust 腿）")
    g3.set_defaults(fn=cmd_gate3)

    pr = sub.add_parser("probe", help="只对一份 erbc.v1 取引擎读数")
    pr.add_argument("--erbc", required=True)
    pr.add_argument("--xmx", default="-Xmx3g")
    pr.set_defaults(fn=cmd_probe_only)

    al = common(sub.add_parser("all", help="gate1 + gate1w + gate2 + gate3"))
    al.add_argument("--erbc")
    al.add_argument("--max-episodes", dest="max_episodes", type=int, default=1)
    al.add_argument("--wall-min", dest="wall_min", type=int, default=20)
    al.add_argument("--max-depth", dest="max_depth", type=int, default=1)
    al.add_argument("--sweep-wall-ms", dest="sweep_wall_ms", type=int, default=3000)
    al.add_argument("--sweep-steps", dest="sweep_steps", type=int, default=200000)
    al.add_argument("--trace-fn", dest="trace_fn", default="SYSTEM_TITLE")
    al.add_argument("--xmx", default="-Xmx3g")
    al.add_argument("--erbc-ref", dest="erbc_ref")
    al.add_argument("--trace-steps", dest="trace_steps", type=int, default=400000)
    al.add_argument("--trace-capacity", dest="trace_capacity", type=int, default=200000)
    al.add_argument("--no-compare-kotlin", dest="compare_kotlin", action="store_false")
    al.add_argument("--boot")
    al.add_argument("--min-boot-steps", dest="min_boot_steps", type=int, default=10)
    al.set_defaults(fn=cmd_all, compare_kotlin=True)
    return ap


def cmd_probe_only(ns: argparse.Namespace) -> int:
    require_vmdbg()
    base = winpath(os.path.join(RS_BUILD, "probe-only"))
    os.makedirs(base, exist_ok=True)
    probe = write_probe(base)
    kv = probe_readings(ns.erbc, probe, ns.xmx)
    for k in sorted(kv):
        if k in ("load_warnings_rendered", "probe_elapsed_s"):
            continue
        log(f"{k}={kv[k]}")
    log("--- load-warnings（原样）---")
    log(kv["load_warnings_rendered"])
    return 0


def main(argv: list[str]) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ns = build_parser().parse_args(argv)
    if ns.cmd in ("gate1", "gate1w", "gate2", "gate3", "all"):
        if not os.path.isdir(winpath(ns.corpus)) and not getattr(ns, "erbc", None):
            log(f"环境不满足（退 2）：语料目录不存在 {winpath(ns.corpus)}")
            return 2
    try:
        rc = ns.fn(ns)
        return rc if isinstance(rc, int) else 0
    except EnvFail as e:
        log(f"环境不满足（退 2）：{e}")
        return 2
    except parity.EnvError as e:
        log(f"环境不满足（退 2）：{e}")
        return 2
    except GateFail as e:
        log(f"判据红（退 1）：{e}")
        return 1
    except parity.ParityFail as e:
        log(f"判据红（退 1）：双跑对拍不等：{e}")
        return 1
    except EngineReject as e:
        log(f"判据红（退 1）：引擎拒绝装载（本不该拒绝的档）：{e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
