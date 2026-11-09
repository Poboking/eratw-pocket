<div align="center">

# EraTW Pocket

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)
![players](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Poboking/eratw-pocket/main/badges/players.json)
![active today](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Poboking/eratw-pocket/main/badges/active-today.json)

**EraTW の Android ネイティブ移植版。**

エミュレータを使わず、EraTW をスマホでネイティブに動かすためのプロジェクトです。
このリポジトリが担うのはシェルだけ：Compose UI、アセット展開、セーブ、オーディオ、
設定。ゲームの意味論はすべて [eraKcore](https://github.com/Poboking/eraKcore)
エンジンが扱い、両リポジトリの間に意味論レベルの結合はありません。エンジンは
デスクトップ上で単独検証できます。

</div>

---

## リポジトリ構成

| パス | 概要 |
|---|---|
| `app` | Compose シェル + アセンブリ + パッケージング。設定画面、入力バー、オーバーレイ、エラーレポート、更新 UI |
| `platform` | プラットフォーム機能：アセット展開 / 素材インポート、セーブ入出力、オーディオフォーカス、フォント、設定ストレージ（Android library） |
| `render-canvas` | Android カスタム View：当たり判定と描画。eraKcore のディスプレイリストを消費 |
| `buildSrc` | パッケージ時のタスクと純関数：アセット staging、コーパスパッチ、erbc 鮮度、ART profile 変換 |
| `devtools/` | リリース検査、更新マニフェスト / 差分、エラーレポート分診、性能・実機プローブ |
| `tools/apkpack/` | APK 内容の自己検査：マニフェスト / シャード / STORED / 圧縮口径の照合 |
| `tools/report-endpoint/` | エラーレポート用 Cloudflare Worker テンプレート（ドメインと KV id はプレースホルダ） |
| `docs/` | 設計ドキュメント、golden 取得手順、操作可能なプロトタイプ。索引は [docs/README.md](docs/README.md) |
| `badges/` | README のプレイヤー数 / アクティブ数バッジ用静的データ |
| `gradle/libs.versions.toml` | 依存座標と Android / Kotlin バージョンの単一ソース |

## ビルド

JDK 17 以上と Android SDK（AGP 8.8.2）が必要です。コマンドはすべてリポジトリの
ルートで実行します。

**エンジンリポジトリは兄弟ディレクトリとしてチェックアウトしてください**
（Gradle composite build）：

```
parent/
├── eraKcore/      ← エンジン（core-vm / render / tools/erbcc）
└── eratw-pocket/  ← 本リポジトリ
```

日常用の小パッケージ（ゲームデータなし、CI 口径）：

```bash
./gradlew :app:assembleDebug -PeratwSkipAssets
```

インストールしてすぐ遊べるフルパッケージ（ゲームルートと erbc 産物が必要）：

```bash
./gradlew :app:assembleDebug -PeratwRequireComplete=1
```

単体テスト：

```bash
./gradlew :app:testDebugUnitTest :platform:testDebugUnitTest
```

CI と同等のコマンド：

```bash
./gradlew --no-daemon \
  :app:assembleDebug -PeratwSkipAssets \
  :app:testDebugUnitTest :platform:testDebugUnitTest
```

本リポジトリはゲームデータを同梱しません。同梱アセット・産物・更新チャネルは
変数 / プロパティで指定します：

| ソース | 役割 |
|---|---|
| `ERATW_GAMEDIR` / `-PeratwGameRoot` | ゲームルート（`ERB/`・`CSV/`・`resources/`・`sound/`・設定）。未指定なら erbc のみの小パッケージ |
| `ERATW_ERBC_DIR` / `-PeratwErbcDir` | erbcc 産物ディレクトリ。既定では `../eraKcore/tools/erbcc/build/erbc-out/erbc.v1` を探索 |
| `-PeratwSkipAssets` | アセット同梱を完全にスキップ（実機で展開済み / CI） |
| `-PeratwRequireComplete` | 正式パッケージ用ゲート：同梱カテゴリの欠落や産物の鮮度切れで失敗 |
| `-PeratwErbcChunkMb` | `program.erbc` を `program.partNNN.erbc` + `program.parts` マニフェストへ分割 |
| `-PeratwErbcCompress` | プログラム項目の DEFLATE を許可（既定は STORED。出包レポート参照） |
| `-PeratwAbi` | 対象 ABI 許可リスト（既定 `arm64-v8a,armeabi-v7a`） |
| `ERATW_REPORT_ENDPOINT` / `ERATW_REPORT_TOKEN` / `-PeratwReportEndpoint` | エラーレポートのエンドポイントとトークン。出包スクリプトは環境変数、直接の Gradle ビルドは `-PeratwReportEndpoint` / `-PeratwReportToken`、`local.report.properties` でも可 |
| `ERATW_UPDATE_MIRRORS` / `-PeratwUpdateMirrors` | 更新ミラー基址リスト。公開リポジトリの既定は空（自動更新は無効）なので明示設定が必要 |
| `ERATW_KCORE_ROOT` | devtools が兄弟エンジンリポジトリを探す位置（既定 `../eraKcore`） |
| `ERATW_T63_PROF_DIR` / `ERATW_T63_DIAG_DIR` | `t63_*` 実機 profile / 診断スクリプトの出力先 |

`local.report.properties` は gitignore 済みです。テンプレートは
[local.report.properties.example](local.report.properties.example)。未設定でも
レポート経路が無効になるだけで、ビルドや出包には影響しません。

## 出包とツールチェーン

リリースは唯一の入口を使います：

```bash
python devtools/pack_release.py            # フルパッケージ + 検査
python devtools/pack_release.py --skip-assets
```

APK 内の実際の文字列と設定（レポートエンドポイント / トークン / コーパス完全性 /
マニフェストとシャード / STORED 口径）を読み戻して項目ごとに照合し、不適合は
非ゼロ終了します。

| ツール | 用途 |
|---|---|
| `devtools/pack_release.py` | リリース唯一の入口と出包検査 |
| `devtools/emit_update.py` | 更新マニフェスト・署名・ブロック差分の生成（`--selftest` でオフライン自己検査） |
| `devtools/update_drill.py` | 更新ドリル / mock mirror / レジューム検証 |
| `devtools/triage_reports.py` | プレイヤーエラーレポートの取得と分診（トークンはローカル設定からのみ） |
| `devtools/boot2play.py` / `apk_truth.py` / `hawkeye.py` / `soak.py` | 実機起動、APK 真値、性能、ソークプローブ |
| `devtools/corpus-patches/` | コーパス内容パッチ（CB-xxx）とマニフェスト痕跡 |
| `tools/apkpack/inspect_apk.py` | マニフェスト / parts / 実パッケージの三者照合 |
| `tools/report-endpoint/` | レポート Worker テンプレート（デプロイ時に自分のドメインと KV id を設定） |

## アーキテクチャと設計判断

**ゲーム意味論へのゼロ知識。** シェルはスクリプトを解析せず、組み込み関数の
挙動も一切知りません。エンジンのディスプレイリストとコールバックを消費するだけ
です。上流の意味論修正は本リポジトリへゼロコストで同期できます。追いかけるべき
ゲームロジックが存在しないからです。エンジンに手を入れないと直せない問題に
見えたら、本リポジトリで回避せず
[eraKcore](https://github.com/Poboking/eraKcore) へ issue を。シェル側の回避策は、
たいてい次のバグの種になります。

**ディスプレイリスト描画の境界。** `render-canvas` は eraKcore のディスプレイ
リストを Android Canvas へ描画します。ヒット判定・スクロール・入力プレフィルは
すべてエンジン側の純 JVM `render` モデル由来で、デスクトッププレビューと実機は
同じレイアウト計算を走ります。UI の差異はアダプタ自身にしか発生しません。

**composite build 接続 + ビルド時バージョン突合。** eraKcore は `includeBuild`
で接続され、依存置換後の APK 内エンジンは常に兄弟チェックアウトのソースから
ビルドされます。erbcc のバージョンとコンパイラソースフィンガープリントは app の
ビルド時に検証し、逸脱はビルド失敗。エンジンだけ更新してバイトコードを再ビルドし
忘れる隙間はありません。

**素材インポートと Mod の落点。** リソースパック / Mod の判定、安全ゲート、
配置、三言語レポートはシェル側で完結し、エンジンは最終的な `resources/`・`CSV/`・
`.erbc` だけを見ます。素材の表示名とダウンロードジョブの `label` は別の意味論で、
古い言語ラベルをプレイヤー面へ漏らさないためのソースガードがあります。

**可観測な更新とレポート。** 更新チャネルは確認中 / 進捗 / 失敗再試行 / ミラー
フォールバックを読み取れる文言で提示します。エラーレポートはローカルキュー、
剪枝、スロットル、手動エクスポートを備えます。どちらも明示設定された端点でのみ
動作し、公開リポジトリでは既定で無効です。

**出包自己検査。** `pack_release.py` と `tools/apkpack/inspect_apk.py` は出包時に
実産物を読み戻して照合します。レポート経路・古い erbc・欠落アセット・シャード
ずれといった無音故障は、プレイヤーが気づく前にここで止まります。

## コーパスと著作権

本リポジトリは EraTW ゲーム本体（スクリプト・画像・音声・セーブ・設定）を配布しません。
正規に入手したゲームデータをご自身でご用意ください。パッケージング時には変数経由で
産物に入り、リポジトリには入りません。

## 状態とフィードバック

開発は進行中で、一部のプラットフォーム機能（オーディオフォーカスの検収、境界機種の
スモーク）は未収口。issue と PR を歓迎します。ゲームの挙動に関する報告には、原版
（Emuera / PC 版）との比較をお添えください。シェルとエンジンのどちらに原因があるかを
切り分けやすくなります。

## 謝辞

- **eraKcore の貢献者の皆さま**——ゲームの意味論に関する権威ある定義はそちらにあり、
  本リポジトリはそのフロントエンドの一つです。
- **MinorShift** と **妊）|дﾟ)の中の人**——Emuera の著作者。ゲーム挙動の互換性は
  Emuera の動作を参考にしています。
- **まだ名前は無い人**、および eraTW の口上・イベント・データ・ドキュメントを
  書かれたすべての方々。本リポジトリはゲーム本体を含まず、その著作権と利用条件は
  原プロジェクトの添付ファイルに従います。
- era コミュニティのスクリプト作者、コンテンツ作者、ツール保守者の皆さま。

上記の謝辞は、いかなる第三者材料の著作権・ライセンス条件も変更するものではありません。

## ライセンス

[GPL-3.0](LICENSE)。エンジンの eraKcore は MIT で公開しています（両者は互換、
組み合わせは合法）。Emuera の帰属表示はそちらの
[NOTICE.md](https://github.com/Poboking/eraKcore/NOTICE.md) を。ゲーム本体の
著作権は原作者に帰属し、本リポジトリもエンジンリポジトリもそれを含まず、代替でも
ありません。
