#!/usr/bin/env python3
"""壳层品牌配置的构建期单源（SHELL-PROFILE）。

配置与引擎档**分开**：`engine.config.json` 的 v1 三键只描述引擎身份，本模块描述
「这台壳显示成哪款游戏」。出包脚本读本配置，把品牌值投影成 Gradle `-P` 属性，
再由 app 模块烙进 `resources.arsc`（app label / 加载页 / 教程模式）。

配置 schema v1::

    {
      "schema": 1,
      "gameName": "eratohoReverse",
      "appLabel": "eratohoReverse",
      "applicationId": "com.eratw.port.eratohoreverse",
      "loadingHeadline": "― E R A T O H O R E V E R S E ―",
      "loadingDetail": "正在准备 eratohoReverse…",
      "tutorialMode": "basic"
    }

- `gameName` 必填；`appLabel` / 两句加载文案可省，缺省由 gameName 生成。
- `applicationId` 可省：缺省 `com.eratw.port.<gameName 的 ASCII 归一>`。第三方包**必须**
  与 eraTW 的 `com.eratw.port.android` 分开 —— 否则两款游戏的版本线会互相压号，
  且从 1 起算的 versionCode 会低于已装包而装不上。
- `versionName` / `versionCode` 可省：缺省由版本账本（`game_versions.py`）给，
  新游戏从 1.0.0+1 起算；显式给了就以配置为准（账本仍按出包结果继续抬号）。
- `tutorialMode` 只有 `full`（eraTW 原七页）与 `basic`（中性基础功能介绍），
  给壳层配置时缺省 `basic`。
- 未知字段、空串、换/控制字符、非法 mode / 包名 / 版本号一律 [ShellProfileError]。

无配置时 [gradle_args] 返回空表 —— 这是 eraTW 零干扰的早退本体。
stdlib only，无仓库内 import。
"""
from __future__ import annotations

import json
import os
import re
import unicodedata

SCHEMA_VERSION = 1
ALLOWED_KEYS = (
    "schema", "gameName", "appLabel", "loadingHeadline", "loadingDetail", "tutorialMode",
    "applicationId", "versionName", "versionCode",
)
TUTORIAL_MODES = ("full", "basic")
MAX_FIELD_CHARS = 120
# 第三方包的默认包名前缀：与 eraTW 的 `com.eratw.port.android` 并列，互不覆盖。
APP_ID_PREFIX = "com.eratw.port."
APP_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
VERSION_NAME_RE = re.compile(r"^\d+(\.\d+){0,2}$")
MAX_VERSION_CODE = 2_100_000_000


class ShellProfileError(ValueError):
    """壳层配置不合法（未知字段 / 空值 / 控制字符 / 非法教程模式 / 读不到文件）。"""


def _require_text(raw: dict, key: str, where: str, required: bool) -> str | None:
    if key not in raw:
        if required:
            raise ShellProfileError(f"壳层配置{where}缺必填字段 {key}")
        return None
    value = raw[key]
    if not isinstance(value, str):
        raise ShellProfileError(f"壳层配置{where}的 {key} 必须是字符串，实际 {value!r}")
    if any(unicodedata.category(ch)[0] == "C" for ch in value):
        raise ShellProfileError(f"壳层配置{where}的 {key} 含控制字符")
    value = value.strip()
    if not value:
        raise ShellProfileError(f"壳层配置{where}的 {key} 不许是空串")
    if len(value) > MAX_FIELD_CHARS:
        raise ShellProfileError(
            f"壳层配置{where}的 {key} 超过 {MAX_FIELD_CHARS} 字（实际 {len(value)}）")
    return value


def validate_profile(raw, source: str = "") -> dict:
    """原始 dict → 规范化配置 dict（六字段全齐，可直接投影 Gradle 属性）。"""
    where = f"（{source}）" if source else ""
    if not isinstance(raw, dict):
        raise ShellProfileError(f"壳层配置{where}必须是 JSON 对象，实际 {type(raw).__name__}")
    unknown = sorted(k for k in raw if k not in ALLOWED_KEYS)
    if unknown:
        raise ShellProfileError(
            f"壳层配置{where}含未知字段 {unknown}（v1 只有 {list(ALLOWED_KEYS)}）")
    if "schema" not in raw:
        raise ShellProfileError(f"壳层配置{where}缺字段 schema")
    schema = raw["schema"]
    if isinstance(schema, bool) or schema != SCHEMA_VERSION:
        raise ShellProfileError(f"壳层配置{where}的 schema 不符：期望 {SCHEMA_VERSION}，实际 {schema!r}")

    game_name = _require_text(raw, "gameName", where, required=True)
    app_label = _require_text(raw, "appLabel", where, required=False) or game_name
    headline = _require_text(raw, "loadingHeadline", where, required=False) or f"― {game_name} ―"
    detail = _require_text(raw, "loadingDetail", where, required=False) or f"正在准备 {game_name}…"
    mode = raw.get("tutorialMode", "basic")
    if not isinstance(mode, str) or mode not in TUTORIAL_MODES:
        raise ShellProfileError(
            f"壳层配置{where}的 tutorialMode 必须是 {list(TUTORIAL_MODES)} 之一，实际 {mode!r}")
    app_id = _require_text(raw, "applicationId", where, required=False)
    if app_id is None:
        app_id = default_application_id(game_name)
        if not app_id:
            raise ShellProfileError(
                f"壳层配置{where}的 gameName「{game_name}」归不出合法包名，"
                "请显式给 applicationId")
    if not APP_ID_RE.match(app_id):
        raise ShellProfileError(
            f"壳层配置{where}的 applicationId 不是合法 Android 包名：{app_id!r}")
    version_name = _require_text(raw, "versionName", where, required=False)
    if version_name is not None and not VERSION_NAME_RE.match(version_name):
        raise ShellProfileError(
            f"壳层配置{where}的 versionName 必须是 1~3 段数字（例 1.0.0），实际 {version_name!r}")
    version_code = raw.get("versionCode")
    if version_code is not None:
        if isinstance(version_code, bool) or not isinstance(version_code, int):
            raise ShellProfileError(
                f"壳层配置{where}的 versionCode 必须是整数，实际 {version_code!r}")
        if not 1 <= version_code <= MAX_VERSION_CODE:
            raise ShellProfileError(
                f"壳层配置{where}的 versionCode 越界（1..{MAX_VERSION_CODE}）：{version_code}")
    return {
        "gameName": game_name,
        "appLabel": app_label,
        "loadingHeadline": headline,
        "loadingDetail": detail,
        "tutorialMode": mode,
        "applicationId": app_id,
        "versionName": version_name,
        "versionCode": version_code,
    }


def load_profile(path: str) -> dict:
    """读配置文件 → 规范化配置；文件缺失 / 坏 JSON / 校验失败都抛 [ShellProfileError]。"""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        raise ShellProfileError(f"读不到壳层配置 {path}：{exc}") from exc
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ShellProfileError(f"壳层配置 {path} 不是合法 JSON：{exc}") from exc
    return validate_profile(raw, source=path)


def gradle_args(profile: dict | None) -> list:
    """配置 → Gradle -P 属性；None = 无配置，返回空表（零干扰早退）。"""
    if profile is None:
        return []
    args = [
        f"-PeratwShellGameName={profile['gameName']}",
        f"-PeratwAppLabel={profile['appLabel']}",
        f"-PeratwLoadingHeadline={profile['loadingHeadline']}",
        f"-PeratwLoadingDetail={profile['loadingDetail']}",
        f"-PeratwTutorialMode={profile['tutorialMode']}",
        f"-PeratwApplicationId={profile['applicationId']}",
    ]
    # 版本号由版本账本解析后回填（显式配置优先）；两者都缺时交给 Gradle 缺省（eraTW 线）。
    if profile.get("versionName"):
        args.append(f"-PeratwVersionName={profile['versionName']}")
    if profile.get("versionCode"):
        args.append(f"-PeratwVersionCode={profile['versionCode']}")
    return args


def summary(profile: dict) -> str:
    """出包日志用的一行摘要（不含路径，便于对拍）。"""
    return (f"gameName={profile['gameName']} appLabel={profile['appLabel']} "
            f"tutorialMode={profile['tutorialMode']}")


ARTIFACT_STEM_FALLBACK = "eratw"
MAX_STEM_CHARS = 48


def _slug(text: str) -> str:
    """文件名前缀归一：路径分隔符/Windows 非法字符/控制字符 → `-`，空白 → `-`。"""
    out = []
    for ch in text.strip():
        if ch.isspace() or ch in '<>:"/\\|?*' or unicodedata.category(ch)[0] == "C":
            out.append("-")
        else:
            out.append(ch)
    return "".join(out).strip(" .-_")


def default_application_id(game_name: str) -> str:
    """第三方包名：`com.eratw.port.<game 的 ASCII 归一>`；归不出字面量时返回空串。"""
    ascii_part = "".join(ch for ch in game_name.lower() if ch.isascii() and ch.isalnum())
    return (APP_ID_PREFIX + ascii_part) if ascii_part else ""


def artifact_stem(profile: dict | None, game_root: str = "") -> str:
    """归档文件名前缀：跟随随包语料，而不是永远叫 eratw。

    优先级：壳层配置 `gameName` > 游戏根目录名 > `eratw`（eraTW 原生无配置路径）。
    第三方包出成 `eratohoReverse-0.2.47+23-….apk`，一眼能看出装的是哪款游戏，
    不会与 eraTW 的包混在同一个归档目录里。
    """
    candidates = []
    if profile:
        candidates.append(profile.get("gameName", ""))
    if game_root:
        candidates.append(os.path.basename(os.path.normpath(game_root)))
    for cand in candidates:
        stem = _slug(cand or "")
        if stem:
            return stem[:MAX_STEM_CHARS]
    return ARTIFACT_STEM_FALLBACK
