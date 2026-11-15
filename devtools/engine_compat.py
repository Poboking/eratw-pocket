#!/usr/bin/env python3
"""引擎兼容构建期配置与印章（ENGC-6 / B6-PACK）。

配置 schema v1（契约 CONTRACT-ENGCOMPAT §8.0.5）::

    { "schema": 1, "engine": "snake", "version": "4.981" }

- `engine` ∈ {snake, em_ee, v24}，`version` 必须与该引擎受支持的版本**逐字**匹配，
  组合解析到 profile id（如 `snake` + `4.981` → `snake_4981`）。
- 未知字段、schema 不符、未知组合一律抛 [EngineConfigError]，报错正文附支持表；
  用户只选择 `engine` + `version`，不给能力/方言覆盖项（I2：一个事实一个出处）。

印章（出包时烙进 APK `assets/engine-profile.json`）四字段固定::

    { "schema": 1, "profile": "<profile_id>", "engine": "...", "version": "..." }

与配置的字段名不同处：印章用 `profile`（不混用 `profileId`，契约 §8.0.5）。

三元组同步表 [TARGETS] **不再手抄**：它的唯一出处是手写权威
`specs/engine-profiles.input.yaml` 的 `targets` 段（契约 CONTRACT-ENGCPL §4 E1/E2），
本模块在导入时用 PyYAML 直读该文件（读不到或缺 PyYAML 一律 [EngineConfigError]）。
对外形状保持不变（三键 dict 的 list：profile / engine / version），
与 Kotlin 侧生成物 `EngineProfileData.TARGETS` 的一致性由
`devtools/test_engine_compat.py` 对拍「生成后 yaml 的 targets 段」钉住 —— 任何一侧
手抄或改错都会红（禁止再读对方源码文本做正则对拍）。

stdlib + PyYAML，无仓库内 import（pack_release.py 与测试脚本反向引用本模块）。
"""
from __future__ import annotations

import json
import os

SCHEMA_VERSION = 1

# 配置 schema v1 的全部合法键（多一个字段都算未知字段，宁红不猜）。
ENGINE_CONFIG_KEYS = ("schema", "engine", "version")

# 印章在 APK 内的落点（StageGameAssetsTask 写 staging 根，Lane C 接线）。
STAMP_ASSET_PATH = "assets/engine-profile.json"

# 引擎身份表的仓库相对落点（相对 eratw-android/）。
INPUT_SPEC_REL = os.path.join("specs", "engine-profiles.input.yaml")


class EngineConfigError(ValueError):
    """引擎配置不合法（未知字段 / schema 不符 / 未知组合 / 读不出文件）。"""


def input_spec_path() -> str:
    """手写引擎身份表的绝对路径（<repo>/specs/engine-profiles.input.yaml）。"""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        INPUT_SPEC_REL)


def load_targets(path: str = "") -> list:
    """读引擎身份表 → `[{"profile","engine","version"}, …]`。

    顺序 = `targets` 段的书写顺序；`version` 取 `stamp_version`（印章里的对外版本号，
    snake 就是 eraTW 语料版本 `4.981`）。缺 PyYAML / 读不到 / 段缺失一律抛
    [EngineConfigError]（此时 [format_support_table] 还不可用，错误正文只给原因）。
    """
    try:
        import yaml  # type: ignore
    except ImportError as exc:  # pragma: no cover - 环境缺依赖
        raise EngineConfigError(
            "读引擎身份表需要 PyYAML：先 pip install pyyaml"
            "（devtools/engine_compat.py 不再内联档位字面量）") from exc
    spec = path or input_spec_path()
    try:
        with open(spec, "r", encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
    except OSError as exc:
        raise EngineConfigError(f"读不到引擎身份表 {spec}：{exc}") from exc
    except yaml.YAMLError as exc:
        raise EngineConfigError(f"引擎身份表 {spec} 不是合法 YAML：{exc}") from exc
    targets = doc.get("targets") if isinstance(doc, dict) else None
    if not isinstance(targets, dict) or not targets:
        raise EngineConfigError(f"引擎身份表 {spec} 缺 targets 段或为空")
    rows = []
    for profile, entry in targets.items():
        if not isinstance(entry, dict):
            raise EngineConfigError(f"引擎身份表 {spec} 的 targets.{profile} 不是映射")
        engine, version = entry.get("engine"), entry.get("stamp_version")
        if not isinstance(engine, str) or not isinstance(version, str):
            raise EngineConfigError(
                f"引擎身份表 {spec} 的 targets.{profile} 缺 engine/stamp_version 字符串")
        rows.append({"profile": profile, "engine": engine, "version": version})
    return rows


# 三元组同步表（profile/engine/version）：python 出包侧权威 = input yaml 的 targets 段。
# `engine` / `version` 字符串逐字取自该段（snake 例外：对外版本号就是 eraTW 语料版本
# `4.981`，见 PLAN-ENGCOMPAT §2.4 的配置样例）。
TARGETS = load_targets()


def format_support_table() -> str:
    """受支持的 engine + version 组合表（每条失败报错的正文都附它）。"""
    width_profile = max(len(t["profile"]) for t in TARGETS)
    width_engine = max(len(t["engine"]) for t in TARGETS)
    lines = [
        "受支持的 engine + version 组合（engine.config.json，schema 固定为 1）：",
        f"  {'profile':<{width_profile}}  {'engine':<{width_engine}}  version",
    ]
    for t in TARGETS:
        lines.append(
            f"  {t['profile']:<{width_profile}}  {t['engine']:<{width_engine}}  {t['version']}"
        )
    return "\n".join(lines)


def validate_config(raw, source: str = "") -> dict:
    """配置 dict → 档位 dict（`profile` / `engine` / `version`）。

    校验顺序：JSON 对象 → 未知字段 → 缺字段 → schema → 组合查表。
    任何一步失败抛 [EngineConfigError]，正文附支持表。
    """
    where = f"（{source}）" if source else ""

    def err(msg: str) -> None:
        raise EngineConfigError(f"{msg}\n{format_support_table()}")

    if not isinstance(raw, dict):
        err(f"引擎配置{where}必须是 JSON 对象，实际 {type(raw).__name__}")
    unknown = sorted(k for k in raw if k not in ENGINE_CONFIG_KEYS)
    if unknown:
        err(f"引擎配置{where}含未知字段 {unknown}（v1 只有 {list(ENGINE_CONFIG_KEYS)}，"
            "无能力/方言覆盖项）")
    missing = [k for k in ENGINE_CONFIG_KEYS if k not in raw]
    if missing:
        err(f"引擎配置{where}缺字段 {missing}")
    # bool 是 int 的子类：True == 1 会把 `schema: true` 放过去，单独拦。
    if isinstance(raw["schema"], bool) or raw["schema"] != SCHEMA_VERSION:
        err(f"引擎配置{where}的 schema 不符：期望 {SCHEMA_VERSION}，实际 {raw['schema']!r}")
    engine, version = raw["engine"], raw["version"]
    if not isinstance(engine, str) or not isinstance(version, str):
        err(f"引擎配置{where}的 engine/version 必须是字符串，实际 "
            f"engine={raw['engine']!r} version={raw['version']!r}")
    for t in TARGETS:
        if t["engine"] == engine and t["version"] == version:
            return {"profile": t["profile"], "engine": engine, "version": version}
    err(f"引擎配置{where}的 engine/version 组合不受支持："
        f"engine={engine!r} version={version!r}")


def load_engine_config(path: str) -> dict:
    """读配置文件 → 档位 dict；文件缺失 / 坏 JSON / 校验失败都抛 [EngineConfigError]。"""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        raise EngineConfigError(f"读不到引擎配置 {path}：{exc}\n{format_support_table()}") from exc
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise EngineConfigError(
            f"引擎配置 {path} 不是合法 JSON：{exc}\n{format_support_table()}") from exc
    return validate_config(raw, source=path)


def stamp_document(config: dict) -> dict:
    """配置 → 印章四字段 dict（schema/profile/engine/version，三串逐字取自同步表）。"""
    return {
        "schema": SCHEMA_VERSION,
        "profile": config["profile"],
        "engine": config["engine"],
        "version": config["version"],
    }


def stamp_bytes(config: dict) -> bytes:
    """印章文件字节（UTF-8、键序固定、带换行，保证同配置逐字节同印章）。"""
    return (json.dumps(stamp_document(config), ensure_ascii=False, indent=2)
            + "\n").encode("utf-8")


def write_stamp_file(config: dict, path: str) -> str:
    """把印章写到 path（父目录不存在则创建），返回 abs path。"""
    path = os.path.abspath(path)
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(stamp_bytes(config))
    return path


def verify_stamp_document(doc, config: dict) -> list:
    """印章 dict 与配置逐字段比对（schema/profile/engine/version）；返回问题列表（空 = 一致）。"""
    if not isinstance(doc, dict):
        return [f"印章必须是 JSON 对象，实际 {type(doc).__name__}"]
    expect = stamp_document(config)
    problems = []
    for key, want in expect.items():
        got = doc.get(key)
        if got != want:
            problems.append(f"字段 {key} 期望 {want!r} 实际 {got!r}")
    return problems


def verify_stamp_text(text: str, config: dict) -> list:
    """印章文本与配置逐字段比对；坏 JSON / 字段不符都进问题列表。"""
    try:
        doc = json.loads(text)
    except json.JSONDecodeError as exc:
        return [f"印章不是合法 JSON：{exc}"]
    return verify_stamp_document(doc, config)
