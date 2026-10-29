<div align="center">

# EraTW Pocket

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**A native Android port of EraTW.**

This repo is the shell only: Compose UI, asset unpacking, saves, audio, settings.
Game semantics are handed, untouched, to the [eraKcore](https://github.com/Poboking/eraKcore)
engine — zero semantic coupling between the two, and the engine verifies
standalone on desktop.

</div>

---

## Design Boundary

This repo has zero knowledge of game semantics: the authoritative definitions of
script behavior, built-in functions, and save formats all live in
[eraKcore](https://github.com/Poboking/eraKcore)'s `core-vm` and `specs/`. From
this follow our conventions:

1. Shell changes must not alter any game behavior;
2. If a fix seems to require touching the engine, please file the issue on the
   eraKcore repo instead of working around it here;
3. The engine renders output as a display list; this repo's `render-canvas` only
   consumes it and never derives game state on its own.

## Repository Layout

| Module | Description |
|---|---|
| `app` | Compose shell + assembly + release packaging |
| `platform` | Platform capabilities: asset unpacking, save / audio / font / settings bindings (Android library) |
| `render-canvas` | Android custom View: hit regions and drawing, consuming eraKcore's display list |
| `devtools/` | Release health-check scripts (`pack_release.py`) and debugging tools |
| `docs/` | Design docs and runbooks |

## Architecture & Design Decisions

**Zero knowledge of game semantics.** The shell never parses scripts and knows nothing
about built-in behavior; it consumes the engine's display lists and callbacks only.
Upstream semantic fixes therefore sync into this repo at zero cost — no game logic to
chase.

**Display-list rendering boundary.** `render-canvas` paints eraKcore's display lists
onto the Android Canvas; hit testing, scrolling, and input pre-fill all come from the
engine-side pure-JVM `render` model. Desktop preview and a real device run the same
layout computation — UI differences can only originate in the adapter itself.

**Composite-build wiring + build-time version reconciliation.** eraKcore is wired in
via `includeBuild`, so after dependency substitution the engine inside the APK is always
built from the sibling checkout. erbcc's version and compiler source fingerprint are
verified during the app build; drift fails the build. There is no window where the
engine is upgraded but the bytecode is not rebuilt.

**Packaging self-checks.** After packing, `pack_release.py` reads back the actual
strings and configuration inside the APK (report endpoint, token, corpus package
integrity) and verifies them item by item; any mismatch exits non-zero. A silently
disabled crash-report chain gets caught at packaging time, not after a player's crash.

## Build

Requires JDK 17+ and the Android SDK (AGP 8.8.2). All commands run from the
repository root by default.

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
(template: `local.report.properties.example`, gitignored; leaving it unconfigured
only disables reporting — packaging is unaffected).

## Corpus & Copyright

This repo distributes no EraTW game data (scripts, images, audio, saves, configs) —
bring your own legally obtained copy. At packaging time they enter the artifact via
properties, never the repository.

## Status

Under active development; some platform capabilities (audio-focus acceptance,
edge-device smoke) are not yet closed out. Issues and PRs are welcome. For
game-behavior reports, please include a comparison against the original
(Emuera / PC build), so we can tell whether the shell or the engine is at fault.

## Acknowledgments

This project would not exist without the following projects and authors:

- the **eraKcore contributors** — the authoritative definitions of all game
  semantics live there; this repo is just one of its frontends;
- **MinorShift** and **妊）|дﾟ)の中の人**, the authors of Emuera, whose behavior
  serves as the reference for game-behavior compatibility;
- **まだ名前は無い人** and the writers and porters behind eraTW's character
  dialogues, events, and data — the creators of the game itself. This repo does
  not include the game; its copyright and terms of use are governed by the files
  that accompany the original project;
- every script/content author and tool maintainer in the era community.

These acknowledgments do not alter the copyright, attribution, license, or terms
of use of any third-party material.

## License

[GPL-3.0](LICENSE). The eraKcore engine is MIT-licensed (the combination is
compatible and lawful); see its [NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md)
for Emuera attribution. Game data remains the property of its original authors —
neither repo includes or replaces it.
