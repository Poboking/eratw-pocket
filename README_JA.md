<div align="center">

# EraTW Pocket

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**EraTW の Android 移植版。**

シェルはシェルの仕事だけをします：Compose UI、アセット展開、セーブ、オーディオ、設定。
ゲームの意味論はそのまま [eraKcore](https://github.com/Poboking/eraKcore) エンジンへ——
両者の意味論的な結合はゼロで、エンジンはデスクトップ上で単独検証できます。

</div>

---

## リポジトリ構成

| モジュール | 概要 |
|---|---|
| `app` | Compose シェル + アセンブリ + リリースパッケージング |
| `platform` | プラットフォーム機能：アセット展開、セーブ / オーディオ / フォント / 設定バインディング（Android library） |
| `render-canvas` | Android カスタム View：当たり判定と描画。eraKcore のディスプレイリストを消費 |
| `devtools/` | リリース検査スクリプト（`pack_release.py`）とデバッグツール |
| `docs/` | 設計ドキュメントとランブック |

## ビルド

JDK 17 以上と Android SDK（AGP 8.8.2）が必要です。

**エンジンリポジトリは兄弟ディレクトリとしてチェックアウトしてください**（Gradle composite build）：

```
parent/
├── eraKcore/      ← エンジン（core-vm / erbcc / render）
└── eratw-pocket/  ← 本リポジトリ
```

```bash
./gradlew :app:assembleDebug
```

本リポジトリはゲームデータを同梱しません。同梱アセットは変数で指定します：

| ソース | 役割 |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | ゲームルート（`ERB/`・画像・設定）。未指定なら erbc のみの小パッケージになります |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc 産物ディレクトリ。既定では `../eraKcore/tools/erbcc/build/erbc-out/erbc.v1` を探索 |
| `-PeratwSkipAssets` | アセット同梱を完全にスキップ（実機で展開済みの場合） |

リリース時は唯一の入口を使ってください：

```bash
python devtools/pack_release.py            # フルパッケージ + 検査
python devtools/pack_release.py --skip-assets
```

エラーレポートのエンドポイントとトークンは `local.report.properties` に
（テンプレート：`local.report.properties.example`、gitignore 済み。未設定でもレポートが
無効になるだけで、パッケージングには影響しません）。

## エンジンとの境界

本リポジトリはゲームの意味論を一切知りません：スクリプトの挙動・組み込み関数・セーブ形式の
権威ある定義はすべて [eraKcore](https://github.com/Poboking/eraKcore) の `core-vm` と `specs/` に
あります。シェルの変更がゲームの挙動を変えてはいけません。エンジンに手を入れないと直せない
問題に見えたら、issue はエンジンリポジトリへ。

## コーパスと著作権

本リポジトリは EraTW ゲーム本体（スクリプト・画像・音声・セーブ・設定）を配布しません。
正規に入手したゲームデータをご自身でご用意ください。パッケージング時には変数経由で
産物に入り、リポジトリには入りません。

## 状態

開発は現在も進行中です。一部のプラットフォーム機能（オーディオフォーカスの検収、
境界機種のスモーク）は未収口。issue と PR を歓迎します。ゲームの挙動に関する報告には、
原版（Emuera / PC 版）との比較をお添えください。

## ライセンス

[GPL-3.0](LICENSE)。エンジンの eraKcore は MIT で公開しています（両者は互換、組み合わせは合法）。
Emuera の帰属表示はそちらの [NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md) を。
ゲーム本体の著作権は原作者に帰属し、本リポジトリもエンジンリポジトリもそれを含まず、代替でもありません。
