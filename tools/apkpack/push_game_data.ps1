<#
.SYNOPSIS
    开发调试通道（-Dev）：把游戏数据用 adb 推到设备上的游戏根，绕开 APK 随包。

.DESCRIPTION
    **正式形态不需要本脚本。** M10-PKG400 之后，装机即玩靠的是随包 APK：
    `app/build.gradle.kts` 的 `stageGameAssets` 默认把 erbc.v1 + CSV/resources/sound +
    emuera.config 全打进 assets（一律 STORED），首启由 platform/.../GameAssetInstaller
    自己解到游戏根 —— 装完直接玩，不接 adb、不推文件。

    本脚本只剩一个身份：**开发调试通道**。用它的时候必须显式加 `-Dev`，作用是
    「我知道我在绕过随包形态」。三种真需要它的场景：
      1. 换一份 `program.erbc` 做装载/性能实验，不想重打 700 MB 的包；
      2. 验证外部根优先（GameStorage.selectRoot）与 adb 直推链路本身；
      3. 包还没带某类资源（比如只跑了 -PeratwSkipAssets 的小包）时的临时喂数据。

    目标路径与 GameStorage.externalRoot 同源（platform/.../storage/GameStorage.kt）：
        Android/data/<包名>/files/eratw/
    在设备上就是 /sdcard/Android/data/<包名>/files/eratw/（应用专属外部目录，不需要任何权限，
    也不需要 run-as）。**外部根一旦「像游戏根」就优先于内部根**，所以推过数据的设备上
    随包解包会落到外部根、并覆盖不了你推的那份 —— 做完实验要清掉这个目录再验随包形态。

    默认推 5 项：erbc.v1（program.erbc + program.meta + manifest.txt）、CSV、resources、
    sound、emuera.config。少任何一项都不算「能玩」：
      - erbc.v1       程序（328 MB 容器 + 47 MB 侧车），GameHost 缺它直接停在失败屏；
      - emuera.config 原版标志文件，GameStorage 的「像游戏根」判据之一；
      - CSV           常量/变量定义，开局读取；
      - resources     图片（312 MiB，webp），缺了画面是空白；
      - sound         音频，缺了只是没声音。

    幂等：默认按「远端文件字节数 == 本地字节数」跳过已推项；要强制重推加 -Force。

.PARAMETER Dev
    开发调试通道的显式声明（推荐每次都带上）。不带也能跑，但会先打一行提醒：
    「正式形态是随包 APK，本脚本只是调试用的旁路」。

.PARAMETER Source
    本地游戏根（**含** erbc.v1 子目录）。缺省取 $env:ERATW_GAMEDIR（AGENTS 第 5 节会导出）。

.PARAMETER Package
    应用包名，缺省 com.eratw.port.android。

.PARAMETER Serial
    多设备时指定序列号（adb devices 的第一列）。

.PARAMETER Include
    只推这几项。缺省 erbc.v1,CSV,resources,sound,emuera.config。

.PARAMETER PrintOnly
    只打印可复制的 adb 命令（含屏上引导文案该用的那几行），不碰设备。

.PARAMETER Force
    不跳过已存在项，整份重推。

.PARAMETER InstallApk
    推完之后 adb install -r 这个 APK（可选）。

.PARAMETER Adb
    adb 可执行文件路径。缺省依次找 $env:ADB、PATH 里的 adb、local.properties 的 sdk.dir。

.EXAMPLE
    # 先看要敲什么命令（不连设备）
    powershell -ExecutionPolicy Bypass -File tools/apkpack/push_game_data.ps1 -Dev -Source D:\eratw-build\erbc-root -PrintOnly

.EXAMPLE
    # 真推 + 装包（调试口径）
    powershell -ExecutionPolicy Bypass -File tools/apkpack/push_game_data.ps1 `
        -Dev -Source D:\eratw-build\erbc-root -InstallApk app\build\outputs\apk\debug\app-debug.apk

.EXAMPLE
    # 装机即玩的正式包（对照：不需要本脚本，也不需要在设备上推任何东西）
    .\gradlew --configure-on-demand :app:assembleDebug -PeratwRequireComplete=1
    python tools/apkpack/inspect_apk.py --require-bundled
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Dev,
    [string]$Source = $env:ERATW_GAMEDIR,
    [string]$Package = 'com.eratw.port.android',
    [string]$Serial,
    [string[]]$Include = @('erbc.v1', 'CSV', 'resources', 'sound', 'emuera.config'),
    [switch]$PrintOnly,
    [switch]$Force,
    [string]$InstallApk,
    [string]$Adb
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $Dev) {
    Write-Host '提醒：正式形态是**随包 APK**（stageGameAssets 默认开，首启自行解包），装机即玩不需要推数据。'
    Write-Host '本脚本是开发调试通道；确认是在做调试就加 -Dev（见 .SYNOPSIS）。'
}

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # eratw-android
$RemoteRoot = "/sdcard/Android/data/$Package/files/eratw"

# ---------------------------------------------------------------------------
# adb 定位
# ---------------------------------------------------------------------------
function Resolve-Adb {
    param([string]$Explicit)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "指定的 adb 不存在：$Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    if ($env:ADB -and (Test-Path -LiteralPath $env:ADB)) { return $env:ADB }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $props = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Select-String -LiteralPath $props -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($line) {
            $raw = ($line.Line -replace '^\s*sdk\.dir\s*=', '').Trim()
            # Properties 转义：\: 还原成 :，\\ 还原成 \
            $sdk = $raw.Replace('\\', [string][char]92).Replace('\:', ':')
            foreach ($exe in @('adb.exe', 'adb')) {
                $candidate = Join-Path (Join-Path $sdk 'platform-tools') $exe
                if (Test-Path -LiteralPath $candidate) { return $candidate }
            }
        }
    }
    throw '找不到 adb：设 $env:ADB，或用 -Adb 指定，或在 local.properties 里配 sdk.dir'
}

$adbPath = Resolve-Adb -Explicit $Adb
# 设备选择在下面「设备与安装状态」一节定（多设备时自动挑唯一在线的，或要求 -Serial），
# 这里先留空；Invoke-AdbShell 等函数读的是脚本作用域的 $prefix，所以赋值必须在调用之前。
$prefix = @()
if ($InstallApk -and (Test-Path -LiteralPath $InstallApk)) {
    $InstallApk = (Resolve-Path -LiteralPath $InstallApk).Path
}

function Invoke-AdbShell {
    param([string]$Command, [switch]$AllowFail)
    $out = & $adbPath @prefix shell $Command 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        throw "adb shell 失败（退出码 $LASTEXITCODE）：$Command`n$out"
    }
    return $out
}

function Get-RemoteSize {
    param([string]$DevPath)
    $out = Invoke-AdbShell -Command "wc -c < '$DevPath'" -AllowFail
    if ($LASTEXITCODE -ne 0 -or -not $out) { return $null }
    $text = ($out | Out-String)
    $m = [regex]::Match($text, '(\d+)')
    if (-not $m.Success) { return $null }
    return [int64]$m.Groups[1].Value
}

# ---------------------------------------------------------------------------
# 本地源检查
# ---------------------------------------------------------------------------
if (-not $Source) {
    throw '没有本地游戏根：用 -Source 指定，或先按 AGENTS 第 5 节导出 $env:ERATW_GAMEDIR'
}
if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
    throw "本地游戏根不是目录：$Source"
}
$Source = (Resolve-Path -LiteralPath $Source).Path

$items = foreach ($name in $Include) {
    $local = Join-Path $Source $name
    if (-not (Test-Path -LiteralPath $local)) {
        Write-Warning "跳过 $name：本地缺 $local"
        continue
    }
    $isDir = Test-Path -LiteralPath $local -PathType Container
    $bytes = if ($isDir) {
        $sum = [int64]0
        Get-ChildItem -LiteralPath $local -Recurse -File | ForEach-Object { $sum += $_.Length }
        $sum
    } else {
        [int64](Get-Item -LiteralPath $local).Length
    }
    [pscustomobject]@{
        Name   = $name
        Local  = $local
        Remote = "$RemoteRoot/$name"
        IsDir  = $isDir
        Bytes  = $bytes
    }
}
if (-not $items) { throw '没有任何可推项：检查 -Source 下的 erbc.v1 / CSV / resources' }

# 关键文件先卡一道：缺 program.erbc 或 program.meta 时游戏必然停在失败屏。
$art = Join-Path $Source 'erbc.v1'
foreach ($f in @('program.erbc', 'program.meta')) {
    $p = Join-Path $art $f
    if ((Test-Path -LiteralPath $art) -and -not (Test-Path -LiteralPath $p)) {
        throw "erbc.v1 下缺 $f：$art（先跑 :tools:erbcc 的 compile）"
    }
}

# ---------------------------------------------------------------------------
# -PrintOnly：只出命令（屏上引导文案与这里逐字同源）
# ---------------------------------------------------------------------------
function Quote-ForShell {
    param([string]$Text)
    if ($Text -match '[\s()]') { return '"' + $Text + '"' }
    return $Text
}

if ($PrintOnly) {
    Write-Output '# --- 可复制的 adb 命令（目标路径 = GameStorage.externalRoot）---'
    Write-Output ("{0} shell mkdir -p '{1}'" -f (Quote-ForShell $adbPath), $RemoteRoot)
    foreach ($it in $items) {
        if ($it.IsDir) {
            $from = $it.Local.TrimEnd('\') + '\.'
            Write-Output ("{0} push {1} '{2}/'" -f (Quote-ForShell $adbPath), (Quote-ForShell $from), $it.Remote)
        } else {
            Write-Output ("{0} push {1} '{2}'" -f (Quote-ForShell $adbPath), (Quote-ForShell $it.Local), $it.Remote)
        }
    }
    if ($InstallApk) {
        Write-Output ("{0} install -r {1}" -f (Quote-ForShell $adbPath), (Quote-ForShell $InstallApk))
    }
    Write-Output ("{0} shell am force-stop {1}" -f (Quote-ForShell $adbPath), $Package)
    Write-Output ''
    Write-Output '# --- 屏上引导文案（首启失败屏该显示这几行）---'
    Write-Output ('目标目录: ' + $RemoteRoot + '/erbc.v1')
    Write-Output ('需要两份文件: program.erbc + program.meta（缺一不可）')
    foreach ($it in $items) {
        if ($it.IsDir) {
            Write-Output (('  adb push "{0}\." "{1}/"' -f $it.Local, $it.Remote))
        } else {
            Write-Output (('  adb push "{0}" "{1}"' -f $it.Local, $it.Remote))
        }
    }
    exit 0
}

# ---------------------------------------------------------------------------
# 设备与安装状态
# ---------------------------------------------------------------------------
$devicesRaw = (& $adbPath devices 2>&1) | Out-String
$online = @([regex]::Matches($devicesRaw, '(?m)^(\S+)\s+device\s*$') | ForEach-Object { $_.Groups[1].Value })
if ($online.Count -eq 0) {
    throw "没有在线设备：`n$devicesRaw`n（adb devices 要看到 'device' 状态；unauthorized 先在手机上点允许）"
}
if (-not $Serial -and $online.Count -eq 1) {
    # adb 只要列表里多于一台（哪怕另一台 offline）就会报 more than one device/emulator，
    # 所以唯一在线的那台要显式 -s 上去。
    $Serial = $online[0]
    Write-Host "自动选中唯一在线设备：$Serial"
} elseif (-not $Serial -and $online.Count -gt 1) {
    throw ("有 $($online.Count) 台设备在线，用 -Serial 指定其一：`n  " + ($online -join "`n  "))
}
if ($Serial) { $prefix = @('-s', $Serial) }

$pmPath = (& $adbPath @prefix shell "pm path $Package" 2>&1) | Out-String
if ($pmPath -match 'package:') {
    Write-Host "已安装：$Package"
} else {
    Write-Warning "$Package 未安装，推完数据后要 -InstallApk 或手动 adb install -r"
}

Write-Host "[1/3] 目标目录 $RemoteRoot"
Invoke-AdbShell -Command "mkdir -p '$RemoteRoot'" | Out-Null

Write-Host '[2/3] 推送'
$rows = @()
foreach ($it in $items) {
    if ($it.IsDir) {
        $probe = if ($it.Name -eq 'erbc.v1') { "$($it.Remote)/program.meta" } else { $null }
        $skip = $false
        if (-not $Force -and $probe) {
            $remoteBytes = Get-RemoteSize -DevPath $probe
            $localBytes = (Get-Item -LiteralPath (Join-Path $it.Local 'program.meta')).Length
            if ($remoteBytes -eq $localBytes) { $skip = $true }
        }
        if ($skip) {
            Write-Host "  跳过 $($it.Name)（远端 program.meta 字节数已一致）"
        } else {
            Write-Host "  推送 $($it.Name)（$([math]::Round($it.Bytes / 1MB, 1)) MiB）"
            $from = $it.Local.TrimEnd('\') + '\.'
            & $adbPath @prefix push $from "$($it.Remote)/"
            if ($LASTEXITCODE -ne 0) { throw "推送失败：$($it.Name)" }
        }
    } else {
        $skip = $false
        if (-not $Force) {
            $remoteBytes = Get-RemoteSize -DevPath $it.Remote
            if ($remoteBytes -eq (Get-Item -LiteralPath $it.Local).Length) { $skip = $true }
        }
        if ($skip) {
            Write-Host "  跳过 $($it.Name)（远端字节数已一致）"
        } else {
            Write-Host "  推送 $($it.Name)"
            & $adbPath @prefix push $it.Local $it.Remote
            if ($LASTEXITCODE -ne 0) { throw "推送失败：$($it.Name)" }
        }
    }
}

Write-Host '[3/3] 校验'
$bad = 0
$checks = @(
    @{ Name = 'erbc.v1/program.erbc'; Local = (Join-Path $Source 'erbc.v1\program.erbc') },
    @{ Name = 'erbc.v1/program.meta'; Local = (Join-Path $Source 'erbc.v1\program.meta') },
    @{ Name = 'emuera.config'; Local = (Join-Path $Source 'emuera.config') }
)
foreach ($c in $checks) {
    if (-not (Test-Path -LiteralPath $c.Local -PathType Leaf)) { continue }
    $localBytes = (Get-Item -LiteralPath $c.Local).Length
    $remoteBytes = Get-RemoteSize -DevPath "$RemoteRoot/$($c.Name)"
    $ok = ($remoteBytes -eq $localBytes)
    if (-not $ok) { $bad++ }
    $rows += [pscustomobject]@{
        文件   = $c.Name
        本地   = $localBytes
        远端   = if ($null -eq $remoteBytes) { '缺失' } else { $remoteBytes }
        结果   = if ($ok) { 'OK' } else { '不一致' }
    }
}
$rows | Format-Table -AutoSize | Out-String | Write-Host

if ($InstallApk) {
    if (-not (Test-Path -LiteralPath $InstallApk)) { throw "APK 不存在：$InstallApk" }
    Write-Host "安装 $InstallApk"
    & $adbPath @prefix install -r $InstallApk
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
}

& $adbPath @prefix shell "am force-stop $Package" | Out-Null

if ($bad -gt 0) {
    Write-Error "校验失败：$bad 个文件字节数不一致（远端路径 $RemoteRoot）"
    exit 1
}
Write-Host "就绪：启动 $Package 即应直接进游戏（产物根 $RemoteRoot/erbc.v1）"
exit 0
