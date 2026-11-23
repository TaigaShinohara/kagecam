# UI検証（実機なし検証の標準）— KageCam

2026-11-23 に本人の決定で全プロジェクト標準となった「実機なし検証」の
KageCam での正本。交付前にここに書いたチェックを通す。

## 構成

- スクリーンショットテスト：Roborazzi 1.29.0 ＋ Robolectric 4.14.1
  （`app/src/test/java/app/kagecam/ScreenshotTest.kt`）
- ゴールデン画像：`app/src/test/screenshots/`（リポジトリで管理。
  captureRoboImage にファイルを明示して置き場所を固定している）
- テーマは本体と共通の `KageCamTheme` を使う（見た目の正本を1つにする）

## カバーしている画面（最低ライン＋α）

| テスト | 画面 |
|---|---|
| shelf_empty | 起動直後の画面＝棚（作品がまだ無い状態） |
| onboarding_firstPage | オンボーディング入口（1ページ目「影だけが、残る。」） |
| extract_defaultPreset | 編集画面「怖くする」（ゆうれい初期値、結果が出た状態） |

## 実行方法

```sh
# 照合（通常の交付前チェック：全単体テスト＋スクショ照合＋APKビルド）
./build.sh :app:verifyRoborazziDebug :app:assembleDebug

# ゴールデンを作り直す時（画面を変えた版でのみ）
./build.sh :app:recordRoborazziDebug --tests "app.kagecam.ScreenshotTest"
```

## このVM固有の注意（はまった点）

- テストJVMに `-Djava.net.preferIPv4Stack=true` が必須。無いと IPv6
  ループバックが横取りされて Gradle テストワーカーが本体と通信できず、
  `SuiteTestClassProcessor.stop` の NPE で全滅する（app/build.gradle.kts の
  Test 設定に入れてある）。
- Robolectric の android-all は `.robolectric-deps/` に事前取得し、
  オフラインで読む（同フォルダは .gitignore 済み。通常環境では不要）。
- 日付表示を決定的にするためテストのタイムゾーンは Asia/Tokyo に固定。
- アニメーション尺は 0 にして撮影する（フェード待ちの不安定さを避ける）。

## 初回導入時の収穫（2026-11-23）

- 3画面ともレイアウト崩壊・見切れなしを目視確認。
- 主要ボタン（「撮る」「棚にしまう」）の文字色が M3 暗色テーマ既定の
  暗い紫になり、血の赤の上で読めない問題を発見 → テーマに
  `onPrimary = Palette.Bone` を追加して修正（KageCamTheme に集約）。
- 従来「このVMでは単体テストが実行できない」とされていた問題も、
  上記の IPv4 対処で解消した（ShadowExtractorTest / ShadowEffectsTest も
  実行できるようになった）。初回実行で既存テスト2件が落ち、切り分けた：
  - 引き伸ばし（stretchMask）の丸めが 0.5 切り上げで、中心の上下に
    非対称に広がる実装側のずれ → 偶数丸めに直してテストの仕様どおりにした。
  - 大津法のテストは「山の間のどこも等価な最適解」という性質に対して
    期待範囲が狭すぎた → 実装は正しく、テストの範囲を実態に合わせた。

## 対象外（従来どおり実機待ち行列）

カメラの見え方・色味、録音／再生の聞こえ方、触感など身体感覚が要る確認は
ここでは扱わない。催促せず実機待ち行列に置く。
