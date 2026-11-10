<div align="center">

# EraTW Pocket

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)
![players](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Poboking/eratw-pocket/main/badges/players.json)
![active today](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Poboking/eratw-pocket/main/badges/active-today.json)

**A native Android port of EraTW.**

Run EraTW natively on your phone, without an emulator. This repo is the shell
only: Compose UI, asset unpacking, saves, audio, settings. Game semantics are
handled entirely by [erak-engine](https://github.com/Poboking/erak-engine);
the two repos have no coupling at the semantic level, and the engine verifies
standalone on desktop.

</div>

---

## Repository Layout

| Path | Description |
|---|---|
| `app` | Compose shell + assembly + packaging; settings, input bar, overlays, error reporting, update UI |
| `platform` | Platform capabilities: asset unpacking / material import, save import-export, audio focus, fonts, settings storage (Android library) |
| `render-canvas` | Android custom View: hit regions and drawing, consuming erak-engine's display list |
| `buildSrc` | Packaging-time tasks and pure helpers: asset staging, corpus patches, erbc freshness, ART profile conversion |
| `devtools/` | Release health checks, update manifest / delta tools, error-report triage, performance and on-device probes |
| `tools/apkpack/` | APK content self-check: manifest / shards / STORED / compression accounting |
| `tools/report-endpoint/` | Cloudflare Worker template for the error-report endpoint (placeholder domain and KV id) |
| `docs/` | Design docs, golden-runbook, and an interactive prototype; indexed in [docs/README.md](docs/README.md) |
| `badges/` | Static player-count / active-today badge data used by the README |
| `gradle/libs.versions.toml` | Single source for dependency coordinates and Android / Kotlin versions |

## Build

Requires JDK 17+ and the Android SDK (AGP 8.8.2). Run all commands from the
repository root.

**The engine repo must be checked out as a sibling** (Gradle composite build):

```
parent/
├── erak-engine/   ← engine (core-vm / render / tools/erbcc)
└── eratw-pocket/  ← this repo
```

Everyday small package (no game data; the CI tier):

```bash
./gradlew :app:assembleDebug -PeratwSkipAssets
```

Install-and-play full package (requires a game root and erbc artifacts):

```bash
./gradlew :app:assembleDebug -PeratwRequireComplete=1
```

Unit tests:

```bash
./gradlew :app:testDebugUnitTest :platform:testDebugUnitTest
```

The CI-equivalent command is:

```bash
./gradlew --no-daemon \
  :app:assembleDebug -PeratwSkipAssets \
  :app:testDebugUnitTest :platform:testDebugUnitTest
```

This repo ships no game data; bundled assets, artifacts, and update channels are
configured through variables / properties:

| Source | Purpose |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | Game root (`ERB/`, `CSV/`, `resources/`, `sound/`, configs); unset produces an erbc-only small package |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc output directory; defaults to probing `../erak-engine/tools/erbcc/build/erbc-out/erbc.v1` |
| `-PeratwSkipAssets` | Skip asset bundling entirely (device already unpacked / CI) |
| `-PeratwRequireComplete` | Release gate: fail if any bundled category is missing or the artifact is stale |
| `-PeratwErbcChunkMb` | Split `program.erbc` into `program.partNNN.erbc` + a `program.parts` manifest |
| `-PeratwErbcCompress` | Allow DEFLATE for program entries (default STORED; see the package report) |
| `-PeratwAbi` | Target ABI allow-list (default `arm64-v8a,armeabi-v7a`) |
| `ERATW_REPORT_ENDPOINT` / `ERATW_REPORT_TOKEN` / `-PeratwReportEndpoint` | Error-report endpoint and token; the release script reads the environment, direct Gradle builds use `-PeratwReportEndpoint` / `-PeratwReportToken`, and `local.report.properties` also works |
| `ERATW_UPDATE_MIRRORS` / `-PeratwUpdateMirrors` | Update mirror base URLs; the public repo default is empty (auto-update disabled), so configure explicitly |
| `ERATW_KCORE_ROOT` | Where devtools locate the sibling engine checkout (default `../erak-engine`) |
| `ERATW_T63_PROF_DIR` / `ERATW_T63_DIAG_DIR` | Output directories for the `t63_*` on-device profile / diagnostic scripts |

`local.report.properties` is gitignored; see
[local.report.properties.example](local.report.properties.example). Leaving it
unconfigured only disables the reporting chain — packaging is unaffected.

## Packaging & Toolchain

Releases go through a single entry point:

```bash
python devtools/pack_release.py            # full package + health checks
python devtools/pack_release.py --skip-assets
```

It reads back the actual strings and configuration inside the APK (report
endpoint / token / corpus integrity / manifest and shards / STORED accounting)
and exits non-zero on any mismatch.

| Tool | Purpose |
|---|---|
| `devtools/pack_release.py` | Single release entry point and package health checks |
| `devtools/emit_update.py` | Update manifests, signatures, and block deltas (`--selftest` runs offline) |
| `devtools/update_drill.py` | Update drills / mock mirrors / resume verification |
| `devtools/triage_reports.py` | Fetch and triage player error reports (token read only from local config) |
| `devtools/boot2play.py` / `apk_truth.py` / `hawkeye.py` / `soak.py` | On-device boot, APK truth, performance, and soak probes |
| `devtools/corpus-patches/` | Corpus content patches (CB-xxx) and manifest trace |
| `tools/apkpack/inspect_apk.py` | Three-way reconciliation of manifest / parts / actual package |
| `tools/report-endpoint/` | Report worker template (fill in your own domain and KV id at deploy time) |

## Architecture & Design Decisions

**Zero knowledge of game semantics.** The shell never parses scripts and knows
nothing about built-in behavior; it consumes the engine's display lists and
callbacks only. Upstream semantic fixes therefore sync into this repo at zero
cost — there is no game logic to chase. If a fix seems to require touching the
engine, please file the issue on
[erak-engine](https://github.com/Poboking/erak-engine) instead of working around it
here — a workaround in the shell is usually the next bug.

**Display-list rendering boundary.** `render-canvas` paints erak-engine's display
lists onto the Android Canvas; hit testing, scrolling, and input pre-fill all
come from the engine-side pure-JVM `render` model. Desktop preview and a real
device run the same layout computation — UI differences can only originate in
the adapter itself.

**Composite-build wiring + build-time version reconciliation.** erak-engine is
wired in via `includeBuild`, so after dependency substitution the engine inside
the APK is always built from the sibling checkout. erbcc's version and compiler
source fingerprint are verified during the app build; drift fails the build.
There is no window where the engine is upgraded but the bytecode is not
rebuilt.

**Material import and Mod landing.** Resource-pack / Mod detection, safety
gates, placement, and trilingual reporting all happen in the shell; the engine
only sees the final `resources/`, `CSV/`, and `.erbc`. A material source name
and a download job's `label` are different semantics, and source gates stop
stale language labels from leaking into player-facing text.

**Observable updates and reporting.** The update channel exposes readable
states for checking / progress / failure retry / mirror fallback; error reports
have a local queue, pruning, throttling, and manual export. Both work only with
explicitly configured endpoints, and the public repo has them disabled by
default.

**Packaging self-checks.** `pack_release.py` and `tools/apkpack/inspect_apk.py`
read back the real artifact and reconcile it at packaging time. Silent failures
(reporting chain, stale erbc, missing assets, misaligned shards) are stopped
there instead of being discovered by players.

## Corpus & Copyright

This repo distributes no EraTW game data (scripts, images, audio, saves,
configs) — bring your own legally obtained copy. At packaging time they enter
the artifact via properties, never the repository.

## Status & Feedback

Under active development; some platform capabilities (audio-focus acceptance,
edge-device smoke) are not yet closed out. Issues and PRs are welcome. When
reporting a game-behavior problem, please include a comparison against the
original (Emuera / PC build) — it makes it much easier to tell whether the
shell or the engine is at fault.

## Acknowledgments

- The **erak-engine contributors** — the authoritative definitions of game
  semantics live there; this repo is just one of its frontends.
- **MinorShift** and **妊）|дﾟ)の中の人**, authors of Emuera — the reference for
  game-behavior compatibility.
- **まだ名前は無い人**, and everyone who has written eraTW dialogues, events,
  data, and documentation. This repo does not include the game itself; its
  copyright and terms of use are governed by the files that accompany the
  original project.
- The era community's script authors, content creators, and tool maintainers.

These acknowledgments do not alter the copyright or license terms of any
third-party material.

## License

[GPL-3.0](LICENSE). erak-engine is MIT-licensed (the combination is
compatible and lawful); see its
[NOTICE.md](https://github.com/Poboking/erak-engine/NOTICE.md) for Emuera
attribution. Game data remains the property of its original authors — neither
repo includes or replaces it.
