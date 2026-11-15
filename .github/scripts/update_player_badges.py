#!/usr/bin/env python3
"""把 Worker 的 /players/raw 读数转成两枚 shields.io endpoint 徽章 JSON。

设计口径（与 README 的隐私说明同源）：

* 徽章**不直接打 Worker**：GitHub Actions 定时取一次 /players/raw，把聚合数写成
  仓库内 badges/*.json；shields.io 从 raw.githubusercontent.com 读这两个静态文件。
  这样 shields.io / GitHub camo 的请求量、Worker 的可用性与玩家更新链路完全解耦。
* 失败一律「保留上一次读数」而不是写 0：Worker 还没部署、R2 暂时读不出来、
  回包形状不对 —— 全部打印一行说明并以 0 退出，不红 CI、不产生坏图。
* **截断读数不再静默停更**：Worker 标注 truncated 时，说明这一轮只数到一部分，
  徽章按「至少这么多」显示并带 +。否则一次列举分页上限就会让徽章永远冻在旧快照
  上，看起来像「最多只能显示 999」。端点取不到 / 回包不合法仍按上一条保留旧值。
* **人数自动按 1000 进制缩写**：< 1000 原样；之后 k / M / G / T 自动进位，取三位
  有效数字（1k / 1.02k / 12.3k / 123k / 1.23M）。全程整数向下取整，不会四舍五入
  冒出 1000k 这种越界文案，徽章宽度也不会随玩家数线性变长。
* 只回聚合总数：这个脚本连一个匿名编号都不会见到，也不写任何时间戳或个人数据进 JSON。

用法：python3 .github/scripts/update_player_badges.py <raw.json> [输出目录]
      python3 .github/scripts/update_player_badges.py --selftest
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

CACHE_SECONDS = 1800  # 与 workflow 的 30 分钟节奏对齐
K = 1000
UNITS = ("k", "M", "G", "T")


class Skip(Exception):
    """这一轮读数不可信：保留已有徽章 JSON，正常退出。"""


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


def compact(n: int, *, at_least: bool = False) -> str:
    """把非负人数压成短徽章文案，>= 1000 起自动按 1000 进制换单位。

    精度随量级收敛（三位有效数字），例如 1028 -> 1.02k、12345 -> 12.3k、
    123456 -> 123k、1234567 -> 1.23M。at_least 用于读数被截断时标注下界。
    """
    if n < 0:
        raise ValueError("人数不能是负数")

    if n < K:
        text = str(n)
    else:
        base = K
        unit = 0
        while n >= base * K and unit < len(UNITS) - 1:
            base *= K
            unit += 1

        whole = n // base
        if whole < 10:  # 两位小数：1.02k
            scaled = (n * 100) // base
            text = f"{scaled // 100}.{scaled % 100:02d}".rstrip("0").rstrip(".")
        elif whole < 100:  # 一位小数：12.3k
            scaled = (n * 10) // base
            text = f"{scaled // 10}.{scaled % 10}".rstrip("0").rstrip(".")
        else:  # 整数：123k
            text = str(whole)
        text += UNITS[unit]

    return text + ("+" if at_least else "")


def build_payloads(raw: object) -> dict[str, dict]:
    """校验一轮读数并产出两枚徽章 JSON；不可信时抛 Skip。"""
    if not isinstance(raw, dict):
        raise Skip("读数不是 JSON 对象")
    if raw.get("error"):
        raise Skip("端点回的是错误对象（多半是 R2 列举暂时不可用）")
    players = raw.get("players")
    active = raw.get("active_today")
    if not is_count(players) or not is_count(active):
        raise Skip("players / active_today 不是非负整数")
    day = raw.get("day")
    if not isinstance(day, str) or len(day) != 10 or day[4] != "-" or day[7] != "-":
        raise Skip("day 不是 YYYY-MM-DD")

    # truncated = 这一轮只数到一部分：标成下界，别让徽章冻在旧快照上。
    at_least = raw.get("truncated") is True
    return {
        "players.json": badge("players", compact(players, at_least=at_least), "brightgreen"),
        "active-today.json": badge("active today", compact(active, at_least=at_least), "blue"),
    }


def selftest() -> int:
    """离线自检：数字进制边界 + 各类降级分支，不需要端点、不写任何文件。"""
    cases = [
        (0, "0"), (1, "1"), (999, "999"),
        (1000, "1k"), (1001, "1k"), (1028, "1.02k"), (1099, "1.09k"),
        (1500, "1.5k"), (9999, "9.99k"), (10000, "10k"),
        (12345, "12.3k"), (99999, "99.9k"), (100000, "100k"),
        (123456, "123k"), (999999, "999k"),
        (1000000, "1M"), (1234567, "1.23M"), (999999999, "999M"),
        (1000000000, "1G"),
    ]
    checks = 0
    for n, want in cases:
        got = compact(n)
        assert got == want, f"compact({n}) = {got!r}, 期望 {want!r}"
        assert len(got) <= 7, f"compact({n}) = {got!r} 过长"
        checks += 1

    assert compact(999, at_least=True) == "999+"
    assert compact(1028, at_least=True) == "1.02k+"
    checks += 2

    ok_raw = {"players": 1028, "active_today": 187, "day": "2026-11-15", "truncated": False}
    payloads = build_payloads(ok_raw)
    assert payloads["players.json"]["message"] == "1.02k"
    assert payloads["active-today.json"]["message"] == "187"
    assert payloads["players.json"]["schemaVersion"] == 1
    assert payloads["players.json"]["cacheSeconds"] == CACHE_SECONDS
    checks += 4

    cut = build_payloads({**ok_raw, "truncated": True})
    assert cut["players.json"]["message"] == "1.02k+"
    assert cut["active-today.json"]["message"] == "187+"
    checks += 2

    bad_raws = [
        None, [], {"error": "r2 down"},
        {"players": True, "active_today": 1, "day": "2026-11-15"},
        {"players": "1028", "active_today": 1, "day": "2026-11-15"},
        {"players": 1, "active_today": 1, "day": "2026-11-5"},
        {"players": 1, "active_today": 1},
    ]
    for raw in bad_raws:
        try:
            build_payloads(raw)
        except Skip:
            checks += 1
        else:
            raise AssertionError(f"本应跳过却生成了徽章：{raw!r}")

    print(f"ok: badge selftest {checks} checks, 0 failed", flush=True)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "--selftest":
        return selftest()
    if not args:
        print("skip: 没给读数文件路径; 保留已有徽章 JSON", flush=True)
        return 0

    raw_path = Path(args[0])
    out_dir = Path(args[1]) if len(args) > 1 else Path("badges")
    try:
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"skip: 读数不是合法 JSON 或读不出来（{exc}）; 保留已有徽章 JSON", flush=True)
        return 0

    try:
        payloads = build_payloads(raw)
    except Skip as exc:
        print(f"skip: {exc}; 保留已有徽章 JSON", flush=True)
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    for name, payload in payloads.items():
        (out_dir / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    print(
        f"ok: players={raw['players']} active_today={raw['active_today']} day={raw['day']} "
        f"truncated={raw.get('truncated') is True} -> {out_dir}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
