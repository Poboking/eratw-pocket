<div align="center">

# EraTW Pocket

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eratw-pocket/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android_5.0%2B_API_21-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**EraTW の Android ネイティブ移植版。**

エミュレータを使わず、EraTW をスマホでネイティブに動かすためのプロジェクトです。
このリポジトリが担うのはシェルだけ：Compose UI、アセット展開、セーブ、オーディオ、
設定。ゲームの意味論はすべて [eraKcore](https://github.com/Poboking/eraKcore)
エンジンが扱い、両リポジトリの間に意味論レベルの結合はありません。エンジンは
デスクトップ上で単独検証できます。

</div>

---

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

**パッケージング自己検証。** `pack_release.py` はパック後、APK 内の実際の文字列と
設定（レポートエンドポイント・トークン・コーパスパッケージの完全性）を読み戻して
項目ごとに照合し、不適合は非ゼロ終了。クラッシュレポート経路の無音故障は、プレイヤー
が気づく前に出荷段階で止まります。

## リポジトリ構成

| モジュール | 概要 |
|---|---|
| `app` | Compose シェル + アセンブリ + リリースパッケージング |
| `platform` | プラットフォーム機能：アセット展開、セーブ / オーディオ / フォント / 設定バインディング（Android library） |
| `render-canvas` | Android カスタム View：当たり判定と描画。eraKcore のディスプレイリストを消費 |
| `devtools/` | リリース検査スクリプト（`pack_release.py`）とデバッグツール |
| `docs/` | 設計ドキュメントとランブック |

## ビルド

JDK 17 以上と Android SDK（AGP 8.8.2）が必要です。コマンドはすべてリポジトリの
ルートで実行します。

**エンジンリポジトリは兄弟ディレクトリとしてチェックアウトしてください**
（Gradle composite build）：

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
（テンプレート：`local.report.properties.example`、gitignore 済み。未設定でも
レポートが無効になるだけで、パッケージングには影響しません）。

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
