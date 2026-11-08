#!/usr/bin/env python3
"""把 Worker 的 /players/raw 读数转成两枚 shields.io endpoint 徽章 JSON。

设计口径（与 README 的隐私说明同源）：

* 徽章**不直接打 Worker**：GitHub Actions 定时取一次 /players/raw，把聚合数写成
  仓库内 badges/*.json；shields.io 从 raw.githubusercontent.com 读这两个静态文件。
  这样 shields.io / GitHub camo 的请求量、Worker 的可用性与玩家更新链路完全解耦。
* 失败一律「保留上一次读数」而不是写 0：Worker 还没部署、R2 暂时读不出来、
  读数被截断、回包形状不对 —— 全部打印一行说明并以 0 退出，不红 CI、不产生坏图。
* 只回聚合总数：这个脚本连一个匿名编号都不会见到，也不写任何时间戳或个人数据进 JSON。

用法：python3 .github/scripts/update_player_badges.py <raw.json> [输出目录]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

CACHE_SECONDS = 1800  # 与 workflow 的 30 分钟节奏对齐


def skip(reason: str) -> None:
    """取不到可信读数时正常退出：workflow 会跳过提交，README 继续显示上一次的数。"""
    print(f"skip: {reason}; 保留已有徽章 JSON", flush=True)
    sys.exit(0)


def is_count(value: object) -> bool:
    # bool 是 int 的子类，别让 true 混进来当人数。
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def badge(label: str, message: str, color: str) -> dict:
    return {
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": color,
        "cacheSeconds": CACHE_SECONDS,
    }


def thousands(n: int) -> str:
    return f"{n:,}"


def main() -> None:
    if len(sys.argv) < 2:
        skip("没给读数文件路径")
    raw_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("badges")

    try:
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        skip(f"读数不是合法 JSON 或读不出来（{exc}）")

    if not isinstance(raw, dict):
        skip("读数不是 JSON 对象")
    if raw.get("error"):
        skip("端点回的是错误对象（多半是 R2 列举暂时不可用）")
    players = raw.get("players")
    active = raw.get("active_today")
    if not is_count(players) or not is_count(active):
        skip("players / active_today 不是非负整数")
    if raw.get("truncated") is True:
        skip("R2 列举被截断，这一轮的累计或今日数不全")
    day = raw.get("day")
    if not isinstance(day, str) or len(day) != 10 or day[4] != "-" or day[7] != "-":
        skip("day 不是 YYYY-MM-DD")

    out_dir.mkdir(parents=True, exist_ok=True)
    payloads = {
        "users.json": badge("users", thousands(players), "brightgreen"),
        "active-today.json": badge("active today", thousands(active), "blue"),
    }
    for name, payload in payloads.items():
        (out_dir / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    print(f"ok: players={players} active_today={active} day={day} -> {out_dir}", flush=True)


if __name__ == "__main__":
    main()
