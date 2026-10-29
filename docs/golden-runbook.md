# Golden Runbook — PC 原版引擎 GOLDEN.ERB 采集与 diff 指南

目的：在 PC 原版引擎 `Emuera_LazyLoadingV3.0_x64.exe` 上采集 `fixtures/golden/GOLDEN_*.ERB` 的控制台输出，
作为 L2 规约一致性的黄金基准；未来 Kotlin VM 运行同一份脚本时输出必须逐字节一致（时间/内存类行除外，见 §6）。

依据的反编译源码位置均以 `reference/emuera-cs/` 为根，仅引用过一次的行号会随源码漂移，故此处给出「文件:大致位置」。

---

## 1. 涉及的交付文件

```
eratw-android/fixtures/golden/
  GOLDEN_00_ROOT.ERB          入口 @GOLDEN_ROOT（根信息 + 依次 CALL 各节 + === END ===）+ 自动触发钩子 @EVENTFIRST/#PRI
  GOLDEN_01_BASIC.ERB         变量/赋值/数组/字符串/数学
  GOLDEN_02_PRINT.ERB         PRINT 全家族、按钮、颜色、字体、对齐、HTML、行控制
  GOLDEN_03_FLOW.ERB          分支/循环/标签/CALL/TRY 系/CALLF/事件
  GOLDEN_04_CHARA.ERB         角色增删改查、角色打印、CSV 查询
  GOLDEN_05_DATA.ERB          DATA/DATALIST/STRDATA + 固定种子 RNG 段
  GOLDEN_06_INPUT_AUTO.ERB    可全自动的输入语句（无人值守）
  GOLDEN_07_INPUT_MANUAL.ERB  需人工按键的输入语句（半人工）
  GOLDEN_08_IO.ERB            文本文件/存档/全局存档/角色存档（全部写 sav/）
  GOLDEN_09_MISC.ERB          SKIPDISP/NOSKIP/AWAIT/GETTIME/ASSERT/声音/TOOLTIP/RESETDATA/OUTPUTLOG
  GOLDEN_10_XMLMAP.ERB        XML/MAP/DataTable/变量与函数枚举
  GOLDEN_11_GVIEW.ERB         图形缓冲/精灵/CBG/窗口尺寸
eratw-android/tools/golden-coverage.py    覆盖率统计（对照 specs，可重复运行）
```

编码注意：所有 ERB 为纯 ASCII（无 BOM）。`emuera.config` 的 `DEFAULT ANSI ENCODING:CHINESE_HANS`（GBK）与 UTF-8 读取结果一致，避免编码成为 diff 噪声源。

## 2. 让原版引擎加载并运行（操作步骤 · 2026-10-18 A11b 勘误 + A11c 事件钩子定稿）

> **勘误（重要）**：旧版 §2 步骤 5/6 写的「在控制台输入 `@GOLDEN_ROOT` / `@GOLDEN_INPUT_MANUAL` 回车」**不可行**，
> 实机与源码双重证实（`reference/emuera-cs/`，行号为准）：
>
> 1. 控制台 `@X` → `EmueraConsole.doSystemCommand`（`MinorShift.Emuera.GameView/EmueraConsole.cs:1644` 剥掉 `@`）→ `DebugCommand`（`:2167`）；
> 2. 不以 `@`/`"`/`\` 开头的文本先 `LogicalLineParser.ParseLine`；失败即按**表达式**求值（`:2185-2190`，包成 `PRINTVL <expr>`），
>    因此 `GOLDEN_ROOT` 只会得到「无法解析的标识符」（表达式位能调用的只有 `#FUNCTION` **方法**）；
> 3. 解析成功的 `InstructionLine` 还要连过三道闸：`IsFlowContorol()` / `IsWaitInput()` / 非 `IsMethodSafe()` 任一命中即 CodeEE（`:2204-2212`，另有 `IsPartial()` `:2216`）；
>    另外 `genericTimer.Enabled`（计时等待中）时系统命令整体被拒（`:1627-1633`）；
> 4. 各候选的 flag 值（定义 `FunctionIdentifier.cs:3721 FLOW_CONTROL=1 / 3725 METHOD_SAFE=4`，ctor 合成 `:4200-4206`）：
>    CALL/TRYCALL/CALLFORM/TRYCALLFORM/TRYCCALL/JUMP 族 flag=33（`:3506`，注册 `:4041-4050`）含 FLOW_CONTROL → 控制台拒；
>    GOTO 族 flag=37（`:4057-4062`）→ 拒；CALLEVENT flag=3（`:3607`）→ 拒；TRYCALLLIST/TRYJUMPLIST/TRYGOTOLIST flag=147/211 → 拒；
>    RESTART flag=7 → 拒；CALLSHARP flag=38（`:1173`）只能调 C# 插件；
>    **唯一控制台可达的调用语句**是 CALLF/CALLFORMF/TRYCALLF/TRYCALLFORMF（flag=38，`:1114/:1245`），
>    但它们的目标必须是 `#FUNCTION` 方法（`IdentifierDictionary.cs:555-559`，普通 `@label` 直接抛 CallfNonMethodFunc）；
> 5. 而**方法体内只允许 METHOD_SAFE 语句**（`ErbLoader.cs:1032-1036`）：INPUT/WAIT 全家族（flag=24576/8192…）、
>    RETURN（flag=1，`:3979`）、RETURNFORM（`:3980`）、PRINT*W 后缀（`FunctionIdentifier.cs:158-171`，768 无 bit4）、
>    FUNC 块（`:4068`）、TOOLTIP_CUSTOM/SETFONT/FORMAT/IMG（`:4113-4117`）全部非 METHOD_SAFE，装载即报错。
>    把各节改造成方法 = 恰好删掉 golden 要覆盖的那批语句（契约 §2「不许砍语句」），**不可行**。
>
> **结论：golden 套件没有任何控制台可达路径（无解）。必须走游戏流程内调用点，或 VM E2E 直跑。**

> **勘误 2（A11c，2026-10-18）：不再需要往游戏本体插 `CALL GOLDEN_BOOT`。**
> 上一条勘误的出路改由**事件函数钩子**实现，游戏任何文件都不碰：`GOLDEN_00_ROOT.ERB` 自带
> `@EVENTFIRST` + `#PRI` 入口（`CALL GOLDEN_BOOT`），引擎开局自动调起。依据：
> `EVENTFIRST` 是引擎事件名（`IdentifierDictionary.cs:72-89`）；同名事件函数允许多定义且**四桶全执行**，
> 桶序 `#ONLY(0)→#PRI(1)→普通(2)→#LATER(3)`、同桶按装载序（`LabelDictionary.cs:70-105,185-239`、
> `CalledFunction.cs:228-243`、`specs/loader.md` 附录 D 规则 2/D.4）；`#PRI` 仅事件函数可用、在
> `LogicalLineParser.cs:81-107` 解析。本作 `@EVENTFIRST` 只有 2 个普通桶处理器（`SYSTEM.ERB:8`、
> `DLC/音乐补丁/音乐播放.ERB:81`，见 D.4 表），本套件用 `#PRI` 排在它们**之前**。
> 为什么不用 `#LATER`：`SYSTEM.ERB:8` 末尾是 `BEGIN SHOP`，而 `ProcessState.Begin()` 会清空整个调用栈
> （`ProcessState.cs:219-271`，其中 `functionList.Clear()`），普通桶之后的桶永远轮不到；
> 为什么不用 `@EVENTLOAD`：它**每次读档都触发**（`Process.cs:2674`），套件含 DELALLCHARA/SAVEDATA，
> 且 `#LATER` 桶内已有 5 个本作处理器按装载序排列（D.4 注），无法证明排到最后，守卫又是进程级的（读档=新进程即复位）。
> 事件派发链：标题页输入 `0` 开新局 → `endOpenning`（`Process.cs:2167-2180`，引擎在此建 0 号角色）→
> `beginFirst()`（`:2201`）→ `callFunction("EVENTFIRST", force:true)`（`:2207`）。
> 幂等：`@GOLDEN_BOOT` 的 `#DIM GOLDEN_BOOT_DONE` 私有变量守卫保留（重复派发直接 RETURN）。
> 连带修正：`GOLDEN_03` 的 CALLEVENT 用例目标从 `EventFirst` 改为 `EVENTEND`（本作零处理器的事件，
> D.4 表），因为在钩子运行期间 `CALLEVENT EVENTFIRST` 会再次派发 `SYSTEM.ERB:8` 的 `BEGIN SHOP`，同样清空调用栈杀死整套。

现行步骤（A11c 定稿，全程**只往游戏 ERB/ 添加 GOLDEN_*.ERB，不改游戏任何既有文件**）：

1. **复制脚本**：把 `fixtures/golden/` 下全部 `GOLDEN_*.ERB` 复制到游戏目录 `eratw-画蛇添足版eratw4.980/ERB/`（**必须放 ERB 根目录**，保证启动即全量装载、钩子在 `@EVENTFIRST` 派发前已注册；`SORT FILENAMES:YES` 下 `GOLDEN_00_ROOT.ERB` 与 `SYSTEM.ERB` 同层，桶序由 `#PRI` 机制保证、与文件名无关）。
2. **lazyloadingfiles.dat**：~~旧版本节称"磁盘新文件与 `.dat` 缓存对比，不在缓存中的文件自动进入 `ChangedFiles` → 启动时增量重建"，**已证伪**~~。实测（2026-10-18，A11b/组长）：`lazyloading.cfg` 只声明个人口上目录，索引 2,154 行**全部**落在这些目录内（剔除 `IsMethod/IsEvent`，`Process.cs:858-870`）；删除 `lazyloadingfiles.dat`/`lazyloading.dat` 后引擎全量重建，重建产物与备份**逐字节相同**，且**不含**根目录新函数的任何名字——根目录文件根本不走懒加载账本，它们是启动全量扫描装载的（`specs/loader.md` C.8/C.9）。结论不变但依据更正：**该文件无需也不应手工重建**，`索引生成.bat` 也不需要跑。
3. **emuera.config**：无需改动（根目录全量装载与启动无关于此配置；`ALLOW DEBUG COMMANDS` 与启动无关，见上方勘误 1）。
4. **启动**：双击 `Emuera_LazyLoadingV3.0_x64.exe`（不要用 DEBUG_MODE.bat，避免调试窗口混入截图/日志路径）。
5. **执行（引擎自动，无需插任何调用点）**：标题页按提示选**开始新游戏**（`Process.cs:2168` 判据 `systemResult == 0`，实际键入序号以本作标题菜单显示为准）→ 引擎经 `Process.cs:2167-2207` 派发 `@EVENTFIRST`，本套件 `#PRI` 钩子在全部普通桶处理器之前自动跑完 `GOLDEN_01..12`，控制台末尾出现 `=== END ===`。
   半人工节（GOLDEN_07）另起一次采集：临时把 `GOLDEN_00_ROOT.ERB` 钩子体内的 `CALL GOLDEN_BOOT` 改为 `CALL GOLDEN_BOOT_MANUAL`（**改 fixture 后重新复制到 ERB/**，仍不碰游戏文件），开局自动进入，按 §4.1 操作单按键。
   **残余风险（必须知情）**：
   a) 钩子跑完后本作的普通桶 `@EVENTFIRST`（NEWGAME 等）在被套件清空的角色世界上继续跑，`=== END ===` 之后可能出现游戏侧报错行——均在 golden 区间外，§5 按标记截取即可；
   b) **采集完立即把 `GOLDEN_*.ERB` 移出 `ERB/`**：文件在场时**每一次新开局的 `@EVENTFIRST` 都会跑整套并删角色**（含中途训练存档恢复等间接触发路径，`Process.cs:2204` 的 `isCTrain` 分支只是部分缓解），`#DIM` 守卫仅在同一进程内幂等；
   c) 采完直接退出、**不要存档**（套件写 `sav/GOLDEN_*` 与槽 88）。
6. **执行（免碰游戏的替代路径，保持不变）**：Kotlin 侧 E2E（A6 `E2EGoldenTest`）与 `tools/golden_pc_oracle` 直接以 `GOLDEN_ROOT` 为根入口执行，plain-label `CALL` 语义完整；PC 基准采集完成后按 §5 归一化对拍。

## 3. 导出控制台日志

**主路径（run5 定稿）：套件内 `OUTPUTLOG "GOLDEN_ROOT_LOG.txt"`。** `@GOLDEN_ROOT` 在打印 `=== END ===` 之后立刻执行该语句，把完整控制台回放写入 exe 目录（`OutputSystemLog` → `GetLog(false)`，同下述控制台路径）。这是**必须**的时点：套件返回后游戏自身 `@EVENTFIRST` 走到 `BEGIN SHOP` 会清空控制台日志，之后再从控制台 `@OUTPUTLOG` 只能拿到游戏自己的界面行（run4 实测 `emuera.log` 仅 498 字节，只剩"★请选择游戏模式★"）。

**备用路径：控制台输入 `@OUTPUTLOG` 回车（必须在离开套件前、日志未被 BEGIN 清空前）。** 取证（`reference/emuera-cs/MinorShift.Emuera.GameView/EmueraConsole.cs`）：

- `@X` 行进入 `doSystemCommand`（`:1625`；`:1644` 剥 `@`），`OUTPUT`/`OUTPUTLOG` 分支**不经过** `DebugCommand` 语句闸（`:1654-1656` 直接 `OutputSystemLog(Program.ExeDir + "emuera.log")`）；
- `OutputSystemLog`（`:3484-3505`）→ `outputLog(filename, hideInfo:false)`（`:3448` → `GetLog(false)`）＝ **完整回放文本**（含环境/版本头 + 全部显示行），落盘 exe 目录 `emuera.log`；路径受限 exe 目录及其子目录（`:3490-3494` StartsWith(ExeDir) 校验）。
- 前提：仅两处在"进程执行中"拒收系统命令（`:1627-1633` 计时等待、`:1634-1639` `IsInProcess`）；套件 `=== END ===` 后游戏回到等待输入态，直接键入 `@OUTPUTLOG` 即可。
- GOLDEN_09 里脚本内 `OUTPUTLOG "GOLDEN_LOG.txt"` 是中途快照，不含 GOLDEN_10/11/12 节与 `=== END ===`，仅作冗余；完整基准以 `GOLDEN_ROOT_LOG.txt` 为准。

备用路径（菜单导出）：主窗口「文件 → 保存日志 / 输出日志」→ 另存为（同一 `OutputSystemLog` 代码路径，仅文件名自选）。

**确切操作**：§2 步骤 5 跑完（打印 `=== END ===` 且套件已自动写出 `GOLDEN_ROOT_LOG.txt`）→ 取 exe 目录 `GOLDEN_ROOT_LOG.txt`。日志会混入游戏自身流程的行（标题页、钩子后的开局输出），截取基准时以 `=== GOLDEN RUN START ===` 到 `=== END ===` 为界（§5 的按节定位同样适用）。重命名保存到 `eratw-android/fixtures/golden/baseline/pc-golden-root.txt`。

## 4. 输入重放调研结论与操作单

### 4.0 结论摘要

1. **没有命令行级的输入重放**。`Program.cs` 的 Main 只解析 `--ExeDir`、`-Debug`、`-GenLang` 和位置参数（分析模式），无「回放输入文件」能力。
2. **键盘宏（USE KEYBOARD MACROS）只是文本预填**。宏存于 exe 目录 `macro.txt`（格式见下），按 F1–F12 时把宏内容填入输入框（`MainWindow.cs`：`richTextBox1.Text = KeyMacro.GetMacro(...)`），**不会自动回车提交**。宏文件格式（简体中文界面）：`宏快捷键F1:内容`，`G1:宏快捷键F1:内容`（组）。
3. **ERB 内可全自动化大部分输入语句**（已用于 GOLDEN_06）：
   - `SKIPLOG 1` 直接置位 `Console.MesSkip`；INPUT 系列第 3 参数（CanSkip）非空且 MesSkip=true 时**立即取默认值、不等待**（`FunctionIdentifier.cs` INPUT/INPUTS/BINPUT 实现中 `CanSkip != null && MesSkip` 分支）；
   - TINPUT/TONEINPUT 超时自动取默认值（关闭剩余时间显示即无输出）；
   - INPUTMOUSEKEY 带超时参数时超时返回 0；
   - PRINT 输出不受影响（抑制 PRINT 的是 SKIPDISP，另一个独立标志）；
   - 注意 `MesSkip` 在每次**提交输入后**被复位（`EmueraConsole.cs` 输入循环末尾 `MesSkip = false`），所以每个输入语句前都要重新 `SKIPLOG 1`（GOLDEN_06 已如此编排）。
4. **必须人工的**（GOLDEN_07）：WAIT/FORCEWAIT（FORCEWAIT 明确禁止消息跳过中断）、WAITANYKEY、TWAIT 的第一段按键等待、ONEINPUT/ONEINPUTS（单键输入 NeedValue=true）、INPUTANY、不带 CanSkip 的 INPUT/INPUTS、PRINTDATAW/PRINTDATAKW/PRINTDATADW（W = 等待按键）。这类语句的 golden 属于**半人工采集**：按固定操作单执行即可确定性复现。

### 4.1 GOLDEN_INPUT_MANUAL 操作单（采集者照做）

| 提示出现时 | 操作 |
|---|---|
| `OP: press ENTER.`（共 8 处） | 按一次回车 |
| `OP: type 123 then ENTER.` | 输入 `123` 回车 |
| `OP: type HELLO then ENTER.` | 输入 `HELLO` 回车 |
| `OP: press any digit key.` | 按数字键 `7` |
| `OP: press any letter key.` | 按字母键 `A`（ONEINPUTS 取首字符） |

（可选加速：在 exe 目录 `macro.txt` 预置 `宏快捷键F1:` 行使 F1 预填文本，但回车仍需手按。）

## 5. diff 命令示例

```bash
# 1) 归一化（剥离不确定性）后再 diff：
#    - DRAWLINE/CUSTOMDRAWLINE/DRAWLINEFORM 行按窗口宽度填充 → 把连续重复字符行折叠为标记
#    - GOLDEN_09 的 GETTIME 行只保留长度校验结果，无时间值（脚本内已保证）
#    - 去除 \r（Windows 换行）
sed -e 's/\r$//' \
    -e 's/^\(=\{10,\}\)$/LINE(=)/' \
    -e 's/^\(-\{10,\}\)$/LINE(-)/' \
    baseline/pc-golden-root.txt  > /tmp/a.norm
# 对 Kotlin VM 侧输出做同样归一化后：
diff -u /tmp/a.norm /tmp/b.norm

# 2) 快速分节定位：
grep -n "=== SECTION" /tmp/a.norm
```

颜色/字体样式（SETCOLOR、FONTBOLD…）不会进入文本日志，diff 文本时无需剥离；若以后做**截图级**对比，才需要处理颜色（runbook 层面：SETCOLOR 的数值已通过 `GETCOLOR` 回读进入文本，文本 diff 已覆盖语义）。

## 6. 已知的不确定性与排除项

| 类别 | 说明 | 处理 |
|---|---|---|
| 窗口宽度 | DRAWLINE/CUSTOMDRAWLINE/DRAWLINEFORM、CLIENTWIDTH/CLIENTHEIGHT、PRINTC 折行时机依赖窗口宽度（config 固定 1800 宽；高度可变） | 采集时**不要拉伸窗口**；diff 前按 §5 归一化分隔线行 |
| 时间 | GETTIME 值随采集时刻变化 | 脚本内只输出 `STRLENSU(RESULT)==14` 与 `ISNUMERIC(RESULTS)` 的判断结果，值本身不入日志 |
| 随机 | GOLDEN_05 的 RNG 段使用 `RANDOMIZE 固定种子` | **该 golden 只对实现完全相同 RNG 序列的引擎有效**（对照 `specs/rng.md`）；通用 L2 diff 应剔除 `SECTION:RNG-*` 段 |
| 字体/文化 | GETFONT/CHKFONT 返回值依赖系统字体安装（config 指定黑体）；数字格式化依赖 .NET culture | 同机采集基准与复验机需安装黑体；Kotlin 侧需复刻同名行为 |
| 排除的语句/函数 | `ADDSPCHARA`(SP 未启用会抛错)、`CALLSHARP`(需插件)、`FORCE_BEGIN`/`BEGIN`(切换状态机)、`SAVEGAME/LOADGAME/LOADGAME-UI`(进入 UI 流程)、`LOADDATA`(清空调用栈中断流程)、`SAVEVAR/LOADVAR/REF/REFBYNAME`(NotImpl 占位)、`QUIT` 系列(退出程序)、`UPDATECHECK`(联网弹窗)、`CALLTRAIN/DOTRAIN`(训练状态机)、`UPCHECK/CUPCHECK`(依赖 PALAM 结算输出形状)、`ADDDEFCHARA`(限 SYSTEM_TITLE)、`PRINT_IMG/PRINT_RECT/PRINT_SPACE/SETBGIMAGE/REMOVEBGIMAGE/CLEARBGIMAGE/HTML_PRINT_ISLAND/CLEAR`(渲染节点不入文本日志)、`TOOLTIP_*` 已测调用但外观不入日志、`CLEARTEXTBOX/SETTEXTBOX/MOVETEXTBOX/RESUMETEXTBOX/GETTEXTBOX`(输入框 UI 状态)、`GETCONFIG/GETCONFIGS`(键名格式待复核)、`GETMILLISECOND/GETSECOND/GETTIMES/GETMEMORYUSAGE`(值随时间/内存变化)、`MOUSEX/MOUSEY/MOUSEB/MOUSESKIP/GETKEY`(依赖实时鼠标键盘；GETKEY 的未按键=0 已在 07 覆盖)、`GETSPCHARA`(SP 未启用抛错)、`FLOWINPUT/FLOWINPUTS`(改写进程输入状态)、`GCREATEFROMFILE`(需图片资源)、`GGETTEXTSIZE`(参数 medium)、`SOUNDCONTROL/BGMCONTROL/GETSOUNDORBGMINFO`(音频内部状态)、`GETLINESTR`(medium) | 均在脚本头注释与覆盖率脚本中留痕；待相关规约补齐后可增量加入 |
| 字符串赋值语义 | 字符串变量的 `=` 是 **FORM 原文赋值**：右侧引号是字面字符、`{}`/`%var%`/三连符号会展开（`ArgumentParser.cs:1024-1040` 调 `AnalyseFormattedString`）；表达式赋值必须用 `'=`（`LexicalAnalyzer.cs:566-571` + `ArgumentParser.cs:1042-1069`）。fixtures 里凡是要"求值"的字符串赋值一律 `'=`（GOLDEN_01 的 SB/REPARR、02 的 HTM/HSUB、08 的 FTXT/GLOBALS/CSTR、09 的 WINDOW_TITLE、10 的 MAPSTR/XDATA/PV:0）；GOLDEN_01 `SECTION:BASIC-FORM-ASSIGN` 专门保留 `=` 的展开语义作对照 |
| 幂等性 | GOLDEN_08 写 sav/ 的文件名全部固定（`GOLDEN_TEST.txt`、槽 88、`GOLDEN_CHARA.dat`、`GOLDEN_LOG.txt`），重复采集覆盖写，ENUMFILES 结果稳定 | 采集前可清空 sav/ 下 GOLDEN_* 文件一次 |
| OUTPUTLOG | 主采集 = 套件内 `OUTPUTLOG "GOLDEN_ROOT_LOG.txt"`（`=== END ===` 后立即执行，完整回放，§3）；游戏 `BEGIN` 会清空控制台日志，控制台 `@OUTPUTLOG` 在返回游戏后已失效（run4 实证）；GOLDEN_09 的 `GOLDEN_LOG.txt` 为中途快照，仅冗余 | 采集后把 `GOLDEN_ROOT_LOG.txt` 移入 baseline/ 并删除游戏目录的 GOLDEN_LOG.txt/GOLDEN_ROOT_LOG.txt/emuera.log 残留 |
| 定时/实时输入 | `TONEINPUT*` 的 500ms 超时窗口内若有按键落入，结果会从默认值变为输入值（run3 `TONEINPUTS=TS` vs run4 空）；`INPUTMOUSEKEY`/`ISTIMEOUT` 同理 | 自动套件里 `TONEINPUT*` 一律带 `canskip`（mouse=0 必须显式给出，跳过路径无条件解引用 Mouse，FunctionIdentifier.cs:1040/1088）；`INPUTMOUSEKEY`+`ISTIMEOUT` 移到手工套件，不进自动基准 |

## 7. 覆盖率现状（golden-coverage.py 输出）

- isa.yaml 语句名（含 aliases 展开共 303 个）：**命中 300（99.0%）**，未命中 3 条（ADDSPCHARA / CALLSHARP / FORCE_BEGIN，理由见 §6）。
- builtin-functions.yaml（271 个）：**命中 252（93.0%）**，未命中 19 条（全部属于 §6 表中的排除项）。
- 13 个 ERB 文件共 1586 行（含 GOLDEN_12_SYMBOLS，采集符号解析规则用；A11c 加钩子/注释后计数）。

统计可复现：`python eratw-android/tools/golden-coverage.py`

## 8. GOLDEN_12 的验证点与预期输出

对应 `fixtures/golden/GOLDEN_12_SYMBOLS.ERB`（73 行，纯 ASCII），验证 `specs/loader.md` 附录 D 的符号表规则：
同名标签的**后装载者生效**（含同文件内重复）与**事件函数四桶执行序**。

### 8.1 采集方式

- 随套件自动执行：钩子（§2 勘误 2 的 `@EVENTFIRST`/`#PRI`）跑 `@GOLDEN_ROOT`，其末尾已加 `CALL GOLDEN_SYMBOLS`；
- 局部复验时把 `CALL GOLDEN_SYMBOLS` 插到钩子体内（临时改 `GOLDEN_00_ROOT.ERB` 这个 fixture，控制台无法直接启动 plain label，见 §2 勘误 1）。

### 8.2 预期输出（追加在 GOLDEN_GVIEW 段之后、`=== END ===` 之前）

```
=== SECTION:DUP-METHOD ===
DUP-METHOD=2
=== SECTION:DUP-PLAIN ===
DUP-PLAIN=3
=== SECTION:EVENT-BUCKET ===
EVENT-DEFS=3 EXPECT-ORDER=PRI,NORMAL,LATER
EVENT-BUCKET-REMARK=collect one turn end to see markers
```

判定：

| 行 | 期望 | 说明 |
|---|---|---|
| `DUP-METHOD=2` | 2 | 同一文件内 `@G12_FUNC` 定义两次（`#FUNCTION`，分别 `RETURNF 1` / `RETURNF 2`），最后一条生效 → 2。若为 1 则说明实现成了"先装载者胜"（旧的错误规约） |
| `DUP-PLAIN=3` | 3 | 同一文件内 `@G12_PLAIN` 定义三次（`RESULT:2 = 1/2/3` 后 `RETURN`），用 `CALL G12_PLAIN` 调入 → 最后一条生效 → 3。必须观测 `RESULT:2`：plain `RETURN` 会把 `RESULT:0` 清零（FunctionIdentifier.cs:3252-3254） |

### 8.3 加载期告警（3 行，非运行输出）

同一文件内重复定义**不中断装载**，但原版在装载阶段对每个被遮蔽的**非事件**定义打一条 level-1 告警
（`ErbLoader.cs:443-451`，`WarnNormalFunctionOverloading` 本游戏为 `YES`），共 3 条：

- `函数"@G12_FUNC"已经被定义过(GOLDEN_12_SYMBOLS.ERB的39行)` —— 第 43 行的第二个 `@G12_FUNC`
- `函数"@G12_PLAIN"已经被定义过(GOLDEN_12_SYMBOLS.ERB的48行)` —— 第 52 行的第二个 `@G12_PLAIN`
- `函数"@G12_PLAIN"已经被定义过(GOLDEN_12_SYMBOLS.ERB的52行)` —— 第 56 行的第三个 `@G12_PLAIN`

说明与处理：

1. 文案随界面语言变化（本游戏 `EMUERA INTERFACE LANGUAGE:简体中文`），因此**不属于 ASCII 输出**。
2. 事件函数重复（`@EVENTTURNEND` ×3）**不产生告警**（`ErbLoader.cs:443` 的条件是 `!IsEvent`）。
3. `diff` 前建议二选一：从 `=== GOLDEN RUN START ===` 起截取；或剔除匹配 `^函数"@` 的行。

### 8.4 事件桶序（需真实回合采集）

`GOLDEN_12` 在同一文件内注册了 3 个 `@EVENTTURNEND`，**定义顺序是 NORMAL → PRI → LATER**，
而执行顺序应为 **PRI → NORMAL → LATER**（`LabelDictionary.cs:70-105,185-239` 分桶 + `CalledFunction.cs:228-243` 桶序）：

- 采集：在游戏中让一个回合结束（睡觉/跨日），控制台应依次出现
  `EVENT-BUCKET-PRI` → `EVENT-BUCKET-NORMAL` → `EVENT-BUCKET-LATER`；
- 位标记：三段分别对 `RESULT:1` 置 1/2/4，回合结束后 `RESULT:1 & 7` 应为 7（若只有 1 或 3 说明漏执行）；
- 该段**不能**用 `CALLEVENT EVENTTURNEND` 观察：本游戏 `ALLOW CALL ON EVENT FUNCTIONS:NO`，
  事件函数不可 CALL（`CalledFunction.cs:100-103`）；`CALLEVENT` 则要求先进入事件解析流程。
- 采集完成后建议把 `GOLDEN_12_SYMBOLS.ERB` 移出 ERB 目录：它会让**每个回合结束**都打印这 3 行，
  并污染其它 golden 的运行日志。

### 8.5 与编译期断言的关系（已自动化）

本节断言已固化为单元测试，**不再依赖人工看 CLI 输出**：

- 测试：`tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/SymbolsTest.kt`
- 命令：`gradle :tools:erbcc:test`（随 `:tools:erbcc:build` 一并执行）
- 覆盖：`fixtures/golden` → 重名 4（非事件 2 / 事件 2）、`@G12_FUNC` 生效行 43、`@G12_PLAIN` 生效行 56、
  `@EVENTTURNEND` 执行序 65→61→70、标签名与系统语句零冲突；另有合成用例覆盖形参个数 / `#LOCALSIZE` /
  `#LOCALSSIZE` 的解析（含带默认值、字符串内含逗号、空括号、无括号）。
- 全量语料冒烟：同一文件内的 `fullCorpusSmoke`，语料路径由环境变量 `ERATW_CORPUS`（或系统属性
  `-Deratw.corpus`）提供；缺失时自动 skip（CI 上没有语料）：
  `ERATW_CORPUS=<游戏>/ERB gradle :tools:erbcc:test`
  断言 6 组事件重名的定义数与 3 个抽样函数的 `argCount/localSize/localsSize`。

调试时仍可单跑 CLI：`erbcc symbols --corpus fixtures/golden`（期望同上），
`--collator-check` 对该目录应报 `PASS`（无定义集合翻转）。
