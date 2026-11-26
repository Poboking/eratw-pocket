# PC-original 对拍装置 -- 入口 shim（主人判词 m00130 第21条）
# 逻辑全部在同目录 run.py：复制根构造、--CliDebug 驱动、端口侧 erbcc symbols 读数、语料红线自证。
# 用法示例：
#   powershell -File devtools/pc_cmp/run.ps1 --case buckets
#   powershell -File devtools/pc_cmp/run.ps1 --list
$ErrorActionPreference = "Stop"
# The rig prints CJK (engine warnings, corpus paths); without this the console shows mojibake.
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONUTF8 = "1"
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
& $py -X utf8 (Join-Path $PSScriptRoot "run.py") @args
exit $LASTEXITCODE
