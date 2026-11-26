#!/usr/bin/env python3
"""每款 era 游戏各自的版本账本（GAME-VERSION）。

eraTW 与第三方移植目标共用同一套壳，但**产品线不同**：eratohoReverse 从 1.0.0+1
起算，逐次出包抬号，不继承 eraTW 的 0.2.47+23。本模块只做三件事：

- 读 `devtools/game-versions.json`（schema 1，`games` 下每款一条）；
- 解析某款游戏本次出包该用的 (versionName, versionCode)：
  显式配置 > 账本 > 首次默认（1.0.0 / 1）；
- 出包成功后把账本抬到下一条（versionCode +1，versionName 末段 +1），
  这样「下一次出包」自动就是 1.0.1+2。

账本本身是仓库文件（随提交走），抬号会让工作树变脏 —— 与 `pack_one` 写
`build.gradle.kts` 的口径一致，归档名里的 `-dirty` 会如实标出。

stdlib only，无仓库内 import。
"""
from __future__ import annotations

import json
import os

SCHEMA_VERSION = 1
DEFAULT_VERSION_NAME = "1.0.0"
DEFAULT_VERSION_CODE = 1
LEDGER_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "game-versions.json")


class VersionLedgerError(ValueError):
    """账本不合法（坏 JSON / 字段缺失 / 版本号格式错）。"""


def _check_version_name(value) -> str:
    parts = str(value).split(".")
    if not 1 <= len(parts) <= 3 or not all(p.isdigit() for p in parts):
        raise VersionLedgerError(f"versionName 必须是 1~3 段数字，实际 {value!r}")
    return ".".join(str(int(p)) for p in parts)


def _check_version_code(value) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise VersionLedgerError(f"versionCode 必须是正整数，实际 {value!r}")
    return value


def load(path: str = LEDGER_FILE) -> dict:
    """读账本；文件不存在 ⇒ 空账本（首次为某款游戏出包时自动建立）。"""
    if not os.path.isfile(path):
        return {"schema": SCHEMA_VERSION, "games": {}}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            raw = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        raise VersionLedgerError(f"读不到/读不懂版本账本 {path}：{exc}") from exc
    if not isinstance(raw, dict) or raw.get("schema") != SCHEMA_VERSION:
        raise VersionLedgerError(f"版本账本 {path} 的 schema 必须是 {SCHEMA_VERSION}")
    games = raw.get("games", {})
    if not isinstance(games, dict):
        raise VersionLedgerError(f"版本账本 {path} 的 games 必须是对象")
    for name, entry in games.items():
        if not isinstance(entry, dict):
            raise VersionLedgerError(f"版本账本 {path} 的 {name} 必须是对象")
        _check_version_name(entry.get("versionName", ""))
        _check_version_code(entry.get("versionCode"))
    return {"schema": SCHEMA_VERSION, "games": games}


def resolve(ledger: dict, game_name: str, explicit_name=None, explicit_code=None) -> tuple:
    """本次出包该用的 (versionName, versionCode)。

    优先级：配置文件显式值 > 账本 > 新游戏默认 1.0.0 / 1。
    只算不写 —— 写盘发生在出包成功之后的 [bump]。
    """
    entry = ledger.get("games", {}).get(game_name, {})
    name = explicit_name or entry.get("versionName") or DEFAULT_VERSION_NAME
    code = explicit_code if explicit_code is not None else entry.get("versionCode", DEFAULT_VERSION_CODE)
    return _check_version_name(name), _check_version_code(code)


def next_version(version_name: str, version_code: int) -> tuple:
    """抬号：versionCode +1，versionName 末段 +1（1.0.0 → 1.0.1）。"""
    parts = _check_version_name(version_name).split(".")
    while len(parts) < 3:
        parts.append("0")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts), _check_version_code(version_code) + 1


def record(path: str, game_name: str, version_name: str, version_code: int) -> tuple:
    """出包成功后记账：把该游戏抬到下一条，返回 (下一次 versionName, versionCode)。"""
    ledger = load(path)
    nxt = next_version(version_name, version_code)
    ledger["games"][game_name] = {"versionName": nxt[0], "versionCode": nxt[1]}
    payload = {
        "_note": "每款游戏各自一条版本线；pack_release 出包成功后自动抬号。手工改也行，"
                 "但 versionCode 必须单调递增。",
        "schema": SCHEMA_VERSION,
        "games": dict(sorted(ledger["games"].items())),
    }
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    return nxt


def summary(game_name: str, version_name: str, version_code: int) -> str:
    """出包日志用的一行摘要。"""
    return f"{game_name} {version_name}+{version_code}"
