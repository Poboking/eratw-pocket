#!/usr/bin/env python3
"""SHELL-PROFILE 配置解析 / Gradle 投影的反向证明（stdlib only）。"""
from __future__ import annotations

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import shell_profile  # noqa: E402


def check(name: str, cond: bool) -> None:
    if not cond:
        raise AssertionError(name)
    print(f"[ ok ] {name}")


def rejects(name: str, raw) -> None:
    try:
        shell_profile.validate_profile(raw)
    except shell_profile.ShellProfileError:
        print(f"[ ok ] {name}")
        return
    raise AssertionError(name)


def main() -> int:
    minimal = shell_profile.validate_profile({"schema": 1, "gameName": "eratohoReverse"})
    check("最小配置补全缺省 appLabel", minimal["appLabel"] == "eratohoReverse")
    check("最小配置教程默认 basic", minimal["tutorialMode"] == "basic")
    check("最小配置生成加载标题", "eratohoReverse" in minimal["loadingHeadline"])
    check("最小配置生成进度说明", minimal["loadingDetail"].endswith("eratohoReverse…"))

    full = shell_profile.validate_profile({
        "schema": 1,
        "gameName": "eratohoReverse",
        "appLabel": "Reverse",
        "loadingHeadline": "HEAD",
        "loadingDetail": "DETAIL",
        "tutorialMode": "full",
        "applicationId": "com.example.reverse",
        "versionName": "2.1.0",
        "versionCode": 7,
    })
    check("显式字段逐字保留", full == {
        "gameName": "eratohoReverse", "appLabel": "Reverse",
        "loadingHeadline": "HEAD", "loadingDetail": "DETAIL", "tutorialMode": "full",
        "applicationId": "com.example.reverse", "versionName": "2.1.0", "versionCode": 7,
    })
    check("包名缺省按 gameName 派生",
          minimal["applicationId"] == "com.eratw.port.eratohoreverse")
    check("版本字段缺省为空（交给账本）",
          minimal["versionName"] is None and minimal["versionCode"] is None)

    rejects("未知字段判红", {"schema": 1, "gameName": "g", "oops": 1})
    rejects("schema 判红", {"schema": 2, "gameName": "g"})
    rejects("gameName 必填", {"schema": 1})
    rejects("gameName 空串判红", {"schema": 1, "gameName": "  "})
    rejects("换行控制字符判红", {"schema": 1, "gameName": "g" + chr(10)})
    rejects("教程模式判红", {"schema": 1, "gameName": "g", "tutorialMode": "none"})
    rejects("非字符串判红", {"schema": 1, "gameName": 7})
    rejects("非法包名判红", {"schema": 1, "gameName": "g", "applicationId": "com..bad"})
    rejects("单段包名判红", {"schema": 1, "gameName": "g", "applicationId": "eratw"})
    rejects("版本名判红", {"schema": 1, "gameName": "g", "versionName": "v1"})
    rejects("版本号非整数判红", {"schema": 1, "gameName": "g", "versionCode": "1"})
    rejects("版本号 0 判红", {"schema": 1, "gameName": "g", "versionCode": 0})
    rejects("纯中文名归不出包名判红", {"schema": 1, "gameName": "東方"})

    check("无配置零干扰：参数表为空", shell_profile.gradle_args(None) == [])
    args = shell_profile.gradle_args(full)
    check("八枚 -P 参数齐全（含包名与版本）",
          len(args) == 8 and all(a.startswith("-Peratw") for a in args))
    check("app label 投影", "-PeratwAppLabel=Reverse" in args)
    check("教程模式投影", "-PeratwTutorialMode=full" in args)
    check("包名投影", "-PeratwApplicationId=com.example.reverse" in args)
    check("版本号投影",
          "-PeratwVersionName=2.1.0" in args and "-PeratwVersionCode=7" in args)
    plain = shell_profile.gradle_args(minimal)
    check("版本缺省不投影（交给账本）",
          not any(a.startswith("-PeratwVersion") for a in plain))

    # 归档名跟随语料（SHELL-PROFILE 附带）：第三方包不许再顶着 eratw- 前缀。
    check("归档前缀取壳层 gameName", shell_profile.artifact_stem(full) == "eratohoReverse")
    check("无壳层配置时取游戏根目录名",
          # 用 os.path.join 拼「本机原生分隔符」的根目录：Windows 上是 C:\game\...，
          # POSIX 上是 C:/game/... —— 硬写反斜杠会在 POSIX 上被当成普通字符
          # （os.path.basename 不切分），判据就只对 Windows 成立。
          shell_profile.artifact_stem(None, os.path.join("C:", "game", "eratohoReverse")) == "eratohoReverse")
    check("尾斜杠不影响取目录名",
          shell_profile.artifact_stem(None, "C:/game/eratohoReverse/") == "eratohoReverse")
    check("壳层配置优先于目录名",
          shell_profile.artifact_stem(full, "C:/game/other") == "eratohoReverse")
    check("无配置无根目录回落 eratw",
          shell_profile.artifact_stem(None) == shell_profile.ARTIFACT_STEM_FALLBACK)
    check("非法字符归一", shell_profile.artifact_stem({"gameName": "era/tw:β"}) == "era-tw-β")
    check("空目录名不产生空前缀",
          shell_profile.artifact_stem(None, "C:/") == shell_profile.ARTIFACT_STEM_FALLBACK)
    check("空串根目录不产生空前缀",
          shell_profile.artifact_stem(None, "") == shell_profile.ARTIFACT_STEM_FALLBACK)

    fixture = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "fixtures", "engine-compat", "shell.eratohoReverse.json",
    )
    loaded = shell_profile.load_profile(fixture)
    check("仓库样例可解析且为 basic", loaded["gameName"] == "eratohoReverse" and loaded["tutorialMode"] == "basic")
    with tempfile.TemporaryDirectory() as td:
        bad = os.path.join(td, "bad.json")
        with open(bad, "w", encoding="utf-8") as fh:
            fh.write("{")
        try:
            shell_profile.load_profile(bad)
        except shell_profile.ShellProfileError:
            print("[ ok ] 坏 JSON 判红")
        else:
            raise AssertionError("坏 JSON 判红")

    print("壳层配置闸回归全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
