<div align="center">

# EraTW Pocket

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_(API_21)-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**An Android port of EraTW.**

The shell does shell things only: Compose UI, asset unpacking, saves, audio, settings.
Game semantics are handed, untouched, to the
[eraKcore](https://github.com/Poboking/eraKcore) engine —
zero semantic coupling between the two; the engine verifies standalone on desktop.

</div>

---

## Repository Layout

| Module | Description |
|---|---|
| `app` | Compose shell + assembly + release packaging |
| `platform` | Platform capabilities: asset unpacking, save / audio / font / settings bindings (Android library) |
| `render-canvas` | Android custom View: hit regions and drawing, consuming eraKcore's display list |
| `devtools/` | Release health-check scripts (`pack_release.py`) and debugging tools |
| `docs/` | Design docs and runbooks |

## Build

Requires JDK 17+ and the Android SDK (AGP 8.8.2).

**The engine repo must be checked out as a sibling** (Gradle composite build):

```
parent/
├── eraKcore/      ← engine (core-vm / erbcc / render)
└── eratw-pocket/  ← this repo
```

```bash
./gradlew :app:assembleDebug
```

This repo ships no game data; bundled assets are provided via properties:

| Source | Purpose |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | Game root (`ERB/`, images, configs); unset produces an erbc-only small package |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc output directory; defaults to probing `../eraKcore/tools/erbcc/build/erbc-out/erbc.v1` |
| `-PeratwSkipAssets` | Skip asset bundling entirely (when the device has already unpacked data) |

For releases use the single entry point:

```bash
python devtools/pack_release.py            # full package + health checks
python devtools/pack_release.py --skip-assets
```

Error-report endpoint and token go into `local.report.properties`
(template: `local.report.properties.example`, gitignored; leaving it unconfigured only
disables reporting — packaging is unaffected).

## Boundary with the Engine

This repo has zero knowledge of game semantics: the authoritative definitions of script
behavior, built-in functions, and save formats live in
[eraKcore](https://github.com/Poboking/eraKcore)'s `core-vm` and `specs/`.
Shell changes must not alter game behavior; if a fix seems to require touching the engine,
please file the issue on the engine repo.

## Corpus & Copyright

This repo distributes no EraTW game data (scripts, images, audio, saves, configs) —
bring your own legally obtained copy. At packaging time they enter the artifact via
properties, never the repository.

## Status

Under active development; some platform capabilities (audio-focus acceptance, edge-device
smoke) are not yet closed out. Issues and PRs welcome; for game-behavior reports, please
include a comparison against the original (Emuera / PC build).

## License

[GPL-3.0](LICENSE). The eraKcore engine is MIT-licensed (the combination is compatible and
lawful); see its [NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md) for Emuera
attribution. Game data remains the property of its original authors — neither repo includes
or replaces it.
