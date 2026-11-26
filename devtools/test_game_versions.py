#!/usr/bin/env python3
"""GAME-VERSION 版本账本的反向证明（stdlib only）。"""
from __future__ import annotations

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import game_versions  # noqa: E402


def check(name: str, cond: bool) -> None:
    if not cond:
        raise AssertionError(name)
    print(f"[ ok ] {name}")


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        path = os.path.join(td, "game-versions.json")
        # 空账本：新游戏从 1.0.0+1 起算
        ledger = game_versions.load(path)
        check("账本缺失 = 空账本", ledger["games"] == {})
        check("新游戏默认 1.0.0+1",
              game_versions.resolve(ledger, "eratohoReverse") == ("1.0.0", 1))

        # 抬号：写盘后下一次读到 1.0.1+2
        nxt = game_versions.record(path, "eratohoReverse", "1.0.0", 1)
        check("抬号结果 1.0.1+2", nxt == ("1.0.1", 2))
        again = game_versions.load(path)
        check("抬号已落盘",
              game_versions.resolve(again, "eratohoReverse") == ("1.0.1", 2))

        # 两款游戏各走各的线，互不影响
        game_versions.record(path, "eratohoK", "1.0.0", 1)
        merged = game_versions.load(path)
        check("游戏间版本线独立",
              game_versions.resolve(merged, "eratohoReverse") == ("1.0.1", 2)
              and game_versions.resolve(merged, "eratohoK") == ("1.0.1", 2))

        # 显式配置优先于账本
        check("显式版本优先",
              game_versions.resolve(merged, "eratohoReverse", "3.2.1", 99) == ("3.2.1", 99))
        check("只显式 versionName 时 versionCode 用账本",
              game_versions.resolve(merged, "eratohoReverse", "9.9.9") == ("9.9.9", 2))

        # 版本名归一到三段、末段 +1
        check("两段版本名补零", game_versions.next_version("1.2", 5) == ("1.2.1", 6))
        check("一段版本名补零", game_versions.next_version("7", 1) == ("7.0.1", 2))

        # 坏账本判红
        bad = os.path.join(td, "bad.json")
        with open(bad, "w", encoding="utf-8") as fh:
            json.dump({"schema": 1, "games": {"x": {"versionName": "v1", "versionCode": 1}}}, fh)
        try:
            game_versions.load(bad)
        except game_versions.VersionLedgerError:
            print("[ ok ] 坏 versionName 判红")
        else:
            raise AssertionError("坏 versionName 判红")

        with open(bad, "w", encoding="utf-8") as fh:
            json.dump({"schema": 2, "games": {}}, fh)
        try:
            game_versions.load(bad)
        except game_versions.VersionLedgerError:
            print("[ ok ] 错 schema 判红")
        else:
            raise AssertionError("错 schema 判红")

    # 仓库账本必须能读，且 eratohoReverse 已开户（首发 1.0.0+1，出包后会被抬号，
    # 所以这里只钉「有这条线且版本号合法"，不钉某个具体值）。
    repo = game_versions.load()
    check("仓库账本已给 eratohoReverse 开户", "eratohoReverse" in repo["games"])
    name, code = game_versions.resolve(repo, "eratohoReverse")
    check("账本版本号合法且 code ≥ 1", name.count(".") == 2 and code >= 1)

    print("版本账本回归全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
