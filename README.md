# 画面キャプチャ定期POST実験アプリ

Android端末の画面を定期キャプチャ→縮小/JPEG圧縮→サーバーへ multipart POST する実験アプリ。
業務用途・サイドロード専用(Play Store非公開)。仕様は `screenshot-poster-spec.md` 参照。

## 構成

- `android/` — Kotlin製 Androidアプリ(Gradle)
  - `ScreenSource` インターフェースで取得方式を切り替え:
    - `RootScreenSource` — root端末向け。su 書式を自動判別(`su 0` / `su root` / `su -c`)して screencap をファイル経由で読む(フェーズ1)
    - `A11yScreenSource` — root無し / Android 11-14 向け。`AccessibilityService.takeScreenshot()`(フェーズ2)
  - 共通パイプライン(`CapturePipeline`): capture → downscale → 差分検知 → JPEG → Outbox → POST → リトライ
  - Outbox(送信待ちキュー): `/sdcard/Android/data/com.example.screenshotposter/files/outbox/`(外部ストレージ不可なら内部領域)
  - 確認用に最新フレームを `Download/sspost_latest.jpg` に上書き保存(API 29+、`Config.SAVE_LOCAL_COPY`)
  - `CaptureService` — プレーンな前景サービス(MediaProjectionなし)でタイマーループを回す
  - `MainActivity` — 開始/停止、a11yサービスの無人有効化ボタン、簡易ステータス表示
- `server/` — Cloudflare Workers (Hono) + R2 の受け側。multipart を受けてR2に保存するだけの最小実装

## Android: ビルド・導入

Gradle Wrapper は未生成(このリポジトリをコーディングした環境がネットワーク制限で
`services.gradle.org` に到達できなかったため)。Android Studio で `android/` を開けば
自動生成されるので、CLIビルドしたい場合は初回のみ以下を実行してから `./gradlew` を使う。

```bash
cd android
gradle wrapper --gradle-version 8.7   # 初回のみ(ローカルに Gradle 8.x が必要)
./gradlew assembleRelease
```

Android Studio を使う場合はこの手順は不要(プロジェクトを開くだけで wrapper が作られる)。

初回セットアップ:
- **root端末**: su の許可ダイアログで本アプリを「常に許可」に設定(初回のみ手動、以後無人)。a11y を使う場合も adb 不要(アプリが su 経由で自分に WRITE_SECURE_SETTINGS を付与する)。
- **rootなし / Android 11-14**:
  ```bash
  adb shell pm grant com.example.screenshotposter android.permission.WRITE_SECURE_SETTINGS
  ```
  その後アプリを起動し「a11y サービスを有効化」ボタンを押す(以後再起動しても有効のまま)。
- 設定値は `android/app/src/main/java/com/example/screenshotposter/Config.kt` の `POST_URL` を実サーバーのURLに書き換える。
  Cloudflare Workers なら `https://<worker名>.<subdomain>.workers.dev/upload`。
  AUTH_TOKEN を設定した場合は Config.AUTH_TOKEN にも同じ値を入れる。

**非対応**: rootなし / Android 9〜10(無音の正規手段が端末上に存在しないため、明示的に非スコープ)。

## Server: Cloudflare Workers + R2

```bash
cd server
npm install
npx wrangler login                 # 初回のみ
npx wrangler r2 bucket create screenshots
npx wrangler secret put AUTH_TOKEN  # 任意。設定すると Bearer 認証必須になる
npx wrangler deploy
```

ローカル動作確認:

```bash
npm run dev   # http://localhost:8787
curl -F "file=@test.jpg;filename=shot_$(date +%s000).jpg;type=image/jpeg" http://localhost:8787/upload
curl http://localhost:8787/shots
```

エンドポイント:
- `POST /upload` — multipart、part名 `file`、`shots/<filename>` としてR2に保存
- `GET /shots` — 保存済み一覧(確認用)
- `GET /shots/:name` — 画像取得(確認用)
