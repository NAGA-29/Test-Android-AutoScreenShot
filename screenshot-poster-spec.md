# 画面キャプチャ定期POST実験アプリ — 実装仕様 (AIエージェント向け)

> このドキュメントはコーディングエージェントへの引き継ぎ用。
> 「何を・なぜ・どの順で」を記述する。実装中に迷ったらこのスコープ判断に従うこと。

## 1. ゴール

Android端末の画面を定期的にキャプチャし、縮小・JPEG圧縮して、一定間隔でサーバーへ
multipart POST する実験アプリを作る。業務用途。**Play Store には公開しない**(サイドロード)。

## 2. スコープと非スコープ

対象OSは Android 9 (API 28) 〜 Android 14 (API 34)。
ただし **全端末を1方式でカバーしようとしない**。端末の状態で取得方式を切り替える。

- ✅ **対応する**: root を持つ端末(OS問わず)
- ✅ **対応する**: root なし / OS 11〜14 の端末
- ❌ **対応しない(明示的に非スコープ)**: root なし / OS 9〜10 の端末
  - 端末上で完結する無音の正規手段が存在しないため。
  - クライアント提供時も「この構成の端末は非対応」と言い切る方針で合意済み。
  - このタイルの対応コードは**書かない**。将来必要になったらホスト駆動の
    `adb exec-out screencap` 運用を別途検討するが、本アプリには含めない。

## 3. 対応マトリクス

| 端末の状態 | 取得方式 | 無人運用 | 備考 |
|---|---|---|---|
| root あり(OS 9〜14) | `screencap` via `su` | ✅ 完全無人・単一コードパス | **フェーズ1** |
| root なし / OS 11〜14 | AccessibilityService `takeScreenshot()` | ✅ ダイアログなし | **フェーズ2** |
| root なし / OS 9〜10 | — | — | **非対応** |

## 4. アーキテクチャ方針

取得方式だけが違い、**下流(圧縮・送信・リトライ)は完全共通**。
`ScreenSource` インターフェースを1枚切り、実装を差し替える構造にする。

```
interface ScreenSource {
    // 1フレーム取得して JPEG バイト列を返す（失敗時は null）
    suspend fun capture(): ByteArray?
}
```

- `RootScreenSource` : `su -c screencap` ベース(フェーズ1)
- `A11yScreenSource` : `AccessibilityService.takeScreenshot()` ベース(フェーズ2)

共通パイプライン(どちらのソースでも同一):

```
capture(): Bitmap → downscale(TARGET_WIDTH) → JPEG(quality) → Outbox → POST → (失敗なら retry)
```

圧縮・送信・リトライは一度書けば両フェーズで使い回す。フェーズ1で作ったものが
フェーズ2でもほぼ丸ごと生きる設計にすること。

## 5. フェーズ1: root / `screencap`(最初に作る)

### 方針
MediaProjection は**一切使わない**。フォアグラウンドサービス内のタイマーループで
`CAPTURE_INTERVAL_MS` ごとに `screencap` を実行し、出力PNGを読む。

- VirtualDisplay / ImageReader / MediaProjection 同意ダイアログ / Android 14 の
  startForeground 順序問題 — **すべて不要**。ここがこのフェーズの利点。
- プロセスを生かし続けるための**プレーンなフォアグラウンドサービス**は必要
  (Android 14 では type が要る。`dataSync` もしくは `specialUse` を使う。
  `mediaProjection` ではない)。

### 実装の地雷
- `screencap -p` の stdout を直接読むと端末によってバイト列が化けることがある。
  **一旦ファイルに吐いてから読む**ほうが堅い:
  `su -c "screencap -p /data/local/tmp/shot.png"` → その後ファイルを読む。
- su の都度ダイアログを無人化するには、Magisk 側で当該アプリを「常に許可」に
  しておく(初回のみ手動、以後無人)。
- root があれば `FLAG_SECURE` の画面も撮れることが多いが端末差あり。実機確認。

## 6. フェーズ2: root なし / OS 11〜14 / a11y `takeScreenshot()`

### 方針
`AccessibilityService` を実装し、その中で `takeScreenshot()` を定期実行する。
MediaProjection のセッションも同意ダイアログも不要で、ステータスバー表示も出ない
(完全無音)。キャプチャ処理は**アクセシビリティサービス自身**の中で行う。

### セットアップ(ここが肝)
a11y サービスの有効化は通常ユーザーが設定画面で行うが、無人化のため
**初回セットアップ時に adb で `WRITE_SECURE_SETTINGS` を付与**し、アプリから
自分のサービスをプログラムで有効化する:

```
adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
```

付与後、アプリから `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` に自サービスを
書き込んで有効化する。付与・有効化とも再起動後も残るので、一度仕込めば以後無人。

- Android 13+ はサイドロードアプリの a11y 有効化を「制限付き設定」でブロックするため、
  この adb 有効化ルートはむしろ正道。
- a11y サービスの meta-data で `canTakeScreenshot` を宣言すること(必須)。

### 実装の地雷
- `takeScreenshot()` には**呼び出し間隔の下限**がある。速すぎると
  `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` で弾かれる。
  `CAPTURE_INTERVAL_MS` は数秒以上に設定し、この下限を割らないこと。
- Android 11 の一部実機は有効化済みでも
  `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` 等で撮影を拒否することがある。
  その世代向けに MediaProjection へのフォールバックを**想定だけ**しておく
  (実装優先度は低。まず素の takeScreenshot で通すこと)。
- `FLAG_SECURE` 画面は撮れない(黒塗り)。これは仕様。

## 7. 共通パイプライン詳細

### 圧縮
- `TARGET_WIDTH` に縮小(元がそれより小さければ縮小しない)。
- JPEG `JPEG_QUALITY`(目安60)。1080p→幅720/品質60 で数十〜100KB台が目安。
- **差分検知**(前フレームとほぼ同一なら送らない)を入れると通信量・バッテリーが
  大きく減る。実験が通ったら足す。初版では必須ではない。

### 送信
- `POST_URL` へ `multipart/form-data`。
- フィールド名 `file` / ファイル名 `shot_<epochMillis>.jpg` / content-type `image/jpeg`。
- HTTPクライアントは OkHttp。

### リトライ(実運用で必須、実験初版では簡易でよい)
- 送信失敗分は**ローカルのoutbox(ディスク)に退避**してリトライする。
- 握りつぶしは実験のみ許容。クライアント提供版では必ずoutbox+リトライを入れる。

## 8. サーバー契約

受け側は multipart を受け取りファイル保存するだけの最小エンドポイントでよい。

- method: `POST`
- content-type: `multipart/form-data`
- part: name=`file`, filename=`shot_<epochMillis>.jpg`, type=`image/jpeg`
- 成功時 2xx を返す(それ以外はクライアントが失敗扱いしてリトライ)

## 9. 設定値(1ファイルに集約する)

```kotlin
object Config {
    const val POST_URL = "https://example.com/upload" // 要書き換え
    const val CAPTURE_INTERVAL_MS = 5_000L            // a11yの下限に注意
    const val TARGET_WIDTH = 720
    const val JPEG_QUALITY = 60
}
```

## 10. 既知の制限(全フェーズ共通)

- `FLAG_SECURE` 画面: フェーズ2では撮れない。フェーズ1(root)は撮れることが多い。
- 画面が変化しないとフレームが更新されない方式ではないが、差分検知を入れる場合は
  同一画面の連投を避ける挙動になる。
- 非root / OS 9〜10 は非対応(セクション2参照)。

## 11. 実装順序

1. **共通パイプライン**(`ScreenSource` インターフェース、圧縮、OkHttp POST、Config)
2. **フェーズ1**: `RootScreenSource`(`screencap` → ファイル経由で読む)+
   プレーンなフォアグラウンドサービスのタイマーループ。root実機で疎通確認。
3. **フェーズ2**: `A11yScreenSource`(`takeScreenshot()`)+ a11yサービス +
   adb の `WRITE_SECURE_SETTINGS` 有効化手順。OS 11 と 14 の実機で確認。
4. 疎通が取れたら **差分検知** と **outbox+リトライ** を追加。

> 最初の一歩はフェーズ1。MediaProjection 一式が不要で最も軽い。
