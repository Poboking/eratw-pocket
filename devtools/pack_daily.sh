#!/usr/bin/env bash
# eratw 日包一键流水线：代理拉代码 → 0.5s 新鲜度预检 → 可视化出包 → 自动交付
#
# 为什么有这个脚本（2026-11-14 出包剖析）：
#   过去每次出包要人盯着来回处理「宿主代理拉不到 GitHub」「一键工具内部 git 同步踩错代理」
#   「共享 Gradle home 锁」「erbc 新鲜度到构建后期才炸，白跑一整轮」。这些都不是构建本身慢，
#   是流程要人陪。本脚本把决策全部固化：一条命令到底，中途不再问任何问题。
#
# 用法（Git Bash / MSYS）：
#   bash devtools/pack_daily.sh                    # 测试用正式包，全程零交互
#   bash devtools/pack_daily.sh --mode dev         # 一分钟级小包
#   bash devtools/pack_daily.sh --mode release     # 正式发行包（headless 默认不上传 / 不发 Release）
#   bash devtools/pack_daily.sh --mode release --version 0.2.47   # 正式包并抬号（仍不上传）
#   bash devtools/pack_daily.sh --no-pull          # 不联网拉代码，直接出本地 HEAD 的包
#   bash devtools/pack_daily.sh --no-window        # 无桌面/CI 才用；默认保留实时进度窗
#
# 代理口径见 AGENTS.md §1.1：显式 127.0.0.1:7890，不依赖宿主注入的 http_proxy/https_proxy。
# 桌面实时进度窗默认开启（不加 --no-window）；结果恒在 build/pack-one-last.json。

set -euo pipefail

PROXY="${ERATW_CLASH_PROXY:-http://127.0.0.1:7890}"
APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$APP_ROOT/.." && pwd)"
EXE="$APP_ROOT/devtools/pack_one/eratw-pack.exe"
PROBE="$APP_ROOT/devtools/erbc_freshness_probe.py"
LAST="$APP_ROOT/build/pack-one-last.json"

MODE="test-release"
DO_PULL=1
VERSION=""
EXTRA=()

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    --no-pull) DO_PULL=0; shift ;;
    --no-window) EXTRA+=("--no-window"); shift ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    --proxy) PROXY="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

case "$MODE" in test-release|dev|release) ;; *) echo "包型只能是 test-release / dev / release" >&2; exit 2 ;; esac
[[ -x "$EXE" ]] || { echo "找不到一键工具 $EXE" >&2; exit 2; }

START=$(date +%s)
echo "=== eratw 日包 · $(date '+%F %T') · 包型 $MODE ==="

# 1) 用 Clash 代理把代码真正拉到最新，再让一键工具跳过它自己的 git 同步。
#    一键工具内部调 git 不会带 §1.1 的代理参数，宿主注入的端口对 GitHub 会 502；
#    在这里拉干净、再传 --no-git，等于把这个失败源从流程里彻底摘掉。
if [[ "$DO_PULL" -eq 1 ]]; then
  echo "[1/4] 经 Clash 代理 $PROXY 同步远程 main …"
  GIT_C=(-c "http.proxy=$PROXY" -c "https.proxy=$PROXY")
  git -C "$REPO_ROOT" "${GIT_C[@]}" fetch origin --prune
  if ! git -C "$REPO_ROOT" "${GIT_C[@]}" merge --ff-only origin/main; then
    echo "快进失败（本地有独有提交或已跟踪改动）。不出包，避免交出一个说不清基线的包。" >&2
    echo "先手动处理工作树，或加 --no-pull 明确按本地 HEAD 出包。" >&2
    exit 2
  fi
  echo "      HEAD = $(git -C "$REPO_ROOT" rev-parse --short=7 HEAD)"
else
  echo "[1/4] --no-pull：按本地 HEAD $(git -C "$REPO_ROOT" rev-parse --short=7 HEAD) 出包"
fi

# 2) 半秒预判：只报读数，不拦包。重编与否由一键工具按「compiler-digest + 语料新度」定；
#    编译档由降级清单在册条数定，两处都只认磁盘事实，不在本脚本里另立一份口径。
echo "[2/4] erbc 新鲜度 / 编译档预判（0.5s）…"

# 第三方口上副本对账：语料根不入库，各机从 kojo-community 自行同步。
# 清单已是 0 条而语料仍是旧版 ⇒ strict 档会在编译末尾直接判红，白跑几分钟；
# 这里 0.1 秒先挡下来，并给出修法（其它机器没有 kojo-community 克隆时跳过）。
KOJO_SRC="$REPO_ROOT/kojo-community/整合内容/蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日常系指令.ERB"
CORPUS_NAME="$(python "$APP_ROOT/devtools/pack_release.py" --print-corpus-root-name 2>/dev/null || true)"
KOJO_DST="$REPO_ROOT/$CORPUS_NAME/ERB/口上・メッセージ関連/個人口上/016 Remilia [レミリア]/蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日常系指令.ERB"
if [[ -f "$KOJO_SRC" && -n "$CORPUS_NAME" && -f "$KOJO_DST" ]]; then
  if [[ "$(sha256sum "$KOJO_SRC" | cut -d' ' -f1)" != "$(sha256sum "$KOJO_DST" | cut -d' ' -f1)" ]]; then
    echo "第三方口上副本未同步：kojo-community 上游与 4.981 语料根的 K16_5 不一致。" >&2
    echo "按 kojo-community/README.md 覆盖同步两处语料根副本后再出包（清单已是 0 条，旧副本会撞 strict 硬闸）。" >&2
    exit 2
  fi
  echo "      第三方口上副本 sha256 一致（上游 ↔ 4.981 语料根）"
fi

ALLOWLIST="$APP_ROOT/devtools/erbc-degraded-allowlist.tsv"
N_DEG=0
if [[ -f "$ALLOWLIST" ]]; then
  N_DEG="$(grep -v '^#' "$ALLOWLIST" | grep -cv '^[[:space:]]*$' || true)"
fi
if [[ "$N_DEG" -eq 0 ]]; then
  echo "      降级清单在册 0 条 → strict 档（不容许解析失败，出包不带降级放行）"
else
  echo "      降级清单在册 $N_DEG 条 → compat 档 + 容错清单（工具自动带降级放行）"
fi
if [[ -f "$PROBE" ]]; then
  VERDICT="$(python "$PROBE" --short 2>/dev/null || true)"
  case "$VERDICT" in
    MATCH*)    echo "      compiler-digest 一致（编译器源码未变；语料若有更新仍会重编）" ;;
    MISMATCH*) echo "      编译器源码变过：本轮需重编 erbc（约 +6 分钟），工具会自动处理" ;;
    ERROR*)    echo "      ${VERDICT#ERROR }；工具将退回 mtime 兜底判据" ;;
    *)         echo "      探针无输出；工具将退回 mtime 兜底判据" ;;
  esac
else
  echo "      未找到探针，工具将退回 mtime 兜底判据"
fi

# 3) 一键出包。--headless 仍保留桌面实时进度窗（只有显式 --no-window 才关）。
#    test-release 不抬号不上传；release 才放行上传与 GitHub Release。
ARGS=(--headless --mode "$MODE" --no-git --no-install)
# --version 交给一键工具做抬号（它会改 app/build.gradle.kts、留备份、自动 +1 versionCode）。
# headless 且未带 --yes-upload 时工具只出包、不动线上清单，抬号本地包可以安全地先自用。
if [[ -n "$VERSION" ]]; then
  ARGS+=(--version "$VERSION")
fi
if [[ "$MODE" != "release" ]]; then
  ARGS+=(--no-upload --no-github-release)
fi
ARGS+=("${EXTRA[@]}")
echo "[3/4] 启动可视化出包（桌面有实时进度窗，全程无需干预）…"
echo "      $EXE ${ARGS[*]}"
if ! "$EXE" "${ARGS[@]}"; then
  echo "出包失败，完整日志见 build/pack-one-*.log。" >&2
  exit 1
fi

# 4) 直接把交付读数打印出来，主人回来只看这一屏即可。
echo "[4/4] 交付对账 …"
python - "$LAST" <<'PY'
import json, sys, os
p = sys.argv[1]
try:
    r = json.load(open(p, encoding="utf-8"))
except Exception as e:
    print(f"读不出一键工具结果 {p}: {e}", file=sys.stderr)
    sys.exit(1)
if not r.get("ok"):
    print(f"出包未成功：status={r.get('status')} error={r.get('error')}", file=sys.stderr)
    sys.exit(1)
apk = r.get("apk", "")
print(f"包型      {r.get('mode')} · {r.get('version')} · {r.get('head')}")
print(f"APK       {apk}")
if apk and os.path.isfile(apk):
    print(f"体积      {os.path.getsize(apk):,} B（{os.path.getsize(apk)/1048576:.1f} MiB）")
print(f"sha256    {r.get('sha256')}")
print(f"线上清单  {'已更新' if r.get('online_manifest_verified') else '未改动'}")
print(f"装机      {'已执行' if r.get('installed') else '未执行'}")
print(f"日志      {r.get('log')}")
PY

echo "=== 全部完成 · 总耗时 $(( $(date +%s) - START ))s ==="
echo "对外发布时请记得告知玩家「崩溃报告会自动上报」。"
